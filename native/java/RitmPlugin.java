package com.ritm.app;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.media.AudioManager;
import android.provider.Settings;
import android.webkit.WebView;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

@CapacitorPlugin(name = "Ritm")
public class RitmPlugin extends Plugin {

    private static RitmPlugin instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable sleepTask;

    @Override
    public void load() {
        instance = this;
    }

    /** Sends an action (play, pause, next, prev, stop, seek, mode, hideBubble, sleep) to the web app. */
    static void emit(final String action, final double value) {
        final RitmPlugin p = instance;
        if (p == null) return;
        p.handler.post(new Runnable() {
            @Override
            public void run() {
                // wake the web page first: after a pause in the background it may be frozen
                try {
                    WebView w = p.getBridge().getWebView();
                    if (w != null) {
                        w.onResume();
                        w.resumeTimers();
                    }
                } catch (Exception ignored) {
                }
                JSObject o = new JSObject();
                o.put("action", action);
                o.put("value", value);
                p.notifyListeners("action", o);
            }
        });
    }

    /** True while a phone or VoIP call is ringing or active (the music must not restart then). */
    @PluginMethod
    public void callActive(PluginCall call) {
        boolean active = false;
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            int m = am.getMode();
            active = m == AudioManager.MODE_IN_CALL || m == AudioManager.MODE_RINGTONE
                    || m == AudioManager.MODE_IN_COMMUNICATION;
        } catch (Exception ignored) {
        }
        JSObject o = new JSObject();
        o.put("active", active);
        call.resolve(o);
    }

    @PluginMethod
    public void configure(PluginCall call) {
        RitmState.notif = Boolean.TRUE.equals(call.getBoolean("notification", false));
        RitmState.bubble = Boolean.TRUE.equals(call.getBoolean("bubble", false));
        PlayerService.refresh(getContext());
        call.resolve();
    }

    @PluginMethod
    public void update(PluginCall call) {
        RitmState.has = Boolean.TRUE.equals(call.getBoolean("has", false));
        RitmState.playing = Boolean.TRUE.equals(call.getBoolean("playing", false));
        RitmState.title = call.getString("title", "");
        RitmState.subtitle = call.getString("subtitle", "");
        RitmState.letter = call.getString("letter", "\u266A");
        RitmState.mode = call.getString("mode", "loop");
        Double d = call.getDouble("duration", 0.0);
        Double p = call.getDouble("position", 0.0);
        Double h = call.getDouble("hue", 305.0);
        RitmState.duration = d == null ? 0L : d.longValue();
        RitmState.position = p == null ? 0L : p.longValue();
        RitmState.hue = h == null ? 305f : h.floatValue();
        Integer ix = call.getInt("index", 0);
        Integer ct = call.getInt("count", 0);
        RitmState.index = ix == null ? 0 : ix;
        RitmState.count = ct == null ? 0 : ct;
        PlayerService.refresh(getContext());
        call.resolve();
    }

    /** Native sleep timer: fires even if JavaScript timers are throttled in the background. */
    @PluginMethod
    public void setSleep(PluginCall call) {
        Double min = call.getDouble("minutes", 0.0);
        if (sleepTask != null) {
            handler.removeCallbacks(sleepTask);
            sleepTask = null;
        }
        if (min != null && min > 0) {
            sleepTask = new Runnable() {
                @Override
                public void run() {
                    sleepTask = null;
                    emit("sleep", 0);
                }
            };
            handler.postDelayed(sleepTask, (long) (min * 60000.0));
        }
        call.resolve();
    }

    @PluginMethod
    public void status(PluginCall call) {
        Context c = getContext();
        JSObject o = new JSObject();
        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c);
        boolean notifications = Build.VERSION.SDK_INT < 33
                || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        boolean unrestricted = true;
        if (Build.VERSION.SDK_INT >= 23) {
            PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
            unrestricted = pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
        }
        o.put("overlay", overlay);
        o.put("notifications", notifications);
        o.put("unrestricted", unrestricted);
        call.resolve(o);
    }

    @PluginMethod
    public void requestOverlay(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception ignored) {
        }
        call.resolve();
    }

    @PluginMethod
    public void requestNotifications(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 33 && getActivity() != null) {
            getActivity().requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7701);
        }
        call.resolve();
    }

    @PluginMethod
    public void requestBattery(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 23) {
            try {
                Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            } catch (Exception e) {
                try {
                    Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    getContext().startActivity(i);
                } catch (Exception ignored) {
                }
            }
        }
        call.resolve();
    }
}
