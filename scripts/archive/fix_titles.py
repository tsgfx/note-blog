#!/usr/bin/env python3
"""按新的标题规则重算已有笔记的标题。

    python3 scripts/fix_titles.py              # 只看统计与前若干条改动（默认）
    python3 scripts/fix_titles.py --sql out.sql # 生成可直接灌库的 SQL
    python3 scripts/fix_titles.py --apply       # 备份旧标题后直接执行

只改 `notes.title`，不动正文、slug、发布状态。
执行前会把「旧标题 → 新标题」逐条写进 `notes_title_backup_<日期>` 表，
想回滚直接 UPDATE 回去。
"""

from __future__ import annotations

import argparse
import datetime as dt
import re
import sqlite3
import subprocess
import sys
import unicodedata
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from title_rules import clean_title, title_for  # noqa: E402

ROOT = Path(__file__).resolve().parent.parent
EXPORT = ROOT / "export"


def current_titles() -> dict[str, str]:
    """从数据库读出 source_path → 当前标题。

    用 mysql 客户端而不是 Python 驱动，避免为了改标题引入额外依赖；
    容器里的客户端连的是同一个库，凭据用容器自带的。
    """
    sql = "SELECT source_path, title FROM notes;"
    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "mysql",
         "sh", "-c",
         'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" --default-character-set=utf8mb4 '
         '-N --batch notes_blog -e ' + shell_quote(sql)],
        cwd=ROOT, capture_output=True, text=True,
    )
    if result.returncode != 0:
        sys.exit(f"读取数据库失败：{result.stderr.strip()}")

    mapping: dict[str, str] = {}
    for line in result.stdout.splitlines():
        if "\t" not in line:
            continue
        path, title = line.split("\t", 1)
        mapping[path] = title
    return mapping


def shell_quote(text: str) -> str:
    return "'" + text.replace("'", "'\\''") + "'"


def sql_text(value: str) -> str:
    return "'" + value.replace("\\", "\\\\").replace("'", "''") + "'"


def is_clearly_bad(title: str, duplicate_count: int) -> bool:
    """只判定「明显坏掉」的标题，避免把好标题改成寡淡的文件名。

    四条标准，都是不需要品味就能判断的：
    1. 混进了 markdown 垃圾（图片、URL、转义残留）—— 一眼就是导入事故
    2. 太长（>60 字）—— 列表里根本放不下
    3. 占位词/纯数字/两字以内 —— 「题目描述」「安装」「1」这类当标题没有信息量
    4. 多篇重名 —— 列表里出现 25 个「题目描述」，等于没有标题
    """
    from title_rules import JUNK_TITLES, is_meaningful

    if "![" in title or "http://" in title or "https://" in title or "\\" in title:
        return True
    if len(title) > 60:
        return True
    # 「5队列」这类是导出时加的排序号，不是标题的一部分
    if re.match(r"^\d{1,2}[\u4e00-\u9fff]", title):
        return True
    if title in JUNK_TITLES or not is_meaningful(title):
        return True
    return duplicate_count > 1


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="真正执行 UPDATE")
    parser.add_argument("--sql", metavar="FILE", help="把 SQL 写到文件而不执行")
    parser.add_argument("--limit", type=int, default=15, help="打印多少条改动样例")
    parser.add_argument(
        "--mode",
        choices=["targeted", "all"],
        default="targeted",
        help="targeted=只修明显坏的（默认）；all=全部改用文件名",
    )
    args = parser.parse_args()

    existing = current_titles()
    # 重名统计：列表里出现多个同名笔记，等于这些笔记没有标题
    seen: dict[str, int] = {}
    for title in existing.values():
        seen[title] = seen.get(title, 0) + 1

    changes: list[tuple[str, str, str]] = []  # (path, old, new)
    missing_on_disk: list[str] = []

    for path, old_title in existing.items():
        source = EXPORT / path
        content = source.read_text(encoding="utf-8", errors="replace") if source.exists() else None
        if content is None:
            missing_on_disk.append(path)
        if args.mode == "targeted" and not is_clearly_bad(old_title, seen.get(old_title, 1)):
            continue
        new_title = title_for(path, content)
        if clean_title(new_title) != clean_title(old_title):
            changes.append((path, old_title, new_title))

    print(f"库里笔记 {len(existing)} 篇，模式 {args.mode}，标题需要修改 {len(changes)} 篇")
    if missing_on_disk:
        print(f"注意：{len(missing_on_disk)} 篇在磁盘上找不到源文件，标题沿用旧值")

    for path, old, new in changes[: args.limit]:
        print(f"  {path}\n    旧：{old[:70]}\n    新：{new[:70]}")

    if not changes:
        return 0

    stamp = dt.date.today().strftime("%Y%m%d")
    backup = f"notes_title_backup_{stamp}"
    lines = [
        f"CREATE TABLE IF NOT EXISTS {backup} (",
        "  source_path VARCHAR(512) NOT NULL,",
        "  old_title   VARCHAR(255) NOT NULL,",
        "  new_title   VARCHAR(255) NOT NULL,",
        "  changed_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,",
        "  PRIMARY KEY (source_path)",
        ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;",
        "",
    ]
    for path, old, new in changes:
        lines.append(
            f"INSERT IGNORE INTO {backup} (source_path, old_title, new_title) "
            f"VALUES ({sql_text(path)}, {sql_text(old)}, {sql_text(new)});"
        )
        lines.append(f"UPDATE notes SET title = {sql_text(new)} WHERE source_path = {sql_text(path)};")
    script = "\n".join(lines) + "\n"

    if args.sql:
        Path(args.sql).write_text(script, encoding="utf-8")
        print(f"\nSQL 已写入 {args.sql}（{len(changes)} 条改动 + 备份表 {backup}）")
        return 0

    if not args.apply:
        print(f"\n这是 dry-run。加 --apply 执行，或 --sql <file> 先导出审阅。")
        print(f"执行时会先把旧标题存进 {backup}。")
        return 0

    result = subprocess.run(
        ["docker", "compose", "exec", "-T", "mysql",
         "sh", "-c",
         'mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" --default-character-set=utf8mb4 notes_blog'],
        cwd=ROOT, input=script, text=True, capture_output=True,
    )
    if result.returncode != 0:
        sys.exit(f"执行失败：{result.stderr.strip()}")
    print(f"\n✓ 已更新 {len(changes)} 篇标题，旧标题存在 {backup} 表")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
