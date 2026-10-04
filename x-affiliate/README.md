# ミモザデータ — X × 女性の働き方データ × キャリア系アフィリエイト

> 女性の働き方を、数字で選ぶ。

厚生労働省の公開データ（男女の賃金差異・女性管理職比率・男性育休取得率）を出典つきで読み解く X アカウント＋データメディア。
集客は X、収益は転職・キャリア系のアフィリエイト（＋本）。**初期設定後のオーナー作業は週15分＋月10分**になるよう設計・実装してあります。

## まず読むもの

| | ファイル |
|---|---|
| あなたがやること（10項目） | [docs/10_owner_checklist.md](docs/10_owner_checklist.md) |
| 最初の30日間 | [docs/11_first_30_days.md](docs/11_first_30_days.md) |
| 事業の全体設計 | [docs/05_business_design.md](docs/05_business_design.md) |

## 設計ドキュメント

1. [市場調査](docs/01_market_research.md)
2. [事業案10個](docs/02_business_models.md)
3. [スコアリングと時給換算](docs/03_scoring.md)
4. [上位3案のファネル逆算と最終決定](docs/04_top3_funnel.md)
5. [事業設計・ファネル](docs/05_business_design.md)
6. [Xアカウント設計（名前・プロフィール・NG事項）](docs/06_x_account.md)
7. [投稿システムと30日分の投稿](docs/07_post_system.md)（本体：[posts/plan_30days.yaml](posts/plan_30days.yaml)）
8. [自動化設計・技術構成](docs/08_automation.md)
9. [コンプライアンス](docs/compliance.md)

## 仕組み（1枚で）

```
土曜 09:07  GitHub Actions：ネタ収集 → 厚労省データ更新(月1) → X指標取得 → 週次レポート
            → 翌週の投稿14本＋記事1本を生成 → 自動チェック → PR「【要確認】…（マージ＝承認）」
あなた       スマホでPRを読んでマージ（15分）
毎日2回      承認済みの投稿を X API で予約投稿（記事リンクは自分のリプ欄に）
main更新     Cloudflare Pages がサイト（記事・業種/企業データページ）を自動公開
```

## よく使うコマンド（任意・ローカル）

```bash
pip install -r requirements-dev.txt
python -m pytest -q tests
python scripts/lint_content.py
python scripts/build_site.py                  # → dist/
python scripts/generate_posts.py --plan-only  # 翌週の投稿ファイル（API不要）
python scripts/post_scheduled.py --dry-run
```

## 費用

ドメイン 約¥1,500〜2,000/年、X API 約$3/月、Claude API 約$3〜8/月。その他（GitHub Actions・Cloudflare Pages・解析）は無料枠。
