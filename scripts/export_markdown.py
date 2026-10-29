#!/usr/bin/env python3
"""把笔记库导出为普通 Markdown 目录，可直接用 Obsidian 打开。

* 只读数据库，不修改任何数据；输出到独立目录（默认 项目内 export/）；
* 有原始路径的笔记按原目录结构还原，新建笔记放入「新建笔记」目录；
* 图片链接改写为相对路径，并复制被引用的图片，保证离线可用；
* 有标签或摘要时写入 YAML frontmatter。

用法：DB_PASSWORD=... python3 scripts/export_markdown.py [--out DIR]
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
from pathlib import Path
from urllib.parse import quote, unquote

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "storage" / "assets"
MYSQL = os.environ.get("MYSQL_BIN", "/usr/local/mysql/bin/mysql")
IMAGE = re.compile(r"(!\[[^\]]*\]\()(/api/assets/[^)\s]+)(\s+\"[^\"]*\")?\)")
UNSAFE = re.compile(r'[\\/:*?"<>|\r\n]')


def query(sql: str) -> list[list[str]]:
    cmd = [MYSQL, "--protocol=TCP", "-h", os.environ.get("DB_HOST", "127.0.0.1"),
           "-u", os.environ.get("DB_USER", "notes"), "--database=notes_blog",
           "-N", "-B", "-e", sql]
    env = dict(os.environ, MYSQL_PWD=os.environ["DB_PASSWORD"])
    out = subprocess.check_output(cmd, env=env, stderr=subprocess.DEVNULL, text=True)
    return [line.split("\t") for line in out.splitlines() if line]


def text(hex_value: str) -> str:
    return bytes.fromhex(hex_value).decode("utf-8") if hex_value else ""


def safe_name(value: str) -> str:
    return UNSAFE.sub("_", value).strip()[:120] or "未命名"


def yaml_string(value: str) -> str:
    return '"' + value.replace("\\", "\\\\").replace('"', '\\"') + '"'


def note_path(source_hex: str, title: str, slug: str) -> str:
    source = text(source_hex)
    if source:
        return source
    return f"新建笔记/{safe_name(title)}-{slug}.md"


def rewrite_images(content: str, note_dir: Path, out: Path,
                   copied: set[str], missing: list[str]) -> str:
    def replace(match: re.Match) -> str:
        prefix, url, title = match.group(1), match.group(2), match.group(3) or ""
        rel = unquote(url[len("/api/assets/"):])
        source = ASSETS / rel
        if not source.is_file():
            missing.append(rel)
            return match.group(0)
        target = out / rel
        if rel not in copied:
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            copied.add(rel)
        relative = os.path.relpath(target, note_dir).replace(os.sep, "/")
        encoded = "/".join(quote(part, safe="") for part in relative.split("/"))
        return f"{prefix}{encoded}{title})"
    return IMAGE.sub(replace, content)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default=str(ROOT / "export"))
    args = parser.parse_args()
    out = Path(args.out).expanduser().resolve()
    out.mkdir(parents=True, exist_ok=True)

    tags_by_note: dict[str, list[str]] = {}
    for note_id, tag_hex in query("SELECT nt.note_id, HEX(t.name) FROM note_tags nt "
                                  "JOIN tags t ON t.id = nt.tag_id ORDER BY t.name"):
        tags_by_note.setdefault(note_id, []).append(text(tag_hex))

    rows = query("SELECT id, slug, HEX(title), HEX(COALESCE(summary, '')), "
                 "HEX(COALESCE(source_path, '')), HEX(content_md) FROM notes ORDER BY id")
    copied: set[str] = set()
    missing: list[str] = []
    written = 0
    for note_id, slug, title_hex, summary_hex, source_hex, content_hex in rows:
        title = text(title_hex)
        summary = text(summary_hex)
        rel_path = note_path(source_hex, title, slug)
        note_file = out / rel_path
        note_file.parent.mkdir(parents=True, exist_ok=True)

        body = rewrite_images(text(content_hex), note_file.parent, out, copied, missing)
        tags = tags_by_note.get(note_id, [])
        front = []
        if tags:
            front.append("tags:")
            front.extend(f"  - {yaml_string(tag)}" for tag in tags)
        if summary:
            front.append(f"summary: {yaml_string(summary)}")
        if front:
            body = "---\n" + "\n".join(front) + "\n---\n\n" + body.lstrip("\n")
        note_file.write_text(body, encoding="utf-8")
        written += 1

    print(f"导出笔记：{written} 篇 → {out}")
    print(f"复制图片：{len(copied)} 张")
    print(f"缺失图片引用：{len(missing)}（保持原样，见下）")
    for item in missing[:20]:
        print("  -", item)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
