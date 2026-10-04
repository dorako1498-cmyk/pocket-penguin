"""共通ユーティリティ: パス、YAML/CSV入出力、X文字数カウント、日時。"""
from __future__ import annotations

import csv
import json
import os
import re
from datetime import datetime, timedelta, timezone
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "config"
DATA = ROOT / "data"
CONTENT = ROOT / "content"
POSTS = ROOT / "posts"
PROMPTS = ROOT / "prompts"
ANALYTICS = ROOT / "analytics"
TEMPLATES = ROOT / "templates"
STATIC = ROOT / "static"
DIST = ROOT / "dist"

JST = timezone(timedelta(hours=9))


def now_jst() -> datetime:
    override = os.environ.get("XA_NOW")  # テスト用: "2026-10-12T08:00"
    if override:
        return datetime.fromisoformat(override).replace(tzinfo=JST)
    return datetime.now(JST)


def load_yaml(path: Path, default=None):
    if not path.exists():
        return default
    with path.open(encoding="utf-8") as f:
        data = yaml.safe_load(f)
    return default if data is None else data


class _BlockDumper(yaml.SafeDumper):
    """複数行の文字列を | ブロックで書き出す（スマホのGitHubでも読みやすくするため）。"""


def _str_representer(dumper, value):
    if "\n" in value:
        return dumper.represent_scalar("tag:yaml.org,2002:str", value.rstrip("\n") + "\n", style="|")
    return dumper.represent_scalar("tag:yaml.org,2002:str", value)


_BlockDumper.add_representer(str, _str_representer)


def dump_yaml(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8") as f:
        yaml.dump(data, f, Dumper=_BlockDumper, allow_unicode=True, sort_keys=False, width=1000)


def load_json(path: Path, default=None):
    if not path.exists():
        return default
    return json.loads(path.read_text(encoding="utf-8"))


def dump_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def read_csv(path: Path) -> list[dict]:
    if not path.exists():
        return []
    with path.open(encoding="utf-8-sig", newline="") as f:
        return list(csv.DictReader(f))


def write_csv(path: Path, rows: list[dict], fieldnames: list[str] | None = None) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    if fieldnames is None:
        fieldnames = list(rows[0].keys()) if rows else []
    with path.open("w", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        w.writeheader()
        w.writerows(rows)


def brand() -> dict:
    return load_yaml(CONFIG / "brand.yaml", {})


def site_config() -> dict:
    return load_yaml(CONFIG / "site.yaml", {})


def schedule_config() -> dict:
    return load_yaml(CONFIG / "schedule.yaml", {})


def links() -> dict:
    return (load_yaml(DATA / "links.yaml", {}) or {}).get("links", {})


# ---- X の文字数（twitter-text の weighted length 準拠の簡易版） ----
_URL_RE = re.compile(r"https?://\S+")
_LIGHT_RANGES = ((0, 4351), (8192, 8205), (8208, 8223), (8242, 8247))
X_MAX_WEIGHT = 280
X_URL_WEIGHT = 23


def x_weighted_length(text: str) -> int:
    total = 0
    for m in _URL_RE.finditer(text):
        total += X_URL_WEIGHT
    rest = _URL_RE.sub("", text)
    for ch in rest:
        cp = ord(ch)
        total += 1 if any(lo <= cp <= hi for lo, hi in _LIGHT_RANGES) else 2
    return total


def iso_week_label(d: datetime) -> str:
    y, w, _ = d.isocalendar()
    return f"{y}-W{w:02d}"


def week_monday(d: datetime) -> datetime:
    return (d - timedelta(days=d.weekday())).replace(hour=0, minute=0, second=0, microsecond=0)


def article_url(slug: str) -> str:
    base = site_config().get("site", {}).get("base_url", "").rstrip("/")
    return f"{base}/articles/{slug}/"


def with_utm(url: str, post_id: str) -> str:
    sep = "&" if "?" in url else "?"
    return f"{url}{sep}utm_source=x&utm_medium=social&utm_campaign={post_id}"
