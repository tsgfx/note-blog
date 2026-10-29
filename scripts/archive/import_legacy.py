#!/usr/bin/env python3
"""把旧 note-ai 的 Markdown、分片和向量导入 notes_blog。

安全特性：
* 默认只预览；必须传 --apply 才写库；
* 所有 SQL 在一个事务中执行，失败自动回滚；
* 使用 source_path 幂等导入，重复执行不会产生重复笔记；
* 不修改 ~/note-ai/vault 和 ~/note-ai/index。

示例：
  python scripts/import_legacy.py
  DB_PASSWORD='...' python scripts/import_legacy.py --apply
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

import numpy as np


PROJECT = Path(__file__).resolve().parents[1]
SOURCE = Path.home() / "note-ai"
VAULT = SOURCE / "vault"
INDEX = SOURCE / "index"


def sql_text(value: str | None) -> str:
    """用十六进制字面量安全传输 utf8mb4 文本，避免 SQL 注入/转义问题。"""
    if value is None:
        return "NULL"
    if value == "":
        return "''"
    return "CONVERT(0x" + value.encode("utf-8").hex() + " USING utf8mb4)"


def slug_for(path: str) -> str:
    stem = Path(path).with_suffix("").as_posix().lower()
    stem = re.sub(r"[^a-z0-9\u4e00-\u9fff]+", "-", stem).strip("-")
    # 路径很长或同名时仍然稳定唯一。
    suffix = hashlib.sha1(path.encode("utf-8")).hexdigest()[:10]
    return (stem[:220] + "-" + suffix).strip("-")


def title_for(path: str, content: str) -> str:
    # 标题规则统一放在 scripts/title_rules.py，与 fix_titles.py 共用。
    # 2026-10-24 之前这里取「正文第一个 # 标题」，但导出文件的第一层标题几乎都是
    # 章节名，结果 25 篇笔记都叫「题目描述」、47 篇标题只有两个字。
    from title_rules import title_for as shared_title_for

    return shared_title_for(path, content)


def strip_frontmatter(content: str) -> tuple[str, list[str]]:
    """移除 YAML frontmatter，并提取常见的 tags 列表。"""
    if not content.startswith("---"):
        return content, []
    match = re.match(r"^---\s*\n(.*?)\n---\s*\n?", content, flags=re.S)
    if not match:
        return content, []
    tags: list[str] = []
    in_tags = False
    for line in match.group(1).splitlines():
        if re.match(r"^tags\s*:", line, flags=re.I):
            in_tags = True
            inline = line.split(":", 1)[1].strip()
            if inline.startswith("[") and inline.endswith("]"):
                tags.extend(x.strip(" '\"") for x in inline[1:-1].split(",") if x.strip())
            continue
        if in_tags and re.match(r"^\s*-\s+", line):
            tag = re.sub(r"^\s*-\s+", "", line).strip(" '\"")
            if tag:
                tags.append(tag)
        elif line and not line.startswith(" "):
            in_tags = False
    return content[match.end():], list(dict.fromkeys(tags))


def summary_for(content: str) -> str | None:
    text = re.sub(r"```.*?```", "", content, flags=re.S)
    text = re.sub(r"!\[[^]]*\]\([^)]*\)", "", text)
    text = re.sub(r"\[[^]]*\]\([^)]*\)", r"\g<0>", text)
    text = re.sub(r"[#*_>`]", "", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text[:500] or None


def source_notes() -> dict[str, tuple[str, str, str, list[str]]]:
    result = {}
    for path in sorted(VAULT.rglob("*.md")):
        rel = path.relative_to(VAULT).as_posix()
        raw = path.read_text(encoding="utf-8", errors="replace")
        content, tags = strip_frontmatter(raw)
        result[rel] = (title_for(rel, content), content, slug_for(rel), tags)
    return result


def load_chunks():
    meta = json.loads((INDEX / "meta.json").read_text(encoding="utf-8"))
    vectors = np.load(INDEX / "vectors.npy", mmap_mode="r")
    if len(meta) != len(vectors):
        raise RuntimeError(f"meta/vector 数量不一致：{len(meta)} vs {len(vectors)}")
    return meta, vectors


def generate_sql(notes, meta, vectors):
    yield "SET NAMES utf8mb4;\nSTART TRANSACTION;\n"
    for path, (title, content, slug, tags) in notes.items():
        yield (
            "INSERT INTO notes (title, slug, summary, content_md, status, source_path) "
            f"VALUES ({sql_text(title)}, {sql_text(slug)}, {sql_text(summary_for(content))}, "
            f"{sql_text(content)}, 'DRAFT', {sql_text(path)}) "
            "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id), title=VALUES(title), "
            "summary=VALUES(summary), content_md=VALUES(content_md);\n"
            "SET @current_note_id = LAST_INSERT_ID();\n"
            "DELETE FROM note_chunks WHERE note_id=@current_note_id;\n"
            "DELETE FROM note_tags WHERE note_id=@current_note_id;\n"
        )
        for tag in tags:
            yield (
                "INSERT INTO tags (name) VALUES (" + sql_text(tag[:64]) + ") "
                "ON DUPLICATE KEY UPDATE id=LAST_INSERT_ID(id);\n"
                "SET @current_tag_id = LAST_INSERT_ID();\n"
                "INSERT IGNORE INTO note_tags (note_id, tag_id) "
                "VALUES (@current_note_id, @current_tag_id);\n"
            )

    # 将分片按 source_path 归属到对应笔记。
    grouped: dict[str, list[tuple[int, dict, np.ndarray]]] = {}
    for ordinal, (item, vector) in enumerate(zip(meta, vectors)):
        grouped.setdefault(item["path"], []).append((ordinal, item, vector))

    for path, chunks in grouped.items():
        if path not in notes:
            continue
        yield f"SET @current_note_id = (SELECT id FROM notes WHERE source_path={sql_text(path)});\n"
        values = []
        for ordinal, item, vector in chunks:
            vector_json = json.dumps(vector.astype(float).tolist(), separators=(",", ":"))
            heading = item.get("heading")
            if heading:
                heading = heading[:255]
            values.append(
                f"(@current_note_id, {sql_text(heading)}, {ordinal}, "
                f"{sql_text(item.get('text', ''))}, STRING_TO_VECTOR({sql_text(vector_json)}))"
            )
            if len(values) >= 25:
                yield "INSERT INTO note_chunks (note_id, heading, ordinal, content, embedding) VALUES " + ",\n".join(values) + ";\n"
                values = []
        if values:
            yield "INSERT INTO note_chunks (note_id, heading, ordinal, content, embedding) VALUES " + ",\n".join(values) + ";\n"

    yield "COMMIT;\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--apply", action="store_true", help="真正写入 MySQL；默认只预览")
    args = parser.parse_args()

    notes = source_notes()
    meta, vectors = load_chunks()
    paths = {x["path"] for x in meta}
    print(f"笔记文件：{len(notes)}")
    print(f"索引分片：{len(meta)}")
    print(f"向量形状：{vectors.shape}")
    print(f"有向量对应的笔记：{len(paths & notes.keys())}")
    if not args.apply:
        print("预览模式：加 --apply 才会写入 notes_blog。")
        return 0

    password = os.environ.get("DB_PASSWORD")
    if not password:
        print("缺少 DB_PASSWORD 环境变量", file=sys.stderr)
        return 2
    cmd = [
        os.environ.get("MYSQL_BIN", "/usr/local/mysql/bin/mysql"),
        "--protocol=TCP", "-h", os.environ.get("DB_HOST", "127.0.0.1"),
        "-u", os.environ.get("DB_USER", "notes"),
        f"-p{password}", "--database=notes_blog", "--binary-mode",
    ]
    print("开始导入（事务中）...")
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE, text=True)
    assert proc.stdin is not None
    try:
        for sql in generate_sql(notes, meta, vectors):
            proc.stdin.write(sql)
        proc.stdin.close()
        code = proc.wait()
    except Exception:
        proc.kill()
        proc.wait()
        raise
    if code != 0:
        print(f"导入失败，事务应已回滚（mysql exit={code}）", file=sys.stderr)
        return code
    print("导入完成。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
