"""data/topics_backlog.csv の先頭の未着手テーマから、記事を1本 Claude API で生成する。

- web_search で一次情報を確認しながら書く（出典を sources に格納）
- lint 不合格なら1回だけ書き直し、それでもダメなら保存しない（人の手を煩わせない）
- 生成記事は週次PRに含まれ、PRのマージ＝公開承認
"""
from __future__ import annotations

import json
import re
import sys

from common import CONTENT, DATA, links, load_json, now_jst, read_csv, write_csv
from lint_content import lint_article, split_front_matter

BACKLOG = DATA / "topics_backlog.csv"
ARTICLE_SCHEMA = {
    "type": "object",
    "properties": {
        "title": {"type": "string"},
        "slug": {"type": "string", "description": "英小文字とハイフンのみ"},
        "description": {"type": "string", "description": "120字以内のメタディスクリプション"},
        "tags": {"type": "array", "items": {"type": "string"}},
        "sources": {"type": "array", "items": {"type": "string"}},
        "body_markdown": {"type": "string"},
    },
    "required": ["title", "slug", "description", "tags", "sources", "body_markdown"],
    "additionalProperties": False,
}


def existing_articles() -> list[dict]:
    out = []
    for p in sorted((CONTENT / "articles").glob("*.md")):
        fm, _ = split_front_matter(p.read_text(encoding="utf-8"))
        out.append({"slug": fm.get("slug"), "title": fm.get("title")})
    return out


def to_markdown(data: dict, category: str) -> str:
    import yaml
    fm = {
        "title": data["title"], "slug": data["slug"], "date": now_jst().strftime("%Y-%m-%d"),
        "description": data["description"], "category": category, "tags": data["tags"],
        "sources": data["sources"], "generated_by": "claude",
    }
    return "---\n" + yaml.safe_dump(fm, allow_unicode=True, sort_keys=False) + "---\n\n" + data["body_markdown"].strip() + "\n"


def main() -> int:
    from llm import call_json, prompt, render

    rows = read_csv(BACKLOG)
    todo = [r for r in rows if r.get("status") == "todo"]
    if not todo:
        print("未着手テーマがありません（data/topics_backlog.csv に追加してください）")
        return 0
    todo.sort(key=lambda r: int(r.get("priority") or 9))
    topic = todo[0]
    stats = load_json(DATA / "womendb" / "stats.json", {}) or {}
    stats_brief = json.dumps({k: v for k, v in stats.items() if k != "by_industry"}, ensure_ascii=False) if stats else "（未取得）"
    services = "\n".join(f"- {k}: {v.get('label')}" for k, v in links().items() if v.get("active", True))
    arts = existing_articles()
    user = render(prompt("generate_article.md"), title=topic["title"], intent=topic.get("intent", ""),
                  category=topic.get("category", "career"), link_keys=topic.get("link_keys", ""),
                  stats=stats_brief, services=services,
                  articles="\n".join(f"- /articles/{a['slug']}/ {a['title']}" for a in arts))
    data = call_json(user, ARTICLE_SCHEMA, web_search=True, max_tokens=64000)
    data["slug"] = re.sub(r"[^a-z0-9-]", "-", data["slug"].lower()).strip("-")[:60] or f"article-{now_jst():%Y%m%d}"
    if any(a["slug"] == data["slug"] for a in arts):
        data["slug"] += f"-{now_jst():%m%d}"
    md = to_markdown(data, topic.get("category", "career"))
    errors, _ = lint_article(md)
    if errors:
        fix = render("次の記事は自動チェックで不合格でした。指摘をすべて解消して、同じJSON形式で全文を返してください。\n\n# 指摘\n{e}\n\n# 記事JSON\n{j}",
                     e="\n".join(errors), j=json.dumps(data, ensure_ascii=False))
        data = call_json(fix, ARTICLE_SCHEMA, max_tokens=64000)
        md = to_markdown(data, topic.get("category", "career"))
        errors, _ = lint_article(md)
    if errors:
        print(f"lint 不合格のため保存しません: {errors}")
        topic["status"] = "failed"
    else:
        path = CONTENT / "articles" / f"{data['slug']}.md"
        path.write_text(md, encoding="utf-8")
        topic["status"] = "done"
        topic["slug"] = data["slug"]
        print(f"wrote {path.name}")
    write_csv(BACKLOG, rows, list(rows[0].keys()))
    return 0


if __name__ == "__main__":
    sys.exit(main())
