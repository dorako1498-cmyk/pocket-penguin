# STEP 10：あなたにやってもらうのはこれだけです（10項目）

初期設定は合計 **3〜5時間**（ASPの審査待ちを除く）。そのあとは **週15分＋月10分** です。
コピペで済むものは、すべてこのリポジトリに用意してあります。

## 初期設定（1回だけ）

| # | やること | 目安 | 用意済みのもの |
|---|---|---|---|
| 1 | **新しい Private リポジトリを作る**（例：`mimosa-data`）。作ったら名前を Claude に伝えてください。`x-affiliate/` とワークフローを私が移します。※ 匿名性のため、Androidアプリの公開リポジトリとは分けるのがおすすめです | 5分 | 移行作業は Claude |
| 2 | **独自ドメインを購入**（Cloudflare Registrar 推奨。例：`mimosa-data.jp` / `.com`）。決まったら Claude に伝えてください。設定ファイルは私が書き換えます | 15分 | 候補は `docs/06_x_account.md` |
| 3 | **Cloudflare Pages にリポジトリを接続**：Workers & Pages → Create → Pages → Connect to Git。設定は下の表をそのまま入力。ドメインを接続し、Web Analytics を ON にしてトークンを Claude に伝える | 20分 | 下表 |
| 4 | **Xアカウントを作成**：表示名・プロフィール・固定ポストは `docs/06_x_account.md` をコピペ、アイコン/ヘッダーは `static/brand/icon.png` / `header.png` をアップロード | 20分 | 文面・画像 |
| 5 | **X Developer Portal でアプリ作成**：developer.x.com → アプリ作成 → User authentication settings で権限 **Read and Write** → Keys and tokens で **API Key / API Key Secret / Access Token / Access Token Secret** の4つを発行 → 従量課金のクレジットを **$10** チャージ（数か月もちます） | 30分 | — |
| 6 | **Anthropic の APIキーを取得**：console.anthropic.com → API Keys → 作成、**$10** チャージ | 10分 | — |
| 7 | **GitHub に Secrets を5つ登録**：リポジトリ Settings → Secrets and variables → Actions → New repository secret（`ANTHROPIC_API_KEY`, `X_API_KEY`, `X_API_SECRET`, `X_ACCESS_TOKEN`, `X_ACCESS_TOKEN_SECRET`）。さらに Settings → Actions → General → **「Allow GitHub Actions to create and approve pull requests」をON** | 10分 | — |
| 8 | **ASPに登録して提携申請**：A8.net・もしもアフィリエイト・afb に登録（本人確認・振込口座・サイトURL＝手順2のドメイン）。`data/affiliates.csv` の priority=1 の案件カテゴリで検索して提携申請。承認されたリンク（URL）を Claude に貼って渡すか、`data/links.yaml` の `url:` に貼る | 60〜90分（＋審査数日） | 案件DB・リンク枠 |

### Cloudflare Pages の設定値（手順3）

| 項目 | 値 |
|---|---|
| Framework preset | None |
| Root directory | `x-affiliate` |
| Build command | `pip install -r requirements.txt && python scripts/build_site.py` |
| Build output directory | `dist` |
| 環境変数 | `PYTHON_VERSION` = `3.11` |

## 運用（ずっと）

| # | やること | 頻度・目安 |
|---|---|---|
| 9 | **週次PRを読んでマージ**：毎週土曜の朝に「【要確認】来週の投稿と記事（マージ＝承認）」というPRが届きます。スマホのGitHubアプリで本文（全投稿が並んでいます）を読み、気になる投稿だけ編集 or 削除してマージ。**マージしない限り何も投稿されません** | 週1回・15分 |
| 10 | **月1回、ASPの確定報酬を記録**：A8.net 等の管理画面で前月の確定報酬を見て、`analytics/kpi_monthly.csv` に1行追加（金額・件数・その月の作業時間）。Claude に数字を伝えるだけでもOK | 月1回・5〜10分 |

## やらなくていいこと

- 毎日の投稿・返信・いいね（すべて不要。返信もしない運用です）
- 記事執筆・投稿文の作成（Claude が下書き、PRで承認だけ）
- 数値の調査（厚労省データを自動取得・自動集計）
- リンク切れ・PR表記の確認（自動チェック）
- 分析（週次レポートが PR に添付されます）

## 困ったとき

- PR が届かない → Actions タブで「XA: weekly content PR」を開き、Run workflow で手動実行
- 投稿されない → PR をマージしたか、Secrets の名前が正しいかを確認。Actions の「XA: post scheduled」のログに理由が出ます
- それでも分からない → Claude Code にこのリポジトリを開いて「ミモザデータの◯◯が動かない」と伝えてください
