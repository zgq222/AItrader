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

## 工作台概念轮动

个股工作台新增“概念轮动·主板非ST”，与行业板块轮动使用相同的共享交易日历、全市场等权基准及四项排名百分位评分：5日/20日相对强度、5日跑赢市场比例、站上MA20比例等权平均，强度与广度各50%。缺项不补零，四项齐全才评分。概念内保留全部沪深主板非ST成员，按最近30个交易日最低日K低点至最新收盘价涨幅降序，缺行情置后。一只股票可在多个概念出现，市场基准仍只计算一次。

当前覆盖293个同花顺概念。成员采用开盘红/levistock提供的2026-09-30日期快照，保存在`板块/成分股/concept`；点击分组刷新按钮补齐缺失或过期快照并重算排名。网页读取时按本地行情变化更新分组。点击概念查看本地同花顺日K、与大盘合并图、强度与广度历史、资金净额及分钟K线；缺失资金或分钟行情留空并说明。历史使用当前成员回看，不视为历史时点成分股回测。

新增`/api/concept-rotation/refresh`、`/api/board/concept-strength-history`及`/api/board/concept-flow-history`。验证：`python -m unittest test_concept_rotation test_industry_strength test_sector_rotation test_industry_board_summary`与`node --test test_watchlist_frontend.js test_board_minute_frontend.js`。

## 手机端工程

Android原生工程位于`mobile-hammer/`，包含市场总览、行业轮动、自选股、持仓股与双层自选延伸；行业和概念图显示日K、强度及广度，个股支持日K和15分钟K线。K线页面默认竖屏，图表标题旁可点击“横屏查看”，顶部操作栏在滚动时保持可见。具体版本及规则见`mobile-hammer/README.md`。

工程使用Android Gradle Plugin 8.7.3、Gradle 8.9、Java 17及Android SDK 35，最低Android 8.0（API 26）。安装相应工具后，在`mobile-hammer/`运行`gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`。源码保留内置分类、板块快照及测试资源；本机SDK路径、签名密钥、构建产物和动态行情缓存留在本机。

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
