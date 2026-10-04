"""翌週（月〜日）の投稿ファイル posts/scheduled/<週>.yaml を作る。

優先順位:
  1. posts/plan_30days.yaml（最初の30日分の手書きプラン）の未使用日を、翌週の日付に割り当てる
  2. 埋まらない枠は Claude API で生成（ブランド方針・データ・トレンド・学習メモ・過去投稿を入力）
  3. すべての投稿を lint。不合格は Claude に1回だけ書き直させ、それでもダメなら skip: true にする

生成ファイルは approved: true で作られるが、週次ワークフローが Pull Request として出すため、
「PR をマージする＝人間が承認する」になる（マージされるまで投稿されない）。

使い方:
  python scripts/generate_posts.py                 # 翌週分
  python scripts/generate_posts.py --start 2026-10-19 --force
  python scripts/generate_posts.py --plan-only     # Claude を呼ばない（APIキー無しでも可）
"""
from __future__ import annotations

import argparse
import json
import sys
from datetime import datetime, timedelta

from common import (ANALYTICS, CONTENT, DATA, JST, POSTS, brand, dump_json, dump_yaml, iso_week_label,
                    links, load_json, load_yaml, now_jst, schedule_config, week_monday)
from lint_content import lint_post, split_front_matter

PLAN_PATH = POSTS / "plan_30days.yaml"
PROGRESS_PATH = POSTS / "state" / "plan_progress.json"

POST_SCHEMA = {
    "type": "object",
    "properties": {
        "posts": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "slot": {"type": "string", "description": "YYYY-MM-DD HH:MM（与えられた枠のいずれか）"},
                    "type": {"type": "string", "enum": list("ABCDEF")},
                    "theme": {"type": "string"},
                    "text": {"type": "string"},
                    "link_article": {"type": "string", "description": "E/D で誘導する記事の slug。無ければ空文字"},
                    "affiliate": {"type": "boolean"},
                    "sources": {"type": "array", "items": {"type": "string"}},
                    "ab_group": {"type": "string"},
                    "variant": {"type": "string"},
                },
                "required": ["slot", "type", "theme", "text", "link_article", "affiliate", "sources", "ab_group", "variant"],
                "additionalProperties": False,
            },
        }
    },
    "required": ["posts"],
    "additionalProperties": False,
}
REWRITE_SCHEMA = {
    "type": "object",
    "properties": {"text": {"type": "string"}, "sources": {"type": "array", "items": {"type": "string"}}},
    "required": ["text", "sources"],
    "additionalProperties": False,
}


def week_slots(start: datetime) -> list[dict]:
    cfg = schedule_config()
    out = []
    for d in range(7):
        day = start + timedelta(days=d)
        for s in cfg.get("slots", []):
            out.append({"at": f"{day:%Y-%m-%d} {s['time']}", "types": s["types"]})
    return out


def from_plan(slots: list[dict], label: str) -> tuple[list[dict], list[dict]]:
    """プランの未使用日を枠に割り当てる。(割り当て済み投稿, 残りの枠) を返す。
    同じ週を --force で作り直した場合は、その週の開始日から割り当て直す。"""
    plan = (load_yaml(PLAN_PATH, {}) or {}).get("posts", [])
    progress = load_json(PROGRESS_PATH, {"next_day": 1}) or {"next_day": 1}
    by_day: dict[int, list[dict]] = {}
    for p in plan:
        by_day.setdefault(int(p["day"]), []).append(p)
    if not by_day:
        return [], slots
    max_day = max(by_day)
    weeks = progress.setdefault("weeks", {})
    day = int(weeks.get(label, progress.get("next_day", 1)))
    weeks[label] = day
    posts, remaining = [], []
    dates = sorted({s["at"][:10] for s in slots})
    for date in dates:
        day_slots = [s for s in slots if s["at"].startswith(date)]
        if day > max_day:
            remaining += day_slots
            continue
        plan_posts = sorted(by_day.get(day, []), key=lambda p: str(p["slot"]))
        for s, p in zip(day_slots, plan_posts):
            post = {k: v for k, v in p.items() if k not in ("day", "slot")}
            post["at"] = s["at"]
            post["id"] = s["at"].replace(" ", "-").replace(":", "")
            post["source_plan_day"] = day
            posts.append(post)
        remaining += day_slots[len(plan_posts):]
        day += 1
    progress["next_day"] = day
    dump_json(PROGRESS_PATH, progress)
    return posts, remaining


def _articles() -> list[dict]:
    out = []
    for path in sorted((CONTENT / "articles").glob("*.md")):
        fm, _ = split_front_matter(path.read_text(encoding="utf-8"))
        if not fm.get("draft"):
            out.append({"slug": fm.get("slug"), "title": fm.get("title"), "category": fm.get("category")})
    return out


def _stats_brief() -> str:
    stats = load_json(DATA / "womendb" / "stats.json", {}) or {}
    if not stats:
        return "（まだデータ未取得。数値プレースホルダは使わず、制度・読み方・問いかけ中心で書く）"
    brief = {k: v for k, v in stats.items() if k != "by_industry"}
    return json.dumps(brief, ensure_ascii=False, indent=1)


def _recent_posts(weeks: int = 4) -> str:
    rows = []
    for path in sorted((POSTS / "scheduled").glob("*.yaml"))[-weeks:]:
        for p in (load_yaml(path, {}) or {}).get("posts", []):
            rows.append(f"- [{p.get('type')}] {str(p.get('text', '')).strip()[:100]}")
    return "\n".join(rows[-60:]) or "（なし）"


def generate_with_claude(slots: list[dict], start: datetime) -> list[dict]:
    from llm import call_json, prompt, render

    cfg = schedule_config()
    mix = cfg.get("type_mix", {})
    n = len(slots)
    targets = ", ".join(f"{t}:{round(r * n)}本" for t, r in mix.items())
    b = brand()
    trends = (load_json(DATA / "trends.json", {}) or {}).get("items", [])[:15]
    services = [f"- {k}: {v.get('label')}（{'アフィリエイト' if v.get('affiliate') else '非広告'}）"
                for k, v in links().items() if v.get("active", True) and v.get("url")]
    learnings = (ANALYTICS / "learnings.md")
    user = render(
        prompt("generate_posts.md"),
        week_start=f"{start:%Y-%m-%d}", week_end=f"{start + timedelta(days=6):%Y-%m-%d}", n_posts=n,
        slots="\n".join(f"- {s['at']}（{'/'.join(s['types'])}）" for s in slots),
        type_targets=targets,
        themes="\n".join(f"- {t['key']}: {t['name']}" for t in b.get("themes", [])),
        stats=_stats_brief(),
        trends="\n".join(f"- {t['title']}（{t['source']}）" for t in trends) or "（なし）",
        articles="\n".join(f"- {a['slug']}: {a['title']}" for a in _articles()) or "（なし）",
        services="\n".join(services) or "（提携済みサービスなし。D は一般的な使い方の説明に留め、affiliate=false）",
        learnings=learnings.read_text(encoding="utf-8") if learnings.exists() else "（まだなし）",
        recent=_recent_posts(),
    )
    data = call_json(user, POST_SCHEMA)
    valid_at = {s["at"] for s in slots}
    used, posts = set(), []
    for p in data["posts"]:
        at = p["slot"].strip()
        if at not in valid_at or at in used:
            continue
        used.add(at)
        post = {
            "id": at.replace(" ", "-").replace(":", ""), "at": at, "type": p["type"], "theme": p["theme"],
            "text": p["text"].strip(), "affiliate": p["affiliate"], "sources": p["sources"],
            "generated_by": "claude",
        }
        if p["link_article"]:
            post["link"] = {"article": p["link_article"]}
        if p["ab_group"]:
            post["ab_group"], post["variant"] = p["ab_group"], p["variant"] or "a"
        posts.append(post)
    return posts


def fix_or_skip(posts: list[dict], use_claude: bool) -> None:
    stats = load_json(DATA / "womendb" / "stats.json", {}) or {}
    for post in posts:
        errors, warnings = lint_post(post, stats=stats)
        if errors and use_claude:
            from llm import call_json, prompt, render
            try:
                fixed = call_json(render(prompt("rewrite_post.md"),
                                         post=json.dumps({k: post.get(k) for k in ("type", "text", "sources", "affiliate")},
                                                         ensure_ascii=False),
                                         errors="\n".join(errors), stats=_stats_brief()), REWRITE_SCHEMA)
                post["text"], post["sources"] = fixed["text"].strip(), fixed["sources"]
                errors, warnings = lint_post(post, stats=stats)
            except Exception as e:  # 書き直し失敗は skip 扱い
                errors.append(f"rewrite failed: {e}")
        if errors:
            post["skip"] = True
            post["lint_errors"] = errors
        if warnings:
            post["lint_warnings"] = warnings


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--start", help="週の開始日（月曜）YYYY-MM-DD。省略時は翌週月曜")
    ap.add_argument("--force", action="store_true")
    ap.add_argument("--plan-only", action="store_true")
    args = ap.parse_args()

    if args.start:
        start = datetime.strptime(args.start, "%Y-%m-%d").replace(tzinfo=JST)
    else:
        start = week_monday(now_jst()) + timedelta(days=7)
    label = iso_week_label(start)
    out = POSTS / "scheduled" / f"{label}.yaml"
    if out.exists() and not args.force:
        print(f"{out.name} は既に存在します（--force で上書き）")
        return 0

    slots = week_slots(start)
    posts, remaining = from_plan(slots, label)
    use_claude = not args.plan_only
    if remaining and use_claude:
        posts += generate_with_claude(remaining, start)
    elif remaining:
        print(f"未充足の枠 {len(remaining)} 件（--plan-only のため生成しません）")
    order = ["id", "at", "type", "theme", "text", "link", "affiliate", "sources", "ab_group", "variant"]
    posts = [{**{k: p[k] for k in order if k in p}, **{k: v for k, v in p.items() if k not in order}} for p in posts]
    posts.sort(key=lambda p: p["at"])
    fix_or_skip(posts, use_claude)

    doc = {
        "week": label,
        "approved": True,  # このファイルは PR で届く。PR をマージ＝承認。不要な投稿は削除 or skip: true
        "note": "気に入らない投稿は行ごと削除するか skip: true に。text を直接書き換えてもOK。",
        "posts": posts,
    }
    dump_yaml(out, doc)
    n_skip = sum(1 for p in posts if p.get("skip"))
    print(f"wrote {out.relative_to(POSTS.parent)}: {len(posts)} posts ({n_skip} skipped by lint)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
