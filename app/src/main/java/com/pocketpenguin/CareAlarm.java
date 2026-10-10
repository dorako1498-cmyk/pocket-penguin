package com.pocketpenguin;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;

/**
 * Every ~30 minutes: if the wallpaper has not been looked at for a few hours and the penguin is hungry,
 * post one gentle notification (at most every 6 hours). Setting "notify" turns it off.
 */
public class CareAlarm extends BroadcastReceiver {
    private static final String CH = "care";

    static void schedule(Context ctx) {
        try {
            final AlarmManager am = (AlarmManager) ctx.getSystemService(Context.ALARM_SERVICE);
            if (am == null) return;
            final PendingIntent pi = PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, CareAlarm.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HALF_HOUR, AlarmManager.INTERVAL_HALF_HOUR, pi);
        } catch (Exception ignored) { }
    }

    @Override public void onReceive(Context ctx, Intent intent) {
        final SharedPreferences prefs = ctx.getSharedPreferences("penguin", Context.MODE_PRIVATE);
        if (!prefs.getBoolean("notify", true) || prefs.getLong("care_first", 0L) == 0L) return;
        final long now = System.currentTimeMillis(), seen = prefs.getLong("care_last", now);
        if (now - seen < 3L * 3600000L || now - prefs.getLong("notify_last", 0L) < 6L * 3600000L) return;
        final Care c = new Care(prefs, now);           // read only: advances in memory, the wallpaper saves the real values
        final boolean hungry = c.pFull < .35f, thirsty = c.pHyd < .3f, lonely = c.pMood < .3f;
        if (!hungry && !thirsty && !lonely) return;
        final String text = hungry ? "おなかすいたよ〜。お魚ちょうだい！" : thirsty ? "のどがかわいたよ〜。お水ちょうだい！" : "さみしいよ〜。あそんで！";
        try {
            final NotificationManager nm = (NotificationManager) ctx.getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm == null) return;
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(new NotificationChannel(CH, "ペンギンのおしらせ", NotificationManager.IMPORTANCE_DEFAULT));
            final PendingIntent open = PendingIntent.getActivity(ctx, 1, new Intent(ctx, MainActivity.class), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            final Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(ctx, CH) : new Notification.Builder(ctx);
            b.setSmallIcon(R.drawable.ic_notify).setContentTitle("Pocket Penguin").setContentText(text).setContentIntent(open).setAutoCancel(true);
            nm.notify(7, b.build());
            prefs.edit().putLong("notify_last", now).apply();
        } catch (Exception ignored) { }   // no permission (Android 13+ asks in the app)
    }
}
