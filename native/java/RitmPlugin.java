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
import android.os.SystemClock;
import android.util.Base64;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileOutputStream;
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
    /** Returns false when the web page is gone, so the caller can restart it. */
    private static int seq = 0;

    /** Sends a command to the page. Returns its number, or -1 when the page is gone. */
    static synchronized int emit(final String action, final double value) {
        final RitmPlugin p = instance;
        if (p == null) return -1;
        try {
            if (p.getBridge() == null || p.getBridge().getWebView() == null) return -1;
        } catch (Exception e) {
            return -1;
        }
        final int id = ++seq;
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
                o.put("id", id);
                p.notifyListeners("action", o);
            }
        });
        return id;
    }

    /** The page confirms it received command number 'id'. */
    @PluginMethod
    public void ack(PluginCall call) {
        Integer id = call.getInt("id", 0);
        if (id != null && id > RitmState.ackId) RitmState.ackId = id;
        call.resolve();
    }

    @Override
    protected void handleOnDestroy() {
        if (instance == this) instance = null;
    }

    /** A command the service could not deliver because the page was closed. */
    @PluginMethod
    public void takePending(PluginCall call) {
        JSObject o = new JSObject();
        boolean fresh = RitmState.pendingAction != null
                && SystemClock.elapsedRealtime() - RitmState.pendingAt < 20000;
        o.put("action", fresh ? RitmState.pendingAction : "");
        o.put("value", RitmState.pendingValue);
        o.put("id", RitmState.pendingId);
        RitmState.pendingAction = null;
        call.resolve(o);
    }

    /** Sends the app to the background again (used after a command restarted it). */
    @PluginMethod
    public void hideApp(PluginCall call) {
        try {
            if (getActivity() != null) getActivity().moveTaskToBack(true);
        } catch (Exception ignored) {
        }
        call.resolve();
    }

    /** Path of a .ritm file received from another app (or empty). */
    @PluginMethod
    public void takePendingImport(PluginCall call) {
        JSObject o = new JSObject();
        if (RitmState.pendingImport != null) o.put("path", RitmState.pendingImport);
        RitmState.pendingImport = null;
        call.resolve(o);
    }

    private File shareFile;

    @PluginMethod
    public void shareBegin(PluginCall call) {
        try {
            File dir = new File(getContext().getCacheDir(), "share");
            if (dir.exists()) {
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
            } else {
                dir.mkdirs();
            }
            String name = call.getString("name", "Ritm share.ritm");
            if (name == null) name = "Ritm share.ritm";
            name = name.replaceAll("[^A-Za-z0-9._ -]", "_");
            shareFile = new File(dir, name);
            call.resolve();
        } catch (Exception e) {
            call.reject("share");
        }
    }

    @PluginMethod
    public void shareChunk(PluginCall call) {
        try {
            byte[] b = Base64.decode(call.getString("data", ""), Base64.DEFAULT);
            FileOutputStream o = new FileOutputStream(shareFile, true);
            o.write(b);
            o.close();
            call.resolve();
        } catch (Exception e) {
            call.reject("chunk");
        }
    }

    @PluginMethod
    public void shareOpen(PluginCall call) {
        try {
            Context c = getContext();
            Uri uri = FileProvider.getUriForFile(c, c.getPackageName() + ".ritmshare", shareFile);
            Intent s = new Intent(Intent.ACTION_SEND);
            s.setType("application/octet-stream");
            s.putExtra(Intent.EXTRA_STREAM, uri);
            s.putExtra(Intent.EXTRA_SUBJECT, shareFile.getName());
            s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            Intent ch = Intent.createChooser(s, call.getString("title", "Share"));
            ch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (getActivity() != null) {
                getActivity().startActivity(ch);
            } else {
                ch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(ch);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("open");
        }
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
        Double vl = call.getDouble("vol", 1.0);
        RitmState.volume = vl == null ? 1f : vl.floatValue();
        RitmState.posAt = SystemClock.elapsedRealtime();
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
