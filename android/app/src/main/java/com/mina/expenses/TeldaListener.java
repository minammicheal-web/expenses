package com.mina.expenses;

import android.app.Notification;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import java.util.Locale;

/**
 * Reads the notifications that the Telda app shows (card purchases, money in / out) and keeps them
 * in the same queue as bank SMS, so the page records them the same way. Other apps' notifications
 * are ignored and never stored.
 */
public class TeldaListener extends NotificationListenerService {

    static boolean on(Context c) { return SmsReceiver.prefs(c).getBoolean("notif", false); }

    static boolean isTelda(Context c, String pkg) {
        if (pkg == null) return false;
        if (pkg.toLowerCase(Locale.ROOT).contains("telda")) return true;
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            return l != null && l.toString().toLowerCase(Locale.ROOT).contains("telda");
        } catch (Exception e) {
            return false;
        }
    }

    static String text(Notification n) {
        Bundle e = n.extras;
        if (e == null) return "";
        CharSequence t = e.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence big = e.getCharSequence(Notification.EXTRA_BIG_TEXT);
        CharSequence x = e.getCharSequence(Notification.EXTRA_TEXT);
        String body = big != null && big.length() > 0 ? big.toString() : x != null ? x.toString() : "";
        String title = t != null ? t.toString() : "";
        if (title.isEmpty()) return body;
        if (body.isEmpty()) return title;
        return title + "\n" + body;
    }

    static boolean handle(Context c, StatusBarNotification s, boolean alert) {
        if (s == null || !on(c) || !isTelda(c, s.getPackageName())) return false;
        Notification n = s.getNotification();
        if (n == null) return false;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return false;
        String body = text(n).trim();
        if (body.isEmpty() || !body.matches("(?s).*[0-9٠-٩].*")) return false;
        SmsReceiver.add(c, "Telda app", body, s.getPostTime());
        if (alert) ReminderWorker.notify(c, 2, "bank-sms", "Bank messages", "Telda payment recorded",
                "Open Expenses to see it and pick a category.");
        return true;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification s) {
        try { handle(this, s, true); } catch (Exception ignored) { }
    }

    /** When access is (re)granted, pick up the Telda notifications still in the shade. */
    @Override
    public void onListenerConnected() {
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification s : all) handle(this, s, false);
        } catch (Exception ignored) { }
    }
}
