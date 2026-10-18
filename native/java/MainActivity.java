package com.ritm.app;

import android.os.Bundle;
import android.webkit.WebView;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(RitmPlugin.class);
        super.onCreate(savedInstanceState);
    }

    /**
     * While music is playing, keep the WebView "awake" so audio and JavaScript
     * keep running when the screen turns off or the app goes to the background.
     */
    private void keepAlive() {
        if (!RitmState.playing) return;
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
