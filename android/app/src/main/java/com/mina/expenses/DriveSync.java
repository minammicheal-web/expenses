package com.mina.expenses;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;
import com.google.android.gms.tasks.Tasks;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Google Drive backup: a "Expenses Backups" folder in the user's own Drive, newest 30 kept. */
final class DriveSync {
    static final String SCOPE = "https://www.googleapis.com/auth/drive.file";
    static final String FOLDER = "Expenses Backups";
    static final String API = "https://www.googleapis.com/drive/v3/files";
    static final int KEEP = 30;

    private DriveSync() { }

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("drive", Context.MODE_PRIVATE); }

    static File snapshot(Context c) { return new File(c.getFilesDir(), "latest-backup.json"); }

    static AuthorizationRequest request() {
        return AuthorizationRequest.builder()
                .setRequestedScopes(Collections.singletonList(new Scope(SCOPE)))
                .build();
    }

    /** Background thread only. Returns null when the user has to sign in again. */
    static String tokenSilently(Context c) throws Exception {
        AuthorizationResult r = Tasks.await(Identity.getAuthorizationClient(c).authorize(request()), 60, TimeUnit.SECONDS);
        if (r.hasResolution()) return null;
        return r.getAccessToken();
    }

    static void saveSnapshot(Context c, String json) throws IOException {
        File tmp = new File(c.getFilesDir(), "latest-backup.tmp");
        try (OutputStream o = new FileOutputStream(tmp)) { o.write(json.getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(snapshot(c))) throw new IOException("Could not save backup copy");
    }

    static byte[] readFile(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) { return readAll(in); }
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static String http(String method, String url, String token, String type, byte[] body) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setRequestMethod(method);
        h.setConnectTimeout(20000);
        h.setReadTimeout(60000);
        h.setRequestProperty("Authorization", "Bearer " + token);
        if (body != null) {
            h.setDoOutput(true);
            h.setRequestProperty("Content-Type", type);
            try (OutputStream o = h.getOutputStream()) { o.write(body); }
        }
        int code = h.getResponseCode();
        InputStream in = code < 400 ? h.getInputStream() : h.getErrorStream();
        String text = in == null ? "" : new String(readAll(in), StandardCharsets.UTF_8);
        h.disconnect();
        if (code >= 400) throw new IOException("Google Drive error " + code + ": " + text);
        return text;
    }

    static String q(String s) throws IOException { return URLEncoder.encode(s, "UTF-8"); }

    static String email(String token) throws Exception {
        JSONObject r = new JSONObject(http("GET", "https://www.googleapis.com/drive/v3/about?fields=user(emailAddress)", token, null, null));
        return r.getJSONObject("user").optString("emailAddress", "");
    }

    static String folderId(String token) throws Exception { return folderId(token, FOLDER); }

    static String folderId(String token, String name) throws Exception {
        String FOLDER = name;
        String query = "name='" + FOLDER + "' and mimeType='application/vnd.google-apps.folder' and trashed=false";
        JSONArray f = new JSONObject(http("GET", API + "?spaces=drive&fields=files(id)&q=" + q(query), token, null, null)).getJSONArray("files");
        if (f.length() > 0) return f.getJSONObject(0).getString("id");
        JSONObject meta = new JSONObject().put("name", FOLDER).put("mimeType", "application/vnd.google-apps.folder");
        return new JSONObject(http("POST", API + "?fields=id", token, "application/json; charset=UTF-8",
                meta.toString().getBytes(StandardCharsets.UTF_8))).getString("id");
    }

    static JSONArray listBackups(String token, String folder, int size) throws Exception {
        String query = "'" + folder + "' in parents and trashed=false";
        return new JSONObject(http("GET", API + "?spaces=drive&orderBy=createdTime%20desc&pageSize=" + size
                + "&fields=files(id,name,createdTime)&q=" + q(query), token, null, null)).getJSONArray("files");
    }

    static void upload(Context c, String token) throws Exception {
        File s = snapshot(c);
        if (!s.exists()) throw new IOException("Nothing to back up yet — add an entry first.");
        byte[] data = readFile(s);
        String folder = folderId(token);
        String name = "expenses-backup-" + new SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.US).format(new Date()) + ".json";
        String b = "expenses" + System.currentTimeMillis();
        JSONObject meta = new JSONObject().put("name", name).put("parents", new JSONArray().put(folder));
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + b + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta
                + "\r\n--" + b + "\r\nContent-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(data);
        body.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
        http("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", token,
                "multipart/related; boundary=" + b, body.toByteArray());
        JSONArray all = listBackups(token, folder, 100);
        for (int i = KEEP; i < all.length(); i++) {
            try { http("DELETE", API + "/" + all.getJSONObject(i).getString("id"), token, null, null); } catch (IOException ignored) { }
        }
        prefs(c).edit().putLong("last", System.currentTimeMillis()).putString("error", "").apply();
        try { uploadInvoices(c, token); }
        catch (Exception e) { prefs(c).edit().putString("error", "Backup saved, but some invoices were not uploaded: " + e.getMessage()).apply(); }
    }

    /* ---- invoice photos / PDFs: one Drive file per invoice, in its own folder, uploaded once ---- */
    static final String INV_FOLDER = "Expenses Invoices";

    static File invDir(Context c) { return new File(c.getFilesDir(), "invoices"); }

    static void uploadInvoices(Context c, String token) throws Exception {
        if (!prefs(c).getBoolean("inv", false)) return;
        File[] fs = invDir(c).listFiles();
        if (fs == null || fs.length == 0) return;
        String folder = folderId(token, INV_FOLDER);
        java.util.HashSet<String> there = new java.util.HashSet<>();
        String page = null;
        do {
            String query = "'" + folder + "' in parents and trashed=false";
            JSONObject r = new JSONObject(http("GET", API + "?spaces=drive&pageSize=1000&fields=nextPageToken,files(name)&q=" + q(query)
                    + (page != null ? "&pageToken=" + q(page) : ""), token, null, null));
            JSONArray a = r.getJSONArray("files");
            for (int i = 0; i < a.length(); i++) there.add(a.getJSONObject(i).getString("name"));
            page = r.optString("nextPageToken", null);
            if (page != null && page.isEmpty()) page = null;
        } while (page != null);
        int n = 0;
        for (File f : fs) {
            if (there.contains(f.getName())) continue;
            String b = "inv" + System.currentTimeMillis();
            JSONObject meta = new JSONObject().put("name", f.getName()).put("parents", new JSONArray().put(folder));
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(("--" + b + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta
                    + "\r\n--" + b + "\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(readFile(f));
            body.write(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));
            http("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id", token,
                    "multipart/related; boundary=" + b, body.toByteArray());
            if (++n >= 60) break;
        }
    }

    static byte[] download(String url, String token) throws IOException {
        HttpURLConnection h = (HttpURLConnection) new URL(url).openConnection();
        h.setConnectTimeout(20000);
        h.setReadTimeout(60000);
        h.setRequestProperty("Authorization", "Bearer " + token);
        int code = h.getResponseCode();
        if (code >= 400) { h.disconnect(); throw new IOException("Google Drive error " + code); }
        byte[] b = readAll(h.getInputStream());
        h.disconnect();
        return b;
    }

    /** After a restore: bring back the invoice files that are not on this phone. Returns how many. */
    static int restoreInvoices(Context c, String token) {
        int n = 0;
        try {
            String query = "name='" + INV_FOLDER + "' and mimeType='application/vnd.google-apps.folder' and trashed=false";
            JSONArray f = new JSONObject(http("GET", API + "?spaces=drive&fields=files(id)&q=" + q(query), token, null, null)).getJSONArray("files");
            if (f.length() == 0) return 0;
            String folder = f.getJSONObject(0).getString("id");
            File dir = invDir(c);
            dir.mkdirs();
            String page = null;
            do {
                String qq = "'" + folder + "' in parents and trashed=false";
                JSONObject r = new JSONObject(http("GET", API + "?spaces=drive&pageSize=1000&fields=nextPageToken,files(id,name)&q=" + q(qq)
                        + (page != null ? "&pageToken=" + q(page) : ""), token, null, null));
                JSONArray a = r.getJSONArray("files");
                for (int i = 0; i < a.length(); i++) {
                    String name = a.getJSONObject(i).getString("name");
                    if (!name.matches("[A-Za-z0-9_-]{1,64}")) continue;
                    File out = new File(dir, name);
                    if (out.exists()) continue;
                    byte[] data = download(API + "/" + a.getJSONObject(i).getString("id") + "?alt=media", token);
                    try (FileOutputStream o = new FileOutputStream(out)) { o.write(data); }
                    n++;
                }
                page = r.optString("nextPageToken", null);
                if (page != null && page.isEmpty()) page = null;
            } while (page != null);
        } catch (Exception ignored) { }
        return n;
    }

    static String latest(String token) throws Exception {
        JSONArray f = listBackups(token, folderId(token), 1);
        if (f.length() == 0) throw new IOException("No backups found in your Google Drive yet.");
        return http("GET", API + "/" + f.getJSONObject(0).getString("id") + "?alt=media", token, null, null);
    }

    static void schedule(Context c) {
        PeriodicWorkRequest w = new PeriodicWorkRequest.Builder(BackupWorker.class, 24, TimeUnit.HOURS)
                .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build();
        WorkManager.getInstance(c).enqueueUniquePeriodicWork("daily-drive-backup", ExistingPeriodicWorkPolicy.KEEP, w);
    }

    static void unschedule(Context c) { WorkManager.getInstance(c).cancelUniqueWork("daily-drive-backup"); }
}
