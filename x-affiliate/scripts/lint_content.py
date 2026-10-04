"""投稿・記事のコンプライアンス／品質チェック。

チェック内容:
  - NG表現（体験談の捏造・属性叩き・誇大表現・金融/薬機ワード）: config/brand.yaml の ng
  - X の文字数（weighted length 280 以内、リンクは23換算）
  - 未解決のプレースホルダ {{stat:...}}
  - 数値を含むのに sources（出典）が空
  - affiliate: true なのに PR 表記がない
  - 記事: アフィリエイトリンクを含むのに PR 表記が front matter で無効化されていないか等

使い方:
  python scripts/lint_content.py            # posts/scheduled と content/articles を全チェック
  python scripts/lint_content.py --strict   # 警告もエラー扱い
"""
from __future__ import annotations

import argparse
import re
import sys

from common import CONTENT, POSTS, brand, links, load_yaml, x_weighted_length, X_MAX_WEIGHT, X_URL_WEIGHT

VALID_TYPES = set("ABCDEF")
PLACEHOLDER_RE = re.compile(r"\{\{\s*stat:([a-zA-Z0-9_.]+)\s*\}\}")
NUMBER_CLAIM_RE = re.compile(r"\d+(?:\.\d+)?\s*(?:%|％|倍|位|万人|人に1人|円|万円|ポイント|pt)")
LINK_TAG_RE = re.compile(r"\[\[link:([a-zA-Z0-9_]+)(?:\|[^\]]*)?\]\]")


def _ng_patterns():
    ng = brand().get("ng", {})
    out = []
    for cat, words in ng.items():
        if cat == "require_pr_tag":
            continue
        for w in words or []:
            out.append((cat, re.compile(w)))
    return out


def find_ng(text: str) -> list[str]:
    hits = []
    for cat, pat in _ng_patterns():
        m = pat.search(text)
        if m:
            hits.append(f"NG表現[{cat}]: 「{m.group(0)}」")
    return hits


def has_pr_tag(text: str) -> bool:
    tags = brand().get("ng", {}).get("require_pr_tag", ["#PR"])
    return any(t in text for t in tags)


def lint_post(post: dict, *, stats: dict | None = None, final: bool = False) -> tuple[list[str], list[str]]:
    """(errors, warnings) を返す。final=True は投稿直前（プレースホルダ解決後）のチェック。"""
    errors, warnings = [], []
    text = post.get("text", "") or ""
    if post.get("type") not in VALID_TYPES:
        errors.append(f"type が不正: {post.get('type')}")
    if not text.strip():
        errors.append("本文が空")
    errors += find_ng(text)

    placeholders = PLACEHOLDER_RE.findall(text)
    if placeholders:
        if final:
            errors.append(f"未解決のプレースホルダ: {placeholders}")
        elif stats is not None:
            missing = [p for p in placeholders if _lookup(stats, p) is None]
            if missing:
                warnings.append(f"stats.json に無いキー（データ取得後に解決されなければ投稿スキップ）: {missing}")

    length = x_weighted_length(PLACEHOLDER_RE.sub("00.0", text))
    if post.get("link"):
        length += 1 + X_URL_WEIGHT  # 改行 + URL
    if length > X_MAX_WEIGHT:
        errors.append(f"文字数オーバー: {length}/{X_MAX_WEIGHT}（全角は2換算）")

    if (NUMBER_CLAIM_RE.search(text) or placeholders) and not post.get("sources"):
        errors.append("数値を含むのに sources（出典）が空")

    if post.get("affiliate") and not has_pr_tag(text):
        errors.append("affiliate: true なのに PR表記（#PR 等）がない")
    if post.get("type") == "E" and not post.get("link"):
        warnings.append("type E（送客）なのに link がない")
    return errors, warnings


def _lookup(stats: dict, dotted: str):
    cur = stats
    for part in dotted.split("."):
        if isinstance(cur, dict) and part in cur:
            cur = cur[part]
        else:
            return None
    return cur


def resolve_placeholders(text: str, stats: dict) -> str:
    def repl(m):
        v = _lookup(stats, m.group(1))
        if v is None:
            return m.group(0)
        if isinstance(v, float):
            return f"{v:.1f}"
        return str(v)
    return PLACEHOLDER_RE.sub(repl, text)


def split_front_matter(raw: str) -> tuple[dict, str]:
    if raw.startswith("---"):
        parts = raw.split("---", 2)
        if len(parts) >= 3:
            import yaml
            return (yaml.safe_load(parts[1]) or {}), parts[2].lstrip("\n")
    return {}, raw


def lint_article(raw: str) -> tuple[list[str], list[str]]:
    errors, warnings = [], []
    fm, body = split_front_matter(raw)
    for key in ("title", "slug", "date", "description"):
        if not fm.get(key):
            errors.append(f"front matter に {key} がない")
    errors += find_ng(fm.get("title", "") + "\n" + body)
    known = links()
    for key in LINK_TAG_RE.findall(body):
        if key not in known:
            errors.append(f"links.yaml に無いリンクキー: {key}")
    if NUMBER_CLAIM_RE.search(body) and not fm.get("sources"):
        warnings.append("数値を含むのに front matter の sources が空")
    return errors, warnings


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()
    from common import DATA, load_json
    stats = load_json(DATA / "womendb" / "stats.json", {}) or {}

    n_err = n_warn = 0
    for path in sorted((POSTS / "scheduled").glob("*.yaml")):
        doc = load_yaml(path, {}) or {}
        for post in doc.get("posts", []):
            if post.get("skip"):
                continue
            errs, warns = lint_post(post, stats=stats)
            for e in errs:
                print(f"ERROR {path.name}:{post.get('id')}: {e}")
            for w in warns:
                print(f"WARN  {path.name}:{post.get('id')}: {w}")
            n_err += len(errs)
            n_warn += len(warns)
    for path in sorted((CONTENT / "articles").glob("*.md")):
        errs, warns = lint_article(path.read_text(encoding="utf-8"))
        for e in errs:
            print(f"ERROR {path.name}: {e}")
        for w in warns:
            print(f"WARN  {path.name}: {w}")
        n_err += len(errs)
        n_warn += len(warns)
    print(f"--- lint: {n_err} errors, {n_warn} warnings")
    if n_err or (args.strict and n_warn):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
