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
 * Reads the notifications that the user's bank / wallet apps show (Telda, CIB, NBE, Vodafone Cash…:
 * card purchases, money in / out) and keeps them in the same queue as bank SMS, so the page records
 * them the same way. Only the apps the page lists are read; other apps' notifications are never stored.
 */
public class TeldaListener extends NotificationListenerService {

    static boolean on(Context c) { return SmsReceiver.prefs(c).getBoolean("notif", false); }

    /** The id of the bank / wallet whose app posted this (from the page's list), or null. Telda when no list yet. */
    static String bankOf(Context c, String pkg) {
        if (pkg == null || pkg.equals(c.getPackageName())) return null;
        String p = pkg.toLowerCase(Locale.ROOT), label = "";
        try {
            PackageManager pm = c.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence l = pm.getApplicationLabel(ai);
            if (l != null) label = l.toString().toLowerCase(Locale.ROOT);
        } catch (Exception ignored) { }
        try {
            org.json.JSONArray apps = new org.json.JSONArray(SmsReceiver.prefs(c).getString("apps", "[]"));
            if (apps.length() == 0) apps.put(new org.json.JSONObject().put("id", "telda").put("kw", new org.json.JSONArray().put("telda")));
            for (int i = 0; i < apps.length(); i++) {
                org.json.JSONObject o = apps.getJSONObject(i);
                org.json.JSONArray kw = o.optJSONArray("kw");
                if (kw == null) continue;
                for (int k = 0; k < kw.length(); k++) {
                    String w = kw.getString(k).toLowerCase(Locale.ROOT);
                    if (w.length() < 3) continue;
                    if (hit(p, label, w)) return o.optString("id");
                }
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Long words may appear anywhere; short ones (cib, nbe, fab…) must be a whole word of the app name or a whole part of the package. */
    static boolean hit(String pkg, String label, String w) {
        if (w.length() > 4) return pkg.contains(w.replace(" ", "")) || label.contains(w);
        for (String part : pkg.split("[._]")) if (part.equals(w) || part.startsWith(w + "bank") || part.startsWith(w + "mobile") || part.startsWith(w + "app")) return true;
        for (String word : label.split("[^a-z0-9\u0600-\u06ff&]+")) if (word.equals(w)) return true;
        return false;
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
        if (s == null || !on(c)) return false;
        String bank = bankOf(c, s.getPackageName());
        if (bank == null || bank.isEmpty()) return false;
        Notification n = s.getNotification();
        if (n == null) return false;
        if ((n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return false;
        if ((n.flags & Notification.FLAG_ONGOING_EVENT) != 0) return false;
        String body = text(n).trim();
        if (body.isEmpty() || !body.matches("(?s).*[0-9٠-٩].*")) return false;
        SmsReceiver.add(c, "app:" + bank, body, s.getPostTime());
        if (alert) ReminderWorker.notify(c, 2, "bank-sms", "Bank messages", "Payment recorded from a bank app",
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
