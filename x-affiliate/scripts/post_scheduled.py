"""承認済み（approved: true）の予約投稿を、予定時刻になったら X API で投稿する。

- GitHub Actions から1日数回呼ばれる（.github/workflows/xa-post.yml）
- posts/scheduled/*.yaml のうち、ファイル先頭が approved: true のものだけが対象（=人間の承認ゲート）
- 投稿済みは posts/state/posted.json に記録し、二重投稿しない
- 予定時刻から grace_hours 以上過ぎたものは投稿せず missed として記録（障害復帰時の連投防止）
- 投稿直前に lint（NG表現・文字数・未解決プレースホルダ・PR表記）を再実行し、NGなら投稿しない

必要な環境変数（GitHub Secrets）:
  X_API_KEY, X_API_SECRET, X_ACCESS_TOKEN, X_ACCESS_TOKEN_SECRET
いずれかが無い場合は自動的に dry-run（投稿せずに表示のみ）。

X の自動化ルール上の位置づけ:
  自分のアカウントで、自分が作成・承認したオリジナル投稿を予約投稿するだけ。
  自動リプライ・自動いいね・自動フォロー・他人へのメンションは一切しない。
"""
from __future__ import annotations

import argparse
import os
import sys
from datetime import datetime, timedelta

from common import (DATA, JST, POSTS, article_url, dump_json, load_json, load_yaml, now_jst,
                    schedule_config, with_utm)
from lint_content import lint_post, resolve_placeholders

STATE_PATH = POSTS / "state" / "posted.json"
X_POST_URL = "https://api.x.com/2/tweets"


def x_session():
    keys = [os.environ.get(k) for k in ("X_API_KEY", "X_API_SECRET", "X_ACCESS_TOKEN", "X_ACCESS_TOKEN_SECRET")]
    if not all(keys):
        return None
    from requests_oauthlib import OAuth1Session
    return OAuth1Session(keys[0], client_secret=keys[1], resource_owner_key=keys[2],
                         resource_owner_secret=keys[3])


def create_post(session, text: str, reply_to: str | None = None) -> str:
    payload = {"text": text}
    if reply_to:
        payload["reply"] = {"in_reply_to_tweet_id": reply_to}
    r = session.post(X_POST_URL, json=payload, timeout=30)
    if r.status_code >= 300:
        raise RuntimeError(f"X API error {r.status_code}: {r.text[:500]}")
    return r.json()["data"]["id"]


def resolve_link(post: dict) -> str | None:
    link = post.get("link")
    if not link:
        return None
    if isinstance(link, str):
        url = link
    elif link.get("article"):
        url = article_url(link["article"])
    else:
        url = link.get("url")
    return with_utm(url, post["id"]) if url else None


def due_posts(now: datetime, state: dict, grace_hours: float):
    due, missed = [], []
    for path in sorted((POSTS / "scheduled").glob("*.yaml")):
        doc = load_yaml(path, {}) or {}
        if not doc.get("approved"):
            continue
        for post in doc.get("posts", []):
            pid = post.get("id")
            if not pid or post.get("skip") or pid in state.get("posted", {}) or pid in state.get("missed", {}):
                continue
            at = datetime.strptime(str(post["at"]), "%Y-%m-%d %H:%M").replace(tzinfo=JST)
            if at > now:
                continue
            if now - at > timedelta(hours=grace_hours):
                missed.append(post)
            else:
                due.append(post)
    due.sort(key=lambda p: str(p["at"]))
    return due, missed


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    args = ap.parse_args()

    cfg = schedule_config()
    state = load_json(STATE_PATH, {}) or {}
    state.setdefault("posted", {})
    state.setdefault("missed", {})
    state.setdefault("blocked", {})
    stats = load_json(DATA / "womendb" / "stats.json", {}) or {}
    now = now_jst()

    due, missed = due_posts(now, state, float(cfg.get("grace_hours", 6)))
    for p in missed:
        state["missed"][p["id"]] = {"at": str(p["at"]), "noted_at": now.isoformat()}
        print(f"missed (grace超過のため投稿しない): {p['id']}")

    session = None if args.dry_run else x_session()
    if session is None:
        print("dry-run モード（X API キー未設定 or --dry-run）")
    link_mode = cfg.get("link_mode", "reply")

    posted_count = 0
    for post in due:
        if posted_count >= int(cfg.get("max_posts_per_run", 2)):
            break
        text = resolve_placeholders(post["text"].strip(), stats)
        final = dict(post, text=text)
        errors, _ = lint_post(final, final=True)
        if errors:
            state["blocked"][post["id"]] = {"errors": errors, "at": now.isoformat()}
            print(f"BLOCKED {post['id']}: {errors}")
            continue
        url = resolve_link(post)
        mode = post.get("link_mode", link_mode)
        main_text = f"{text}\n{url}" if (url and mode == "inline") else text
        print(f"--- {post['id']} ({post['type']}) ---\n{main_text}")
        if url and mode == "reply":
            print(f"  ↳ reply: {url}")
        if session is None:
            continue
        tweet_id = create_post(session, main_text)
        reply_id = None
        if url and mode == "reply":
            reply_text = post.get("reply_text") or "記事はこちら（出典つきでまとめています）"
            reply_id = create_post(session, f"{reply_text}\n{url}", reply_to=tweet_id)
        state["posted"][post["id"]] = {
            "tweet_id": tweet_id, "reply_id": reply_id, "posted_at": now.isoformat(),
            "type": post["type"], "theme": post.get("theme"), "variant": post.get("variant"),
            "ab_group": post.get("ab_group"), "has_link": bool(url),
        }
        posted_count += 1

    if session is not None or missed:
        dump_json(STATE_PATH, state)
    return 0


if __name__ == "__main__":
    sys.exit(main())
