package com.shaforostoff.neonvideocompressor.engine;

import android.media.MediaCodecInfo;

import java.io.Serializable;

/** User-chosen conversion options. */
public class Options implements Serializable {

    public enum VideoMode {
        ENCODE_HEVC,
        COPY,
        REMOVE
    }

    public enum AudioMode {
        ENCODE_AAC_LC,
        ENCODE_AAC_HE,
        ENCODE_AAC_HE_V2,
        COPY,
        REMOVE
    }

    public VideoMode videoMode = VideoMode.ENCODE_HEVC;
    public int crf = 30;
    public String preset = "slow";

    public AudioMode audioMode = AudioMode.ENCODE_AAC_LC;
    public int audioBitrate = 40_000; // bits per second

    public boolean encodesVideo() {
        return videoMode == VideoMode.ENCODE_HEVC;
    }

    public boolean copiesVideo() {
        return videoMode == VideoMode.COPY;
    }

    /** When true the output carries no video track (audio-only, .m4a). */
    public boolean removesVideo() {
        return videoMode == VideoMode.REMOVE;
    }

    public boolean encodesAudio() {
        return audioMode == AudioMode.ENCODE_AAC_LC
                || audioMode == AudioMode.ENCODE_AAC_HE
                || audioMode == AudioMode.ENCODE_AAC_HE_V2;
    }

    public boolean copiesAudio() {
        return audioMode == AudioMode.COPY;
    }

    /** When true the output carries no audio track. */
    public boolean removesAudio() {
        return audioMode == AudioMode.REMOVE;
    }

    /** AAC profile constant for MediaCodec, valid only when {@link #encodesAudio()}. */
    public int aacProfile() {
        switch (audioMode) {
            case ENCODE_AAC_HE:
                return MediaCodecInfo.CodecProfileLevel.AACObjectHE;
            case ENCODE_AAC_HE_V2:
                return MediaCodecInfo.CodecProfileLevel.AACObjectHE_PS;
            default:
                return MediaCodecInfo.CodecProfileLevel.AACObjectLC;
        }
    }
}
