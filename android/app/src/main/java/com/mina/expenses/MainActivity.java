package com.mina.expenses;

import android.Manifest;
import android.app.Activity;
import android.app.KeyguardManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.hardware.biometrics.BiometricManager;
import android.hardware.biometrics.BiometricPrompt;
import android.os.CancellationSignal;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.provider.Settings;
import android.provider.Telephony;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.ApiException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private static final int PICK = 1;
    private static final int AUTH = 2;
    private static final int CAM = 7;
    private Uri camUri;
    private WebView web;
    private WebView printView;
    private ValueCallback<Uri[]> fileCallback;
    private volatile String pendingAction = "";
    private static final int LOCK = 6;
    private LinearLayout lockView;
    private TextView lockMsg;
    private boolean unlocked = false, authing = false;
    private long leftAt = 0;
    private String authPurpose = "unlock";
    private boolean darkUi = false;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        FrameLayout root = new FrameLayout(this);
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lockView = buildLockView();
        root.addView(lockView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        lockView.setVisibility(lockOn() ? View.VISIBLE : View.GONE);
        setContentView(root);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.addJavascriptInterface(new M3Feedback(this, web), "M3");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                Uri u = r.getUrl();
                if ("file".equals(u.getScheme())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                if (p.isCaptureEnabled()) {
                    try {
                        for (File f : CamProvider.dir(MainActivity.this).listFiles()) f.delete();
                        camUri = Uri.parse("content://" + CamProvider.AUTH + "/invoice-" + System.currentTimeMillis() + ".jpg");
                        Intent c = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                        c.putExtra(MediaStore.EXTRA_OUTPUT, camUri);
                        c.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        c.setClipData(android.content.ClipData.newRawUri("", camUri));
                        startActivityForResult(c, CAM);
                        return true;
                    } catch (Exception e) {
                        camUri = null;
                    }
                }
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, p.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(Intent.createChooser(i, "Choose file"), PICK);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });

        if (DriveSync.prefs(this).getBoolean("on", false)) DriveSync.schedule(this);
        ReminderWorker.schedule(this);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{ Manifest.permission.POST_NOTIFICATIONS }, 3);
        }

        if (state != null) web.restoreState(state);
        else web.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == LOCK) {
            authing = false;
            if (res == RESULT_OK) authOk(); else authFailed("Not unlocked.");
            return;
        }
        if (req == AUTH) {
            try {
                AuthorizationResult r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data);
                afterToken(r.getAccessToken());
            } catch (Exception e) {
                fail(e);
            }
            return;
        }
        if (req == CAM) {
            if (fileCallback == null) return;
            Uri u = camUri;
            camUri = null;
            boolean ok = false;
            try { ok = res == RESULT_OK && u != null && new File(CamProvider.dir(this), u.getLastPathSegment()).length() > 0; } catch (Exception ignored) { }
            fileCallback.onReceiveValue(ok ? new Uri[]{ u } : null);
            fileCallback = null;
            return;
        }
        if (req != PICK || fileCallback == null) return;
        Uri[] out = null;
        if (res == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                out = new Uri[n];
                for (int k = 0; k < n; k++) out[k] = data.getClipData().getItemAt(k).getUri();
            } else if (data.getData() != null) {
                out = new Uri[]{ data.getData() };
            }
        }
        fileCallback.onReceiveValue(out);
        fileCallback = null;
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] res) {
        super.onRequestPermissionsResult(req, perms, res);
        if (req != 4) return;
        try { sendJs("window.onSms&&window.onSms(" + new JSONObject().put("type", "perm").put("granted", smsGranted()).toString() + ")"); } catch (Exception ignored) { }
    }

    /* ---------- fingerprint / screen-lock protection ---------- */

    private SharedPreferences lockPrefs() { return getSharedPreferences("lock", MODE_PRIVATE); }

    private boolean lockOn() { return lockPrefs().getBoolean("on", false); }

    private boolean deviceSecure() {
        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    private LinearLayout buildLockView() {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(Color.rgb(7, 18, 38));
        v.setClickable(true);
        TextView t = new TextView(this);
        t.setText("CheckBook is locked");
        t.setTextColor(Color.WHITE);
        t.setTextSize(24);
        t.setGravity(Gravity.CENTER);
        v.addView(t);
        lockMsg = new TextView(this);
        lockMsg.setText("Use your fingerprint or screen lock to open it.");
        lockMsg.setTextColor(Color.rgb(155, 171, 196));
        lockMsg.setTextSize(15);
        lockMsg.setGravity(Gravity.CENTER);
        lockMsg.setPadding(48, 24, 48, 40);
        v.addView(lockMsg);
        Button b = new Button(this);
        b.setText("Unlock");
        b.setAllCaps(false);
        b.setTextSize(17);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.rgb(4, 18, 43));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.rgb(141, 184, 255));
        bg.setCornerRadius(dp(28));
        b.setBackground(bg);
        b.setStateListAnimator(null);
        b.setPadding(dp(40), 0, dp(40), 0);
        b.setMinWidth(dp(160));
        b.setOnClickListener(x -> auth("unlock"));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(54));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        v.addView(b, lp);
        return v;
    }

    private int dp(float x) { return Math.round(x * getResources().getDisplayMetrics().density); }

    private void auth(String purpose) {
        if (authing) return;
        authPurpose = purpose;
        if (!deviceSecure()) {
            if ("unlock".equals(purpose)) { authOk(); return; }
            authFailed("Set a fingerprint or screen lock in your phone's settings first.");
            return;
        }
        authing = true;
        String title = "unlock".equals(purpose) ? "Unlock CheckBook" : "enable".equals(purpose) ? "Turn on the lock" : "Turn off the lock";
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                BiometricPrompt.Builder b = new BiometricPrompt.Builder(this).setTitle(title)
                        .setSubtitle("Fingerprint, face or your screen lock");
                if (Build.VERSION.SDK_INT >= 30) {
                    b.setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_WEAK | BiometricManager.Authenticators.DEVICE_CREDENTIAL);
                } else if (Build.VERSION.SDK_INT >= 29) {
                    b.setDeviceCredentialAllowed(true);
                } else {
                    b.setNegativeButton("Cancel", getMainExecutor(), (d, w) -> { authing = false; authFailed("Not unlocked."); });
                }
                b.build().authenticate(new CancellationSignal(), getMainExecutor(), new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult r) { authing = false; authOk(); }

                    @Override
                    public void onAuthenticationError(int code, CharSequence msg) { authing = false; authFailed(String.valueOf(msg)); }
                });
            } else {
                KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
                Intent i = km.createConfirmDeviceCredentialIntent(title, "Use your screen lock");
                if (i == null) { authing = false; authOk(); return; }
                startActivityForResult(i, LOCK);
            }
        } catch (Exception e) {
            authing = false;
            authFailed(e.getMessage() == null ? "Could not ask for your fingerprint." : e.getMessage());
        }
    }

    private void authOk() {
        if ("enable".equals(authPurpose)) lockPrefs().edit().putBoolean("on", true).apply();
        else if ("disable".equals(authPurpose)) lockPrefs().edit().putBoolean("on", false).apply();
        unlocked = true;
        lockView.setVisibility(View.GONE);
        if (!"unlock".equals(authPurpose)) sendLock(null);
    }

    private void authFailed(String why) {
        if ("unlock".equals(authPurpose)) { lockMsg.setText(why + "\nTap Unlock to try again."); return; }
        sendLock(why);
    }

    private void sendLock(String error) {
        try {
            JSONObject o = new JSONObject().put("on", lockOn()).put("available", deviceSecure());
            if (error != null) o.put("error", error);
            sendJs("window.onLock&&window.onLock(" + o + ")");
        } catch (Exception ignored) { }
    }

    @Override
    protected void onStop() {
        super.onStop();
        leftAt = System.currentTimeMillis();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (lockOn() && leftAt > 0 && System.currentTimeMillis() - leftAt > 30000) unlocked = false;
    }

    @Override
    public void onConfigurationChanged(Configuration c) {
        super.onConfigurationChanged(c);
        if (web != null) web.evaluateJavascript("window.onNight&&window.onNight(" + isNight() + ")", null);
    }

    private boolean isNight() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    private void applyBars(boolean dark) {
        darkUi = dark;
        int c = dark ? Color.rgb(7, 18, 38) : Color.rgb(241, 244, 249);
        getWindow().setStatusBarColor(c);
        getWindow().setNavigationBarColor(c);
        if (Build.VERSION.SDK_INT >= 23) {
            View d = getWindow().getDecorView();
            int f = d.getSystemUiVisibility();
            int light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | (Build.VERSION.SDK_INT >= 26 ? View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR : 0);
            d.setSystemUiVisibility(dark ? (f & ~light) : (f | light));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (lockOn() && !unlocked) { lockView.setVisibility(View.VISIBLE); auth("unlock"); }
        else lockView.setVisibility(View.GONE);
        if (web != null) web.evaluateJavascript("window.onResumeApp&&window.onResumeApp()", null);
    }

    private void sendJs(String code) { runOnUiThread(() -> web.evaluateJavascript(code, null)); }

    private boolean smsGranted() {
        return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    @Override
    public void onBackPressed() {
        // 1) let the page close its top popup / menu, 2) go back in the page history,
        // 3) go to the first tab, 4) only then send the app to the background (never kill it).
        web.evaluateJavascript("(function(){try{return !!(window.MK&&MK.back())}catch(e){return false}})()", v -> {
            if ("true".equals(v)) return;
            if (web.canGoBack()) { web.goBack(); return; }
            web.evaluateJavascript("(function(){try{return !!(window.MK&&MK.home())}catch(e){return false}})()", h -> {
                if (!"true".equals(h)) moveTaskToBack(true);
            });
        });
    }

    /* ---------- Google Drive ---------- */

    private void send(JSONObject o) {
        final String code = "window.onDrive&&window.onDrive(" + o.toString() + ")";
        runOnUiThread(() -> web.evaluateJavascript(code, null));
    }

    private JSONObject status() {
        JSONObject o = new JSONObject();
        try {
            SharedPreferences p = DriveSync.prefs(this);
            o.put("type", "status").put("on", p.getBoolean("on", false)).put("email", p.getString("email", ""))
                    .put("last", p.getLong("last", 0)).put("error", p.getString("error", ""));
        } catch (Exception ignored) { }
        return o;
    }

    private void startAuth(String action) {
        pendingAction = action;
        runOnUiThread(() -> Identity.getAuthorizationClient(this).authorize(DriveSync.request())
                .addOnSuccessListener(r -> {
                    if (r.hasResolution()) {
                        try {
                            startIntentSenderForResult(r.getPendingIntent().getIntentSender(), AUTH, null, 0, 0, 0);
                        } catch (Exception e) {
                            fail(e);
                        }
                    } else {
                        afterToken(r.getAccessToken());
                    }
                })
                .addOnFailureListener(this::fail));
    }

    private void afterToken(String token) {
        final String action = pendingAction;
        new Thread(() -> {
            try {
                if (token == null) throw new Exception("Google did not return access.");
                if ("connect".equals(action)) {
                    String email = DriveSync.email(token);
                    DriveSync.prefs(this).edit().putBoolean("on", true).putString("email", email).putString("error", "").apply();
                    DriveSync.schedule(this);
                    if (DriveSync.snapshot(this).exists()) DriveSync.upload(this, token);
                    send(status().put("justConnected", true));
                } else if ("backup".equals(action)) {
                    DriveSync.upload(this, token);
                    send(status().put("message", "Backed up to Google Drive."));
                } else if ("restore".equals(action)) {
                    String data = DriveSync.latest(token);
                    int inv = DriveSync.restoreInvoices(this, token);
                    send(new JSONObject().put("type", "restore").put("data", data).put("invoices", inv));
                }
            } catch (Exception e) {
                fail(e);
            }
        }).start();
    }

    private void fail(Exception e) {
        String msg;
        if (e instanceof ApiException) {
            int code = ((ApiException) e).getStatusCode();
            if (code == 10) msg = "Google sign-in isn't set up for this app yet. Finish the Google Cloud steps, then try again.";
            else if (code == 16 || code == 12501) msg = "Sign-in was cancelled.";
            else if (code == 7) msg = "No internet connection.";
            else msg = "Google error " + code + ". Please try again.";
        } else {
            msg = e.getMessage() == null ? "Something went wrong." : e.getMessage();
        }
        DriveSync.prefs(this).edit().putString("error", msg).apply();
        try { send(new JSONObject().put("type", "error").put("message", msg)); } catch (Exception ignored) { }
    }

    /** Functions the page can call as Android.xxx(). */
    private class Bridge {
        /** Paints the status bar (and nav bar) like the top of the page; darkIcons for light colours. */
        @JavascriptInterface
        public void setBars(final String color, final boolean darkIcons) {
            runOnUiThread(() -> {
                try {
                    android.view.Window w = getWindow();
                    int c = android.graphics.Color.parseColor(color);
                    w.setStatusBarColor(c);
                    w.setNavigationBarColor(c);
                    if (Build.VERSION.SDK_INT >= 23) {
                        android.view.View d = w.getDecorView();
                        int f = d.getSystemUiVisibility();
                        f = darkIcons ? (f | android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (f & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
                        d.setSystemUiVisibility(f);
                    }
                } catch (Exception ignored) {}
            });
        }

        /** Paints the navigation bar like the bottom of the page (a bottom sheet, the page, a dim popup). */
        @JavascriptInterface
        public void setNav(final String color, final boolean darkIcons) {
            runOnUiThread(() -> {
                try {
                    android.view.Window w = getWindow();
                    w.setNavigationBarColor(android.graphics.Color.parseColor(color));
                    if (Build.VERSION.SDK_INT >= 26) {
                        android.view.View d = w.getDecorView();
                        int f = d.getSystemUiVisibility();
                        f = darkIcons ? (f | android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR) : (f & ~android.view.View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
                        d.setSystemUiVisibility(f);
                    }
                } catch (Exception ignored) {}
            });
        }

        @JavascriptInterface
        public String saveFile(String name, String text) {
            try {
                byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    cv.put(MediaStore.Downloads.MIME_TYPE, name.endsWith(".csv") ? "text/csv" : name.endsWith(".html") ? "text/html" : "application/json");
                    cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri u = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                    try (OutputStream o = getContentResolver().openOutputStream(u)) { o.write(bytes); }
                    return "Downloads";
                } else {
                    File f = new File(getExternalFilesDir(null), name);
                    try (FileOutputStream o = new FileOutputStream(f)) { o.write(bytes); }
                    return f.getParent();
                }
            } catch (Exception e) {
                return "";
            }
        }

        /** Opens Android's print screen for a report page, where "Save as PDF" makes the PDF. */
        @JavascriptInterface
        public void printHtml(final String title, final String html) {
            runOnUiThread(() -> {
                final WebView pw = new WebView(MainActivity.this);
                printView = pw;
                pw.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView v, String url) {
                        try {
                            android.print.PrintManager pm = (android.print.PrintManager) getSystemService(android.content.Context.PRINT_SERVICE);
                            pm.print(title, v.createPrintDocumentAdapter(title),
                                    new android.print.PrintAttributes.Builder().setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4).build());
                        } catch (Exception ignored) { }
                    }
                });
                pw.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
            });
        }

        /** Invoice files (photos / PDFs), kept in the app's private storage. */
        private File invFile(String id) {
            if (id == null || !id.matches("[A-Za-z0-9_-]{1,64}")) return null;
            File d = new File(getFilesDir(), "invoices");
            d.mkdirs();
            return new File(d, id);
        }

        @JavascriptInterface
        public boolean invPut(String id, String b64) {
            File f = invFile(id);
            if (f == null) return false;
            try (FileOutputStream o = new FileOutputStream(f)) {
                o.write(android.util.Base64.decode(b64, android.util.Base64.DEFAULT));
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public String invGet(String id) {
            File f = invFile(id);
            if (f == null || !f.exists()) return "";
            try {
                byte[] b = new byte[(int) f.length()];
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    int off = 0, n;
                    while (off < b.length && (n = in.read(b, off, b.length - off)) > 0) off += n;
                }
                return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void invDel(String id) {
            File f = invFile(id);
            if (f != null) f.delete();
        }

        @JavascriptInterface
        public String invList() {
            JSONArray a = new JSONArray();
            File[] fs = new File(getFilesDir(), "invoices").listFiles();
            if (fs != null) for (File f : fs) a.put(f.getName());
            return a.toString();
        }

        @JavascriptInterface
        public void storeBackup(String json) {
            try { DriveSync.saveSnapshot(MainActivity.this, json); } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public String lockState() {
            try { return new JSONObject().put("on", lockOn()).put("available", deviceSecure()).toString(); } catch (Exception e) { return "{}"; }
        }

        @JavascriptInterface
        public void lockEnable() { runOnUiThread(() -> auth("enable")); }

        @JavascriptInterface
        public void lockDisable() { runOnUiThread(() -> auth("disable")); }

        @JavascriptInterface
        public boolean isNight() { return MainActivity.this.isNight(); }

        @JavascriptInterface
        public void setTheme(String mode) { runOnUiThread(() -> applyBars("dark".equals(mode))); }

        @JavascriptInterface
        public String smsPermission() { return smsGranted() ? "granted" : "denied"; }

        @JavascriptInterface
        public void smsRequest() {
            runOnUiThread(() -> requestPermissions(new String[]{ Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS }, 4));
        }

        @JavascriptInterface
        public void openAppSettings() {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
                } catch (Exception ignored) { }
            });
        }

        @JavascriptInterface
        public void smsConfig(String json) {
            try {
                JSONObject o = new JSONObject(json);
                JSONArray w = o.optJSONArray("words");
                JSONArray snd = o.optJSONArray("senders");
                SmsReceiver.prefs(MainActivity.this).edit().putBoolean("on", o.optBoolean("on", false))
                        .putString("words", w == null ? "[]" : w.toString())
                        .putString("senders", snd == null ? "[]" : snd.toString()).apply();
            } catch (Exception ignored) { }
        }

        /* ---- Telda notifications ---- */
        @JavascriptInterface
        public String notifAccess() {
            String l = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
            return l != null && l.contains(getPackageName()) ? "granted" : "denied";
        }

        @JavascriptInterface
        public void notifOpen() {
            runOnUiThread(() -> {
                try { startActivity(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")); }
                catch (Exception e) { try { startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { } }
            });
        }

        @JavascriptInterface
        public void notifConfig(boolean on) { SmsReceiver.prefs(MainActivity.this).edit().putBoolean("notif", on).apply(); }

        /** Which bank / wallet apps to read: [{id, kw:[words in the package name or app name]}]. */
        @JavascriptInterface
        public void notifApps(String json) { SmsReceiver.prefs(MainActivity.this).edit().putString("apps", json == null ? "[]" : json).apply(); }

        /* ---- planned reminders, alerts now, home-screen widget ---- */
        @JavascriptInterface
        public void setNotes(String json) { ReminderWorker.prefs(MainActivity.this).edit().putString("notes", json).apply(); }

        @JavascriptInterface
        public void notifyNow(String id, String title, String text) {
            ReminderWorker.notify(MainActivity.this, 100 + (Math.abs(String.valueOf(id).hashCode()) % 800), "alerts", "Spending alerts", title, text);
        }

        @JavascriptInterface
        public void setWidget(String json) { ExpWidget.save(MainActivity.this, json); }

        /* ---- invoices in the Google Drive backup ---- */
        @JavascriptInterface
        public void driveInvoices(boolean on) { DriveSync.prefs(MainActivity.this).edit().putBoolean("inv", on).apply(); }

        @JavascriptInterface
        public String smsTake() { return SmsReceiver.take(MainActivity.this); }

        @JavascriptInterface
        public String smsScan(int days) {
            JSONArray out = new JSONArray();
            if (!smsGranted()) return "{\"error\":\"SMS permission is off.\"}";
            String since = String.valueOf(System.currentTimeMillis() - days * 86400000L);
            try (Cursor cur = getContentResolver().query(Telephony.Sms.Inbox.CONTENT_URI,
                    new String[]{ "address", "body", "date" }, "date>?", new String[]{ since }, "date ASC")) {
                if (cur != null) {
                    while (cur.moveToNext()) {
                        String body = cur.getString(1);
                        String from = cur.getString(0);
                        if (!SmsReceiver.matches(MainActivity.this, from, body)) continue;
                        out.put(new JSONObject().put("from", from == null ? "" : from).put("body", body).put("ts", cur.getLong(2)));
                    }
                }
            } catch (Exception e) {
                try { return new JSONObject().put("error", String.valueOf(e.getMessage())).toString(); } catch (Exception ignored) { return "[]"; }
            }
            return out.toString();
        }

        @JavascriptInterface
        public void setReminder(String json) {
            try {
                JSONObject o = new JSONObject(json);
                SharedPreferences p = ReminderWorker.prefs(MainActivity.this);
                String due = o.optString("due", "");
                SharedPreferences.Editor e = p.edit().putString("amount", o.optString("amount", "")).putInt("days", o.optInt("days", 3));
                if (!due.equals(p.getString("due", ""))) e.putString("shown", "");
                e.putString("due", due).apply();
            } catch (Exception ignored) { }
        }

        @JavascriptInterface
        public String driveStatus() { return status().toString(); }

        @JavascriptInterface
        public void driveConnect() { startAuth("connect"); }

        @JavascriptInterface
        public void driveBackupNow() { startAuth("backup"); }

        @JavascriptInterface
        public void driveRestore() { startAuth("restore"); }

        @JavascriptInterface
        public void driveDisconnect() {
            DriveSync.unschedule(MainActivity.this);
            DriveSync.prefs(MainActivity.this).edit().clear().apply();
            send(status());
        }
    }
}
