package com.mina.expenses;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import java.util.ArrayList;
import org.json.JSONArray;
import org.json.JSONObject;

/** Voice entry: Android speech recognition (Egyptian Arabic by default). Results go to window.onVoice({text, alts} | {err} | {state}). */
public class Voice {
    static final int REQ = 7401;
    private final Activity act;
    private final WebView web;
    private SpeechRecognizer sr;
    private String pendingLang;

    Voice(Activity act, WebView web) { this.act = act; this.web = web; }

    private void send(JSONObject o) { act.runOnUiThread(() -> web.evaluateJavascript("window.onVoice&&window.onVoice(" + o.toString() + ")", null)); }
    private void sendErr(String e) { try { send(new JSONObject().put("err", e)); } catch (Exception ignored) { } }
    private void sendState(String s) { try { send(new JSONObject().put("state", s)); } catch (Exception ignored) { } }

    @JavascriptInterface
    public boolean available() { return SpeechRecognizer.isRecognitionAvailable(act); }

    @JavascriptInterface
    public void listen(String lang) {
        final String l = lang == null || lang.isEmpty() ? "ar-EG" : lang;
        if (act.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingLang = l;
            act.runOnUiThread(() -> act.requestPermissions(new String[]{ Manifest.permission.RECORD_AUDIO }, REQ));
            return;
        }
        act.runOnUiThread(() -> start(l));
    }

    @JavascriptInterface
    public void stop() { act.runOnUiThread(() -> { if (sr != null) sr.stopListening(); }); }

    @JavascriptInterface
    public void cancel() { act.runOnUiThread(this::release); }

    /** called by MainActivity.onRequestPermissionsResult */
    void onPermission(boolean granted) {
        if (pendingLang == null) return;
        String l = pendingLang; pendingLang = null;
        if (granted) act.runOnUiThread(() -> start(l)); else sendErr("permission");
    }

    private void release() { if (sr != null) { try { sr.cancel(); sr.destroy(); } catch (Exception ignored) { } sr = null; } }

    private void start(String lang) {
        release();
        if (!SpeechRecognizer.isRecognitionAvailable(act)) { sendErr("unavailable"); return; }
        sr = SpeechRecognizer.createSpeechRecognizer(act);
        sr.setRecognitionListener(new RecognitionListener() {
            public void onReadyForSpeech(Bundle b) { sendState("listening"); }
            public void onBeginningOfSpeech() { sendState("speaking"); }
            public void onRmsChanged(float v) { }
            public void onBufferReceived(byte[] b) { }
            public void onEndOfSpeech() { sendState("thinking"); }
            public void onError(int e) {
                String s = e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ? "nomatch"
                        : e == SpeechRecognizer.ERROR_NETWORK || e == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ? "network"
                        : e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ? "permission" : "error" + e;
                sendErr(s); release();
            }
            public void onResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                try {
                    JSONArray alts = new JSONArray();
                    if (r != null) for (String x : r) alts.put(x);
                    send(new JSONObject().put("text", r != null && !r.isEmpty() ? r.get(0) : "").put("alts", alts));
                } catch (Exception ignored) { }
                release();
            }
            public void onPartialResults(Bundle b) {
                ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (r == null || r.isEmpty()) return;
                try { send(new JSONObject().put("partial", r.get(0))); } catch (Exception ignored) { }
            }
            public void onEvent(int t, Bundle b) { }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L);
        try { sr.startListening(i); } catch (Exception e) { sendErr("error"); release(); }
    }
}
