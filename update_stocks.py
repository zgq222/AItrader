
# -*- coding: utf-8 -*-
"""
A股数据更新脚本
功能：
1. 更新已有股票的最新数据
2. 补全缺失的技术指标
3. 支持断点续传
"""
import akshare as ak
import pandas as pd
import os
import re
from datetime import datetime, timedelta
import time
from watchlist_service import stock_update_sort_key

def clean_filename(name):
    """清理文件名中的非法字符"""
    return re.sub(r'[\\/*?:"<>|]', '', name)

def calculate_indicators(stock_hist):
    """计算所有技术指标"""
    df = stock_hist.copy()
    
    # 列名标准化
    rename_map = {}
    for col in df.columns:
        if '日期' in col or col == 'date':
            rename_map[col] = 'date'
        elif '开盘' in col or col == 'open':
            rename_map[col] = 'open'
        elif '最高' in col or col == 'high':
            rename_map[col] = 'high'
        elif '最低' in col or col == 'low':
            rename_map[col] = 'low'
        elif '收盘' in col or col == 'close':
            rename_map[col] = 'close'
        elif '成交量' in col or col == 'volume':
            rename_map[col] = 'volume'
    
    df = df.rename(columns=rename_map)
    
    # 确保我们有需要的列
    required_cols = ['date', 'open', 'high', 'low', 'close', 'volume']
    for col in required_cols:
        if col not in df.columns:
            return None
    
    # 计算所有指标
    # 1. 移动平均线 (MA)
    ma_periods = [5, 10, 20, 30, 60, 120, 250]
    for period in ma_periods:
        df[f'MA{period}'] = df['close'].rolling(window=period).mean()
    
    # 2. 指数移动平均线 (EMA)
    ema_periods = [5, 10, 20, 30, 60, 120]
    for period in ema_periods:
        df[f'EMA{period}'] = df['close'].ewm(span=period, adjust=False).mean()
    
    # 3. MACD
    ema12 = df['close'].ewm(span=12, adjust=False).mean()
    ema26 = df['close'].ewm(span=26, adjust=False).mean()
    df['MACD'] = ema12 - ema26
    df['MACD_Signal'] = df['MACD'].ewm(span=9, adjust=False).mean()
    df['MACD_Hist'] = df['MACD'] - df['MACD_Signal']
    
    # 4. RSI (14日)
    delta = df['close'].diff()
    gain = (delta.where(delta > 0, 0)).rolling(window=14).mean()
    loss = (-delta.where(delta < 0, 0)).rolling(window=14).mean()
    rs = gain / loss
    df['RSI'] = 100 - (100 / (1 + rs))
    
    # 5. KDJ
    low_min = df['low'].rolling(window=9).min()
    high_max = df['high'].rolling(window=9).max()
    rsv = (df['close'] - low_min) / (high_max - low_min) * 100
    df['K'] = rsv.ewm(com=2, adjust=False).mean()
    df['D'] = df['K'].ewm(com=2, adjust=False).mean()
    df['J'] = 3 * df['K'] - 2 * df['D']
    
    # 6. 布林带 (BOLL)
    df['BOLL_Mid'] = df['close'].rolling(window=20).mean()
    std = df['close'].rolling(window=20).std()
    df['BOLL_Upper'] = df['BOLL_Mid'] + (std * 2)
    df['BOLL_Lower'] = df['BOLL_Mid'] - (std * 2)
    df['BOLL_Width'] = (df['BOLL_Upper'] - df['BOLL_Lower']) / df['BOLL_Mid']
    
    # 7. OBV (能量潮)
    obv = (df['volume'] * ((df['close'] > df['close'].shift(1)) * 2 - 1)).cumsum()
    df['OBV'] = obv
    df['OBV_MA5'] = df['OBV'].rolling(window=5).mean()
    df['OBV_MA10'] = df['OBV'].rolling(window=10).mean()
    
    # 8. 威廉指标 (WR)
    wr_period = 14
    highest_high = df['high'].rolling(window=wr_period).max()
    lowest_low = df['low'].rolling(window=wr_period).min()
    df['WR'] = ((highest_high - df['close']) / (highest_high - lowest_low)) * -100
    
    # 9. CCI (商品通道指标)
    cci_period = 20
    tp = (df['high'] + df['low'] + df['close']) / 3
    ma_tp = tp.rolling(window=cci_period).mean()
    def calculate_mad(window):
        mean_val = window.mean()
        return ((window - mean_val).abs()).mean()
    md = tp.rolling(window=cci_period).apply(calculate_mad)
    df['CCI'] = (tp - ma_tp) / (0.015 * md)
    
    # 10. ATR (平均真实波幅)
    atr_period = 14
    tr1 = df['high'] - df['low']
    tr2 = abs(df['high'] - df['close'].shift(1))
    tr3 = abs(df['low'] - df['close'].shift(1))
    tr = pd.concat([tr1, tr2, tr3], axis=1).max(axis=1)
    df['TR'] = tr
    df['ATR'] = tr.rolling(window=atr_period).mean()
    
    # 11. DMI (动向指标)
    dmi_period = 14
    plus_dm = df['high'].diff()
    minus_dm = df['low'].diff()
    plus_dm = plus_dm.where((plus_dm > minus_dm) & (plus_dm > 0), 0)
    minus_dm = minus_dm.where((minus_dm > plus_dm) & (minus_dm > 0), 0)
    tr_smooth = df['TR'].rolling(window=dmi_period).sum()
    plus_di = (plus_dm.rolling(window=dmi_period).sum() / tr_smooth) * 100
    minus_di = (minus_dm.rolling(window=dmi_period).sum() / tr_smooth) * 100
    df['+DI14'] = plus_di
    df['-DI14'] = minus_di
    dx = (abs(plus_di - minus_di) / (plus_di + minus_di)) * 100
    df['ADX'] = dx.rolling(window=dmi_period).mean()
    df['ADXR'] = df['ADX'].rolling(window=dmi_period).mean()
    
    # 12. BIAS (乖离率)
    bias_periods = [6, 12, 24]
    for period in bias_periods:
        ma = df['close'].rolling(window=period).mean()
        df[f'BIAS{period}'] = (df['close'] - ma) / ma * 100
    
    # 13. ROC (变动率指标)
    roc_period = 12
    df['ROC'] = (df['close'] - df['close'].shift(roc_period)) / df['close'].shift(roc_period) * 100
    df['ROC_MA6'] = df['ROC'].rolling(window=6).mean()
    
    # 14. MTM (动量指标)
    mtm_period = 10
    df['MTM'] = df['close'] - df['close'].shift(mtm_period)
    df['MTM_MA'] = df['MTM'].rolling(window=mtm_period).mean()
    
    # 15. VR (成交量变异率)
    vr_period = 24
    df['Pct_Change'] = df['close'].pct_change()
    vol_plus = df['volume'].where(df['Pct_Change'] > 0, 0).rolling(window=vr_period).sum()
    vol_minus = df['volume'].where(df['Pct_Change'] < 0, 0).rolling(window=vr_period).sum()
    vol_equal = df['volume'].where(df['Pct_Change'] == 0, 0).rolling(window=vr_period).sum()
    df['VR'] = (vol_plus + 0.5 * vol_equal) / (vol_minus + 0.5 * vol_equal) * 100
    
    # 16. 其他基础指标
    df['Price_Max_5'] = df['high'].rolling(window=5).max()
    df['Price_Min_5'] = df['low'].rolling(window=5).min()
    df['Price_Max_20'] = df['high'].rolling(window=20).max()
    df['Price_Min_20'] = df['low'].rolling(window=20).min()
    df['Price_Max_60'] = df['high'].rolling(window=60).max()
    df['Price_Min_60'] = df['low'].rolling(window=60).min()
    df['Vol_MA5'] = df['volume'].rolling(window=5).mean()
    df['Vol_MA10'] = df['volume'].rolling(window=10).mean()
    df['Vol_MA20'] = df['volume'].rolling(window=20).mean()
    df['Vol_Ratio'] = df['volume'] / df['Vol_MA20']
    df['Vol_Max_20'] = df['volume'].rolling(window=20).max()
    df['Vol_Min_20'] = df['volume'].rolling(window=20).min()
    
    return df

def main():
    print("="*80)
    print("A股数据智能更新脚本")
    print("="*80)
    
    # 设置目录
    base_dir = "个股"
    raw_dir = os.path.join(base_dir, "原始数据")
    indicator_dir = os.path.join(base_dir, "完整指标")
    os.makedirs(raw_dir, exist_ok=True)
    os.makedirs(indicator_dir, exist_ok=True)
    
    # 读取股票列表
    stock_list_path = os.path.join(base_dir, "A股股票列表.csv")
    if not os.path.exists(stock_list_path):
        print("错误：找不到A股股票列表.csv")
        print("正在尝试获取股票列表...")
        try:
            stock_list = ak.stock_info_a_code_name()
            stock_list.to_csv(stock_list_path, index=False, encoding='utf-8-sig')
            print(f"成功获取 {len(stock_list)} 只股票")
        except Exception as e:
            print(f"获取股票列表失败: {e}")
            return
    else:
        stock_list = pd.read_csv(stock_list_path)
        print(f"已加载股票列表: {len(stock_list)} 只股票")
    
    stock_order = sorted(stock_list.index, key=lambda index: stock_update_sort_key(
        stock_list.at[index, 'code'], stock_list.at[index, 'name']))
    stock_list = stock_list.loc[stock_order].reset_index(drop=True)
    print("日K更新顺序：主板非ST优先，其余股票随后更新")

    # 读取已处理记录
    processed_file = os.path.join(base_dir, "已处理股票.txt")
    processed_stocks = set()
    if os.path.exists(processed_file):
        with open(processed_file, 'r', encoding='utf-8') as f:
            for line in f:
                processed_stocks.add(line.strip())
        print(f"已处理记录: {len(processed_stocks)} 只股票")
    
    # 统计现有文件
    existing_raw_files = set([f for f in os.listdir(raw_dir) if f.endswith('.csv')])
    existing_indicator_files = set([f for f in os.listdir(indicator_dir) if f.endswith('.csv')])
    print(f"现有原始数据文件: {len(existing_raw_files)}")
    print(f"现有完整指标文件: {len(existing_indicator_files)}")
    
    print("\n开始处理...")
    print("="*80)
    
    today = datetime.now()
    success_count = 0
    fail_count = 0
    skip_count = 0
    updated_count = 0
    
    for idx, row in stock_list.iterrows():
        code = str(row['code']).zfill(6)
        name = row['name']
        clean_name = clean_filename(name)
        
        # 构造文件名
        raw_filename = f"{code}_{clean_name}_原始数据.csv"
        indicator_filename = f"{code}_{clean_name}_完整指标.csv"
        
        stock_identifier = f"{code}_{name}"
        
        # 检查进度
        print(f"[{idx+1}/{len(stock_list)}] {name} ({code})", end="")
        
        try:
            # 情况1：已有原始数据和完整指标 -> 只更新最新数据
            if raw_filename in existing_raw_files and indicator_filename in existing_indicator_files:
                print(" [更新]", end="")
                
                # 读取现有数据
                raw_path = os.path.join(raw_dir, raw_filename)
                df_raw = pd.read_csv(raw_path)
                
                # 获取最后日期
                if 'date' in df_raw.columns:
                    df_raw['date'] = pd.to_datetime(df_raw['date'])
                elif '日期' in df_raw.columns:
                    df_raw = df_raw.rename(columns={'日期': 'date'})
                    df_raw['date'] = pd.to_datetime(df_raw['date'])
                else:
                    print(" 日期列缺失，跳过")
                    skip_count += 1
                    continue
                
                last_date = df_raw['date'].max()
                
                # 如果数据已经是最新的，跳过
                if last_date >= today - timedelta(days=1):
                    print(" [已是最新]")
                    skip_count += 1
                    continue
                
                # 计算需要更新的天数
                start_date = last_date + timedelta(days=1)
                start_date_str = start_date.strftime('%Y%m%d')
                end_date_str = today.strftime('%Y%m%d')
                
                # 获取新数据
                symbol = 'sh' + code if code.startswith('6') else 'sz' + code
                try:
                    new_data = ak.stock_zh_a_daily(symbol=symbol, start_date=start_date_str, end_date=end_date_str, adjust="")
                except Exception as e:
                    new_data = None
                
                if new_data is not None and len(new_data) > 0:
                    # 合并数据
                    if '日期' in new_data.columns:
                        new_data = new_data.rename(columns={'日期': 'date'})
                    new_data['date'] = pd.to_datetime(new_data['date'])
                    combined_df = pd.concat([df_raw, new_data], ignore_index=True)
                    combined_df = combined_df.drop_duplicates(subset=['date'], keep='last')
                    combined_df = combined_df.sort_values('date').reset_index(drop=True)
                    
                    # 重新计算所有指标
                    df_with_indicators = calculate_indicators(combined_df)
                    
                    if df_with_indicators is not None:
                        # 保存
                        combined_df.to_csv(raw_path, index=False, encoding='utf-8-sig')
                        df_with_indicators.to_csv(os.path.join(indicator_dir, indicator_filename), index=False, encoding='utf-8-sig')
                        print(f" 成功更新 +{len(new_data)} 天")
                        updated_count += 1
                        success_count += 1
                    else:
                        print(" 指标计算失败")
                        fail_count += 1
                else:
                    print(" 无新数据")
                    skip_count += 1
            
            # 情况2：有原始数据但没有完整指标 -> 补全指标
            elif raw_filename in existing_raw_files and indicator_filename not in existing_indicator_files:
                print(" [补全指标]", end="")
                
                # 读取原始数据
                raw_path = os.path.join(raw_dir, raw_filename)
                df_raw = pd.read_csv(raw_path)
                
                # 计算指标
                df_with_indicators = calculate_indicators(df_raw)
                
                if df_with_indicators is not None:
                    df_with_indicators.to_csv(os.path.join(indicator_dir, indicator_filename), index=False, encoding='utf-8-sig')
                    
                    # 记录已处理
                    if stock_identifier not in processed_stocks:
                        with open(processed_file, 'a', encoding='utf-8') as f:
                            f.write(f"{stock_identifier}\n")
                        processed_stocks.add(stock_identifier)
                    
                    print(" 成功")
                    success_count += 1
                else:
                    print(" 指标计算失败")
                    fail_count += 1
            
            # 情况3：什么都没有 -> 重新获取
            else:
                print(" [全新获取]", end="")
                
                # 获取完整历史数据
                symbol = 'sh' + code if code.startswith('6') else 'sz' + code
                start_date = '20210101'
                end_date = today.strftime('%Y%m%d')
                
                try:
                    stock_hist = ak.stock_zh_a_daily(symbol=symbol, start_date=start_date, end_date=end_date, adjust="")
                except Exception as e:
                    stock_hist = None
                
                if stock_hist is not None and len(stock_hist) > 0:
                    # 保存原始数据
                    stock_hist.to_csv(os.path.join(raw_dir, raw_filename), index=False, encoding='utf-8-sig')
                    
                    # 计算指标
                    df_with_indicators = calculate_indicators(stock_hist)
                    
                    if df_with_indicators is not None:
                        df_with_indicators.to_csv(os.path.join(indicator_dir, indicator_filename), index=False, encoding='utf-8-sig')
                        
                        # 记录已处理
                        if stock_identifier not in processed_stocks:
                            with open(processed_file, 'a', encoding='utf-8') as f:
                                f.write(f"{stock_identifier}\n")
                            processed_stocks.add(stock_identifier)
                        
                        print(f" 成功 ({len(stock_hist)} 天)")
                        success_count += 1
                    else:
                        print(" 指标计算失败")
                        fail_count += 1
                else:
                    print(" 获取失败")
                    fail_count += 1
            
            # 防止请求过快
            time.sleep(0.1)
            
        except Exception as e:
            print(f" 错误: {str(e)[:50]}")
            fail_count += 1
            continue
    
    print("\n" + "="*80)
    print("处理完成")
    print(f"成功: {success_count}")
    print(f"更新: {updated_count}")
    print(f"失败: {fail_count}")
    print(f"跳过: {skip_count}")
    print("="*80)

if __name__ == "__main__":
    main()
