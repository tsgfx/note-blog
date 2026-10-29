#!/usr/bin/env python3
"""列出 OpenCode Zen 当前免费（限时）模型，并可选实测可用性。

数据源 models.dev（OpenCode 官方 SST 团队维护的模型库），
cost.input 和 cost.output 均为 0 即视为免费模型。
限时免费随时可能转收费，勿长期依赖；博客长期方案建议 DeepSeek / 硅基流动。

实测注意（2026-10-25 验证）：
- 免费模型走裸 API（非 OpenCode 客户端）有用量上限（FreeUsageLimitError），
  客户端内使用则不限。
- Go 订阅端点（/zen/go/v1）要求 x-opencode-session 头，裸调直接报
  MissingSessionID，不能作为服务端 API 用。

用法:
  python3 scripts/opencode-free-models.py                  # 列出免费模型
  python3 scripts/opencode-free-models.py --all            # 全部模型（含价格）
  python3 scripts/opencode-free-models.py --probe id1 id2  # 实测指定模型
  python3 scripts/opencode-free-models.py --probe --free   # 实测全部免费模型

API key 来源（按序）: --api-key > 环境变量 OPENCODE_API_KEY > opencode.db 凭证。
"""

from __future__ import annotations

import argparse
import json
import sqlite3
import sys
import urllib.error
import urllib.request
from pathlib import Path

MODELS_DEV = "https://models.dev/api.json"
PROVIDER_ID = "opencode"
DEFAULT_BASE = "https://opencode.ai/inference/openai/v1"
DB_CANDIDATES = [
    Path.home() / ".local/share/opencode/opencode.db",
]


def fetch_provider() -> dict:
    req = urllib.request.Request(MODELS_DEV, headers={"User-Agent": "notes-blog/0.1"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = json.load(resp)
    provider = data.get(PROVIDER_ID)
    if not provider:
        sys.exit(f"models.dev 中找不到 provider: {PROVIDER_ID}")
    return provider


def is_free(model: dict) -> bool:
    cost = model.get("cost") or {}
    return (cost.get("input") or 0) == 0 and (cost.get("output") or 0) == 0


def fmt_cost(v: float | None) -> str:
    if v is None:
        return "?"
    return f"${v:g}"


def list_models(models: dict, all_models: bool) -> None:
    rows = []
    for mid, m in models.items():
        cost = m.get("cost") or {}
        limit = m.get("limit") or {}
        rows.append({
            "id": mid,
            "name": m.get("name", mid),
            "free": is_free(m),
            "ctx": limit.get("context"),
            "out": limit.get("output"),
            "in_price": fmt_cost(cost.get("input")),
            "out_price": fmt_cost(cost.get("output")),
            "reasoning": bool(m.get("reasoning")),
            "tools": bool(m.get("tool_call")),
        })
    if not all_models:
        rows = [r for r in rows if r["free"]]
    rows.sort(key=lambda r: (not r["free"], r["id"]))

    hdr = f"{'model id':38} {'名称':24} {'上下文':>9} {'输出':>8} {'输入价':>9} {'输出价':>9} {'推理':>3} {'工具':>3}"
    print(hdr)
    print("-" * len(hdr))
    for r in rows:
        flag = "FREE" if r["free"] else "    "
        print(f"{r['id']:38} {r['name'][:22]:24} {str(r['ctx']):>9} {str(r['out']):>8} "
              f"{r['in_price']:>9} {r['out_price']:>9} {'Y' if r['reasoning'] else '-':>3} "
              f"{'Y' if r['tools'] else '-':>3}  {flag}")
    print(f"\n共 {len(rows)} 个模型（{'全部' if all_models else '免费'}）")


def load_api_key() -> str | None:
    import os
    if os.environ.get("OPENCODE_API_KEY"):
        return os.environ["OPENCODE_API_KEY"]
    for db in DB_CANDIDATES:
        if not db.exists():
            continue
        try:
            conn = sqlite3.connect(db)
            rows = conn.execute("select value from credential").fetchall()
            conn.close()
            for (value,) in rows:
                try:
                    data = json.loads(value)
                except (TypeError, ValueError):
                    continue
                if isinstance(data, dict) and data.get("type") == "key" and data.get("key"):
                    return data["key"]
        except sqlite3.Error:
            continue
    return None


def probe(model_id: str, base: str, api_key: str, timeout: int = 60) -> dict:
    body = json.dumps({
        "model": model_id,
        "messages": [{"role": "user", "content": "请只回复两个字：正常"}],
        "max_tokens": 4096,
    }).encode()
    req = urllib.request.Request(
        f"{base.rstrip('/')}/chat/completions",
        data=body,
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
            "User-Agent": "notes-blog/0.1",
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            data = json.load(resp)
        content = (data.get("choices") or [{}])[0].get("message", {}).get("content", "")
        usage = data.get("usage") or {}
        return {"ok": True, "detail": (content or "(空回复，可能全是思考过程)")[:60],
                "tokens": usage.get("total_tokens")}
    except urllib.error.HTTPError as e:
        try:
            err = json.loads(e.read().decode(errors="replace"))
        except Exception:
            err = {}
        msg = err.get("error", err)
        if isinstance(msg, dict):
            code = msg.get("type") or msg.get("code") or ""
            text = msg.get("message", "")
        else:
            code, text = "", str(msg)
        if "FreeUsageLimit" in code or "Free usage" in text:
            hint = "（免费额度已用完：裸 API 有限制，OpenCode 客户端内不限）"
        elif "MissingSessionID" in code:
            hint = "（该端点要求 x-opencode-session 头，是 Go 客户端专用端点）"
        elif "unavailable" in text:
            hint = "（模型已下架或上游不可用——限时免费可能已结束）"
        else:
            hint = ""
        return {"ok": False, "detail": f"{e.code} {code} {text[:120]} {hint}"}


def main() -> None:
    ap = argparse.ArgumentParser(description="OpenCode Zen 免费模型查询/实测")
    ap.add_argument("--all", action="store_true", help="列出全部模型（含付费）")
    ap.add_argument("--probe", nargs="*", metavar="MODEL_ID",
                    help="实测指定模型；不接 id 时需配合 --free")
    ap.add_argument("--free", action="store_true", help="配合 --probe 实测全部免费模型")
    ap.add_argument("--api-key", help="OpenCode API key（默认自动从 opencode.db 取）")
    ap.add_argument("--base-url", default=DEFAULT_BASE, help=f"Zen API 端点（默认 {DEFAULT_BASE}）")
    args = ap.parse_args()

    provider = fetch_provider()
    models = provider.get("models", {})

    if args.probe is not None:
        ids = args.probe
        if args.free:
            ids = [m for m, v in models.items() if is_free(v)]
        if not ids:
            ap.error("请指定模型 id，或加 --free 实测全部免费模型")
        key = args.api_key or load_api_key()
        if not key:
            sys.exit("找不到 API key：用 --api-key 传入或设置 OPENCODE_API_KEY")
        print(f"端点: {args.base_url}\n")
        ok = 0
        for mid in ids:
            if mid not in models:
                print(f"✗ {mid:36} models.dev 里没有这个模型 id")
                continue
            r = probe(mid, args.base_url, key)
            mark = "✓" if r["ok"] else "✗"
            ok += r["ok"]
            tail = f"  [{r['tokens']} tokens]" if r.get("tokens") else ""
            print(f"{mark} {mid:36} {r['detail']}{tail}")
        print(f"\n{ok}/{len(ids)} 可用")
        return

    list_models(models, args.all)


if __name__ == "__main__":
    main()
