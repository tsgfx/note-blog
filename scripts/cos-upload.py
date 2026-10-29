#!/usr/bin/env python3
"""把本地资产目录整体同步到腾讯云 COS（保持相对路径）。

用法（密钥走环境变量，不落盘）：
  COS_SECRET_ID=xxx COS_SECRET_KEY=xxx python3 scripts/cos-upload.py \
      --dir storage/assets --bucket note-blog-img-1324019419 --region ap-beijing

备份上云（对象标记 private，不依赖桶的可见性）：
  python3 scripts/cos-upload.py --dir /tmp/enc --bucket ... --prefix backups/ \
      --acl private --signed-head --workers 1

特性：
- 幂等续传：先 HEAD 对比大小，一致就跳过，中断后重跑即可
- 8 线程并发 + 失败重试一次
- 相对路径直接作为 COS Key（与 /api/assets/<path> 一一对应）
"""
import argparse
import hashlib
import hmac
import os
import sys
import time
import urllib.request
import urllib.error
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path
from urllib.parse import quote

def sign(method, key, sid, skey):
    now = str(int(time.time()) - 60)
    keytime = f"{now};{int(time.time()) + 600}"
    signkey = hmac.new(skey.encode(), keytime.encode(), hashlib.sha1).hexdigest()
    httpstring = f"{method.lower()}\n/{key}\n\n\n"
    sig_part = hashlib.sha1(httpstring.encode()).hexdigest()
    stringtosign = f"sha1\n{keytime}\n{sig_part}\n"
    signature = hmac.new(signkey.encode(), stringtosign.encode(), hashlib.sha1).hexdigest()
    return ("q-sign-algorithm=sha1&q-ak=" + sid + "&q-sign-time=" + keytime +
            "&q-key-time=" + keytime + "&q-header-list=&q-url-param-list=&q-signature=" + signature)

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", required=True)
    ap.add_argument("--bucket", required=True)
    ap.add_argument("--region", default="ap-beijing")
    ap.add_argument("--prefix", default="", help="对象键前缀，如 backups/")
    ap.add_argument("--workers", type=int, default=8)
    # 对象的 ACL：默认 default（跟随桶的读写权限）。
    # 备份上云时传 private，**不依赖桶本身的可见性**——
    # 万一桶是公有读的，标记 private 的对象也不会被匿名 GET 到。
    ap.add_argument("--acl", default="default", choices=["default", "private"])
    # 增量判断用 HEAD。公有读桶匿名就能 HEAD；私有对象必须带签名，否则一律 403。
    ap.add_argument("--signed-head", action="store_true",
                    help="用签名 HEAD 做增量判断（目标对象为 private 或桶非公有读时用）")
    args = ap.parse_args()

    sid = os.environ.get("COS_SECRET_ID", "")
    skey = os.environ.get("COS_SECRET_KEY", "")
    if not sid or not skey:
        sys.exit("错误：请设置 COS_SECRET_ID / COS_SECRET_KEY 环境变量")

    root = Path(args.dir).resolve()
    files = [p for p in root.rglob("*") if p.is_file()]
    host = f"{args.bucket}.cos.{args.region}.myqcloud.com"
    print(f"本地 {len(files)} 个文件，目标 https://{host}/")

    def rel(p):
        prefix = args.prefix
        if prefix and not prefix.endswith("/"):
            prefix += "/"
        return prefix + p.relative_to(root).as_posix()

    # 先做增量判断（默认匿名 HEAD，公有读桶即可）
    todo, skipped = [], 0
    for p in files:
        key = rel(p)
        try:
            # 请求 URL 必须百分号编码（路径含中文）；签名用原始 key
            headers = {}
            if args.signed_head:
                headers["Authorization"] = sign("head", key, sid, skey)
            req = urllib.request.Request(f"https://{host}/{quote(key)}", method="HEAD",
                                         headers=headers)
            with urllib.request.urlopen(req, timeout=10) as r:
                if int(r.headers.get("Content-Length", -1)) == p.stat().st_size:
                    skipped += 1
                    continue
        except urllib.error.HTTPError as e:
            if e.code not in (403, 404):
                print(f"HEAD 异常 {key}: {e.code}")
        except Exception:
            pass
        todo.append(p)
    print(f"跳过已存在 {skipped}，待上传 {len(todo)}")

    done = ok = fail = 0
    failures = []

    def upload(p):
        key = rel(p)
        auth = sign("put", key, sid, skey)
        headers = {"Authorization": auth, "Content-Type": "application/octet-stream"}
        if args.acl != "default":
            headers["x-cos-acl"] = args.acl
        req = urllib.request.Request(
            f"https://{host}/{quote(key)}", data=p.read_bytes(), method="PUT",
            headers=headers)
        with urllib.request.urlopen(req, timeout=120):
            return key

    with ThreadPoolExecutor(max_workers=args.workers) as pool:
        futures = {pool.submit(upload, p): p for p in todo}
        for fut in as_completed(futures):
            p = futures[fut]
            done += 1
            try:
                fut.result()
                ok += 1
            except Exception as e:
                # 失败重试一次
                try:
                    upload(p)
                    ok += 1
                except Exception as e2:
                    fail += 1
                    failures.append((rel(p), str(e2)[:100]))
            if done % 100 == 0:
                print(f"进度 {done}/{len(todo)} 成功 {ok} 失败 {fail}")

    print(f"\n完成：成功 {ok}，失败 {fail}（本轮跳过 {skipped}）")
    for key, err in failures[:20]:
        print(f"  失败 {key}: {err}")
    sys.exit(1 if failures else 0)

if __name__ == "__main__":
    main()
