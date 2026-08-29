# AITrader A股研究工作台

一个本地运行的 A 股数据同步、行情分析和量化研究工作台。后端使用 Python 标准 HTTP 服务，前端为单页 ECharts 应用。

## 功能

- A股日线、技术指标与财务报告分析
- 行业/概念板块、资金流和成分股
- 指数、全球资产、汇率和中美日国债收益率
- 中金所股指期货会员持仓与中信期货研究
- 龙虎榜及重点营业部历史研究
- 分时板块资金流视频生成
- 本地量化回测报告库

## 环境

- Python 3.10+
- Windows、macOS 或 Linux
- 首次运行需要联网同步公开市场数据

## 安装

```bash
python -m venv .venv
```

Windows：

```powershell
.venv\Scripts\Activate.ps1
python -m pip install -r requirements.txt
```

macOS / Linux：

```bash
source .venv/bin/activate
python -m pip install -r requirements.txt
```

## 启动

```bash
python kline_server.py
```

浏览器访问：<http://localhost:8000>

服务启动后会创建本地数据目录，并按调度任务同步数据。行情CSV、SQLite数据库、运行状态、视频和缓存默认不进入 Git。

## 主要文件

- `kline_server.py`：HTTP API与页面服务
- `market_sync.py`：市场数据同步及本地归档
- `market_database.py`：SQLite数据存储
- `financial_reports.py`：财务报告获取与季度指标处理
- `update_stocks.py`：个股技术指标计算
- `frontend/index.html`：工作台前端
- `板块/`：同花顺板块抓取辅助脚本

## 数据说明

项目使用 AKShare、东方财富、同花顺公开页面、FRED及交易所公开数据。接口可能随数据源页面调整而失效；公开发布时请遵守相关网站服务条款。项目不附带本地历史行情数据库，使用者需要自行同步。

本项目仅用于数据研究，不构成投资建议。
