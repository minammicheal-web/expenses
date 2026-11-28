package com.mina.expenses;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.RemoteViews;
import org.json.JSONObject;

/** Home-screen widget: this month's spending and what is left per day. The page sends the numbers. */
public class ExpWidget extends AppWidgetProvider {

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("widget", Context.MODE_PRIVATE); }

    static void save(Context c, String json) {
        prefs(c).edit().putString("data", json).apply();
        refresh(c);
    }

    static void refresh(Context c) {
        AppWidgetManager m = AppWidgetManager.getInstance(c);
        int[] ids = m.getAppWidgetIds(new ComponentName(c, ExpWidget.class));
        if (ids != null && ids.length > 0) for (int id : ids) draw(c, m, id);
    }

    static void draw(Context c, AppWidgetManager m, int id) {
        RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget);
        try {
            JSONObject o = new JSONObject(prefs(c).getString("data", "{}"));
            if (o.has("spent")) {
                v.setTextViewText(R.id.w_title, o.optString("title", "Spent this month"));
                v.setTextViewText(R.id.w_spent, o.optString("spent"));
                v.setTextViewText(R.id.w_left, o.optString("left"));
                v.setTextViewText(R.id.w_note, o.optString("note"));
            }
        } catch (Exception ignored) { }
        Intent open = new Intent(c, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(c, 900, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
        m.updateAppWidget(id, v);
    }

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        for (int id : ids) draw(c, m, id);
    }
}
