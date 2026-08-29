# -*- coding: utf-8 -*-
"""手动/后台执行全部 CSV 到 SQLite 的增量迁移。"""
from market_database import migrate_all_csv, migration_status


if __name__ == "__main__":
    print("[数据库] 开始增量迁移", migration_status(), flush=True)
    result = migrate_all_csv()
    print("[数据库] 最终状态", result, migration_status(), flush=True)
