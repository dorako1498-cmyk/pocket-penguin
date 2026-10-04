"""週次レポートを作る（analytics/reports/YYYY-Www.md）＋ 学習メモ（analytics/learnings.md）を更新。

入力:
  analytics/post_metrics.csv   … fetch_x_metrics.py が自動更新
  analytics/kpi_monthly.csv    … ASP成果（月1回、手入力 or 空でもOK）
  posts/scheduled/*.yaml       … 投稿本文の参照用
出力:
  analytics/reports/<週>.md    … 週次PRの本文にも使う
  analytics/learnings.md       … 次週の投稿生成プロンプトに渡す「伸びた型／伸びなかった型」
"""
from __future__ import annotations

import sys
from collections import defaultdict
from datetime import datetime, timedelta

from common import ANALYTICS, POSTS, iso_week_label, load_yaml, now_jst, read_csv

TYPE_NAMES = {"A": "インプレッション", "B": "フォロー", "C": "信頼", "D": "興味喚起", "E": "送客", "F": "再利用"}
MIN_IMP_FOR_RANK = 100


def post_index() -> dict:
    idx = {}
    for path in (POSTS / "scheduled").glob("*.yaml"):
        for p in (load_yaml(path, {}) or {}).get("posts", []):
            idx[p.get("id")] = p
    return idx


def _f(v) -> float:
    try:
        return float(v)
    except (TypeError, ValueError):
        return 0.0


def summarize(rows: list[dict]) -> dict:
    imp = sum(_f(r["impressions"]) for r in rows)
    clicks = sum(_f(r["url_link_clicks"]) for r in rows)
    prof = sum(_f(r["user_profile_clicks"]) for r in rows)
    er = [(_f(r["engagement_rate"])) for r in rows if _f(r["impressions"]) > 0]
    return {
        "posts": len(rows), "impressions": int(imp), "link_clicks": int(clicks), "profile_clicks": int(prof),
        "avg_er": sum(er) / len(er) if er else 0.0, "ctr": clicks / imp if imp else 0.0,
    }


def ab_results(rows: list[dict]) -> list[str]:
    groups = defaultdict(list)
    for r in rows:
        if r.get("ab_group"):
            groups[r["ab_group"]].append(r)
    lines = []
    for g, rs in sorted(groups.items()):
        if len(rs) < 2:
            continue
        rs = sorted(rs, key=lambda r: _f(r["engagement_rate"]), reverse=True)
        best, worst = rs[0], rs[-1]
        lift = (_f(best["engagement_rate"]) / _f(worst["engagement_rate"]) - 1) if _f(worst["engagement_rate"]) else 0
        verdict = "差あり" if lift >= 0.3 and _f(best["impressions"]) >= MIN_IMP_FOR_RANK else "差は小さい/サンプル不足"
        lines.append(f"| {g} | {best['variant'] or best['post_id']} | {_f(best['engagement_rate']):.2%} | "
                     f"{worst['variant'] or worst['post_id']} | {_f(worst['engagement_rate']):.2%} | {verdict} |")
    return lines


def main() -> int:
    now = now_jst()
    week = iso_week_label(now - timedelta(days=1))
    rows = read_csv(ANALYTICS / "post_metrics.csv")
    idx = post_index()

    def in_days(r, days):
        return now - datetime.fromisoformat(r["posted_at"]) <= timedelta(days=days)

    w = [r for r in rows if in_days(r, 7)]
    m = [r for r in rows if in_days(r, 28)]
    sw, sm = summarize(w), summarize(m)

    out = [f"# 週次レポート {week}", "", f"作成: {now:%Y-%m-%d %H:%M} JST（自動生成）", ""]
    out += ["## サマリー", "", "| 指標 | 直近7日 | 直近28日 |", "|---|---:|---:|"]
    for k, label in [("posts", "投稿数"), ("impressions", "インプレッション"), ("link_clicks", "リンククリック"),
                     ("profile_clicks", "プロフィールクリック")]:
        out.append(f"| {label} | {sw[k]:,} | {sm[k]:,} |")
    out.append(f"| 平均エンゲージメント率 | {sw['avg_er']:.2%} | {sm['avg_er']:.2%} |")
    out.append(f"| リンクCTR（クリック/インプ） | {sw['ctr']:.2%} | {sm['ctr']:.2%} |")

    out += ["", "## 投稿タイプ別（直近28日）", "", "| タイプ | 投稿数 | 平均インプ | 平均ER | クリック |", "|---|---:|---:|---:|---:|"]
    by_type = defaultdict(list)
    for r in m:
        by_type[r["type"]].append(r)
    for t in "ABCDEF":
        rs = by_type.get(t, [])
        if not rs:
            continue
        s = summarize(rs)
        out.append(f"| {t} {TYPE_NAMES[t]} | {s['posts']} | {s['impressions'] // max(s['posts'], 1):,} | {s['avg_er']:.2%} | {s['link_clicks']} |")

    ranked = sorted([r for r in m if _f(r["impressions"]) >= MIN_IMP_FOR_RANK],
                    key=lambda r: _f(r["engagement_rate"]), reverse=True)
    top, bottom = ranked[:5], ranked[-3:] if len(ranked) > 5 else []
    out += ["", "## 伸びた投稿 TOP5（ER順、インプ100以上）", ""]
    for r in top:
        text = (idx.get(r["post_id"], {}).get("text") or "").strip().replace("\n", " ")
        out.append(f"- **{r['post_id']}**（{r['type']} / ER {_f(r['engagement_rate']):.2%} / インプ {int(_f(r['impressions'])):,}）: {text[:90]}")
    ab = ab_results(m)
    if ab:
        out += ["", "## A/Bテスト", "", "| グループ | 勝ち | ER | 負け | ER | 判定 |", "|---|---|---:|---|---:|---|"] + ab

    kpi = read_csv(ANALYTICS / "kpi_monthly.csv")
    if kpi:
        out += ["", "## 月次KPI（ASP成果・手入力分）", "", "| 月 | 確定報酬 | 発生件数 | 作業時間(h) | 時間あたり利益 |", "|---|---:|---:|---:|---:|"]
        for r in kpi[-6:]:
            h = _f(r.get("owner_hours"))
            rev = _f(r.get("confirmed_revenue_jpy"))
            cost = _f(r.get("cost_jpy"))
            per_h = f"¥{(rev - cost) / h:,.0f}" if h else "—"
            out.append(f"| {r.get('month')} | ¥{rev:,.0f} | {r.get('conversions', '')} | {h:g} | {per_h} |")

    out += ["", "## 次週への自動提案", ""]
    if not rows:
        out.append("- まだ指標データがありません（X APIキー設定後、翌週から自動で集計されます）。")
    else:
        if sm["ctr"] < 0.002:
            out.append("- リンクCTRが0.2%未満。E投稿は「記事で分かること3点」を本文に入れ、数字で具体化する。")
        best_type = max(by_type, key=lambda t: summarize(by_type[t])["avg_er"]) if by_type else None
        if best_type:
            out.append(f"- ERが最も高いタイプは {best_type}（{TYPE_NAMES.get(best_type)}）。翌週はこのタイプの型を1本増やす。")
        if top:
            out.append(f"- TOP投稿 {top[0]['post_id']} を30日後に F（再利用）として言い換え再投稿、記事化候補に追加。")
    report = "\n".join(out) + "\n"
    path = ANALYTICS / "reports" / f"{week}.md"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(report, encoding="utf-8")

    learn = ["# 学習メモ（自動更新・投稿生成プロンプトに渡される）", "", "## 伸びた投稿（真似する型）"]
    for r in top:
        learn.append(f"- [{r['type']}/{r.get('theme')}] {(idx.get(r['post_id'], {}).get('text') or '').strip()[:140]}")
    learn += ["", "## 伸びなかった投稿（避ける型）"]
    for r in bottom:
        learn.append(f"- [{r['type']}/{r.get('theme')}] {(idx.get(r['post_id'], {}).get('text') or '').strip()[:140]}")
    (ANALYTICS / "learnings.md").write_text("\n".join(learn) + "\n", encoding="utf-8")
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
