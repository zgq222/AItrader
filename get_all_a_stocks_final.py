
# -*- coding: utf-8 -*-
"""
功能一：获取所有A股个股的完整指标
用于首次获取所有A股的历史数据并计算全部技术指标
"""
import akshare as ak
import pandas as pd
import os
import re
from datetime import datetime
import time

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
    print("功能一：获取所有A股个股的完整指标")
    print("="*80)
    
    # 创建输出目录
    output_dir = "个股"
    os.makedirs(output_dir, exist_ok=True)
    os.makedirs(os.path.join(output_dir, "原始数据"), exist_ok=True)
    os.makedirs(os.path.join(output_dir, "完整指标"), exist_ok=True)
    
    # 获取股票列表
    print("\n正在获取A股股票列表...")
    try:
        stock_list = ak.stock_info_a_code_name()
        print(f"成功获取 {len(stock_list)} 只股票")
        stock_list.to_csv(os.path.join(output_dir, "A股股票列表.csv"), index=False, encoding='utf-8-sig')
    except Exception as e:
        print(f"获取股票列表失败: {e}")
        return
    
    # 加载已处理记录
    processed_file = os.path.join(output_dir, "已处理股票.txt")
    processed_stocks = set()
    if os.path.exists(processed_file):
        with open(processed_file, 'r', encoding='utf-8') as f:
            for line in f:
                processed_stocks.add(line.strip())
        print(f"已加载处理记录: {len(processed_stocks)} 只股票")
    
    # 处理进度
    today = datetime.now()
    start_date = "20210101"
    end_date = today.strftime('%Y%m%d')
    
    success_count = 0
    fail_count = 0
    skip_count = 0
    
    print("\n开始获取所有A股数据...")
    print("="*80)
    
    for i, row in stock_list.iterrows():
        code = str(row['code']).zfill(6)
        name = row['name']
        clean_name = clean_filename(name)
        
        # 检查是否已处理
        stock_identifier = f"{code}_{name}"
        if stock_identifier in processed_stocks:
            skip_count += 1
            continue
        
        print(f"[{i+1}/{len(stock_list)}] 正在处理: {name} ({code})", end="")
        
        try:
            # 获取历史数据
            symbol = 'sh' + code if code.startswith('6') else 'sz' + code
            stock_hist = ak.stock_zh_a_daily(symbol=symbol, start_date=start_date, end_date=end_date, adjust='')
            
            if len(stock_hist) == 0:
                print(" 无数据")
                fail_count += 1
                continue
            
            # 保存原始数据
            raw_file = os.path.join(output_dir, "原始数据", f"{code}_{clean_name}_原始数据.csv")
            stock_hist.to_csv(raw_file, index=False, encoding='utf-8-sig')
            
            # 计算指标
            df_with_indicators = calculate_indicators(stock_hist)
            
            if df_with_indicators is not None:
                indicator_file = os.path.join(output_dir, "完整指标", f"{code}_{clean_name}_完整指标.csv")
                df_with_indicators.to_csv(indicator_file, index=False, encoding='utf-8-sig')
                
                # 记录已处理
                with open(processed_file, 'a', encoding='utf-8') as f:
                    f.write(f"{stock_identifier}\n")
                processed_stocks.add(stock_identifier)
                
                print(f" 成功 (数据点: {len(stock_hist)})")
                success_count += 1
            else:
                print(" 指标计算失败")
                fail_count += 1
            
            # 防止请求过快
            time.sleep(0.1)
            
        except Exception as e:
            print(f" 失败: {str(e)[:60]}")
            fail_count += 1
            continue
    
    print("\n" + "="*80)
    print("获取完成！")
    print(f"成功: {success_count}")
    print(f"失败: {fail_count}")
    print(f"跳过: {skip_count}")
    print(f"总计: {success_count + fail_count + skip_count}")
    print("\n数据已保存在:")
    print(f"  - {output_dir}/原始数据/")
    print(f"  - {output_dir}/完整指标/")
    print("="*80)

if __name__ == "__main__":
    main()
