# STEP 8・9：自動化設計と技術構成

## 方針

- **完全自動投稿ではなく「週1回の人間承認＋自動投稿」**。生成物は Pull Request で届き、**マージ＝承認**。マージされるまで1件も投稿されない。
- X の自動化ルールに沿い、自動化するのは**自分のオリジナル投稿の予約投稿だけ**。自動リプライ・いいね・フォロー・メンション・DMは実装しない。
- 公式 X API（従量課金）を使う。スクレイピングや非公式APIは使わない。
- 無料・低コスト・保守が簡単：Python標準的ライブラリ＋GitHub Actions＋静的サイト。DB・サーバーなし。

## 技術構成

| 層 | 採用 | 理由 | 費用 |
|---|---|---|---|
| リポジトリ／スケジューラ | GitHub（Private）＋ GitHub Actions | cron・PR承認フロー・履歴がそろう | 無料枠内（月数百分） |
| サイト | Python製の自前静的サイトジェネレータ（Jinja2＋Markdown） | 依存が少なく、データページの大量生成に向く | 無料 |
| ホスティング | Cloudflare Pages（Git連携） | Privateリポジトリから無料配信、ビルド設定のみでシークレット不要 | 無料 |
| アクセス解析 | Cloudflare Web Analytics | Cookie不要・同意バナー不要・トークン1つで導入 | 無料 |
| 投稿 | X API v2（OAuth 1.0a ユーザーコンテキスト） | 規約上安全・最安 | 約$3/月 |
| 生成 | Claude API（`claude-opus-5-5`、構造化出力、web検索、拒否時フォールバック） | 日本語品質・JSON保証・出典確認 | 約$3〜8/月 |
| データ | 厚労省「女性の活躍推進企業データベース」オープンデータ（CSV） | 二次利用可の一次データ | 無料 |
| ドメイン | Cloudflare Registrar 等 | 原価で更新 | 約¥1,500〜2,000/年 |

**ランニングコスト合計：月 約¥1,500〜2,000**

## 自動化マップ

| 業務 | 自動化の方法 | スクリプト | 頻度 | 人の関与 |
|---|---|---|---|---|
| ネタ収集 | 厚労省・Google News の RSS 見出しを収集・キーワード採点 | `collect_trends.py` | 週1 | なし |
| ニュース・トレンド収集 | 同上（見出しのみ、本文転載なし） | 同上 | 週1 | なし |
| データ更新 | 厚労省オープンデータZIPを取得→列を自動判定→正規化→集計 | `fetch_womendb.py` | 月1（27日経過で自動） | なし |
| 投稿案生成 | 30日プラン→以降は Claude が週14本生成 | `generate_posts.py` | 週1 | PR確認 |
| 投稿のリライト | lint 不合格を Claude が自動書き直し | `generate_posts.py` 内 | 都度 | なし |
| 投稿スケジュール | 週ファイルの `at` に日時、2枠/日 | `generate_posts.py` / `config/schedule.yaml` | 週1 | なし |
| 予約投稿 | 承認済みを予定時刻に投稿、二重投稿・連投防止、直前lint | `post_scheduled.py` | 1日2回 | なし |
| ブログ記事生成 | バックログ先頭のテーマを、web検索で出典確認しつつ生成 | `generate_article.py` | 週1本 | PR確認 |
| 商品情報更新 | `data/affiliates.csv`（案件DB）と `data/links.yaml`（リンクの唯一の正）。リンク未設定でもサイトは壊れない | `build_site.py` | 提携時 | URLを貼るだけ |
| アフィリエイトリンク管理 | `[[link:キー]]` `[[cta:キー|見出し|説明]]` を全ページで展開、`rel="sponsored"`、PR表記自動挿入 | `build_site.py` | ビルド毎 | なし |
| 効果測定 | 自分の投稿のインプ・ER・リンククリック・プロフィールクリックをAPIで取得 | `fetch_x_metrics.py` | 週1 | なし |
| ABテスト | `ab_group`/`variant` で同内容・別フックを配置→勝敗判定 | `weekly_report.py` | 週1 | なし |
| 週次レポート | サマリー・タイプ別・TOP5・A/B・月次KPI・次週提案をMarkdownで | `weekly_report.py` | 週1 | PR本文で読むだけ |
| 派生コンテンツ | TOP投稿→学習メモ→翌週の生成に反映、F（再利用）枠、記事化候補 | `weekly_report.py` → `generate_posts.py` | 週1 | なし |
| コンプライアンス | NG表現・文字数・出典・PR表記・未解決プレースホルダを機械チェック | `lint_content.py` | 生成時・投稿直前・CI | なし |

## ワークフロー

```
[土 09:07 JST] XA: weekly content PR（.github/workflows/xa-weekly.yml）
  collect_trends → fetch_womendb(月1) → fetch_x_metrics → weekly_report
  → generate_posts(翌週14本) → generate_article(1本) → lint → build確認
  → PR「【要確認】来週の投稿と記事（マージ＝承認）」（本文に全投稿とレポート）
        │
        ▼ オーナー：スマホのGitHubで読んでマージ（15分）
[毎日 07:50 / 21:20 JST] XA: post scheduled（xa-post.yml）
  approved: true の週ファイルから予定時刻を過ぎた投稿を lint → 投稿 → リプ欄にリンク
  → posts/state/posted.json をコミット
        │
        ▼ main への push
Cloudflare Pages が自動ビルド・公開（記事・データページ更新）

[push/PR] XA: ci（xa-ci.yml）… pytest・lint・ビルド
```

## 安全装置

- **承認ゲート**：週ファイルは PR でしか main に入らない。マージされない限り投稿されない
- **投稿直前の再チェック**：NG表現・140字・未解決プレースホルダ・PR表記。NGは `blocked` として記録し投稿しない
- **連投防止**：1回の実行で最大2件。予定から6時間を過ぎたものは投稿せず `missed`
- **架空の数字を出さない**：データ由来の数値は `{{stat:...}}` のみ。未取得なら投稿・表示しない
- **リンク未提携でも壊れない**：URL空のリンクはテキスト表示、CTAは「準備中」
- **PR表記の自動化**：有効なアフィリンクを含むページに【PR】を自動挿入

## ディレクトリ構成

```
x-affiliate/
├── README.md                 運用マニュアル（まずここ）
├── config/                   brand（ペルソナ・NG語）／site／schedule／feeds
├── content/articles/         記事（Markdown + front matter）
├── data/
│   ├── affiliates.csv        アフィリエイト案件DB
│   ├── links.yaml            アフィリエイトリンク（唯一の正）
│   ├── topics_backlog.csv    記事テーマのバックログ
│   ├── trends.json           ネタ帳（自動）
│   └── womendb/              厚労省データ（自動）
├── posts/
│   ├── plan_30days.yaml      最初の30日・60本
│   ├── scheduled/            週ごとの投稿ファイル（PRで届く）
│   └── state/                投稿済み・プラン進捗（自動）
├── prompts/                  Claude用プロンプト（system / 投稿 / 書き直し / 記事 / 派生）
├── scripts/                  自動化スクリプト一式
├── templates/ static/        サイトのテンプレートとCSS
├── analytics/                post_metrics.csv / kpi_monthly.csv / reports / learnings.md
├── docs/                     事業設計ドキュメント（本ファイル群）
└── tests/                    pytest（フィクスチャはダミー企業データ）
```

## ローカルで動かす（任意）

```bash
cd x-affiliate
pip install -r requirements-dev.txt
python -m pytest -q tests                      # テスト
python scripts/lint_content.py                 # コンプライアンスチェック
python scripts/build_site.py                   # dist/ にサイト生成
python scripts/generate_posts.py --plan-only   # 翌週の投稿ファイル（API不要）
python scripts/post_scheduled.py --dry-run     # 投稿内容の確認（投稿しない）
python scripts/funnel_model.py                 # ファネル逆算
```
