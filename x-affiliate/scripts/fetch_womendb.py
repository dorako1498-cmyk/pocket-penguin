"""厚生労働省「女性の活躍推進企業データベース」オープンデータを取得・正規化し、
集計値 stats.json を作る。

出力:
  data/womendb/companies.csv.gz  … 正規化済み企業データ（サイトの企業・業種ページの元）
  data/womendb/stats.json        … 投稿の {{stat:...}} と記事で使う集計値
  data/womendb/columns_detected.json … どの列を何として読んだか（列名変更の検知用）

使い方:
  python scripts/fetch_womendb.py                 # 公式サイトから最新ZIPを取得
  python scripts/fetch_womendb.py --file x.zip    # ローカルのZIP/CSVから（テスト・手動用）

出典表記（サイト・投稿で使う）:
  「出典：厚生労働省『女性の活躍推進企業データベース』オープンデータ（YYYY年M月D日更新）を加工して作成」
※ 企業が自ら公表した値であり、算定方法（対象範囲・時点）は企業ごとに異なりうる点を必ず併記する。
"""
from __future__ import annotations

import argparse
import csv
import gzip
import io
import re
import statistics
import sys
import zipfile
from collections import defaultdict
from urllib.parse import urljoin

from common import DATA, dump_json, load_json, load_yaml, now_jst

OPENDATA_INDEX = "https://positive-ryouritsu.mhlw.go.jp/positivedb/opendata/index.html"
OUT_DIR = DATA / "womendb"
FIELDS = ["company_name", "corp_number", "prefecture", "industry", "employees", "pay_gap_all",
          "pay_gap_regular", "pay_gap_nonregular", "female_manager_ratio", "female_worker_ratio",
          "male_leave_rate", "updated"]
NUMERIC = {"pay_gap_all", "pay_gap_regular", "pay_gap_nonregular", "female_manager_ratio",
           "female_worker_ratio", "male_leave_rate"}
PREFS = ["北海道", "青森県", "岩手県", "宮城県", "秋田県", "山形県", "福島県", "茨城県", "栃木県", "群馬県",
         "埼玉県", "千葉県", "東京都", "神奈川県", "新潟県", "富山県", "石川県", "福井県", "山梨県", "長野県",
         "岐阜県", "静岡県", "愛知県", "三重県", "滋賀県", "京都府", "大阪府", "兵庫県", "奈良県", "和歌山県",
         "鳥取県", "島根県", "岡山県", "広島県", "山口県", "徳島県", "香川県", "愛媛県", "高知県", "福岡県",
         "佐賀県", "長崎県", "熊本県", "大分県", "宮崎県", "鹿児島県", "沖縄県"]


def _norm(h: str) -> str:
    return re.sub(r"[\s　（）()【】\[\]「」・:：/／]", "", h or "")


def detect_columns(headers: list[str]) -> dict[str, int]:
    """列名のキーワードから各フィールドの列番号を推定する。最初に一致した列を採用。"""
    found: dict[str, int] = {}

    def take(field, idx):
        if field not in found:
            found[field] = idx

    for i, raw in enumerate(headers):
        h = _norm(raw)
        if any(k in h for k in ("企業名", "事業主名", "会社名", "法人名")) and "カナ" not in h and "フリガナ" not in h:
            take("company_name", i)
        elif "法人番号" in h:
            take("corp_number", i)
        elif "都道府県" in h or (("所在地" in h or "住所" in h) and "郵便" not in h):
            take("prefecture", i)
        elif "業種" in h:
            take("industry", i)
        elif "賃金" in h and "差異" in h:
            if "非正規" in h or "パート" in h or "有期" in h:
                take("pay_gap_nonregular", i)
            elif "正規" in h or "正社員" in h:
                take("pay_gap_regular", i)
            else:
                take("pay_gap_all", i)
        elif "管理職" in h and "女性" in h and any(k in h for k in ("割合", "比率", "率")) and not any(
                k in h for k in ("課長", "部長", "係長", "役員", "人数")):
            take("female_manager_ratio", i)
        elif "労働者" in h and "女性" in h and any(k in h for k in ("割合", "比率")) and "管理職" not in h and "採用" not in h:
            take("female_worker_ratio", i)
        elif "育児休業" in h and "男性" in h and "取得率" in h:
            take("male_leave_rate", i)
        elif any(k in h for k in ("労働者数", "労働者の数", "従業員数", "企業規模")) and "女性" not in h and "男性" not in h:
            take("employees", i)
        elif any(k in h for k in ("更新日", "公表日", "データ更新", "最終更新")):
            take("updated", i)
    return found


def parse_number(v: str):
    if v is None:
        return None
    s = str(v).strip().replace("％", "").replace("%", "").replace(",", "")
    m = re.search(r"-?\d+(?:\.\d+)?", s)
    if not m:
        return None
    x = float(m.group(0))
    return x


def parse_prefecture(v: str) -> str:
    for p in PREFS:
        if v and v.startswith(p):
            return p
    for p in PREFS:
        if v and p in v:
            return p
    return ""


def employees_min(v: str) -> int:
    """「301～1000人」「1,234」「101人以上」等から下限人数を推定。"""
    if not v:
        return 0
    nums = [int(n.replace(",", "")) for n in re.findall(r"\d[\d,]*", str(v))]
    return min(nums) if nums else 0


def read_tables(blob: bytes, name: str) -> list[list[str]]:
    if name.lower().endswith(".zip"):
        rows: list[list[str]] = []
        with zipfile.ZipFile(io.BytesIO(blob)) as z:
            csvs = [n for n in z.namelist() if n.lower().endswith(".csv")]
            # 最大のCSV（＝全体版）を使う
            csvs.sort(key=lambda n: z.getinfo(n).file_size, reverse=True)
            if not csvs:
                raise RuntimeError("ZIP内にCSVがありません")
            return read_tables(z.read(csvs[0]), csvs[0])
        return rows
    for enc in ("utf-8-sig", "cp932", "utf-8"):
        try:
            text = blob.decode(enc)
            break
        except UnicodeDecodeError:
            continue
    else:
        raise RuntimeError("CSVの文字コードを判定できません")
    return list(csv.reader(io.StringIO(text)))


def pick_header(table: list[list[str]]) -> tuple[list[str], int]:
    """1〜3行目のうち、検出できる列が最も多い行（または2行結合）をヘッダとする。"""
    best, best_n, best_start = table[0], -1, 1
    for i in range(min(3, len(table))):
        for cand, start in ((table[i], i + 1),
                            ([a + b for a, b in zip(table[i], table[i + 1])] if i + 1 < len(table) else None, i + 2)):
            if cand is None:
                continue
            n = len(detect_columns(cand))
            if n > best_n:
                best, best_n, best_start = cand, n, start
    return best, best_start


def normalize(table: list[list[str]]) -> tuple[list[dict], dict]:
    header, start = pick_header(table)
    override = load_yaml(OUT_DIR / "column_map.yaml", {}) or {}
    cols = detect_columns(header)
    for field, colname in override.items():
        if colname in header:
            cols[field] = header.index(colname)
    if "company_name" not in cols:
        raise RuntimeError(f"企業名の列を検出できません。header={header[:30]}")
    rows = []
    for raw in table[start:]:
        if not raw or len(raw) < len(header) // 2:
            continue
        rec = {}
        for f in FIELDS:
            i = cols.get(f)
            v = raw[i].strip() if i is not None and i < len(raw) else ""
            if f in NUMERIC:
                n = parse_number(v)
                # 割合は 0〜200 の範囲のみ有効（入力ミス除外）
                rec[f] = n if n is not None and 0 <= n <= 200 else None
            elif f == "prefecture":
                rec[f] = parse_prefecture(v)
            else:
                rec[f] = v
        if rec["company_name"]:
            rows.append(rec)
    detected = {f: header[i] for f, i in cols.items()}
    return rows, detected


def _median(xs):
    xs = [x for x in xs if x is not None]
    return round(statistics.median(xs), 1) if xs else None


def compute_stats(rows: list[dict], updated_label: str) -> dict:
    by_ind = defaultdict(list)
    for r in rows:
        if r.get("industry"):
            by_ind[r["industry"]].append(r)
    ind_stats = {}
    for ind, rs in by_ind.items():
        pg = [r["pay_gap_all"] for r in rs if r["pay_gap_all"] is not None]
        mg = [r["female_manager_ratio"] for r in rs if r["female_manager_ratio"] is not None]
        ind_stats[ind] = {"n": len(rs), "n_paygap": len(pg), "paygap_median": _median(pg),
                          "mgr_median": _median(mg), "n_mgr": len(mg)}
    eligible_pg = {k: v for k, v in ind_stats.items() if v["n_paygap"] >= 30 and v["paygap_median"] is not None}
    eligible_mg = {k: v for k, v in ind_stats.items() if v["n_mgr"] >= 30 and v["mgr_median"] is not None}

    pg_all = [r["pay_gap_all"] for r in rows if r["pay_gap_all"] is not None]
    mg_all = [r["female_manager_ratio"] for r in rows if r["female_manager_ratio"] is not None]
    lv_all = [r["male_leave_rate"] for r in rows if r["male_leave_rate"] is not None]

    def share(xs, cond):
        return round(100 * sum(1 for x in xs if cond(x)) / len(xs), 1) if xs else None

    stats = {
        "source_label": f"厚生労働省「女性の活躍推進企業データベース」オープンデータ（{updated_label}取得）を加工",
        "fetched": updated_label,
        "n_companies": len(rows),
        "n_paygap": len(pg_all),
        "n_mgr": len(mg_all),
        "paygap_all_median": _median(pg_all),
        "mgr_ratio_median": _median(mg_all),
        "male_leave_median": _median(lv_all),
        "share_mgr_30plus": share(mg_all, lambda x: x >= 30),
        "share_mgr_zero": share(mg_all, lambda x: x == 0),
        "share_paygap_80plus": share(pg_all, lambda x: x >= 80),
        "share_paygap_under60": share(pg_all, lambda x: x < 60),
        "by_industry": ind_stats,
    }
    if eligible_pg:
        top = max(eligible_pg.items(), key=lambda kv: kv[1]["paygap_median"])
        bot = min(eligible_pg.items(), key=lambda kv: kv[1]["paygap_median"])
        stats.update(industry_top_paygap=top[0], industry_top_paygap_value=top[1]["paygap_median"],
                     industry_bottom_paygap=bot[0], industry_bottom_paygap_value=bot[1]["paygap_median"])
    if eligible_mg:
        top = max(eligible_mg.items(), key=lambda kv: kv[1]["mgr_median"])
        bot = min(eligible_mg.items(), key=lambda kv: kv[1]["mgr_median"])
        stats.update(industry_top_mgr=top[0], industry_top_mgr_value=top[1]["mgr_median"],
                     industry_bottom_mgr=bot[0], industry_bottom_mgr_value=bot[1]["mgr_median"])
    return stats


def download_latest() -> tuple[bytes, str]:
    import os
    import requests
    url = os.environ.get("WOMENDB_ZIP_URL")
    if not url:
        html = requests.get(OPENDATA_INDEX, timeout=60).text
        hrefs = re.findall(r'href="([^"]+\.zip)"', html, flags=re.I)
        if not hrefs:
            raise RuntimeError("オープンデータページにZIPリンクが見つかりません（ページ構成変更の可能性）")
        # 「全体」「all」を含むもの優先、なければ先頭
        hrefs.sort(key=lambda h: (0 if re.search(r"all|zentai|全体", h, re.I) else 1))
        url = urljoin(OPENDATA_INDEX, hrefs[0])
    print(f"download: {url}")
    r = requests.get(url, timeout=300)
    r.raise_for_status()
    return r.content, url.split("/")[-1]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--file")
    ap.add_argument("--if-older-than", type=int, metavar="DAYS", help="前回取得からこの日数以上たっている場合だけ取得")
    args = ap.parse_args()
    if args.if_older_than:
        from datetime import datetime, timedelta
        prev = load_json(OUT_DIR / "stats.json", {}) or {}
        if prev.get("fetched_iso") and now_jst() - datetime.fromisoformat(prev["fetched_iso"]) < timedelta(days=args.if_older_than):
            print(f"前回取得 {prev['fetched_iso']} から{args.if_older_than}日未満のためスキップ")
            return 0
    if args.file:
        with open(args.file, "rb") as f:
            blob, name = f.read(), args.file
    else:
        blob, name = download_latest()
    table = read_tables(blob, name)
    rows, detected = normalize(table)
    label = now_jst().strftime("%Y年%-m月%-d日")
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    with gzip.open(OUT_DIR / "companies.csv.gz", "wt", encoding="utf-8", newline="") as f:
        w = csv.DictWriter(f, fieldnames=FIELDS)
        w.writeheader()
        w.writerows(rows)
    dump_json(OUT_DIR / "columns_detected.json", detected)
    stats = compute_stats(rows, label)
    stats["fetched_iso"] = now_jst().isoformat()
    dump_json(OUT_DIR / "stats.json", stats)
    missing = [f for f in ("pay_gap_all", "female_manager_ratio", "industry") if f not in detected]
    print(f"companies: {len(rows)} / detected: {list(detected)}")
    if missing:
        print(f"WARNING: 未検出の重要列 {missing} → data/womendb/column_map.yaml で列名を指定してください")
    return 0


if __name__ == "__main__":
    sys.exit(main())
