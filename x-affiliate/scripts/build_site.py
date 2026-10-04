"""静的サイトを dist/ に生成する（Cloudflare Pages / GitHub Pages どちらでも配信可）。

  python scripts/build_site.py

- content/articles/*.md（front matter付き Markdown）→ /articles/<slug>/
- data/womendb/companies.csv.gz があれば → /data/ 業種・企業ページ
- [[link:キー]] / [[link:キー|表示テキスト]] / [[cta:キー|見出し|説明]] を data/links.yaml から展開
- {{stat:キー}} を data/womendb/stats.json から展開（未取得なら「データ更新後に表示」）
- 有効なアフィリエイトリンクを含むページには PR 表記を自動挿入
- sitemap.xml / robots.txt / feed.xml / 404.html を生成
"""
from __future__ import annotations

import csv
import gzip
import hashlib
import html
import re
import shutil
import sys
from collections import defaultdict
from datetime import date, datetime

import markdown
from jinja2 import Environment, FileSystemLoader, select_autoescape

from common import CONTENT, DATA, DIST, STATIC, TEMPLATES, links, load_json, site_config
from lint_content import PLACEHOLDER_RE, _lookup, split_front_matter

LINK_RE = re.compile(r"\[\[link:([a-zA-Z0-9_]+)(?:\|([^\]]*))?\]\]")
CTA_RE = re.compile(r"\[\[cta:([a-zA-Z0-9_]+)(?:\|([^\]|]*))?(?:\|([^\]]*))?\]\]")


class Ctx:
    def __init__(self):
        self.cfg = site_config()
        self.site = self.cfg.get("site", {})
        self.links = links()
        self.stats = load_json(DATA / "womendb" / "stats.json", {}) or {}
        self.env = Environment(loader=FileSystemLoader(TEMPLATES), autoescape=select_autoescape(["html", "xml"]))
        self.env.globals.update(site=self.site, now=datetime.now(), stats=self.stats)
        self.pages: list[tuple[str, str]] = []  # (url_path, lastmod)


def link_html(ctx: Ctx, key: str, text: str | None) -> tuple[str, bool]:
    """(html, 有効なアフィリエイトリンクか)"""
    ln = ctx.links.get(key)
    if not ln or not ln.get("active", True):
        return html.escape(text or ""), False
    label = text or ln.get("label", key)
    url = (ln.get("url") or "").strip()
    if not url:
        return f'<span class="link-pending">{html.escape(label)}</span>', False
    if url.startswith("<"):  # ASPの発行タグ（HTML）をそのまま使う場合
        return url, bool(ln.get("affiliate"))
    rel = "sponsored nofollow noopener" if ln.get("affiliate") else "noopener"
    return f'<a href="{html.escape(url)}" rel="{rel}" target="_blank">{html.escape(label)}</a>', bool(ln.get("affiliate"))


def expand(ctx: Ctx, text: str) -> tuple[str, bool]:
    has_aff = False

    def cta(m):
        nonlocal has_aff
        key, title, desc = m.group(1), m.group(2), m.group(3)
        a, aff = link_html(ctx, key, "公式サイトを見る")
        has_aff = has_aff or aff
        label = html.escape(title or ctx.links.get(key, {}).get("label", key))
        d = f"<p>{html.escape(desc)}</p>" if desc else ""
        btn = f'<div class="cta-btn">{a}</div>' if aff or "<a" in a else '<div class="cta-btn pending">準備中</div>'
        return f'\n<div class="cta"><p class="cta-title">{label}</p>{d}{btn}</div>\n'

    def lnk(m):
        nonlocal has_aff
        a, aff = link_html(ctx, m.group(1), m.group(2))
        has_aff = has_aff or aff
        return a

    def stat(m):
        v = _lookup(ctx.stats, m.group(1))
        if v is None:
            return "（データ更新後に表示）"
        return f"{v:.1f}" if isinstance(v, float) else html.escape(str(v))

    text = CTA_RE.sub(cta, text)
    text = LINK_RE.sub(lnk, text)
    text = PLACEHOLDER_RE.sub(stat, text)
    return text, has_aff


def write(ctx: Ctx, url_path: str, content: str, lastmod: str | None = None, sitemap: bool = True) -> None:
    out = DIST / url_path.strip("/") / "index.html" if not url_path.endswith(".html") else DIST / url_path.strip("/")
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(content, encoding="utf-8")
    if sitemap:
        ctx.pages.append((url_path, lastmod or date.today().isoformat()))


def load_articles(ctx: Ctx) -> list[dict]:
    arts = []
    for path in sorted((CONTENT / "articles").glob("*.md")):
        fm, body = split_front_matter(path.read_text(encoding="utf-8"))
        if fm.get("draft"):
            continue
        expanded, has_aff = expand(ctx, body)
        html_body = markdown.markdown(expanded, extensions=["tables", "toc", "fenced_code", "attr_list", "md_in_html"])
        fm = dict(fm)
        fm["date"] = str(fm.get("date"))
        fm["updated"] = str(fm.get("updated") or fm["date"])
        fm.update(html=html_body, has_aff=has_aff, url=f"/articles/{fm['slug']}/")
        arts.append(fm)
    arts.sort(key=lambda a: a["date"], reverse=True)
    return arts


def load_companies() -> list[dict]:
    path = DATA / "womendb" / "companies.csv.gz"
    if not path.exists():
        return []
    with gzip.open(path, "rt", encoding="utf-8") as f:
        rows = list(csv.DictReader(f))
    for r in rows:
        for k in ("pay_gap_all", "pay_gap_regular", "pay_gap_nonregular", "female_manager_ratio",
                  "female_worker_ratio", "male_leave_rate"):
            r[k] = float(r[k]) if r.get(k) not in (None, "", "None") else None
    return rows


def slug_of(text: str) -> str:
    return hashlib.md5(text.encode("utf-8")).hexdigest()[:10]


def build_data_pages(ctx: Ctx, arts: list[dict]) -> dict:
    from fetch_womendb import employees_min
    dcfg = ctx.cfg.get("data_pages", {})
    companies = load_companies() if dcfg.get("enabled", True) else []
    if not companies:
        return {"enabled": False}
    by_ind = defaultdict(list)
    for c in companies:
        if c.get("industry"):
            by_ind[c["industry"]].append(c)
    ind_stats = ctx.stats.get("by_industry", {})
    min_group = int(dcfg.get("min_companies_per_group", 8))
    industries = []
    for ind, rs in sorted(by_ind.items(), key=lambda kv: -len(kv[1])):
        if len(rs) < min_group:
            continue
        industries.append({"name": ind, "slug": slug_of(ind), "n": len(rs), **ind_stats.get(ind, {})})

    # 企業ページ: 人数条件＋主要指標がある企業のみ、上限件数まで（大きい企業優先）
    eligible = [c for c in companies if employees_min(c.get("employees", "")) >= int(dcfg.get("min_employees", 101))
                and (c["pay_gap_all"] is not None or c["female_manager_ratio"] is not None)]
    eligible.sort(key=lambda c: employees_min(c.get("employees", "")), reverse=True)
    eligible = eligible[: int(dcfg.get("max_company_pages", 3000))]
    for c in eligible:
        c["slug"] = c["corp_number"] if re.fullmatch(r"\d{13}", c.get("corp_number") or "") else slug_of(c["company_name"])
        c["url"] = f"/data/company/{c['slug']}/"
    page_set = {id(c) for c in eligible}

    career_arts = [a for a in arts if a.get("category") in ("career", "company_check")][:3]
    tpl_ind = ctx.env.get_template("industry.html")
    for ind in industries:
        rs = by_ind[ind["name"]]
        top_mgr = sorted([c for c in rs if c["female_manager_ratio"] is not None and id(c) in page_set],
                         key=lambda c: c["female_manager_ratio"], reverse=True)[:15]
        top_pg = sorted([c for c in rs if c["pay_gap_all"] is not None and id(c) in page_set],
                        key=lambda c: c["pay_gap_all"], reverse=True)[:15]
        cta, _ = expand(ctx, "[[cta:agent_women_1|女性の転職に強いエージェントで、同業種の求人を見る|業種が同じでも、会社によって数字は大きく違います。気になる会社の実態は、面談で担当者に確認できます。]]")
        write(ctx, f"/data/industry/{ind['slug']}/", tpl_ind.render(ind=ind, top_mgr=top_mgr, top_pg=top_pg,
                                                                     cta=cta, articles=career_arts))
    tpl_c = ctx.env.get_template("company.html")
    ind_slug = {i["name"]: i["slug"] for i in industries}
    for c in eligible:
        med = ind_stats.get(c.get("industry"), {})
        cta, has_aff = expand(ctx, "[[cta:review_1|この会社の社員口コミ・年収も確認する|公表データは「制度と結果」。働き方の実感は口コミで補うと判断しやすくなります。]]\n"
                              "[[cta:agent_women_1|女性の転職に強いエージェントに相談する|数字だけでは分からない配属・評価・育休復帰後の実態は、面談で担当者に確認できます。]]")
        write(ctx, c["url"], tpl_c.render(c=c, med=med, ind_slug=ind_slug.get(c.get("industry")), cta=cta,
                                          has_aff=has_aff, articles=career_arts))
    write(ctx, "/data/", ctx.env.get_template("data_index.html").render(industries=industries, n_pages=len(eligible)))
    return {"enabled": True, "industries": industries[:12], "n_pages": len(eligible)}


def main() -> int:
    ctx = Ctx()
    if DIST.exists():
        shutil.rmtree(DIST)
    DIST.mkdir(parents=True)
    shutil.copytree(STATIC, DIST / "static")

    arts = load_articles(ctx)
    data_info = build_data_pages(ctx, arts)
    tpl = ctx.env.get_template("article.html")
    for a in arts:
        related = [x for x in arts if x["slug"] != a["slug"] and x.get("category") == a.get("category")][:3]
        write(ctx, a["url"], tpl.render(a=a, related=related), a["updated"], sitemap=not a.get("noindex"))
    write(ctx, "/articles/", ctx.env.get_template("list.html").render(articles=arts))
    write(ctx, "/", ctx.env.get_template("index.html").render(articles=arts[:8], data=data_info))
    for page in ("about", "privacy"):
        write(ctx, f"/{page}/", ctx.env.get_template(f"{page}.html").render())
    (DIST / "404.html").write_text(ctx.env.get_template("404.html").render(), encoding="utf-8")

    base = ctx.site.get("base_url", "").rstrip("/")
    sm = ['<?xml version="1.0" encoding="UTF-8"?>', '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">']
    for path, lastmod in ctx.pages:
        sm.append(f"<url><loc>{base}{path}</loc><lastmod>{lastmod}</lastmod></url>")
    sm.append("</urlset>")
    (DIST / "sitemap.xml").write_text("\n".join(sm), encoding="utf-8")
    (DIST / "robots.txt").write_text(f"User-agent: *\nAllow: /\nSitemap: {base}/sitemap.xml\n", encoding="utf-8")
    (DIST / "feed.xml").write_text(ctx.env.get_template("feed.xml").render(articles=arts[:20]), encoding="utf-8")
    print(f"built: {len(arts)} articles, data pages: {data_info.get('n_pages', 0)}, total urls: {len(ctx.pages)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
