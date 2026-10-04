package com.pocketpenguin;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

/** Pre-loaded part bitmaps of the whale-shark buddy. Loaded once per engine, reused every frame. */
final class BuddyArt {
    final Bitmap body, fin, tail;

    BuddyArt(Resources r) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false; o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        body = BitmapFactory.decodeResource(r, R.drawable.bd_body, o);
        fin = BitmapFactory.decodeResource(r, R.drawable.bd_fin, o);
        tail = BitmapFactory.decodeResource(r, R.drawable.bd_tail, o);
    }

    void recycle() { for (Bitmap b : new Bitmap[] { body, fin, tail }) if (b != null) b.recycle(); }
}
