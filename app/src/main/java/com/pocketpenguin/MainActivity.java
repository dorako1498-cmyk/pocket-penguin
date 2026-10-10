package com.pocketpenguin;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Settings screen (Japanese, grouped). Shows how the penguin is doing at the top. */
public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private LinearLayout statusBox;
    private float dp;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("penguin", MODE_PRIVATE);
        dp = getResources().getDisplayMetrics().density;
        if (Build.VERSION.SDK_INT >= 33 && prefs.getBoolean("notify", true) && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 1);
        CareAlarm.schedule(getApplicationContext());

        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xFFF7F1EA);
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(px(18), px(18), px(18), px(24));

        TextView title = new TextView(this); title.setText("Pocket Penguin"); title.setTextSize(26); title.setTypeface(Typeface.DEFAULT_BOLD); title.setTextColor(0xFF20333D); title.setGravity(Gravity.CENTER);
        box.addView(title);
        TextView sub = new TextView(this); sub.setText("スマホの中の、ちいさなペンギンのへや"); sub.setTextSize(14); sub.setTextColor(0xFF5B6B74); sub.setGravity(Gravity.CENTER); sub.setPadding(0, 0, 0, px(12));
        box.addView(sub);

        // ---- status card
        statusBox = card(box);
        // ---- wallpaper / photo
        LinearLayout wp = section(box, "かべがみ");
        button(wp, "ライブ壁紙に設定する", v -> openWallpaperPicker());
        button(wp, "写真をとる（壁紙にもどると撮影）", v -> { prefs.edit().putLong("snap_req", System.currentTimeMillis()).apply(); Toast.makeText(this, "ホーム画面にもどると、いまの部屋を写真にします", Toast.LENGTH_LONG).show(); });
        button(wp, "さいごの写真を共有する", v -> shareLast());
        // ---- care
        LinearLayout care = section(box, "おせわ");
        sw(care, "はやいお世話（数分でおなかがすく）", "carefast", true);
        sw(care, "おしらせ通知（しばらく見ていないと知らせる）", "notify", true);
        button(care, "お世話と部屋をリセット", v -> {
            SharedPreferences.Editor e = prefs.edit();
            for (String k : new String[] { "care_first", "care_last", "care_pf", "care_ph", "care_bf", "care_bh", "care_food", "care_water", "care_feeds", "care_strokes", "care_fed", "care_pm", "care_play",
                    "care_bond", "care_friend", "care_plays", "care_souv", "decor_seen" }) e.remove(k);
            e.apply(); showStatus(); Toast.makeText(this, "リセットしました", Toast.LENGTH_SHORT).show();
        });
        // ---- movement
        LinearLayout mv = section(box, "うごき");
        sw(mv, "タップに反応する", "tap", true);
        sw(mv, "ジンベエ（ジンベエザメのなかま）", "buddy", true);
        sw(mv, "ふきだしで会話する", "talk", true);
        slider(mv, "うごきの量（おだやか / ふつう / げんき）", "speed", 2, 1);
        slider(mv, "ジンベエの大きさ", "buddysize", 4, 2);
        // ---- room
        LinearLayout rm = section(box, "へや");
        slider(rm, "床の高さ（ドックで隠れるときは上げる）", "floor", 10, 5);
        scenePicker(rm);
        sw(rm, "時間帯で部屋や天気が変わる", "time", true);
        picker(rm, "かべの色", Room.WALL_NAMES, "theme_wall");
        picker(rm, "ゆかの色", Room.FLOOR_NAMES, "theme_floor");
        picker(rm, "ラグの色", Room.RUG_NAMES, "theme_rug");
        sw(rm, "季節の衣装（マフラー・麦わら帽子など）", "costume", true);
        sw(rm, "部屋のアイテムを全部出す（おためし）", "unlockall", false);
        birthday(rm);
        // ---- phone
        LinearLayout ph = section(box, "スマホ連動");
        sw(ph, "時計を見る", "clock", true);
        sw(ph, "充電すると充電パッドへ行く", "charging", true);
        sw(ph, "画面をつけるとあいさつ", "screen", true);
        sw(ph, "画面のはしから出かける（おみやげ）", "door", true);
        sw(ph, "音楽を流すとおどる", "music", true);
        sw(ph, "スマホをふると転がる", "shake", true);
        button(ph, "通知が来たらふりむく（許可の画面をひらく）", v -> { try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); } catch (Exception e) { Toast.makeText(this, "この端末では開けませんでした", Toast.LENGTH_SHORT).show(); } });
        sw(ph, "省電力モード（うごきを少しへらす）", "power", false);
        // ---- tips
        TextView tip = new TextView(this);
        tip.setText("あそびかた\n・頭をなでる / タップ: あいさつ・よろこぶ\n・ゆかをタップ: お魚がおちる（ジンベエと取り合いになることも）\n・お皿をタップ: ごはん・お水をいれる\n・ボールをタップ: いっしょにあそぶ\n・けんか中に2匹をタップ: なかなおり\n・かくれんぼ中: ベッドのかげのペンギンをタップ\n・なつき度が上がると、あいさつが豪華になったり、新しい技をおぼえます");
        tip.setTextSize(13); tip.setTextColor(0xFF4A5A63); tip.setPadding(px(6), px(14), px(6), 0); box.addView(tip);

        scroll.addView(box); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    @Override protected void onResume() { super.onResume(); showStatus(); }

    private int px(float v) { return Math.round(v * dp); }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout c = new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(px(16), px(14), px(16), px(14));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xFFFFFFFF); bg.setCornerRadius(px(16)); c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2); lp.bottomMargin = px(12); parent.addView(c, lp);
        return c;
    }
    private LinearLayout section(LinearLayout parent, String name) {
        TextView h = new TextView(this); h.setText(name); h.setTextSize(15); h.setTypeface(Typeface.DEFAULT_BOLD); h.setTextColor(0xFF4C7894); h.setPadding(px(6), px(10), 0, px(4));
        parent.addView(h);
        return card(parent);
    }
    private void sw(LinearLayout box, String text, String key, boolean def) {
        Switch s = new Switch(this); s.setText(text); s.setTextSize(15); s.setTextColor(0xFF20333D); s.setChecked(prefs.getBoolean(key, def)); s.setPadding(0, px(6), 0, px(6));
        s.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            if ("notify".equals(key) && checked && Build.VERSION.SDK_INT >= 33) requestPermissions(new String[] { "android.permission.POST_NOTIFICATIONS" }, 1);
        });
        box.addView(s, new LinearLayout.LayoutParams(-1, -2));
    }
    private void button(LinearLayout box, String text, View.OnClickListener l) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setOnClickListener(l);
        box.addView(b, new LinearLayout.LayoutParams(-1, -2));
    }
    private void label(LinearLayout box, String text) {
        TextView l = new TextView(this); l.setText(text); l.setTextSize(14); l.setTextColor(0xFF20333D); l.setPadding(0, px(8), 0, 0); box.addView(l);
    }
    private void slider(LinearLayout box, String text, String key, int max, int def) {
        label(box, text);
        SeekBar sb = new SeekBar(this); sb.setMax(max); sb.setProgress(prefs.getInt(key, def)); sb.setContentDescription(text);
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b, int p, boolean f) { prefs.edit().putInt(key, p).apply(); }
            public void onStartTrackingTouch(SeekBar b) { }
            public void onStopTrackingTouch(SeekBar b) { }
        });
        box.addView(sb);
    }
    private void picker(LinearLayout box, String text, String[] names, String key) {
        label(box, text);
        Spinner sp = new Spinner(this); sp.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names));
        sp.setSelection(Math.max(0, Math.min(names.length - 1, prefs.getInt(key, 0))));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { prefs.edit().putInt(key, pos).apply(); }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        box.addView(sp, new LinearLayout.LayoutParams(-1, -2));
    }
    private void scenePicker(LinearLayout box) {
        label(box, "部屋のシーン");
        final String[] names = { "自動（時間帯にあわせる）", "ふつう", "あさ", "はれ", "くもり", "あめ", "ゆうやけ", "よる", "ほしぞら", "ゆき", "ぽかぽかの夜" };
        final int[] ids = { -1, Room.NORMAL_ROOM, Room.MORNING, Room.SUNNY, Room.CLOUDY, Room.RAIN, Room.SUNSET, Room.NIGHT, Room.STARRY_NIGHT, Room.SNOW, Room.COZY_NIGHT };
        Spinner sp = new Spinner(this); sp.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names));
        int saved = prefs.getInt("scene", -1), pos = 0; for (int i = 0; i < ids.length; i++) if (ids[i] == saved) pos = i;
        sp.setSelection(pos);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) { prefs.edit().putInt("scene", ids[position]).apply(); }
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        box.addView(sp, new LinearLayout.LayoutParams(-1, -2));
    }
    /** Birthday (month / day): on that day a cake appears and the two hold a little party. */
    private void birthday(LinearLayout box) {
        label(box, "おたんじょうび（その日はケーキとパーティー）");
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        final String[] ms = new String[13]; ms[0] = "なし"; for (int i = 1; i <= 12; i++) ms[i] = i + "月";
        final String[] ds = new String[31]; for (int i = 1; i <= 31; i++) ds[i - 1] = i + "日";
        final Spinner m = new Spinner(this); m.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, ms));
        final Spinner d = new Spinner(this); d.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, ds));
        m.setSelection(Math.max(0, prefs.getInt("bday_m", -1))); d.setSelection(Math.max(0, prefs.getInt("bday_d", 1) - 1));
        m.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { prefs.edit().putInt("bday_m", pos == 0 ? -1 : pos).apply(); }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        d.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) { prefs.edit().putInt("bday_d", pos + 1).apply(); }
            public void onNothingSelected(AdapterView<?> p) { }
        });
        row.addView(m, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(d, new LinearLayout.LayoutParams(0, -2, 1));
        box.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    /** The penguin's state right now (read only: the wallpaper owns and saves the values). */
    private void showStatus() {
        if (statusBox == null) return;
        statusBox.removeAllViews();
        TextView head = new TextView(this); head.setText("ペンギンのようす"); head.setTextSize(16); head.setTypeface(Typeface.DEFAULT_BOLD); head.setTextColor(0xFF20333D);
        statusBox.addView(head);
        if (prefs.getLong("care_first", 0L) == 0L) { label(statusBox, "壁紙をはじめると表示されます"); return; }
        final long now = System.currentTimeMillis();
        final Care c = new Care(prefs, prefs.getLong("care_last", now));
        bar("おなか", c.pFull, 0xFFF29B4A);
        bar("のど", c.pHyd, 0xFF5AA9E6);
        bar("ごきげん", c.pMood, 0xFFF06A9A);
        bar("なつき度  Lv." + c.bondLevel(), c.bond / 100f, 0xFF8C6FD1);
        bar("ジンベエとのなかよし", (c.friend + 1f) / 2f, 0xFF4DB6AC);
        final String sv = c.souv;
        label(statusBox, "せいかく: " + Care.PERSONALITY[c.personality(now)] + "　／　いっしょに " + (c.days(now) + 1) + " 日目");
        label(statusBox, "ごはん皿: " + (c.foodEmpty() ? "からっぽ" : "お魚あり") + "　／　お水: " + (c.waterEmpty() ? "からっぽ" : "あり") + "　／　おみやげ: " + sv.length() + "こ");
    }
    private void bar(String name, float v, int col) {
        LinearLayout row = new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL); row.setPadding(0, px(5), 0, 0);
        TextView t = new TextView(this); t.setText(name); t.setTextSize(13); t.setTextColor(0xFF20333D);
        row.addView(t, new LinearLayout.LayoutParams(px(150), -2));
        ProgressBar pb = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); pb.setMax(100); pb.setProgress(Math.round(Math.max(0f, Math.min(1f, v)) * 100f));
        pb.setProgressTintList(ColorStateList.valueOf(col)); pb.setProgressBackgroundTintList(ColorStateList.valueOf(0xFFE6E0D8));
        row.addView(pb, new LinearLayout.LayoutParams(0, px(14), 1));
        statusBox.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void shareLast() {
        final String u = prefs.getString("snap_uri", null);
        if (u == null) { Toast.makeText(this, "まだ写真がありません。「写真をとる」をおしてからホームにもどってください", Toast.LENGTH_LONG).show(); return; }
        try {
            Intent s = new Intent(Intent.ACTION_SEND); s.setType("image/png"); s.putExtra(Intent.EXTRA_STREAM, Uri.parse(u)); s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(s, "写真を共有"));
        } catch (Exception e) { Toast.makeText(this, "共有できませんでした", Toast.LENGTH_SHORT).show(); }
    }
    private void openWallpaperPicker() {
        try { Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER); i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, PenguinWallpaperService.class)); startActivity(i); }
        catch (Exception e) { startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)); }
    }
}
