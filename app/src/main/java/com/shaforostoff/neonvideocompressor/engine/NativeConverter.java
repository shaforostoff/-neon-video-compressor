package com.shaforostoff.neonvideocompressor.engine;

/**
 * Thin JNI bridge to the native FFmpeg + libx265 engine.
 *
 * <p>Inputs are passed as raw file descriptors; the native side reads them via
 * {@code /proc/self/fd/<fd>} (seekable for regular files). Output is written to a
 * plain filesystem path (the app cache), which the caller then publishes.
 */
public final class NativeConverter {

    static {
        System.loadLibrary("nativeconverter");
    }

    /** Per-decoded-frame progress callback (invoked on the calling thread). */
    public interface ProgressCallback {
        void onProgress(long processedUs);
    }

    public static final int RET_OK = 0;
    public static final int RET_ERROR = -1;
    public static final int RET_CANCELLED = -100;
    /** Stopped early on request; the output was finalized with the partial content. */
    public static final int RET_STOPPED = -101;

    /** @return {@code [durationUs, hasAudio, width, height, rotationDeg, hasVideo]} */
    public static native long[] nativeProbe(int fd);

    // --- pause / cancel control block -------------------------------------
    public static native long nativeCreateControl();

    public static native void nativeSetPaused(long handle, boolean paused);

    public static native void nativeCancel(long handle);

    /**
     * Graceful stop: the running {@link #nativeTranscodeMux} finalizes the output
     * with everything encoded so far (audio truncated to match) and returns
     * {@link #RET_STOPPED}. Other passes treat it like a pause-breaking no-op.
     */
    public static native void nativeRequestStop(long handle);

    public static native void nativeDestroyControl(long handle);

    // --- conversion passes -------------------------------------------------

    /**
     * Decode the source video and encode it to HEVC (libx265) into a video-only
     * mp4 at {@code outPath}, tagged {@code hvc1}.
     *
     * @param maxDurationUs stop after this many microseconds of source video
     *                      (for previews); pass {@code 0} to encode the whole file.
     * @return {@link #RET_OK}, {@link #RET_CANCELLED} or {@link #RET_ERROR}
     */
    public static native int nativeTranscodeVideo(int inFd, String outPath, int crf,
                                                  String preset, long ctrlHandle,
                                                  long maxDurationUs, ProgressCallback cb);

    /**
     * Decode {@code inFd}'s video, encode it to HEVC (libx265, {@code hvc1}) and
     * mux it — interleaved with the first audio stream of {@code audioFd}, or
     * video-only when {@code audioFd} is -1 — straight into {@code outFd} with
     * {@code +faststart}. No temp file and no separate remux pass; {@code outFd}
     * has the same seekable/readable "rw" requirements as in {@link #nativeRemux}.
     *
     * @return {@link #RET_OK}, {@link #RET_STOPPED} (stop requested; the file was
     * finalized with the partial content), {@link #RET_CANCELLED} or
     * {@link #RET_ERROR}
     */
    public static native int nativeTranscodeMux(int inFd, int audioFd, int outFd,
                                                int crf, String preset,
                                                long ctrlHandle, ProgressCallback cb);

    /**
     * Stream-copy (no re-encode) the first {@code maxDurationUs} of the source's
     * video track into a standalone mp4 at {@code outPath} — a lossless reference
     * clip for the preview A/B comparison.
     *
     * @return {@link #RET_OK} or {@link #RET_ERROR}
     */
    public static native int nativeCopyClip(int inFd, String outPath, long maxDurationUs);

    /**
     * Stream-copy the first video stream of {@code videoFd} and the first audio
     * stream of {@code audioFd} straight into {@code outFd} with {@code +faststart}
     * (an mp4/ISO-BMFF container). {@code outFd} must be a seekable, readable and
     * writable fd (e.g. a MediaStore item opened {@code "rw"}) — {@code +faststart}
     * reopens it for reading to relocate the moov atom. Pass -1 for either input
     * fd to omit that track; passing -1 for {@code videoFd} produces an audio-only
     * file. Forces the {@code hvc1} tag when {@code videoWasEncoded}.
     */
    public static native int nativeRemux(int videoFd, int audioFd, int outFd,
                                         boolean videoWasEncoded);

    private NativeConverter() {
    }
}
