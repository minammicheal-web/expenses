package com.mina.expenses;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Checks a few times a day whether the credit card bill is due soon, and shows one notification per day. */
public class ReminderWorker extends Worker {
    static final String CHANNEL = "bill-due";

    public ReminderWorker(@NonNull Context c, @NonNull WorkerParameters p) { super(c, p); }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("reminder", Context.MODE_PRIVATE); }

    static void schedule(Context c) {
        PeriodicWorkRequest w = new PeriodicWorkRequest.Builder(ReminderWorker.class, 3, TimeUnit.HOURS).build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("bill-reminder", ExistingPeriodicWorkPolicy.UPDATE, w);
    }

    @NonNull
    @Override
    public Result doWork() {
        try { check(getApplicationContext()); } catch (Exception ignored) { }
        try { notes(getApplicationContext()); } catch (Exception ignored) { }
        return Result.success();
    }

    static void check(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        String due = p.getString("due", "");
        if (due.isEmpty()) return;
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String today = f.format(new Date());
        if (today.equals(p.getString("shown", ""))) return;
        Calendar a = Calendar.getInstance();
        a.setTime(f.parse(today));
        Calendar b = Calendar.getInstance();
        b.setTime(f.parse(due));
        long days = Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / 86400000.0);
        int before = p.getInt("days", 3);
        if (days > before) return;
        String amount = p.getString("amount", "");
        String text;
        if (days > 1) text = "EGP " + amount + " is due in " + days + " days.";
        else if (days == 1) text = "EGP " + amount + " is due tomorrow.";
        else if (days == 0) text = "EGP " + amount + " is due today.";
        else text = "EGP " + amount + " was due " + (-days) + " day" + (days == -1 ? "" : "s") + " ago.";
        notify(c, 1, CHANNEL, "Credit card bill", "Credit card bill", text);
        p.edit().putString("shown", today).apply();
    }

    /** Reminders the page plans ahead (recurring entries, home bills, monthly summary): [{id, date, title, text}].
     *  Each one is shown once, on or after its date (up to 5 days late). */
    static void notes(Context c) throws Exception {
        SharedPreferences p = prefs(c);
        org.json.JSONArray a = new org.json.JSONArray(p.getString("notes", "[]"));
        org.json.JSONArray shown = new org.json.JSONArray(p.getString("notesShown", "[]"));
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < shown.length(); i++) seen.add(shown.getString(i));
        SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String today = f.format(new Date());
        String late = f.format(new Date(System.currentTimeMillis() - 5L * 86400000L));
        boolean changed = false;
        int n = 0;
        for (int i = 0; i < a.length(); i++) {
            org.json.JSONObject o = a.getJSONObject(i);
            String id = o.optString("id"), date = o.optString("date");
            if (id.isEmpty() || seen.contains(id) || date.compareTo(today) > 0) continue;
            seen.add(id);
            shown.put(id);
            changed = true;
            if (date.compareTo(late) < 0) continue;
            notify(c, 100 + (Math.abs(id.hashCode()) % 800), o.optString("ch", "notes"), o.optString("chName", "Reminders"),
                    o.optString("title"), o.optString("text"));
            if (++n >= 6) break;
        }
        if (changed) {
            org.json.JSONArray keep = new org.json.JSONArray();
            for (int i = Math.max(0, shown.length() - 400); i < shown.length(); i++) keep.put(shown.get(i));
            p.edit().putString("notesShown", keep.toString()).apply();
        }
    }

    static void notify(Context c, int id, String channel, String channelName, String title, String text) {
        NotificationManager m = (NotificationManager) c.getSystemService(Context.NOTIFICATION_SERVICE);
        if (m == null) return;
        Notification.Builder n;
        if (Build.VERSION.SDK_INT >= 26) {
            m.createNotificationChannel(new NotificationChannel(channel, channelName, NotificationManager.IMPORTANCE_DEFAULT));
            n = new Notification.Builder(c, channel);
        } else {
            n = new Notification.Builder(c);
        }
        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        n.setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true);
        try { m.notify(id, n.build()); } catch (SecurityException ignored) { }
    }
}
