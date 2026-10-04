"""自分の投稿の指標（インプレッション・エンゲージメント・リンククリック等）を X API から取得し、
analytics/post_metrics.csv に追記・更新する。

- 対象: posts/state/posted.json に記録された、投稿後30日以内のポスト（non_public_metrics の取得期限）
- 読み取りは自分の投稿のみ。100件ずつまとめて取得してAPIコストを抑える
- キー未設定時は何もしない
"""
from __future__ import annotations

import sys
from datetime import datetime, timedelta

from common import ANALYTICS, POSTS, load_json, now_jst, read_csv, write_csv
from post_scheduled import x_session

METRICS_PATH = ANALYTICS / "post_metrics.csv"
FIELDS = ["post_id", "tweet_id", "posted_at", "type", "theme", "variant", "ab_group", "has_link",
          "impressions", "likes", "replies", "reposts", "quotes", "bookmarks", "url_link_clicks",
          "user_profile_clicks", "engagement_rate", "fetched_at"]


def main() -> int:
    state = load_json(POSTS / "state" / "posted.json", {}) or {}
    posted = state.get("posted", {})
    session = x_session()
    if session is None:
        print("X API キー未設定のためスキップ")
        return 0
    now = now_jst()
    targets = {v["tweet_id"]: (pid, v) for pid, v in posted.items()
               if now - datetime.fromisoformat(v["posted_at"]) <= timedelta(days=29)}
    rows = {r["post_id"]: r for r in read_csv(METRICS_PATH)}
    ids = list(targets)
    for i in range(0, len(ids), 100):
        chunk = ids[i:i + 100]
        r = session.get("https://api.x.com/2/tweets", params={
            "ids": ",".join(chunk),
            "tweet.fields": "public_metrics,non_public_metrics,created_at",
        }, timeout=30)
        if r.status_code >= 300:
            print(f"X API error {r.status_code}: {r.text[:300]}")
            return 1
        for t in r.json().get("data", []):
            pid, meta = targets[t["id"]]
            pub = t.get("public_metrics", {})
            npm = t.get("non_public_metrics", {})
            imp = npm.get("impression_count") or pub.get("impression_count") or 0
            eng = sum(pub.get(k, 0) for k in ("like_count", "reply_count", "retweet_count", "quote_count", "bookmark_count"))
            eng += npm.get("url_link_clicks", 0) + npm.get("user_profile_clicks", 0)
            rows[pid] = {
                "post_id": pid, "tweet_id": t["id"], "posted_at": meta["posted_at"],
                "type": meta.get("type"), "theme": meta.get("theme"), "variant": meta.get("variant") or "",
                "ab_group": meta.get("ab_group") or "", "has_link": meta.get("has_link"),
                "impressions": imp, "likes": pub.get("like_count", 0), "replies": pub.get("reply_count", 0),
                "reposts": pub.get("retweet_count", 0), "quotes": pub.get("quote_count", 0),
                "bookmarks": pub.get("bookmark_count", 0), "url_link_clicks": npm.get("url_link_clicks", 0),
                "user_profile_clicks": npm.get("user_profile_clicks", 0),
                "engagement_rate": round(eng / imp, 4) if imp else 0, "fetched_at": now.isoformat(),
            }
    # リプライ側（リンク）のクリックも合算
    reply_map = {v["reply_id"]: pid for pid, v in posted.items() if v.get("reply_id")}
    reply_ids = [rid for rid, pid in reply_map.items() if pid in rows]
    for i in range(0, len(reply_ids), 100):
        chunk = reply_ids[i:i + 100]
        r = session.get("https://api.x.com/2/tweets", params={
            "ids": ",".join(chunk), "tweet.fields": "non_public_metrics"}, timeout=30)
        if r.status_code >= 300:
            break
        for t in r.json().get("data", []):
            pid = reply_map[t["id"]]
            clicks = t.get("non_public_metrics", {}).get("url_link_clicks", 0)
            rows[pid]["url_link_clicks"] = int(rows[pid].get("url_link_clicks") or 0) + clicks

    write_csv(METRICS_PATH, sorted(rows.values(), key=lambda r: r["posted_at"]), FIELDS)
    print(f"metrics updated: {len(rows)} posts")
    return 0


if __name__ == "__main__":
    sys.exit(main())
