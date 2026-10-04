"""上位3案の収益ファネル逆算モデル（docs/04_top3_funnel.md の表を生成）。

  python scripts/funnel_model.py            # Markdown表を出力
前提値は下の SCENARIOS を編集。すべて「目安」であり、実績で毎月置き換える。
"""
from __future__ import annotations

TARGETS = [10_000, 50_000, 100_000]

SCENARIOS = {
    "案1 女性の働き方データ×キャリア": dict(
        aff_click_rate=0.06,      # サイト訪問→アフィリンククリック
        cvr=0.03,                 # クリック→成果発生（登録・面談予約）
        payout=6_000,             # 承認後の平均報酬（承認率込み・円）
        x_share=0.5,              # 訪問のうちXから来る割合（残りは検索。立ち上げ期は0.9→半年後0.5を想定）
        x_ctr=0.0025,             # Xインプレッション→サイト訪問（全投稿平均）
        imp_per_follower=60,      # フォロワー1人あたり月間インプレッション（1日2投稿・おすすめ表示込みの目安。30〜100で変動）
        seo_pv_per_article=80,    # 1記事あたり月間検索流入（公開6か月後の目安）
    ),
    "案4 働く女性のAI・時短ツール": dict(
        aff_click_rate=0.08, cvr=0.02, payout=5_000, x_share=0.6, x_ctr=0.003,
        imp_per_follower=60, seo_pv_per_article=60,
    ),
    "案3 ジェンダー・働き方の本": dict(
        aff_click_rate=0.15, cvr=0.08, payout=120, x_share=0.6, x_ctr=0.003,
        imp_per_follower=60, seo_pv_per_article=40,
    ),
}


def solve(p: dict, target: int) -> dict:
    rpv = p["aff_click_rate"] * p["cvr"] * p["payout"]
    visits = target / rpv
    clicks = visits * p["aff_click_rate"]
    cv = clicks * p["cvr"]
    x_visits = visits * p["x_share"]
    imps = x_visits / p["x_ctr"]
    followers = imps / p["imp_per_follower"]
    articles = visits * (1 - p["x_share"]) / p["seo_pv_per_article"]
    return dict(rpv=rpv, visits=visits, clicks=clicks, cv=cv, imps=imps, followers=followers, articles=articles)


def _r(x: float) -> str:
    if x >= 10_000:
        return f"{x / 10_000:.1f}万"
    return f"{x:,.0f}"


def main() -> None:
    for name, p in SCENARIOS.items():
        r0 = solve(p, 1)
        print(f"\n### {name}\n")
        print(f"前提: 訪問→アフィクリック {p['aff_click_rate']:.0%} / クリック→成果 {p['cvr']:.0%} / 平均報酬 ¥{p['payout']:,} "
              f"/ 訪問1件あたり収益(RPV) ¥{r0['rpv'] * 1:.1f} / X経由 {p['x_share']:.0%}\n")
        print("| 月の確定報酬 | 月間Xインプ | フォロワー目安 | サイト訪問 | アフィクリック | 成果件数 | 必要記事数（検索流入分） |")
        print("|---|---:|---:|---:|---:|---:|---:|")
        for t in TARGETS:
            r = solve(p, t)
            print(f"| ¥{t:,} | {_r(r['imps'])} | {_r(r['followers'])} | {_r(r['visits'])} | {_r(r['clicks'])} | "
                  f"{r['cv']:,.1f} | {r['articles']:,.0f} |")


if __name__ == "__main__":
    main()
