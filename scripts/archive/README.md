# 一次性脚本归档

这里放的是**已经跑完、不再属于日常运维入口**的脚本。保留它们是为了可追溯
（当初怎么导入的、标题怎么修的），而不是为了再跑一遍。

| 脚本 | 用途 | 归档原因 |
|---|---|---|
| `import_legacy.py` | 把旧 note-ai 的 Markdown / 分片 / 向量导入 `notes_blog` | 2026-10 已完成全量导入（680 篇），再跑会重复写入 |
| `import_assets.py` | 迁移旧 vault 图片到 `storage/assets` 并改写链接 | 同上，图片已全部迁移 |
| `fix_titles.py` | 按新规则重算已有笔记标题 | 2026-10-24 已执行并核对（备份表已清理） |
| `title_rules.py` | `import_legacy` 与 `fix_titles` 共用的标题推导规则 | 上面两个脚本的依赖，跟着一起归档 |

## 想再跑一次怎么办

三个 python 脚本只依赖标准库与 `PyMySQL`，用法写在各自文件头。**注意两点**：

1. 它们连的是**旧库快照**的语义（`source_path` 非空的笔记），新写的笔记不带 `source_path`，
   再跑不会动到；
2. `import_legacy.py` 会写 `notes` / `note_chunks`，跑之前先 `sh scripts/backup.sh`。

日常维护入口仍然在 `scripts/` 下：`deploy.sh`、`backup.sh`、`backup-cloud.sh`、
`backup-upload-cos.sh`、`export_markdown.py`、`daily-maintenance.sh`。
