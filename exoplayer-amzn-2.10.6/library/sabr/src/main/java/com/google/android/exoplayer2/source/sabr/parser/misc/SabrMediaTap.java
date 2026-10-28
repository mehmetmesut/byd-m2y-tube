package com.google.android.exoplayer2.source.sabr.parser.misc;

/**
 * M2Y: hands every media byte the SABR player reads to an optional listener, at its offset in the stream's original
 * file (YouTube's start_range), so the app can keep a copy of what was watched for offline playback.
 */
public final class SabrMediaTap {
    public interface Listener {
        /** Called on the loader threads (one per track); must be quick and must not throw. */
        void onMediaBytes(String videoId, int itag, long fileOffset, byte[] buffer, int offset, int length);
    }

    public static volatile Listener listener;

    private SabrMediaTap() {
    }
}
