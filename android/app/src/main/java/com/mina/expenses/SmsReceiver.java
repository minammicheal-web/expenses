package com.mina.expenses;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Keeps bank messages that mention one of the user's card/account numbers (or InstaPay) until the app reads them. */
public class SmsReceiver extends BroadcastReceiver {

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("sms", Context.MODE_PRIVATE); }

    @Override
    public void onReceive(Context c, Intent i) {
        if (!Telephony.Sms.Intents.SMS_RECEIVED_ACTION.equals(i.getAction())) return;
        if (!prefs(c).getBoolean("on", false)) return;
        SmsMessage[] msgs = Telephony.Sms.Intents.getMessagesFromIntent(i);
        if (msgs == null) return;
        Map<String, StringBuilder> bodies = new LinkedHashMap<>();
        for (SmsMessage m : msgs) {
            if (m == null) continue;
            String from = m.getDisplayOriginatingAddress();
            if (from == null) from = "";
            StringBuilder sb = bodies.get(from);
            if (sb == null) { sb = new StringBuilder(); bodies.put(from, sb); }
            String part = m.getDisplayMessageBody();
            if (part != null) sb.append(part);
        }
        long ts = System.currentTimeMillis();
        boolean any = false;
        for (Map.Entry<String, StringBuilder> e : bodies.entrySet()) {
            String body = e.getValue().toString();
            if (!matches(c, e.getKey(), body)) continue;
            add(c, e.getKey(), body, ts);
            any = true;
        }
        if (any) ReminderWorker.notify(c, 2, "bank-sms", "Bank messages", "Bank SMS recorded",
                "Open Expenses to see it and pick a category.");
    }

    static String norm(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch >= '٠' && ch <= '٩') b.append((char) ('0' + (ch - '٠')));
            else if (ch >= '۰' && ch <= '۹') b.append((char) ('0' + (ch - '۰')));
            else b.append(ch);
        }
        return b.toString().toLowerCase(Locale.ROOT);
    }

    /** A bank's own sender name (CIB, NBE, BanqueMisr…), as the page chose them. */
    static boolean fromBank(Context c, String from) {
        if (from == null) return false;
        String f = norm(from).replaceAll("[^a-z0-9\u0600-\u06ff]", "");
        if (f.isEmpty()) return false;
        try {
            JSONArray w = new JSONArray(prefs(c).getString("senders", "[]"));
            for (int k = 0; k < w.length(); k++) {
                String x = w.getString(k);
                if (x.length() == 0) continue;
                if (x.length() <= 4 ? f.equals(x) : f.startsWith(x)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    static boolean matches(Context c, String from, String body) {
        if (body == null) return false;
        if (fromBank(c, from) && body.matches("(?s).*[0-9\u0660-\u0669].*")) return true;
        return matches(c, body);
    }

    static boolean matches(Context c, String body) {
        if (body == null) return false;
        String b = norm(body);
        try {
            JSONArray w = new JSONArray(prefs(c).getString("words", "[]"));
            for (int k = 0; k < w.length(); k++) {
                String x = w.getString(k);
                if (x.length() > 0 && b.contains(x)) return true;
            }
        } catch (Exception ignored) { }
        return false;
    }

    static synchronized void add(Context c, String from, String body, long ts) {
        try {
            SharedPreferences p = prefs(c);
            JSONArray q = new JSONArray(p.getString("queue", "[]"));
            q.put(new JSONObject().put("from", from).put("body", body).put("ts", ts));
            JSONArray keep = new JSONArray();
            for (int k = Math.max(0, q.length() - 300); k < q.length(); k++) keep.put(q.get(k));
            p.edit().putString("queue", keep.toString()).apply();
        } catch (Exception ignored) { }
    }

    static synchronized String take(Context c) {
        SharedPreferences p = prefs(c);
        String s = p.getString("queue", "[]");
        p.edit().putString("queue", "[]").apply();
        return s;
    }
}
