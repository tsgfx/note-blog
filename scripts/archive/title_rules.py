"""笔记标题的推导规则。导入脚本与标题修复脚本共用同一份，避免两边跑偏。

背景（2026-10-24）：原来 `title_for()` 取「正文里第一个 # 标题」，但导出文件的正体
第一层标题几乎都是**章节名**，于是出现了 25 个都叫「题目描述」、6 个「问题描述」的笔记，
还有 47 个「安装」「概念」「介绍」这种两字标题。

这些文件的 frontmatter 里**没有 title 字段**（只有 tags 与 summary），
而文件名质量很高（695 个里 694 个是有意义的标题），所以：
    文件名优先 → 正文第一个 H1（仅当文件名不可用时）
"""

from __future__ import annotations

import re
from pathlib import Path

# 这些标题是章节名/占位词，不能当笔记标题
JUNK_TITLES = {
    "题目描述",
    "问题描述",
    "输入描述",
    "输出描述",
    "样例",
    "标题",
    "无标题",
    "前言",
    "目录",
    "总目录",
    "简介",
    "介绍",
    "安装",
    "总结",
    "参考",
    "参考资料",
}

_IMAGE = re.compile(r"!\[[^\]]*\]\([^)]*\)")
_LINK = re.compile(r"\[([^\]]*)\]\([^)]*\)")
_HTML = re.compile(r"<[^>]+>")
_EMPHASIS = re.compile(r"[*_`~]+")
_ESCAPE = re.compile(r"\\([\\`*_{}\[\]()#+\-.!|])")


def clean_title(raw: str) -> str:
    """去掉 markdown 语法与转义残留，压成一行。"""
    text = raw
    # 有些导出把制表符写成了字面量 \t（两个字符），不清掉标题里就会出现 "一、time\t别名"
    text = text.replace("\\t", " ").replace("\\n", " ")
    text = _IMAGE.sub("", text)
    text = _LINK.sub(r"\1", text)
    text = _HTML.sub("", text)
    text = _ESCAPE.sub(r"\1", text)
    text = _EMPHASIS.sub("", text)
    text = re.sub(r"\s+", " ", text)
    return text.strip(" -—:：")


def strip_ordering(text: str) -> str:
    """去掉文件名开头的排序编号。

    `21.4_汇编常用指令讲解` → `汇编常用指令讲解`
    `8._循环队列`           → `循环队列`
    `5队列`                 → `队列`
    `3D打印`                → 不动（数字后面不是中文，是真标题的一部分）

    注意：必须在 clean_title **之前**调用。clean_title 会把 `_` 当成 markdown 强调符删掉，
    一旦删掉 `21.4_汇编` 的分隔符，这里就只能剥到 `21.4` 之外，留下 `4汇编` 这种残渣。
    """
    # 前缀全由数字与分隔符组成、且后面紧跟中文 → 整段都是排序号
    stripped = re.sub(r"^[\d\s._\-、&]+(?=[\u4e00-\u9fff])", "", text)
    if stripped != text and stripped.strip():
        return stripped.strip()
    # 纯 ASCII：只剥「数字 + 分隔符」前缀
    return re.sub(r"^\d+(?:[.\-_]\d+)*[.\-_]\s*", "", text).strip()


def is_meaningful(title: str) -> bool:
    """够不够格当标题：不是空的、不是纯数字/纯符号、不在占位词表里。"""
    if not title or len(title) < 2:
        return False
    if re.fullmatch(r"[\d\W_]+", title):
        return False
    return title not in JUNK_TITLES


def first_h1(content: str) -> str | None:
    for line in content.splitlines():
        match = re.match(r"^#\s+(.+?)\s*$", line)
        if match:
            return match.group(1)
    return None


def title_for(path: str, content: str | None = None) -> str:
    """推导标题：文件名优先，不可用时退回正文第一个 H1，再不行就用原名。"""
    # 顺序很重要：先剥编号再清洗，见 strip_ordering 的说明
    raw_stem = clean_title(Path(path).stem)
    stem = clean_title(strip_ordering(Path(path).stem))
    if not is_meaningful(stem):
        stem = raw_stem
    if is_meaningful(stem):
        return stem[:255]

    if content:
        h1 = first_h1(content)
        if h1:
            cleaned = clean_title(h1)
            if is_meaningful(cleaned):
                return cleaned[:255]

    # 兜底：即便不好看，也得有个能点的东西
    return (stem or Path(path).stem)[:255]
