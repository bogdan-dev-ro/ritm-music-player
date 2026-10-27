package com.ritm.app;

import android.app.ActivityManager;
import android.app.ApplicationExitInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        RitmState.init(getApplicationContext());
        RitmState.log("app opened");
        registerPlugin(RitmPlugin.class);
        super.onCreate(savedInstanceState);
        installSafetyNet();
        logEarlierExits();
        keepRendererImportant();
        handleShare(getIntent());
    }

    @Override
    public void onDestroy() {
        RitmState.log("app screen closed" + (isFinishing() ? " (finished)" : " (by Android)"));
        super.onDestroy();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= 10) RitmState.log("the phone is low on memory (level " + level + ")");
    }

    /**
     * When Android kills the page's process (usually because the phone is low on memory), the default is
     * to kill the whole app, with the music, the bubble and the notification. Instead, start a new page.
     */
    private void installSafetyNet() {
        try {
            getBridge().setWebViewClient(new BridgeWebViewClient(getBridge()) {
                @Override
                public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                    RitmState.log("the page's process died (" + (detail.didCrash() ? "crash" : "killed by Android") + ")");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            recreate();
                        }
                    });
                    return true;
                }
            });
        } catch (Throwable t) {
            RitmState.log("safety net not installed: " + t);
        }
    }

    /** Writes into the log why the app was closed the last times (Android 11 and newer). */
    private void logEarlierExits() {
        if (Build.VERSION.SDK_INT < 30) return;
        try {
            ActivityManager am = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(getPackageName(), 0, 6);
            SharedPreferences sp = getSharedPreferences("ritm", Context.MODE_PRIVATE);
            long seen = sp.getLong("exitSeen", 0);
            long max = seen;
            String[] names = {"?", "by itself", "signal", "LOW MEMORY", "crash", "native crash", "not responding",
                    "init failed", "permission change", "too many resources", "user request", "user stopped",
                    "dependency died", "other", "FROZEN", "package state", "package updated"};
            for (ApplicationExitInfo e : list) {
                if (e.getTimestamp() <= seen) continue;
                int r = e.getReason();
                String when = new java.text.SimpleDateFormat("dd HH:mm", java.util.Locale.US).format(new java.util.Date(e.getTimestamp()));
                RitmState.log("earlier, at " + when + ", Android closed the app: " + (r >= 0 && r < names.length ? names[r] : "code " + r)
                        + (e.getDescription() == null ? "" : " (" + e.getDescription() + ")"));
                if (e.getTimestamp() > max) max = e.getTimestamp();
            }
            sp.edit().putLong("exitSeen", max).apply();
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onStart() {
        super.onStart();
        keepRendererImportant();
    }

    /**
     * The page runs in its own Android process. When the app is not on screen that process is normally
     * demoted and, after a while, frozen: commands from the bubble or the notification then reach nobody.
     * This keeps it at the same priority as the app (which has a foreground service).
     */
    private void keepRendererImportant() {
        try {
            if (Build.VERSION.SDK_INT >= 26 && getBridge() != null && getBridge().getWebView() != null) {
                getBridge().getWebView().setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
            }
            // sound may start from the bubble or the notification without a touch on the page
            getBridge().getWebView().getSettings().setMediaPlaybackRequiresUserGesture(false);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShare(intent);
    }

    /** A .ritm file opened with Ritm or shared to it: copy it aside, the page imports it. */
    private void handleShare(Intent in) {
        if (in == null) return;
        String act = in.getAction();
        Uri uri = null;
        if (Intent.ACTION_VIEW.equals(act)) {
            uri = in.getData();
        } else if (Intent.ACTION_SEND.equals(act)) {
            uri = in.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        if (uri == null) return;
        final Uri u = uri;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    InputStream is = getContentResolver().openInputStream(u);
                    if (is == null) return;
                    File dir = new File(getCacheDir(), "import");
                    dir.mkdirs();
                    File out = new File(dir, "in.ritm");
                    FileOutputStream os = new FileOutputStream(out);
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
                    os.close();
                    is.close();
                    RitmState.pendingImport = out.getAbsolutePath();
                    RitmPlugin.emit("import", 0);
                } catch (Exception ignored) {
                }
            }
        }).start();
    }

    /**
     * While music is playing, keep the WebView "awake" so audio and JavaScript
     * keep running when the screen turns off or the app goes to the background.
     */
    private void keepAlive() {
        // keep the page alive while playing, and also while paused if the bubble / notification is on
        if (!(RitmState.playing || (RitmState.has && (RitmState.bubble || RitmState.notif)))) return;
        try {
            WebView w = getBridge().getWebView();
            if (w != null) {
                w.onResume();
                w.resumeTimers();
            }
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        keepAlive();
    }

    @Override
    public void onStop() {
        super.onStop();
        keepAlive();
    }
}
