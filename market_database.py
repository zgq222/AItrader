# -*- coding: utf-8 -*-
"""AItrader 本地 SQLite 数据仓库。

CSV 在迁移期保留为恢复副本；业务查询优先读取 SQLite。每个 CSV 对应一个数据集，
行内容压缩保存，并提取日期、代码、名称作为索引字段。
"""
from __future__ import annotations

import json
import os
import sqlite3
import threading
import zlib
from datetime import datetime
from pathlib import Path

import pandas as pd

BASE_DIR = Path(__file__).resolve().parent
DB_DIR = BASE_DIR / "data" / "database"
DB_PATH = DB_DIR / "aitrader.sqlite3"
MIGRATION_LOCK = DB_DIR / "migration.lock"
_write_lock = threading.RLock()
_migration_thread: threading.Thread | None = None


def connect() -> sqlite3.Connection:
    DB_DIR.mkdir(parents=True, exist_ok=True)
    connection = sqlite3.connect(DB_PATH, timeout=60)
    connection.execute("PRAGMA journal_mode=WAL")
    connection.execute("PRAGMA synchronous=NORMAL")
    connection.execute("PRAGMA temp_store=MEMORY")
    connection.execute("PRAGMA foreign_keys=ON")
    return connection


def initialize() -> None:
    with connect() as connection:
        connection.executescript("""
        CREATE TABLE IF NOT EXISTS datasets (
            id INTEGER PRIMARY KEY,
            source_path TEXT NOT NULL UNIQUE,
            category TEXT NOT NULL,
            dataset_name TEXT NOT NULL,
            source_mtime_ns INTEGER NOT NULL,
            source_size INTEGER NOT NULL,
            row_count INTEGER NOT NULL DEFAULT 0,
            imported_at TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS records (
            dataset_id INTEGER NOT NULL,
            row_no INTEGER NOT NULL,
            trade_date TEXT,
            symbol TEXT,
            display_name TEXT,
            payload BLOB NOT NULL,
            PRIMARY KEY (dataset_id, row_no),
            FOREIGN KEY (dataset_id) REFERENCES datasets(id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS idx_records_dataset_date ON records(dataset_id, trade_date);
        CREATE INDEX IF NOT EXISTS idx_records_symbol ON records(symbol);
        CREATE INDEX IF NOT EXISTS idx_records_name ON records(display_name);
        """)


def _relative(path: Path) -> str:
    path = path.resolve()
    try:
        return path.relative_to(BASE_DIR).as_posix()
    except ValueError:
        return str(path)


def _category(path: Path) -> str:
    relative = _relative(path)
    return relative.split("/", 1)[0] if "/" in relative else "other"


def _scalar(value):
    if value is None or pd.isna(value):
        return None
    if hasattr(value, "item"):
        value = value.item()
    if isinstance(value, (pd.Timestamp, datetime)):
        return value.strftime("%Y-%m-%d %H:%M:%S")
    return value


def _pick(row: dict, keys: tuple[str, ...]):
    for key in keys:
        value = row.get(key)
        if value not in (None, ""):
            return str(value)
    return None


def is_current(path: str | Path) -> bool:
    source = Path(path)
    if not source.exists():
        return False
    stat = source.stat()
    with connect() as connection:
        row = connection.execute(
            "SELECT source_mtime_ns, source_size FROM datasets WHERE source_path=?",
            (_relative(source),),
        ).fetchone()
    return bool(row and row[0] == stat.st_mtime_ns and row[1] == stat.st_size)


def import_csv(path: str | Path, force: bool = False) -> dict:
    source = Path(path)
    if not source.exists():
        raise FileNotFoundError(source)
    if not force and is_current(source):
        return {"state": "current", "path": _relative(source)}

    try:
        chunks = pd.read_csv(source, encoding="utf-8-sig", chunksize=5000, low_memory=False)
    except UnicodeDecodeError:
        chunks = pd.read_csv(source, encoding="gbk", chunksize=5000, low_memory=False)

    stat = source.stat()
    relative = _relative(source)
    imported_at = datetime.now().isoformat(timespec="seconds")
    with _write_lock, connect() as connection:
        connection.execute("BEGIN IMMEDIATE")
        existing = connection.execute("SELECT id FROM datasets WHERE source_path=?", (relative,)).fetchone()
        if existing:
            dataset_id = existing[0]
            connection.execute("DELETE FROM records WHERE dataset_id=?", (dataset_id,))
            connection.execute(
                "UPDATE datasets SET category=?,dataset_name=?,source_mtime_ns=?,source_size=?,row_count=0,imported_at=? WHERE id=?",
                (_category(source), source.stem, stat.st_mtime_ns, stat.st_size, imported_at, dataset_id),
            )
        else:
            cursor = connection.execute(
                "INSERT INTO datasets(source_path,category,dataset_name,source_mtime_ns,source_size,row_count,imported_at) VALUES(?,?,?,?,?,0,?)",
                (relative, _category(source), source.stem, stat.st_mtime_ns, stat.st_size, imported_at),
            )
            dataset_id = cursor.lastrowid

        row_no = 0
        for chunk in chunks:
            values = []
            for record in chunk.to_dict(orient="records"):
                clean = {str(key): _scalar(value) for key, value in record.items()}
                trade_date = _pick(clean, ("date", "日期", "trade_date", "数据日期", "timestamp", "time"))
                symbol = _pick(clean, ("code", "股票代码", "代码", "symbol", "variety", "板块代码"))
                name = _pick(clean, ("name", "股票简称", "股票名称", "名称", "行业", "板块名称"))
                payload = zlib.compress(json.dumps(clean, ensure_ascii=False, separators=(",", ":")).encode("utf-8"), 3)
                values.append((dataset_id, row_no, trade_date, symbol, name, payload))
                row_no += 1
            connection.executemany(
                "INSERT INTO records(dataset_id,row_no,trade_date,symbol,display_name,payload) VALUES(?,?,?,?,?,?)",
                values,
            )
        connection.execute("UPDATE datasets SET row_count=? WHERE id=?", (row_no, dataset_id))
        connection.commit()
    return {"state": "imported", "path": relative, "rows": row_no}


def read_dataframe(path: str | Path, *, usecols=None, nrows: int | None = None, dtype=None) -> pd.DataFrame:
    source = Path(path)
    import_csv(source)
    relative = _relative(source)
    limit_sql = " LIMIT ?" if nrows is not None else ""
    params: tuple = (relative, int(nrows)) if nrows is not None else (relative,)
    with connect() as connection:
        rows = connection.execute(
            "SELECT r.payload FROM records r JOIN datasets d ON d.id=r.dataset_id WHERE d.source_path=? ORDER BY r.row_no" + limit_sql,
            params,
        ).fetchall()
    records = [json.loads(zlib.decompress(row[0]).decode("utf-8")) for row in rows]
    frame = pd.DataFrame(records)
    if usecols is not None and not callable(usecols):
        frame = frame[[column for column in usecols if column in frame.columns]]
    elif callable(usecols):
        frame = frame[[column for column in frame.columns if usecols(column)]]
    if dtype:
        for column, target in dtype.items():
            if column in frame.columns:
                frame[column] = frame[column].astype(target)
    return frame


def list_stock_catalog() -> list[dict]:
    """从数据库数据集目录生成个股工作台股票目录。"""
    with connect() as connection:
        paths = connection.execute(
            "SELECT source_path FROM datasets WHERE source_path LIKE '个股/原始数据/%' OR source_path LIKE '个股/完整指标/%'"
        ).fetchall()
    stocks: dict[str, dict] = {}
    for (path,) in paths:
        match = __import__("re").match(r"个股/(原始数据|完整指标)/(\d{6})_(.+?)_(原始数据|完整指标)\.csv$", path)
        if not match:
            continue
        folder, code, name, kind = match.groups()
        item = stocks.setdefault(code, {"code": code, "name": name, "has_indicator": False, "has_raw": False})
        item["has_indicator" if kind == "完整指标" else "has_raw"] = True
    return sorted(stocks.values(), key=lambda item: item["code"])


def migration_status() -> dict:
    initialize()
    with connect() as connection:
        count, rows, size = connection.execute("SELECT COUNT(*),COALESCE(SUM(row_count),0),COALESCE(SUM(source_size),0) FROM datasets").fetchone()
    return {"database": str(DB_PATH), "datasets": count, "rows": rows, "source_bytes": size}


def _pid_is_running(pid: int) -> bool:
    """跨平台判断进程是否仍存在；Windows 不支持可靠的 os.kill(pid, 0)。"""
    if pid <= 0:
        return False
    if os.name == "nt":
        import ctypes
        process = ctypes.windll.kernel32.OpenProcess(0x1000, False, pid)
        if not process:
            return False
        exit_code = ctypes.c_ulong()
        try:
            if not ctypes.windll.kernel32.GetExitCodeProcess(process, ctypes.byref(exit_code)):
                return False
            return exit_code.value == 259  # STILL_ACTIVE
        finally:
            ctypes.windll.kernel32.CloseHandle(process)
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def migrate_all_csv() -> dict:
    """后台迁移全部业务 CSV；小型公共数据优先，个股大文件随后增量迁移。"""
    DB_DIR.mkdir(parents=True, exist_ok=True)
    try:
        descriptor = os.open(MIGRATION_LOCK, os.O_CREAT | os.O_EXCL | os.O_WRONLY)
        os.write(descriptor, str(os.getpid()).encode("ascii"))
        os.close(descriptor)
    except FileExistsError:
        try:
            owner = int(MIGRATION_LOCK.read_text(encoding="ascii").strip())
            if _pid_is_running(owner):
                print(f"[数据库] 迁移任务已由进程 {owner} 执行，本进程跳过", flush=True)
                return {"state": "already_running", "pid": owner}
            MIGRATION_LOCK.unlink(missing_ok=True)
            return migrate_all_csv()
        except (OSError, ValueError):
            MIGRATION_LOCK.unlink(missing_ok=True)
            return migrate_all_csv()
    roots = [BASE_DIR / name for name in ("板块", "指数", "宏观", "期货", "个股", "财报")]
    files = [path for root in roots if root.exists() for path in root.rglob("*.csv")]
    files.sort(key=lambda path: (path.parts[len(BASE_DIR.parts)] == "个股", path.stat().st_size))
    completed = failed = 0
    try:
        for path in files:
            try:
                import_csv(path)
                completed += 1
            except Exception as exc:
                failed += 1
                print(f"\n[数据库] 迁移失败 {path.name}: {str(exc)[:120]}", flush=True)
            if completed % 100 == 0:
                progress = f"[数据库] 迁移进度 {completed + failed}/{len(files)}，失败 {failed}"
                print("\r" + progress.ljust(100), end="", flush=True)
        print(f"\r[数据库] 迁移完成：{completed} 个数据集，失败 {failed}，数据库 {DB_PATH}".ljust(100), flush=True)
        return {"total": len(files), "completed": completed, "failed": failed}
    finally:
        MIGRATION_LOCK.unlink(missing_ok=True)


def start_background_migration() -> bool:
    global _migration_thread
    if _migration_thread and _migration_thread.is_alive():
        return False
    _migration_thread = threading.Thread(target=migrate_all_csv, name="sqlite-migration", daemon=True)
    _migration_thread.start()
    return True


initialize()
