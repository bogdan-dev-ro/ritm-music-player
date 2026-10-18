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
    static volatile float hue = 305f;

    private RitmState() {}
}
