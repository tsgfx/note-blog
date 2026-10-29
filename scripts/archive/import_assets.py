#!/usr/bin/env python3
"""迁移旧 vault 图片到 storage/assets，并把笔记里的图片链接改写为 /api/assets/...

* 默认只预览；加 --apply 才复制文件并更新 MySQL；
* 不修改 ~/note-ai/vault；
* 数据库更新在单事务中完成，失败回滚。
"""
from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path
from urllib.parse import quote, unquote

VAULT = Path.home() / "note-ai" / "vault"
ASSETS = Path(__file__).resolve().parents[1] / "storage" / "assets"
MYSQL = os.environ.get("MYSQL_BIN", "/usr/local/mysql/bin/mysql")
IMAGE = re.compile(r"!\[([^\]]*)\]\(([^)\s]+)(\s+\"[^\"]*\")?\)")
SKIP_PREFIXES = ("http://", "https://", "data:", "/api/assets/")


def mysql_args() -> list[str]:
    return [MYSQL, "--protocol=TCP", "-h", os.environ.get("DB_HOST", "127.0.0.1"),
            "-u", os.environ.get("DB_USER", "notes"),
            f"-p{os.environ['DB_PASSWORD']}", "--database=notes_blog"]


def load_notes() -> list[tuple[int, str, str]]:
    out = subprocess.check_output(
        mysql_args() + ["-N", "-B", "-e",
                        "SELECT id, source_path, HEX(content_md) FROM notes "
                        "WHERE source_path IS NOT NULL"],
        stderr=subprocess.DEVNULL, text=True)
    rows = []
    for line in out.splitlines():
        note_id, source_path, hex_content = line.split("\t")
        rows.append((int(note_id), source_path, bytes.fromhex(hex_content).decode("utf-8")))
    return rows


def resolve(source_path: str, ref: str) -> Path | None:
    decoded = unquote(ref)
    if decoded.startswith("/"):
        candidate = VAULT / decoded.lstrip("/")
    else:
        candidate = VAULT / Path(source_path).parent / decoded
    candidate = Path(os.path.normpath(candidate))
    try:
        candidate.relative_to(VAULT)
    except ValueError:
        return None
    return candidate if candidate.is_file() else None


def public_url(rel: Path) -> str:
    return "/api/assets/" + "/".join(quote(part, safe="") for part in rel.as_posix().split("/"))


def rewrite(source_path: str, content: str, copies: dict[Path, Path],
            missing: list[str]) -> str:
    def replace(match: re.Match) -> str:
        alt, ref, title = match.group(1), match.group(2), match.group(3) or ""
        if ref.startswith(SKIP_PREFIXES):
            return match.group(0)
        source = resolve(source_path, ref)
        if source is None:
            missing.append(f"{source_path} -> {ref}")
            return match.group(0)
        rel = source.relative_to(VAULT)
        copies[source] = ASSETS / rel
        return f"![{alt}]({public_url(rel)}{title})"
    return IMAGE.sub(replace, content)


def sql_text(value: str) -> str:
    return "CONVERT(0x" + value.encode("utf-8").hex() + " USING utf8mb4)"


def file_digest(path: Path) -> str:
    return hashlib.sha1(path.read_bytes()).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true")
    args = parser.parse_args()

    notes = load_notes()
    copies: dict[Path, Path] = {}
    missing: list[str] = []
    updates: list[tuple[int, str]] = []
    for note_id, source_path, content in notes:
        new_content = rewrite(source_path, content, copies, missing)
        if new_content != content:
            updates.append((note_id, new_content))

    total_bytes = sum(src.stat().st_size for src in copies)
    print(f"笔记：{len(notes)}，需改写：{len(updates)}")
    print(f"需复制图片：{len(copies)}（{total_bytes / 1024 / 1024:.1f} MB）")
    print(f"无法定位的引用：{len(missing)}")
    for item in missing[:20]:
        print("  -", item)
    if not args.apply:
        print("预览模式：加 --apply 才会复制图片并更新 notes_blog。")
        return 0

    ASSETS.mkdir(parents=True, exist_ok=True)
    copied = skipped = 0
    for src, dst in copies.items():
        dst.parent.mkdir(parents=True, exist_ok=True)
        if dst.exists() and dst.stat().st_size == src.stat().st_size:
            skipped += 1
            continue
        shutil.copy2(src, dst)
        copied += 1
    print(f"图片复制完成：新增 {copied}，已存在跳过 {skipped}")

    if updates:
        sql = ["SET NAMES utf8mb4;", "START TRANSACTION;"]
        for note_id, content in updates:
            sql.append(f"UPDATE notes SET content_md = {sql_text(content)} WHERE id = {note_id};")
        sql.append("COMMIT;")
        proc = subprocess.run(mysql_args() + ["--binary-mode"], input="\n".join(sql) + "\n",
                              text=True, capture_output=True)
        if proc.returncode != 0:
            print(proc.stderr, file=sys.stderr)
            return proc.returncode
    print(f"数据库改写完成：{len(updates)} 篇笔记")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
