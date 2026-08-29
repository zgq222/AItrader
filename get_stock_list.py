
# -*- coding: utf-8 -*-
import akshare as ak
import pandas as pd
import os

print("=== 获取A股股票列表 ===\n")

# 创建输出目录
output_dir = "个股"
os.makedirs(output_dir, exist_ok=True)

try:
    print("正在获取股票列表...")
    stock_list = ak.stock_info_a_code_name()
    print(f"成功获取 {len(stock_list)} 只股票\n")
    
    print("股票列表预览:")
    print(stock_list.head(10).to_string(index=False))
    
    # 保存股票列表
    stock_list.to_csv(os.path.join(output_dir, "A股股票列表.csv"), index=False, encoding='utf-8-sig')
    print(f"\n股票列表已保存到: {os.path.join(output_dir, 'A股股票列表.csv')}")
    
except Exception as e:
    print(f"获取股票列表失败: {e}")
