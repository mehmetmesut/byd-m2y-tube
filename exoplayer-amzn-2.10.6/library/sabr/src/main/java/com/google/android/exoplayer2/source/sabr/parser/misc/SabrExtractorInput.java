package com.google.android.exoplayer2.source.sabr.parser.misc;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.extractor.ExtractorInput;
import com.google.android.exoplayer2.source.sabr.parser.SabrStream;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrReloadPlayerError;
import com.google.android.exoplayer2.source.sabr.parser.exceptions.SabrBackoffError;
import com.google.android.exoplayer2.source.sabr.parser.parts.MediaSegmentDataSabrPart;
import com.google.android.exoplayer2.source.sabr.parser.parts.RefreshPlayerResponseSabrPart;
import com.google.android.exoplayer2.source.sabr.parser.parts.SabrPart;
import com.liskovsoft.sharedutils.mylogger.Log;

import java.io.EOFException;
import java.io.IOException;

public final class SabrExtractorInput implements ExtractorInput {
    private static final String TAG = SabrExtractorInput.class.getSimpleName();
    private static final boolean ALLOW_END_OF_INPUT = true;
    private static final int MIN_SWALLOW_BACKOFF_MS = 15_000; // 15 sec
    private final SabrStream sabrStream;
    private ExtractorInput input;
    private long position;
    private long startPosition;
    private int remaining;
    private MediaSegmentDataSabrPart data;
    private static final int TAP_BUFFER_BYTES = 64 * 1024;
    private byte[] tapBuffer;

    public SabrExtractorInput(SabrStream sabrStream) {
        this.sabrStream = sabrStream;
    }

    /** Should be called before passing the extractor to a handler */
    public void init(ExtractorInput input) {
        if (this.input == input) {
            return;
        }

        if (this.input != null) {
            //throw new IllegalStateException("The input should be disposed before initializing");
            Log.e(TAG, "User doing seek? The input should be disposed before initializing");
        }

        this.input = input;
        position = input.getPosition();
        startPosition = position;
        remaining = C.LENGTH_UNSET;
    }

    public void dispose() {
        if (data != null) {
            if (getAdvance() != data.contentLength) {
                //throw new IllegalStateException("The SABR read isn't finished yet");
                Log.e(TAG, "User doing seek? The SABR read isn't finished yet");
            }
        }

        startPosition = 0;
        input = null;
        data = null;
        position = C.POSITION_UNSET;
        remaining = C.LENGTH_UNSET;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException, InterruptedException {
        return forward(length, newLength -> tap(data.data.read(buffer, offset, newLength), buffer, offset, 0));
    }

    @Override
    public void readFully(byte[] buffer, int offset, int length) throws IOException, InterruptedException {
        readFully(buffer, offset, length, false);
    }

    @Override
    public boolean readFully(
            byte[] buffer,
            int offset,
            int length,
            boolean allowEndOfInput) throws IOException, InterruptedException {
        return forwardFully(length, (total, newLength) -> {
            boolean ok = data.data.readFully(buffer, offset + total, newLength, ALLOW_END_OF_INPUT);
            if (ok) {
                tap(newLength, buffer, offset + total, 0);
            }
            return ok;
        });
    }

    @Override
    public int skip(int length) throws IOException, InterruptedException {
        if (SabrMediaTap.listener != null) { // M2Y: read skipped bytes too, so the offline copy has no hole
            return forward(length, newLength -> {
                byte[] b = tapBuffer();
                return tap(data.data.read(b, 0, Math.min(newLength, b.length)), b, 0, 0);
            });
        }
        return forward(length, newLength -> data.data.skip(newLength));
    }

    @Override
    public void skipFully(int length) throws IOException, InterruptedException {
        skipFully(length, false);
    }

    @Override
    public boolean skipFully(
            int length,
            boolean allowEndOfInput) throws IOException, InterruptedException {
        if (SabrMediaTap.listener != null) { // M2Y: see skip()
            return forwardFully(length, (total, newLength) -> {
                byte[] b = tapBuffer();
                for (int done = 0; done < newLength; ) {
                    int n = Math.min(newLength - done, b.length);
                    if (!data.data.readFully(b, 0, n, ALLOW_END_OF_INPUT)) {
                        return false;
                    }
                    tap(n, b, 0, done);
                    done += n;
                }
                return true;
            });
        }
        return forwardFully(length,
                (total, newLength) -> data.data.skipFully(newLength, ALLOW_END_OF_INPUT));
    }

    /** M2Y: gives [count] bytes just read (at [advanceDelta] past the current advance) to the tap; returns [count]. */
    private int tap(int count, byte[] buffer, int offset, int advanceDelta) {
        SabrMediaTap.Listener listener = SabrMediaTap.listener;
        if (count > 0 && listener != null && data.fileStartBytes >= 0) {
            try {
                listener.onMediaBytes(sabrStream.getVideoId(), data.formatId.getItag(),
                        data.fileStartBytes + getAdvance() + advanceDelta, buffer, offset, count);
            } catch (RuntimeException e) { // the copy is optional: playback must go on
                Log.e(TAG, "Media tap failed: %s", e.getMessage());
            }
        }
        return count;
    }

    private byte[] tapBuffer() {
        if (tapBuffer == null) {
            tapBuffer = new byte[TAP_BUFFER_BYTES];
        }
        return tapBuffer;
    }

    @Override
    public long getPosition() {
        return getPositionInt();
    }

    @Override
    public long getLength() {
        return remaining;
    }

    @Override
    public <E extends Throwable> void setRetryPosition(long p, E e) throws E {
        throwShouldNotBeCalled();
    }

    @Override
    public boolean peekFully(
            byte[] target,
            int offset,
            int length,
            boolean allowEndOfInput) {
        throwShouldNotBeCalled();
        return false;
    }

    @Override
    public void peekFully(
            byte[] target,
            int offset,
            int length) {
        throwShouldNotBeCalled();
    }

    @Override
    public boolean advancePeekPosition(
            int length,
            boolean allowEndOfInput) {
        throwShouldNotBeCalled();
        return false;
    }

    @Override
    public void advancePeekPosition(int length) {
        throwShouldNotBeCalled();
    }

    @Override
    public void resetPeekPosition() {
        throwShouldNotBeCalled();
    }

    @Override
    public long getPeekPosition() {
        throwShouldNotBeCalled();
        return -1;
    }

    private void fetchData() {
        while (true) {
            if (data != null) {
                long advance = getAdvance();
                int length = data.contentLength;
                if (advance < length) {
                    break;
                } else if (advance == length) {
                    data = null;
                } else {
                    throwChunkBoundaryExceeded();
                }
            }

            SabrPart sabrPart = sabrStream.parse(input);

            if (sabrPart == null) {
                // The stream ended. If the server told us to back off, honor it instead of
                // re-requesting immediately - otherwise we burn exactly the waiting period the server asked for.
                int backoffMs = sabrStream.getBackoffTimeMs();

                if (backoffMs > MIN_SWALLOW_BACKOFF_MS) {
                    throwBackoffRequested(backoffMs);
                }

                break;
            }

            // Debug
            //if (sabrPart instanceof MediaSegmentDataSabrPart) {
            //    MediaSegmentDataSabrPart data = (MediaSegmentDataSabrPart) sabrPart;
            //    Log.e(TAG, "Consumed contentLength: " + data.contentLength);
            //    data.data.skipFully(data.contentLength);
            //    continue;
            //}

            if (sabrPart instanceof MediaSegmentDataSabrPart) {
                data = (MediaSegmentDataSabrPart) sabrPart;
                startPosition = position;
                break;
            }

            // The server won't send any media until the player response is reloaded.
            // Ignoring this part leads to the endless init chunk loading loop.
            if (sabrPart instanceof RefreshPlayerResponseSabrPart) {
                throwPlayerResponseReloadRequested((RefreshPlayerResponseSabrPart) sabrPart);
            }
        }
    }

    private interface ForwardCallback {
        int forward(int newLength) throws IOException, InterruptedException;
    }

    private int forward(int length, ForwardCallback callback) throws IOException, InterruptedException {
        if (remaining == 0) {
            return C.RESULT_END_OF_INPUT;
        }

        if (remaining != C.LENGTH_UNSET) {
            length = Math.min(length, remaining);
        }

        int result = forwardReal(length, callback);

        if (remaining != C.LENGTH_UNSET && result > 0) {
            remaining -= result;
        }

        return result;
    }

    private int forwardReal(int length, ForwardCallback callback) throws IOException, InterruptedException {
        int result = C.RESULT_END_OF_INPUT;

        fetchData();

        if (data == null) {
            return result;
        }

        int newLength = Math.min(getRemaining(), length);
        result = callback.forward(newLength);

        if (result > 0) {
            position += result;
        }

        return result;
    }

    private interface ForwardFullyCallback {
        boolean forwardFully(int total, int newLength) throws IOException, InterruptedException;
    }

    private boolean forwardFully(int length, ForwardFullyCallback callback) throws IOException, InterruptedException {
        boolean exceeded = remaining != C.LENGTH_UNSET && length > remaining;

        if (exceeded) {
            throwChunkBoundaryExceeded();
        }

        if (remaining != C.LENGTH_UNSET) {
            length = Math.min(length, remaining);
        }

        boolean result = forwardFullyReal(length, callback);

        if (remaining != C.LENGTH_UNSET) {
            remaining -= length;
        }

        return result;
    }

    private boolean forwardFullyReal(int length, ForwardFullyCallback callback) throws IOException, InterruptedException {
        boolean result = false;
        int total = 0;

        while (true) {
            fetchData();

            if (data == null) {
                if (total > 0) {
                    throwEOFException();
                }

                break;
            }

            int newLength = Math.min(getRemaining(), length - total);
            result = callback.forwardFully(total, newLength);

            if (!result) {
                throwEOFException();
            }

            position += newLength;

            if (newLength == length - total) {
                break;
            }

            total += newLength;

            Log.e(TAG, "Continue forwardFully: total=%s, length=%s", total, length - total);
        }

        return result;
    }

    private long getPositionInt() {
        return position;
    }

    private int getRemaining() {
        return data.contentLength - (int) getAdvance();
    }

    private long getAdvance() {
        return position - startPosition;
    }

    private static void throwEOFException() throws EOFException {
        String msg = "EOF should never happened when reading SABR part";
        Log.e(TAG, msg);
        throw new EOFException(msg);
    }

    private static void throwShouldNotBeCalled() {
        String msg = "The peek methods shouldn't be called in SABR extractor";
        Log.e(TAG, msg);
        throw new UnsupportedOperationException(msg);
    }

    private static void throwChunkBoundaryExceeded() {
        String msg = "SABR chunk boundary exceeded";
        Log.e(TAG, msg);
        throw new IllegalStateException(msg);
    }

    private static void throwPlayerResponseReloadRequested(RefreshPlayerResponseSabrPart sabrPart) {
        throw new SabrReloadPlayerError(sabrPart.reloadPlaybackToken);
    }

    private static void throwBackoffRequested(int backoffMs) {
        throw new SabrBackoffError(backoffMs);
    }
}
