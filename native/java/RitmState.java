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
    private static final java.util.ArrayDeque<String> LOG = new java.util.ArrayDeque<String>();

    static synchronized void log(String s) {
        LOG.addLast(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(new java.util.Date()) + "  " + s);
        while (LOG.size() > 60) LOG.removeFirst();
    }

    static synchronized String dump() {
        StringBuilder b = new StringBuilder();
        for (String l : LOG) b.append(l).append('\n');
        return b.toString();
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
