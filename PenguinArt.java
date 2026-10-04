package com.pocketpenguin;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** Pre-loaded part bitmaps. Loaded once per engine, reused every frame (no per-frame Bitmap creation). */
final class PenguinArt {
    final Bitmap body, head, beak, wingL, wingR, footL, footR, tail;

    PenguinArt(Resources r) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        body = BitmapFactory.decodeResource(r, R.drawable.pg_body, o);
        head = BitmapFactory.decodeResource(r, R.drawable.pg_head, o);
        beak = BitmapFactory.decodeResource(r, R.drawable.pg_beak, o);
        wingL = BitmapFactory.decodeResource(r, R.drawable.pg_wing_l, o);
        wingR = BitmapFactory.decodeResource(r, R.drawable.pg_wing_r, o);
        footL = BitmapFactory.decodeResource(r, R.drawable.pg_foot_l, o);
        footR = BitmapFactory.decodeResource(r, R.drawable.pg_foot_r, o);
        tail = BitmapFactory.decodeResource(r, R.drawable.pg_tail, o);
    }

    void recycle() {
        Bitmap[] all = { body, head, beak, wingL, wingR, footL, footR, tail };
        for (Bitmap b : all) if (b != null) b.recycle();
    }
}
