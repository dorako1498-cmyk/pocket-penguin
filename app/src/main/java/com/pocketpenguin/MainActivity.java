package com.pocketpenguin;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ArrayAdapter;
import android.widget.AdapterView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

public class MainActivity extends Activity {
    private SharedPreferences prefs;
    private TextView status;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("penguin", MODE_PRIVATE);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(28, 24, 28, 20);
        TextView title = new TextView(this); title.setText("Pocket Penguin\n\nA tiny room inside your phone"); title.setTextSize(25); title.setTextColor(0xff20333d); title.setGravity(Gravity.CENTER); root.addView(title, new LinearLayout.LayoutParams(-1, -2));
        ScrollView scroll = new ScrollView(this); LinearLayout options = new LinearLayout(this); options.setOrientation(LinearLayout.VERTICAL); options.setPadding(4, 18, 4, 12);
        status = new TextView(this); status.setTextSize(17); status.setTextColor(0xff20333d); status.setTypeface(android.graphics.Typeface.MONOSPACE); status.setPadding(20, 16, 20, 16); status.setBackgroundColor(0xffe8f2f7);
        options.addView(status, new LinearLayout.LayoutParams(-1, -2));
        addSwitch(options, "Tap reactions", "tap", true); addSwitch(options, "Jinbei (whale shark buddy)", "buddy", true); addSwitch(options, "Chat bubbles (the two talk to each other)", "talk", true); addSwitch(options, "Show every room item now (for trying them out)", "unlockall", false); addSwitch(options, "はやいお世話: 数分でおなかがすく (反応を見る用)", "carefast", true); addSwitch(options, "Look at the clock", "clock", true); addSwitch(options, "Charging reaction", "charging", true); addSwitch(options, "Wake when screen turns on", "screen", true); addSwitch(options, "Screen-edge door", "door", true); addSwitch(options, "Time-of-day room & weather", "time", true); addSwitch(options, "省電力 mode", "power", false); addScenePicker(options);
        TextView label = new TextView(this); label.setText("Animation amount (calm / normal / lively)"); label.setTextSize(16); label.setTextColor(0xff20333d); options.addView(label);
        SeekBar speed = new SeekBar(this); speed.setMax(2); speed.setProgress(prefs.getInt("speed", 1)); speed.setContentDescription("Animation amount"); speed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar b,int p,boolean f){prefs.edit().putInt("speed",p).apply();} public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){} }); options.addView(speed);
        TextView fl = new TextView(this); fl.setText("Floor height (raise it if the dock covers the penguin)"); fl.setTextSize(16); fl.setTextColor(0xff20333d); fl.setPadding(0, 18, 0, 0); options.addView(fl);
        SeekBar floor = new SeekBar(this); floor.setMax(10); floor.setProgress(prefs.getInt("floor", 5)); floor.setContentDescription("Floor height");
        floor.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar b,int p,boolean f){prefs.edit().putInt("floor",p).apply();} public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){} });
        options.addView(floor);
        TextView bs = new TextView(this); bs.setText("Jinbei size (the whale shark)"); bs.setTextSize(16); bs.setTextColor(0xff20333d); bs.setPadding(0, 18, 0, 0); options.addView(bs);
        SeekBar bsz = new SeekBar(this); bsz.setMax(4); bsz.setProgress(prefs.getInt("buddysize", 2)); bsz.setContentDescription("Jinbei size");
        bsz.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){ public void onProgressChanged(SeekBar b,int p,boolean f){prefs.edit().putInt("buddysize",p).apply();} public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){} });
        options.addView(bsz);
        Button reset = new Button(this); reset.setText("Reset care and room items"); reset.setOnClickListener(v -> { SharedPreferences.Editor e = prefs.edit(); for (String k : new String[] { "care_first", "care_last", "care_pf", "care_ph", "care_bf", "care_bh", "care_food", "care_water", "care_feeds", "care_strokes", "care_fed", "care_pm", "care_play", "decor_seen" }) e.remove(k); e.apply(); showStatus(); });
        options.addView(reset, new LinearLayout.LayoutParams(-2, -2));
        TextView tip = new TextView(this); tip.setText("Tip: tap the head once to greet, tap it again and again to stroke it. Tap the belly or tap fast to surprise it. Jinbei the whale shark naps a lot: tap or stroke it to wake it up. Tap the food or water bowl to refill it (one serving per meal; a full penguin says no thanks). When it gets bored it waits by the ball: tap the ball to play. New room items appear as the days go by."); tip.setTextSize(13); tip.setPadding(0, 14, 0, 0); options.addView(tip);
        scroll.addView(options); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        Button launch = new Button(this); launch.setText("Set live wallpaper"); launch.setOnClickListener(v -> openWallpaperPicker()); root.addView(launch, new LinearLayout.LayoutParams(-1, -2));
        TextView note = new TextView(this); note.setText("An original little penguin lives in this room. It pauses when the wallpaper is hidden."); note.setTextSize(12); note.setPadding(8, 10, 8, 0); root.addView(note);
        setContentView(root);
    }
    @Override protected void onResume() { super.onResume(); showStatus(); }

    /** The penguin's hunger / thirst / mood right now (read only: the wallpaper owns and saves the values). */
    private void showStatus() {
        if (status == null) return;
        if (prefs.getLong("care_first", 0L) == 0L) { status.setText("ペンギンのようす\n(壁紙をはじめると表示されます)"); return; }
        final Care c = new Care(prefs, prefs.getLong("care_last", System.currentTimeMillis()));
        status.setText("ペンギンのようす\n"
            + "おなか   " + bar(c.pFull) + "\n"
            + "のど     " + bar(c.pHyd) + "\n"
            + "ごきげん " + bar(c.pMood) + "\n"
            + "ごはん皿 " + (c.foodEmpty() ? "からっぽ" : "あり") + " / お水 " + (c.waterEmpty() ? "からっぽ" : "あり") + "\n"
            + "いっしょに " + (c.days(System.currentTimeMillis()) + 1) + " 日目");
    }
    private static String bar(float v) {
        final int n = Math.round(Math.max(0f, Math.min(1f, v)) * 10f);
        final StringBuilder b = new StringBuilder();
        for (int i = 0; i < 10; i++) b.append(i < n ? '■' : '□');
        return b.toString();
    }

    private void addScenePicker(LinearLayout box) {
        TextView l = new TextView(this); l.setText("Room scene"); l.setTextSize(16); l.setTextColor(0xff20333d); l.setPadding(0, 18, 0, 0); box.addView(l);
        final String[] names = { "Auto (follows the time of day)", "Normal room", "Morning", "Sunny", "Cloudy", "Rain", "Sunset", "Night", "Starry night", "Snow", "Cozy night" };
        // Spinner position -> scene id used by Room (see Room.SCENE_NAMES)
        final int[] ids = { -1, Room.NORMAL_ROOM, Room.MORNING, Room.SUNNY, Room.CLOUDY, Room.RAIN, Room.SUNSET, Room.NIGHT, Room.STARRY_NIGHT, Room.SNOW, Room.COZY_NIGHT };
        Spinner sp = new Spinner(this); sp.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, names));
        int saved = prefs.getInt("scene", -1), pos = 0; for (int i = 0; i < ids.length; i++) if (ids[i] == saved) pos = i;
        sp.setSelection(pos);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> parent, View v, int position, long id) { prefs.edit().putInt("scene", ids[position]).apply(); }
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        box.addView(sp, new LinearLayout.LayoutParams(-1, -2));
    }
    private void addSwitch(LinearLayout box, String text, String key, boolean def) { Switch s = new Switch(this); s.setText(text); s.setTextSize(16); s.setChecked(prefs.getBoolean(key, def)); s.setOnCheckedChangeListener((b, checked) -> prefs.edit().putBoolean(key, checked).apply()); box.addView(s, new LinearLayout.LayoutParams(-1, -2)); }
    private void openWallpaperPicker() { try { Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER); i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, new ComponentName(this, PenguinWallpaperService.class)); startActivity(i); } catch (Exception e) { startActivity(new Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER)); } }
}
