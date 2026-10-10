package com.pocketpenguin;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Optional (the user grants it in the system settings): only remembers WHEN another app posted a notification,
 * so the penguin can turn round. Nothing about the notification itself is read or kept.
 */
public class NotifWatch extends NotificationListenerService {
    static volatile long lastPosted;

    @Override public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null || getPackageName().equals(sbn.getPackageName()) || sbn.isOngoing()) return;
        lastPosted = System.currentTimeMillis();
    }
}
