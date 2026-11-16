package com.familyhub.launcher;

import android.app.Activity;
import android.content.IntentSender;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.util.Base64;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.google.android.gms.auth.api.identity.AuthorizationRequest;
import com.google.android.gms.auth.api.identity.AuthorizationResult;
import com.google.android.gms.auth.api.identity.Identity;
import com.google.android.gms.common.api.Scope;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    // The dashboard page. Change this line to point the app at a different address.
    private static final String HOME_URL = "https://risto74.github.io/Family-Hub/";

    private static final String ERROR_HTML =
            "<html><body style='background:#0E1215;color:#E8EBED;font-family:sans-serif;"
                    + "display:flex;align-items:center;justify-content:center;height:100vh;margin:0'>"
                    + "<div style='text-align:center'><h1>Can't reach the dashboard</h1>"
                    + "<p style='color:#A9B3BA'>Checking the Wi-Fi connection. Trying again shortly.</p>"
                    + "<button style='font-size:20px;padding:14px 28px;border-radius:12px;border:0;"
                    + "background:#7A9CB8' onclick='HubApp.reload()'>Try now</button></div></body></html>";

    private WebView web;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable retry = new Runnable() {
        @Override
        public void run() {
            web.loadUrl(HOME_URL);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0E1215);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        web.addJavascriptInterface(new Bridge(), "HubApp");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    view.loadDataWithBaseURL(null, ERROR_HTML, "text/html", "utf-8", null);
                    handler.removeCallbacks(retry);
                    handler.postDelayed(retry, 15000);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                String host = u.getHost();
                if (host != null && host.endsWith("github.io")) {
                    return false;
                }
                openExternal(u);
                return true;
            }
        });

        web.loadUrl(HOME_URL);
        hideSystemUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        hideSystemUi();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            hideSystemUi();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Pressing Home while the dashboard is open resets it to the main view.
        if (web != null) {
            web.evaluateJavascript("window.hubHome && window.hubHome()", null);
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        }
        // Otherwise do nothing: this is the home screen.
    }

    @SuppressWarnings("deprecation")
    private void hideSystemUi() {
        if (Build.VERSION.SDK_INT >= 30) {
            WindowInsetsController c = getWindow().getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private static final int REQ_AUTH = 4711;

    /** Asks Google for a Calendar access token and sends it to the page as window.onGoogleToken(token, error). */
    private void requestGoogleToken() {
        try {
            AuthorizationRequest req = AuthorizationRequest.builder()
                    .setRequestedScopes(Arrays.asList(new Scope("https://www.googleapis.com/auth/calendar")))
                    .build();
            Identity.getAuthorizationClient(this).authorize(req)
                    .addOnSuccessListener(result -> {
                        if (result.hasResolution()) {
                            try {
                                startIntentSenderForResult(result.getPendingIntent().getIntentSender(),
                                        REQ_AUTH, null, 0, 0, 0);
                            } catch (IntentSender.SendIntentException e) {
                                sendToken(null, "Could not open the Google consent screen");
                            }
                        } else {
                            sendToken(result.getAccessToken(), null);
                        }
                    })
                    .addOnFailureListener(e -> sendToken(null, String.valueOf(e.getMessage())));
        } catch (Exception e) {
            sendToken(null, String.valueOf(e.getMessage()));
        }
    }

    private void sendToken(final String token, final String error) {
        final String js = "window.onGoogleToken && window.onGoogleToken("
                + (token == null ? "null" : JSONObject.quote(token)) + ","
                + (error == null ? "null" : JSONObject.quote(error)) + ")";
        runOnUiThread(() -> web.evaluateJavascript(js, null));
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_AUTH) {
            try {
                AuthorizationResult r = Identity.getAuthorizationClient(this).getAuthorizationResultFromIntent(data);
                sendToken(r.getAccessToken(), null);
            } catch (Exception e) {
                sendToken(null, "Sign-in was cancelled or failed: " + e.getMessage());
            }
        }
    }

    private void openExternal(Uri u) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, u);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    /** Functions the dashboard page can call as window.HubApp.* */
    public class Bridge {

        @JavascriptInterface
        public boolean launch(String pkg) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
                if (i == null) {
                    return false;
                }
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        @JavascriptInterface
        public void openUrl(String url) {
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
                return;
            }
            openExternal(Uri.parse(url));
        }

        @JavascriptInterface
        public String getApps() {
            JSONArray arr = new JSONArray();
            try {
                PackageManager pm = getPackageManager();
                Intent main = new Intent(Intent.ACTION_MAIN);
                main.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> found = pm.queryIntentActivities(main, 0);
                Set<String> seen = new HashSet<String>();
                List<String[]> rows = new ArrayList<String[]>();
                for (ResolveInfo ri : found) {
                    String pkg = ri.activityInfo.packageName;
                    if (pkg.equals(getPackageName()) || !seen.add(pkg)) {
                        continue;
                    }
                    rows.add(new String[]{ri.loadLabel(pm).toString(), pkg});
                }
                Collections.sort(rows, new Comparator<String[]>() {
                    @Override
                    public int compare(String[] a, String[] b) {
                        return a[0].compareToIgnoreCase(b[0]);
                    }
                });
                for (String[] r : rows) {
                    JSONObject o = new JSONObject();
                    o.put("n", r[0]);
                    o.put("p", r[1]);
                    arr.put(o);
                }
            } catch (Exception ignored) {
            }
            return arr.toString();
        }

        /** Returns the app's launcher icon as a base64 PNG (96x96), or "" if not installed. */
        @JavascriptInterface
        public String getIcon(String pkg) {
            try {
                Drawable d = getPackageManager().getApplicationIcon(pkg);
                Bitmap bmp = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(bmp);
                d.setBounds(0, 0, 96, 96);
                d.draw(cv);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out);
                return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public void requestGoogleToken() {
            runOnUiThread(() -> MainActivity.this.requestGoogleToken());
        }

        @JavascriptInterface
        public void reload() {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    web.loadUrl(HOME_URL);
                }
            });
        }
    }
}
