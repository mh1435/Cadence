package app.cadence.focus;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewAssetLoader;

import java.io.IOException;
import java.io.InputStream;

/**
 * Runs a Cadence command with the app closed.
 *
 * The web app keeps everything in localStorage, which the WebView partitions by
 * origin. Capacitor serves the app from https://localhost, so this loads the
 * very same origin from assets — the offscreen page therefore reads and writes
 * the same data the visible app does.
 */
final class HeadlessRunner {

    interface Done {
        void onDone(String message, boolean ok);
    }

    private static final String ORIGIN = "https://localhost";
    private static final long SETTLE_MS = 1500;   // let localStorage flush before teardown
    private static final long TIMEOUT_MS = 12000;

    private HeadlessRunner() {}

    static void run(final Context ctx, final String command, final Done done) {
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override public void run() {
                try {
                    start(ctx, command, done);
                } catch (Throwable t) {
                    done.onDone(t.getClass().getSimpleName(), false);
                }
            }
        });
    }

    private static void start(final Context ctx, final String command, final Done done) {
        final WebView web = new WebView(ctx.getApplicationContext());
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        web.getSettings().setDatabaseEnabled(true);

        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .setDomain("localhost")
                .setHttpAllowed(false)
                .addPathHandler("/", new WebViewAssetLoader.PathHandler() {
                    @Override public WebResourceResponse handle(String path) {
                        return fromAssets(ctx, path);
                    }
                })
                .build();

        final Handler main = new Handler(Looper.getMainLooper());
        final boolean[] finished = {false};

        final Runnable teardown = new Runnable() {
            @Override public void run() {
                try { web.destroy(); } catch (Throwable ignored) {}
            }
        };

        final Runnable timeout = new Runnable() {
            @Override public void run() {
                if (finished[0]) return;
                finished[0] = true;
                teardown.run();
                done.onDone("timed out", false);
            }
        };
        main.postDelayed(timeout, TIMEOUT_MS);

        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override public void onPageFinished(WebView view, String url) {
                if (finished[0]) return;
                view.evaluateJavascript(wrap(command), new ValueCallback<String>() {
                    @Override public void onReceiveValue(String value) {
                        if (finished[0]) return;
                        finished[0] = true;
                        main.removeCallbacks(timeout);
                        main.postDelayed(teardown, SETTLE_MS);
                        done.onDone(unquote(value), true);
                    }
                });
            }
        });

        // headless=1 tells the page to skip reminders, sounds and timers
        web.loadUrl(ORIGIN + "/index.html?headless=1");
    }

    private static String wrap(String command) {
        return "(function(){try{return String(runCommand(" + jsString(command) + "));}"
                + "catch(e){return 'ERR '+e;}})()";
    }

    private static String jsString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"' || c == '\\') sb.append('\\').append(c);
            else if (c == '\n') sb.append("\\n");
            else if (c == '\r') sb.append("\\r");
            else if (c < 0x20 || c > 0x7e) sb.append(String.format("\\u%04x", (int) c));
            else sb.append(c);
        }
        return sb.append('"').toString();
    }

    /** evaluateJavascript hands back a JSON-encoded value. */
    private static String unquote(String value) {
        if (value == null) return "";
        String v = value.trim();
        if (v.equals("null")) return "";
        if (v.length() >= 2 && v.charAt(0) == '"' && v.charAt(v.length() - 1) == '"') {
            v = v.substring(1, v.length() - 1);
        }
        return v.replace("\\u201c", "“").replace("\\u201d", "”")
                .replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static WebResourceResponse fromAssets(Context ctx, String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        int q = p.indexOf('?');
        if (q >= 0) p = p.substring(0, q);
        if (p.isEmpty() || p.endsWith("/")) p = p + "index.html";
        try {
            InputStream in = ctx.getAssets().open("public/" + p);
            return new WebResourceResponse(mimeOf(p), "UTF-8", in);
        } catch (IOException e) {
            return null;
        }
    }

    private static String mimeOf(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".svg")) return "image/svg+xml";
        if (p.endsWith(".webmanifest") || p.endsWith(".json")) return "application/json";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }
}
