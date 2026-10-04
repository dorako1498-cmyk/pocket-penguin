import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(ROOT / "scripts"))

import common  # noqa: E402
import fetch_womendb  # noqa: E402
import generate_posts  # noqa: E402
import lint_content  # noqa: E402
import post_scheduled  # noqa: E402

FIXTURE = ROOT / "tests" / "fixtures" / "sample_womendb.zip"


def test_x_weighted_length():
    assert common.x_weighted_length("abc") == 3
    assert common.x_weighted_length("あいう") == 6
    assert common.x_weighted_length("見て https://example.com/very/long/path") == 4 + 1 + 23


@pytest.mark.parametrize("text,ng", [
    ("女は家庭に入るべき", True),
    ("彼女は管理職です", False),
    ("男って結局", True),
    ("長男は", False),
    ("オススメの本", False),
    ("必ずしも悪いとは限りません", False),
    ("必ず年収が上がる", True),
    ("私が転職したとき", True),
    ("ブラック企業ランキング", True),
])
def test_ng_patterns(text, ng):
    assert bool(lint_content.find_ng(text)) is ng


def test_lint_post_rules():
    base = {"id": "x", "type": "C", "text": "女性管理職比率は12.7%です", "sources": []}
    errs, _ = lint_content.lint_post(base)
    assert any("出典" in e for e in errs)
    ok = dict(base, sources=["帝国データバンク"])
    assert lint_content.lint_post(ok)[0] == []
    ad = {"id": "y", "type": "D", "text": "相談してみるのも一つの手です", "affiliate": True, "sources": []}
    assert any("PR" in e for e in lint_content.lint_post(ad)[0])
    ad["text"] += " #PR"
    assert lint_content.lint_post(ad)[0] == []
    long = {"id": "z", "type": "A", "text": "あ" * 141, "sources": []}
    assert any("文字数" in e for e in lint_content.lint_post(long)[0])


def test_placeholders():
    stats = {"paygap_all_median": 73.25, "industry_top_mgr": "医療，福祉"}
    text = "中央値は{{stat:paygap_all_median}}、最も高いのは{{stat:industry_top_mgr}}"
    assert lint_content.resolve_placeholders(text, stats) == "中央値は73.2、最も高いのは医療，福祉"
    post = {"id": "p", "type": "A", "text": "{{stat:unknown}}です", "sources": ["s"]}
    assert any("未解決" in e for e in lint_content.lint_post(post, final=True)[0])


def test_womendb_normalize_and_stats():
    table = fetch_womendb.read_tables(FIXTURE.read_bytes(), FIXTURE.name)
    rows, detected = fetch_womendb.normalize(table)
    assert len(rows) == 240
    for f in ("company_name", "industry", "pay_gap_all", "pay_gap_regular", "pay_gap_nonregular",
              "female_manager_ratio", "female_worker_ratio", "male_leave_rate", "prefecture", "employees"):
        assert f in detected, f
    assert rows[0]["prefecture"] in fetch_womendb.PREFS
    stats = fetch_womendb.compute_stats(rows, "テスト")
    assert stats["n_companies"] == 240
    assert 50 <= stats["paygap_all_median"] <= 95
    assert "industry_top_paygap" in stats  # 管理職は業種あたり30社未満のため対象外になる
    assert fetch_womendb.employees_min("301～1000人") == 301


def test_plan_scheduling(tmp_path, monkeypatch):
    plan = tmp_path / "plan.yaml"
    common.dump_yaml(plan, {"posts": [
        {"day": d, "slot": s, "type": "C", "theme": "paygap", "text": f"day{d} {s}", "sources": []}
        for d in range(1, 4) for s in ("07:45", "21:15")]})
    monkeypatch.setattr(generate_posts, "PLAN_PATH", plan)
    monkeypatch.setattr(generate_posts, "PROGRESS_PATH", tmp_path / "progress.json")
    from datetime import datetime
    start = datetime(2026, 10, 12, tzinfo=common.JST)
    slots = generate_posts.week_slots(start)
    assert len(slots) == 14
    posts, remaining = generate_posts.from_plan(slots, "2026-W42")
    assert len(posts) == 6 and len(remaining) == 8
    assert posts[0]["at"] == "2026-10-12 07:45" and posts[0]["text"] == "day1 07:45"
    # 同じ週を作り直しても同じ日から割り当てる
    posts2, _ = generate_posts.from_plan(slots, "2026-W42")
    assert [p["text"] for p in posts2] == [p["text"] for p in posts]


def test_due_posts(tmp_path, monkeypatch):
    sched = tmp_path / "scheduled"
    sched.mkdir()
    common.dump_yaml(sched / "2026-W42.yaml", {"approved": True, "posts": [
        {"id": "a", "at": "2026-10-12 07:45", "type": "C", "text": "a"},
        {"id": "b", "at": "2026-10-12 21:15", "type": "C", "text": "b"},
        {"id": "c", "at": "2026-10-11 07:45", "type": "C", "text": "c"},
    ]})
    common.dump_yaml(sched / "2026-W43.yaml", {"approved": False, "posts": [
        {"id": "d", "at": "2026-10-12 07:00", "type": "C", "text": "d"}]})
    monkeypatch.setattr(post_scheduled, "POSTS", tmp_path)
    from datetime import datetime
    now = datetime(2026, 10, 12, 8, 0, tzinfo=common.JST)
    due, missed = post_scheduled.due_posts(now, {"posted": {}}, 6)
    assert [p["id"] for p in due] == ["a"]
    assert [p["id"] for p in missed] == ["c"]


def test_repo_content_lints_clean():
    """リポジトリ内の投稿プラン・記事が lint を通ること。"""
    stats = {}
    plan = common.load_yaml(common.POSTS / "plan_30days.yaml", {}) or {}
    for p in plan.get("posts", []):
        errs, _ = lint_content.lint_post(p, stats=stats)
        assert errs == [], (p.get("day"), p.get("slot"), errs)
    for path in (common.CONTENT / "articles").glob("*.md"):
        errs, _ = lint_content.lint_article(path.read_text(encoding="utf-8"))
        assert errs == [], (path.name, errs)


def test_build_site_smoke():
    import build_site
    assert build_site.main() == 0
    assert (common.DIST / "index.html").exists()
    assert (common.DIST / "sitemap.xml").exists()


def test_generate_with_claude_mapping(monkeypatch):
    """Claude の応答（構造化JSON）を投稿ファイル形式に変換できること（API はモック）。"""
    import llm
    from datetime import datetime
    start = datetime(2026, 11, 2, tzinfo=common.JST)
    slots = generate_posts.week_slots(start)[:3]
    fake = {"posts": [
        {"slot": slots[0]["at"], "type": "A", "theme": "paygap", "text": "テスト投稿です", "link_article": "",
         "affiliate": False, "sources": [], "ab_group": "", "variant": ""},
        {"slot": slots[1]["at"], "type": "E", "theme": "career_move", "text": "記事の紹介です #PR",
         "link_article": "choose-career-agent-for-women", "affiliate": True, "sources": [], "ab_group": "g1", "variant": "a"},
        {"slot": "2099-01-01 00:00", "type": "A", "theme": "x", "text": "枠外", "link_article": "",
         "affiliate": False, "sources": [], "ab_group": "", "variant": ""},
    ]}
    captured = {}

    def fake_call(user, schema, **kw):
        captured["user"] = user
        return fake

    monkeypatch.setattr(llm, "call_json", fake_call)
    posts = generate_posts.generate_with_claude(slots, start)
    assert len(posts) == 2
    assert posts[1]["link"] == {"article": "choose-career-agent-for-women"}
    assert posts[1]["ab_group"] == "g1"
    assert "{{stat:" not in captured["user"] or "stat" in captured["user"]
    assert "{week_start}" not in captured["user"]
