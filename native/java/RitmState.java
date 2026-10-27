package com.ritm.app;

/** Shared playback state, written by RitmPlugin (from the web app) and read by PlayerService. */
final class RitmState {
    static volatile boolean notif = false;     // show the media notification
    static volatile boolean bubble = false;    // show the floating bubble
    static volatile boolean has = false;       // a song is loaded
    static volatile boolean playing = false;
    static volatile String title = "";
    static volatile String subtitle = "";
    static volatile String mode = "loop";      // order | loop | shuffle | one
    static volatile String letter = "\u266A";
    static volatile long duration = 0;         // ms
    static volatile long position = 0;         // ms
    static volatile int index = 0;             // position of the song in the list (1-based)
    static volatile int count = 0;             // songs in the list
    static volatile float volume = 1f;         // the app's own volume (0 - 1.5), not the phone's
    static volatile long posAt = 0;            // when 'position' was received (elapsedRealtime)
    private static java.io.File logFile = null;

    static synchronized void init(android.content.Context c) {
        if (logFile == null) logFile = new java.io.File(c.getApplicationContext().getFilesDir(), "ritm-log.txt");
    }

    /** Adds a line to the connection log. It is kept in a file, so it survives the app being closed. */
    static synchronized void log(String s) {
        if (logFile == null) return;
        String line = new java.text.SimpleDateFormat("dd HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "  " + s + "\n";
        try {
            if (logFile.length() > 20000) {
                String old = dump();
                int cut = old.indexOf('\n', old.length() / 2);
                java.io.FileWriter w = new java.io.FileWriter(logFile, false);
                w.write(cut >= 0 ? old.substring(cut + 1) : "");
                w.close();
            }
            java.io.FileWriter w = new java.io.FileWriter(logFile, true);
            w.write(line);
            w.close();
        } catch (Exception ignored) {
        }
    }

    static synchronized String dump() {
        if (logFile == null || !logFile.exists()) return "";
        try {
            java.io.FileInputStream in = new java.io.FileInputStream(logFile);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            return out.toString("UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    static volatile int ackId = 0;             // last command the page confirmed
    static volatile int pendingId = 0;
    static volatile long pendingAt = 0;
    static volatile String pendingAction = null; // a command waiting for the page to start again
    static volatile double pendingValue = 0;
    static volatile String pendingImport = null; // path of a received .ritm file
    static volatile float hue = 305f;

    private RitmState() {}
}
