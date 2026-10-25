package com.ritm.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(RitmPlugin.class);
        super.onCreate(savedInstanceState);
        keepRendererImportant();
        handleShare(getIntent());
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
