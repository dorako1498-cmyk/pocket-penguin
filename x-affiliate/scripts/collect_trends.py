"""RSS/Atom からニュース・トレンドの「見出し」を集めて data/trends.json に保存する。

本文は取得・転載しない（著作権配慮）。見出しとURLは投稿・記事ネタの候補としてだけ使い、
投稿で事実を述べる場合は必ず一次情報（官公庁・企業の公表資料）を出典にする。
"""
from __future__ import annotations

import sys
import xml.etree.ElementTree as ET

import requests

from common import CONFIG, DATA, dump_json, load_yaml, now_jst

UA = {"User-Agent": "Mozilla/5.0 (compatible; mimosa-data-trends/1.0)"}


def parse_feed(xml_bytes: bytes | str) -> list[dict]:
    root = ET.fromstring(xml_bytes)
    items = []
    # RSS 2.0 / RDF (RSS 1.0) / Atom を名前空間ゆるく処理
    for el in root.iter():
        tag = el.tag.split("}")[-1]
        if tag not in ("item", "entry"):
            continue
        title = link = date = ""
        for child in el:
            ctag = child.tag.split("}")[-1]
            if ctag == "title":
                title = (child.text or "").strip()
            elif ctag == "link":
                link = (child.text or "").strip() or child.get("href", "")
            elif ctag in ("pubDate", "date", "updated", "published"):
                date = (child.text or "").strip()
        if title:
            items.append({"title": title, "link": link, "published": date})
    return items


def score(title: str, keywords: list[str]) -> int:
    return sum(1 for k in keywords if k in title)


def main() -> int:
    cfg = load_yaml(CONFIG / "feeds.yaml", {})
    kws = cfg.get("priority_keywords", [])
    seen, out = set(), []
    for feed in cfg.get("feeds", []):
        try:
            r = requests.get(feed["url"], headers=UA, timeout=20)
            r.raise_for_status()
            items = parse_feed(r.content)
        except Exception as e:  # フィード障害は無視して続行
            print(f"skip {feed['name']}: {e}")
            continue
        for it in items:
            key = it["title"][:40]
            if key in seen:
                continue
            seen.add(key)
            it["source"] = feed["name"]
            it["score"] = score(it["title"], kws)
            out.append(it)
    out.sort(key=lambda x: x["score"], reverse=True)
    out = out[: int(cfg.get("max_items", 40))]
    dump_json(DATA / "trends.json", {"fetched_at": now_jst().isoformat(), "items": out})
    print(f"trends: {len(out)} items")
    return 0


if __name__ == "__main__":
    sys.exit(main())
