#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""查看 Chroma 本地持久化目录（chroma-data）里的向量数据（**只读**，不启动 Chroma 服务也能看）。

背景：Chroma 0.5.x 的本地持久化把「向量 + 原文 + 元数据」都写进 chroma-data/chroma.sqlite3，
只有 HNSW 图索引才刷到以 collection_id 命名的子目录（data_level0.bin 等），
所以目录里看不到子文件夹是正常的——数据在 sqlite 里。

用法（Windows 下用 python，路径默认 D:/MyProject/chroma-data）：
  python tools/inspect_chroma.py                  # 概览 + 前 5 条
  python tools/inspect_chroma.py -n 20            # 看前 20 条
  python tools/inspect_chroma.py --kb 1           # 只看 kb_id=1（某知识库）
  python tools/inspect_chroma.py --src README.md  # 只看某个来源文件
  python tools/inspect_chroma.py -q 劳动仲裁       # 按原文关键词过滤
  python tools/inspect_chroma.py --vec 5          # 额外打印每条向量的前 5 维
  python tools/inspect_chroma.py --dir E:/xxx/chroma-data

常用等价的 HTTP 查法（服务在跑时更权威，推荐）：
  # 1) 拿 collection id
  curl "http://127.0.0.1:8000/api/v1/collections?tenant=default_tenant&database=default_database"
  # 2) 条数
  curl "http://127.0.0.1:8000/api/v1/collections/<id>/count?tenant=default_tenant&database=default_database"
  # 3) 内容（POST 才能带上 include）
  curl -X POST "http://127.0.0.1:8000/api/v1/collections/<id>/get?tenant=default_tenant&database=default_database" \
       -H "Content-Type: application/json" \
       -d '{"limit":5,"include":["documents","metadatas","embeddings"]}'
  # 4) 项目自带状态自检（连接状态 / 空间 / 条数）
  curl http://localhost:8080/api/kb/chroma/status
"""
import argparse
import os
import sqlite3
import struct
import sys

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

DEFAULT_DIR = "D:/MyProject/chroma-data"


def meta_of(conn, rid, key, col="string_value"):
    row = conn.execute(
        "select {} from embedding_metadata where id=? and key=?".format(col), (str(rid), key)
    ).fetchone()
    return row[0] if row else None


def main():
    ap = argparse.ArgumentParser(description="查看 Chroma 本地数据（只读）")
    ap.add_argument("--dir", default=DEFAULT_DIR, help="chroma-data 目录，默认 " + DEFAULT_DIR)
    ap.add_argument("-n", "--limit", type=int, default=5, help="列出前 N 条，默认 5")
    ap.add_argument("--kb", type=int, help="只显示指定 kb_id")
    ap.add_argument("--src", help="只显示指定来源文件名（模糊匹配）")
    ap.add_argument("-q", "--query", help="按原文关键词过滤")
    ap.add_argument("--vec", type=int, default=0, help="额外打印向量前 K 维（0=不打印）")
    args = ap.parse_args()

    db = os.path.join(args.dir, "chroma.sqlite3")
    if not os.path.exists(db):
        print("找不到数据库：", db)
        return 1
    conn = sqlite3.connect("file:{}?mode=ro".format(db.replace("\\", "/")), uri=True)

    print("== 集合 ==")
    for cid, name, dim in conn.execute("select id, name, dimension from collections"):
        print("  {}  名称={}  维度={}".format(cid, name, dim))

    try:
        total = conn.execute("select count(*) from embeddings").fetchone()[0]
    except sqlite3.OperationalError:
        total = conn.execute(
            "select count(*) from embeddings_queue where operation != 3"
        ).fetchone()[0]
    print("== 有效向量条数 ==", total)

    print("== 按知识库分组 ==")
    for kb, cnt in conn.execute(
        "select int_value, count(*) from embedding_metadata where key='kb_id' group by int_value"
    ):
        print("  kb_id={}：{} 条".format(kb, cnt))

    print("== 按来源文件分组 ==")
    for src, cnt in conn.execute(
        "select string_value, count(*) from embedding_metadata where key='source' group by string_value"
    ):
        print("  {}：{} 条".format(src, cnt))

    sql = (
        "select id, string_value from embedding_metadata where key='chroma:document' order by id"
    )
    docs = conn.execute(sql).fetchall()
    shown = 0
    print("== 明细（最多 {} 条）==".format(args.limit))
    for rid, doc in docs:
        if shown >= args.limit:
            break
        kb = meta_of(conn, rid, "kb_id", "int_value")
        src = meta_of(conn, rid, "source")
        if args.kb is not None and kb != args.kb:
            continue
        if args.src and not (src and args.src in src):
            continue
        if args.query and not (doc and args.query in doc):
            continue
        # 关联链（Chroma 0.5.x）：embedding_metadata.id = embeddings.id →
        # embeddings.embedding_id = 我们写入的 chunk id → embeddings_queue.id 上取向量
        # （同一 chunk id 可能被 upsert 多次，取 seq_id 最大的那条）
        row = conn.execute(
            """select e.embedding_id, q.vector
                 from embeddings e
                 join embeddings_queue q on q.id = e.embedding_id
                where e.id = ?
                order by q.seq_id desc limit 1""",
            (rid,),
        ).fetchone()
        chunk_id = row[0] if row else None
        blob = row[1] if row else None
        dim = len(blob) // 4 if blob else 0
        print("--- 内部id={} chunk_id={} kb_id={} source={} 维度={}".format(
            rid, chunk_id, kb, src, dim))
        print("    " + (doc or "").replace("\n", " ")[:100])
        if args.vec and blob:
            head = struct.unpack("<{}f".format(min(args.vec, dim)), blob[: 4 * args.vec])
            print("    向量前{}维：{}".format(args.vec, [round(x, 4) for x in head]))
        shown += 1
    if shown == 0:
        print("  （无匹配数据；可去掉 --kb/--src/-q 再试）")
    return 0


if __name__ == "__main__":
    sys.exit(main())
