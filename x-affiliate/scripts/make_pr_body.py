"""週次PRの本文（スマホで読みやすい確認用）を作る。

  python scripts/make_pr_body.py > pr_body.md
"""
from __future__ import annotations

import sys
from datetime import timedelta

from common import ANALYTICS, POSTS, iso_week_label, load_yaml, now_jst, week_monday

TYPE_NAMES = {"A": "インプ", "B": "フォロー", "C": "信頼", "D": "興味", "E": "送客", "F": "再利用"}


def main() -> int:
    now = now_jst()
    label = iso_week_label(week_monday(now) + timedelta(days=7))
    doc = load_yaml(POSTS / "scheduled" / f"{label}.yaml", {}) or {}
    out = [f"## 来週（{label}）の投稿 {len(doc.get('posts', []))}本", "",
           "**このPRをマージすると、下の投稿が予定時刻に自動投稿されます（マージ＝承認）。**",
           "直したい投稿は、Files changed → `posts/scheduled/" + f"{label}.yaml` を編集（本文を書き換え／`skip: true`／削除）してからマージしてください。", ""]
    for p in doc.get("posts", []):
        flag = "⛔ lintで除外" if p.get("skip") else ""
        link = f"（→ 記事 `{p['link']['article']}`）" if isinstance(p.get("link"), dict) and p["link"].get("article") else ""
        out.append(f"**{p['at']}｜{p['type']} {TYPE_NAMES.get(p['type'], '')}** {flag}{link}")
        out.append("> " + str(p.get("text", "")).strip().replace("\n", "\n> "))
        if p.get("lint_errors"):
            out.append(f"> lint: {p['lint_errors']}")
        out.append("")
    reports = sorted((ANALYTICS / "reports").glob("*.md"))
    if reports:
        out += ["---", "", reports[-1].read_text(encoding="utf-8")]
    print("\n".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
