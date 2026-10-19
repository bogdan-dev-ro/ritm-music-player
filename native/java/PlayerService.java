package com.ritm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.RectF;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

/**
 * Keeps the app alive while music plays (foreground service + wake lock),
 * shows the media notification, and draws the floating music bubble.
 * The audio itself is played by the web app; this service only mirrors it.
 */
public class PlayerService extends Service {

    static PlayerService instance;

    private static final String CH_MAIN = "ritm_playback";
    private static final String CH_QUIET = "ritm_quiet";
    private static final int NID = 4242;
    private static final String A_PREV = "ritm.PREV";
    private static final String A_TOGGLE = "ritm.TOGGLE";
    private static final String A_NEXT = "ritm.NEXT";
    private static final String A_STOP = "ritm.STOP";

    private final Handler main = new Handler(Looper.getMainLooper());
    private NotificationManager nm;
    private AudioManager audio;
    private WindowManager wm;
    private MediaSession session;
    private PowerManager.WakeLock wake;
    private boolean isFg = false;
    private Bitmap art;
    private String artKey = "";

    // bubble
    private FrameLayout root;
    private WindowManager.LayoutParams lp;
    private ImageView bubbleView;
    private ImageView pPlay;
    private LinearLayout panel;
    private static final int PANEL_W = 268;
    private TextView pTitle, pSub, pCount;
    private EqView pEq;
    private SeekBar pVol;
    private boolean expanded = false;

    /* ------------------------------------------------------------ entry points */

    static void refresh(Context c) {
        boolean want = RitmState.notif || RitmState.bubble || RitmState.playing;
        PlayerService s = instance;
        if (s == null) {
            if (!want) return;
            try {
                Intent i = new Intent(c, PlayerService.class);
                if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
                else c.startService(i);
            } catch (Exception ignored) {
            }
            return;
        }
        s.render();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel a = new NotificationChannel(CH_MAIN, "Music playback", NotificationManager.IMPORTANCE_LOW);
            a.setShowBadge(false);
            a.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
            nm.createNotificationChannel(a);
            NotificationChannel q = new NotificationChannel(CH_QUIET, "Background playback", NotificationManager.IMPORTANCE_MIN);
            q.setShowBadge(false);
            nm.createNotificationChannel(q);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if (a != null) {
            if (A_PREV.equals(a)) RitmPlugin.emit("prev", 0);
            else if (A_NEXT.equals(a)) RitmPlugin.emit("next", 0);
            else if (A_STOP.equals(a)) RitmPlugin.emit("stop", 0);
            else if (A_TOGGLE.equals(a)) RitmPlugin.emit(RitmState.playing ? "pause" : "play", 0);
            return START_NOT_STICKY;
        }
        // Started with startForegroundService(): we must call startForeground() right away.
        updateArt();
        startFg(buildNotification());
        render();
        return START_NOT_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // The app was swiped away from recents: the web page (and its audio) is gone too.
        RitmState.playing = false;
        RitmState.has = false;
        shutdown();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        hideBubble();
        releaseWake();
        if (session != null) {
            try {
                session.setActive(false);
                session.release();
            } catch (Exception ignored) {
            }
            session = null;
        }
        if (instance == this) instance = null;
        super.onDestroy();
    }

    /* ------------------------------------------------------------ rendering */

    void render() {
        main.post(new Runnable() {
            @Override
            public void run() {
                doRender();
            }
        });
    }

    private void doRender() {
        if (instance != this) return;
        boolean want = RitmState.notif || RitmState.bubble || RitmState.playing;
        if (!want || (!RitmState.has && !RitmState.bubble)) {
            shutdown();
            return;
        }
        updateArt();
        updateSession();
        Notification n = buildNotification();
        boolean wantFg = RitmState.playing || RitmState.bubble || (RitmState.has && RitmState.notif);
        if (wantFg) {
            startFg(n);
        } else {
            if (isFg) {
                if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_DETACH);
                else stopForeground(false);
                isFg = false;
            }
            nm.notify(NID, n);
        }
        syncWake();
        if (RitmState.bubble) showBubble();
        else hideBubble();
        refreshBubbleUi();
    }

    private void startFg(Notification n) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NID, n);
            }
            isFg = true;
        } catch (Exception ignored) {
        }
    }

    private void shutdown() {
        hideBubble();
        releaseWake();
        if (session != null) {
            try {
                session.setActive(false);
                session.release();
            } catch (Exception ignored) {
            }
            session = null;
        }
        try {
            stopForeground(true);
        } catch (Exception ignored) {
        }
        try {
            nm.cancel(NID);
        } catch (Exception ignored) {
        }
        isFg = false;
        stopSelf();
    }

    /* ------------------------------------------------------------ wake lock */

    private void syncWake() {
        try {
            if (RitmState.playing) {
                if (wake == null) {
                    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                    wake = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ritm:playback");
                    wake.setReferenceCounted(false);
                }
                if (!wake.isHeld()) wake.acquire(6L * 60L * 60L * 1000L);
            } else {
                releaseWake();
            }
        } catch (Exception ignored) {
        }
    }

    private void releaseWake() {
        try {
            if (wake != null && wake.isHeld()) wake.release();
        } catch (Exception ignored) {
        }
    }

    /* ------------------------------------------------------------ media session + notification */

    private void updateArt() {
        String key = RitmState.hue + "|" + RitmState.letter;
        if (art != null && key.equals(artKey)) return;
        artKey = key;
        art = makeArt(RitmState.hue, RitmState.letter);
    }

    private void updateSession() {
        if (session == null) {
            session = new MediaSession(this, "Ritm");
            session.setFlags(MediaSession.FLAG_HANDLES_MEDIA_BUTTONS | MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new MediaSession.Callback() {
                @Override
                public void onPlay() {
                    RitmPlugin.emit("play", 0);
                }

                @Override
                public void onPause() {
                    RitmPlugin.emit("pause", 0);
                }

                @Override
                public void onSkipToNext() {
                    RitmPlugin.emit("next", 0);
                }

                @Override
                public void onSkipToPrevious() {
                    RitmPlugin.emit("prev", 0);
                }

                @Override
                public void onStop() {
                    RitmPlugin.emit("stop", 0);
                }

                @Override
                public void onSeekTo(long pos) {
                    RitmPlugin.emit("seek", pos);
                }
            });
        }
        MediaMetadata.Builder md = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, RitmState.title)
                .putString(MediaMetadata.METADATA_KEY_ARTIST, RitmState.subtitle)
                .putLong(MediaMetadata.METADATA_KEY_DURATION, RitmState.duration);
        if (art != null) md.putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, art);
        session.setMetadata(md.build());
        long actions = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE
                | PlaybackState.ACTION_SKIP_TO_NEXT | PlaybackState.ACTION_SKIP_TO_PREVIOUS
                | PlaybackState.ACTION_STOP | PlaybackState.ACTION_SEEK_TO;
        session.setPlaybackState(new PlaybackState.Builder()
                .setActions(actions)
                .setState(RitmState.playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                        RitmState.position, RitmState.playing ? 1f : 0f)
                .build());
        session.setActive(true);
    }

    private int piFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
    }

    private PendingIntent svc(String action, int code) {
        Intent i = new Intent(this, PlayerService.class).setAction(action);
        return PendingIntent.getService(this, code, i, piFlags());
    }

    private int res(String name) {
        return getResources().getIdentifier(name, "drawable", getPackageName());
    }

    private Notification buildNotification() {
        boolean media = RitmState.notif;
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) b = new Notification.Builder(this, media ? CH_MAIN : CH_QUIET);
        else b = new Notification.Builder(this);
        int small = res("ic_note");
        b.setSmallIcon(small != 0 ? small : android.R.drawable.ic_media_play);
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        b.setContentIntent(PendingIntent.getActivity(this, 0, open, piFlags()));
        b.setShowWhen(false);
        if (Build.VERSION.SDK_INT < 26) b.setPriority(media ? Notification.PRIORITY_LOW : Notification.PRIORITY_MIN);

        if (media && RitmState.has) {
            b.setContentTitle(RitmState.title).setContentText(RitmState.subtitle);
            if (art != null) b.setLargeIcon(art);
            b.setOngoing(RitmState.playing);
            b.setOnlyAlertOnce(true);
            b.setVisibility(Notification.VISIBILITY_PUBLIC);
            b.addAction(android.R.drawable.ic_media_previous, "Previous", svc(A_PREV, 1));
            b.addAction(RitmState.playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                    RitmState.playing ? "Pause" : "Play", svc(A_TOGGLE, 2));
            b.addAction(android.R.drawable.ic_media_next, "Next", svc(A_NEXT, 3));
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", svc(A_STOP, 4));
            Notification.MediaStyle st = new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2);
            if (session != null) st.setMediaSession(session.getSessionToken());
            b.setStyle(st);
        } else {
            b.setContentTitle("Ritm");
            b.setContentText(RitmState.has ? (RitmState.playing ? "Playing: " : "Paused: ") + RitmState.title
                    : "Floating bubble is on");
            b.setOngoing(true);
        }
        return b.build();
    }

    /* ------------------------------------------------------------ colors + artwork */

    static int hsl(float h, float s, float l) {
        h = ((h % 360f) + 360f) % 360f;
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float hp = h / 60f;
        float x = c * (1f - Math.abs(hp % 2f - 1f));
        float r = 0, g = 0, b = 0;
        if (hp < 1) { r = c; g = x; }
        else if (hp < 2) { r = x; g = c; }
        else if (hp < 3) { g = c; b = x; }
        else if (hp < 4) { g = x; b = c; }
        else if (hp < 5) { r = x; b = c; }
        else { r = c; b = x; }
        float m = l - c / 2f;
        return Color.rgb(Math.round((r + m) * 255f), Math.round((g + m) * 255f), Math.round((b + m) * 255f));
    }

    private Bitmap makeArt(float hue, String letter) {
        int sz = 320;
        Bitmap bmp = Bitmap.createBitmap(sz, sz, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(hsl(hue, 0.45f, 0.16f));
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int c1 = hsl(hue, 0.88f, 0.62f);
        int c2 = hsl(hue + 55f, 0.92f, 0.58f);
        int c3 = hsl(hue + 300f, 0.78f, 0.46f);
        p.setShader(new RadialGradient(sz * 0.25f, sz * 0.30f, sz * 0.75f, c1, c1 & 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, sz, sz, p);
        p.setShader(new RadialGradient(sz * 0.75f, sz * 0.25f, sz * 0.65f, c2, c2 & 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, sz, sz, p);
        p.setShader(new RadialGradient(sz * 0.45f, sz * 0.85f, sz * 0.80f, c3, c3 & 0x00FFFFFF, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, sz, sz, p);
        p.setShader(null);
        p.setColor(0xE6FFFFFF);
        p.setTypeface(Typeface.create(Typeface.SERIF, Typeface.ITALIC));
        p.setTextSize(sz * 0.55f);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        cv.drawText(letter, sz / 2f, sz / 2f - (fm.ascent + fm.descent) / 2f, p);
        return bmp;
    }

    /* ------------------------------------------------------------ floating bubble */

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void showBubble() {
        if (root != null) return;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(this)) return;
        try {
            buildBubble();
            wm.addView(root, lp);
        } catch (Exception e) {
            root = null;
        }
    }

    private void hideBubble() {
        if (root == null) return;
        try {
            wm.removeView(root);
        } catch (Exception ignored) {
        }
        root = null;
        expanded = false;
    }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private ImageView ibtn(int sizeDp, int padDp, int resId, int marginDp) {
        ImageView v = new ImageView(this);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp));
        p.setMargins(dp(marginDp), 0, dp(marginDp), 0);
        v.setLayoutParams(p);
        v.setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp));
        v.setScaleType(ImageView.ScaleType.FIT_CENTER);
        if (resId != 0) {
            v.setImageResource(resId);
            v.setColorFilter(0xFFFFFFFF);
        }
        return v;
    }

    private void buildBubble() {
        int type = Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                type, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = dp(4);
        lp.y = dp(220);

        root = new FrameLayout(this);

        // collapsed: the round music-note bubble
        bubbleView = new ImageView(this);
        bubbleView.setLayoutParams(new FrameLayout.LayoutParams(dp(52), dp(52)));
        bubbleView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bubbleView.setPadding(dp(14), dp(14), dp(14), dp(14));
        int note = res("ic_note");
        if (note != 0) bubbleView.setImageResource(note);
        bubbleView.setColorFilter(0xFF1B0F22);
        bubbleView.setOnTouchListener(makeDrag(new Runnable() {
            @Override
            public void run() {
                expand();
            }
        }));
        root.addView(bubbleView);

        // expanded: the control panel
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setLayoutParams(new FrameLayout.LayoutParams(dp(PANEL_W), FrameLayout.LayoutParams.WRAP_CONTENT));
        panel.setPadding(dp(16), dp(12), dp(12), dp(14));
        GradientDrawable pbg = rounded(0xF4171021, 26);
        pbg.setStroke(dp(1), 0x33FFFFFF);
        panel.setBackground(pbg);
        panel.setVisibility(View.GONE);

        // header: moving-bars square, song title, playlist name, counter, close
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setOnTouchListener(makeDrag(null));
        pEq = new EqView(this);
        LinearLayout.LayoutParams eqLp = new LinearLayout.LayoutParams(dp(42), dp(42));
        eqLp.rightMargin = dp(12);
        pEq.setLayoutParams(eqLp);
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        pTitle = text(15, 0xFFFFFFFF, true);
        LinearLayout subRow = new LinearLayout(this);
        subRow.setOrientation(LinearLayout.HORIZONTAL);
        subRow.setGravity(Gravity.CENTER_VERTICAL);
        subRow.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        pSub = text(12, 0xFFA79FBA, false);
        pSub.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        pCount = text(11, 0xFFFFFFFF, true);
        pCount.setPadding(dp(8), dp(2), dp(8), dp(2));
        pCount.setBackground(rounded(0x22FFFFFF, 10));
        LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cLp.leftMargin = dp(8);
        pCount.setLayoutParams(cLp);
        subRow.addView(pSub);
        subRow.addView(pCount);
        titles.addView(pTitle);
        titles.addView(subRow);
        CloseView close = new CloseView(this);
        LinearLayout.LayoutParams clLp = new LinearLayout.LayoutParams(dp(34), dp(34));
        clLp.leftMargin = dp(4);
        close.setLayoutParams(clLp);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                collapse();
            }
        });
        head.addView(pEq);
        head.addView(titles);
        head.addView(close);
        panel.addView(head);

        // controls: previous, play / pause, next
        LinearLayout ctl = new LinearLayout(this);
        ctl.setOrientation(LinearLayout.HORIZONTAL);
        ctl.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams ctlLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ctlLp.topMargin = dp(12);
        ctl.setLayoutParams(ctlLp);
        ImageView prev = ibtn(44, 10, android.R.drawable.ic_media_previous, 16);
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RitmPlugin.emit("prev", 0);
            }
        });
        pPlay = ibtn(58, 15, android.R.drawable.ic_media_play, 16);
        pPlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RitmPlugin.emit(RitmState.playing ? "pause" : "play", 0);
            }
        });
        ImageView next = ibtn(44, 10, android.R.drawable.ic_media_next, 16);
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                RitmPlugin.emit("next", 0);
            }
        });
        ctl.addView(prev);
        ctl.addView(pPlay);
        ctl.addView(next);
        panel.addView(ctl);

        // volume
        LinearLayout volRow = new LinearLayout(this);
        volRow.setOrientation(LinearLayout.HORIZONTAL);
        volRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams volLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        volLp.topMargin = dp(10);
        volRow.setLayoutParams(volLp);
        TextView vl = text(12, 0xFFA79FBA, false);
        vl.setText("Volume");
        pVol = new SeekBar(this);
        pVol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        pVol.setMax(audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        pVol.setProgress(audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        pVol.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, p, 0);
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
            }
        });
        volRow.addView(vl);
        volRow.addView(pVol);
        panel.addView(volRow);

        root.addView(panel);
    }

    private View.OnTouchListener makeDrag(final Runnable tap) {
        return new View.OnTouchListener() {
            int sx, sy;
            float tx, ty;
            boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = lp.x;
                        sy = lp.y;
                        tx = e.getRawX();
                        ty = e.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float dx = e.getRawX() - tx;
                        float dy = e.getRawY() - ty;
                        if (!moved && (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6))) moved = true;
                        if (moved) {
                            lp.x = (int) (sx + dx);
                            lp.y = (int) (sy + dy);
                            try {
                                wm.updateViewLayout(root, lp);
                            } catch (Exception ignored) {
                            }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            if (tap != null) tap.run();
                        } else if (!expanded) {
                            snap();
                        }
                        return true;
                    default:
                        return false;
                }
            }
        };
    }

    private void snap() {
        if (root == null) return;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int bw = dp(52);
        lp.x = (lp.x + bw / 2 < dm.widthPixels / 2) ? dp(4) : dm.widthPixels - bw - dp(4);
        lp.y = Math.max(dp(40), Math.min(lp.y, dm.heightPixels - dp(160)));
        try {
            wm.updateViewLayout(root, lp);
        } catch (Exception ignored) {
        }
    }

    private void expand() {
        if (root == null) return;
        expanded = true;
        bubbleView.setVisibility(View.GONE);
        panel.setVisibility(View.VISIBLE);
        int w = getResources().getDisplayMetrics().widthPixels;
        lp.x = Math.max(dp(8), Math.min(lp.x, w - dp(PANEL_W) - dp(8)));
        pVol.setProgress(audio.getStreamVolume(AudioManager.STREAM_MUSIC));
        try {
            wm.updateViewLayout(root, lp);
        } catch (Exception ignored) {
        }
    }

    private void collapse() {
        if (root == null) return;
        expanded = false;
        panel.setVisibility(View.GONE);
        bubbleView.setVisibility(View.VISIBLE);
        snap();
    }

    private void refreshBubbleUi() {
        if (root == null) return;
        int accent = hsl(RitmState.hue, 0.92f, 0.70f);
        int accent2 = hsl(RitmState.hue + 55f, 0.90f, 0.62f);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{accent, accent2});
        g.setShape(GradientDrawable.OVAL);
        if (RitmState.playing) g.setStroke(dp(3), 0xCCFFFFFF);
        bubbleView.setBackground(g);
        bubbleView.setAlpha(RitmState.playing ? 1f : 0.88f);

        pTitle.setText(RitmState.has ? RitmState.title : "Nothing playing");
        pSub.setText(RitmState.has ? RitmState.subtitle : "Open Ritm and pick a song");
        pPlay.setImageResource(RitmState.playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
        GradientDrawable pb = new GradientDrawable();
        pb.setShape(GradientDrawable.OVAL);
        pb.setColor(accent);
        pPlay.setBackground(pb);
        pPlay.setColorFilter(0xFF1B0F22);
        if (RitmState.has && RitmState.count > 0) {
            pCount.setText(RitmState.index + "/" + RitmState.count);
            pCount.setVisibility(View.VISIBLE);
        } else {
            pCount.setVisibility(View.GONE);
        }
        pEq.setColors(accent, accent2);
        pEq.setPlaying(RitmState.playing);
        ColorStateList cs = ColorStateList.valueOf(accent);
        pVol.setProgressTintList(cs);
        pVol.setThumbTintList(cs);
    }

    /** Small gradient square with moving bars, like the cover in the app. */
    private static class EqView extends View {
        private final Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float density;
        private int c1 = 0xFFFF7AD0;
        private int c2 = 0xFF8A7CFF;
        private boolean playing = false;

        EqView(Context c) {
            super(c);
            density = c.getResources().getDisplayMetrics().density;
            bar.setColor(0xFF1B0F22);
        }

        void setColors(int a, int b) {
            if (a == c1 && b == c2) return;
            c1 = a;
            c2 = b;
            bg.setShader(new LinearGradient(0, 0, getWidth(), getHeight(), c1, c2, Shader.TileMode.CLAMP));
            invalidate();
        }

        void setPlaying(boolean p) {
            if (p == playing) return;
            playing = p;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            bg.setShader(new LinearGradient(0, 0, w, h, c1, c2, Shader.TileMode.CLAMP));
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            float r = 11f * density;
            cv.drawRoundRect(new RectF(0, 0, w, h), r, r, bg);
            int n = 4;
            float gap = w * 0.07f;
            float bw = (w * 0.56f - gap * (n - 1)) / n;
            float x0 = w * 0.22f;
            float base = h * 0.76f;
            float maxH = h * 0.52f;
            long t = SystemClock.uptimeMillis();
            for (int i = 0; i < n; i++) {
                float k = playing
                        ? (0.5f + 0.5f * (float) Math.sin(t / (170.0 + i * 55.0) + i * 1.7))
                        : 0.2f + 0.1f * (i % 2);
                float bh = Math.max(bw, maxH * (0.25f + 0.75f * k));
                float left = x0 + i * (bw + gap);
                cv.drawRoundRect(new RectF(left, base - bh, left + bw, base), bw / 2f, bw / 2f, bar);
            }
            if (playing) postInvalidateOnAnimation();
        }
    }

    /** The X that closes the panel, drawn a little thicker than a text glyph. */
    private static class CloseView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        CloseView(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            p.setColor(0xFFFFFFFF);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2.6f * d);
            p.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas cv) {
            float w = getWidth();
            float h = getHeight();
            float m = w * 0.30f;
            cv.drawLine(m, m, w - m, h - m, p);
            cv.drawLine(w - m, m, m, h - m, p);
        }
    }
}
