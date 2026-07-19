package com.shaforostoff.neonvideocompressor.engine;

import android.media.MediaCodecInfo;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.media.MediaCodecInfo.EncoderCapabilities;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Range;

import java.util.Locale;

/**
 * Describes the best hardware-accelerated HEVC encoder on this device, if any.
 *
 * <p>Used both to decide whether to offer the "Encode HEVC (vendor)" option (and
 * what to put in the braces) and, at encode time, to pick the bitrate mode and
 * translate the user's 0..100 quality slider into the encoder's own units.
 */
public final class HardwareEncoderInfo {

    private final String codecName;
    private final String vendorLabel;
    private final boolean supportsCq;
    private final Range<Integer> qualityRange;

    private HardwareEncoderInfo(String codecName, String vendorLabel,
                               boolean supportsCq, Range<Integer> qualityRange) {
        this.codecName = codecName;
        this.vendorLabel = vendorLabel;
        this.supportsCq = supportsCq;
        this.qualityRange = qualityRange;
    }

    /** Exact MediaCodec name, e.g. {@code c2.qti.hevc.encoder}. */
    public String codecName() {
        return codecName;
    }

    /** Human vendor name for the UI braces, e.g. "Qualcomm" or "MediaTek". */
    public String vendorLabel() {
        return vendorLabel;
    }

    /** True when the encoder supports constant-quality (CQ) mode; VBR otherwise. */
    public boolean supportsCq() {
        return supportsCq;
    }

    /** Maps a 0..100 quality slider (higher = better) onto the encoder's CQ range. */
    public int cqQualityFor(int quality0to100) {
        int lo = qualityRange.getLower();
        int hi = qualityRange.getUpper();
        int clamped = Math.max(0, Math.min(100, quality0to100));
        int q = Math.round(lo + (hi - lo) * (clamped / 100f));
        return Math.max(lo, Math.min(hi, q));
    }

    /**
     * Finds the best hardware HEVC encoder, preferring one that supports CQ.
     *
     * @return the encoder, or {@code null} if the device has no hardware HEVC
     * encoder (only the software libx265 path is then offered).
     */
    public static HardwareEncoderInfo detect() {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        HardwareEncoderInfo fallback = null;
        for (MediaCodecInfo info : list.getCodecInfos()) {
            if (!info.isEncoder()) continue;
            if (!supportsHevc(info)) continue;
            // Skip software encoders (OMX.google.*, c2.android.*): they'd offer no
            // speed/power win over the bundled libx265 path we already have.
            if (!info.isHardwareAccelerated()) continue;
            // Skip Qualcomm's specialised constant-quality variant
            // (c2.qti.hevc.encoder.cq): it advertises CQ but fails to configure a
            // surface-fed encode ("Failed to set updated param coded.size"). The
            // general c2.qti.hevc.encoder handles CQ reliably.
            if (isSpecialisedCqVariant(info.getName())) continue;

            boolean cq = false;
            Range<Integer> quality = null;
            try {
                CodecCapabilities caps =
                        info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC);
                EncoderCapabilities enc = caps.getEncoderCapabilities();
                if (enc != null) {
                    cq = enc.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CQ);
                    quality = enc.getQualityRange();
                }
            } catch (Exception ignored) {
                // Some OEM codecs throw for capability queries; treat as VBR-only.
            }
            if (quality == null) quality = new Range<>(0, 100);

            HardwareEncoderInfo candidate = new HardwareEncoderInfo(
                    info.getName(), vendorOf(info.getName()), cq, quality);
            // Prefer the requested CQ mode; otherwise remember the first HW encoder
            // as a VBR fallback and keep looking for a CQ-capable one.
            if (cq) return candidate;
            if (fallback == null) fallback = candidate;
        }
        return fallback;
    }

    /** Qualcomm's dedicated CQ component, e.g. {@code c2.qti.hevc.encoder.cq}. */
    private static boolean isSpecialisedCqVariant(String name) {
        return name.toLowerCase(Locale.US).endsWith(".cq");
    }

    private static boolean supportsHevc(MediaCodecInfo info) {
        for (String type : info.getSupportedTypes()) {
            if (MediaFormat.MIMETYPE_VIDEO_HEVC.equalsIgnoreCase(type)) return true;
        }
        return false;
    }

    /** Best-effort chip vendor from the codec name (the text shown in the braces). */
    private static String vendorOf(String name) {
        String n = name.toLowerCase(Locale.US);
        if (n.contains("qti") || n.contains("qcom") || n.contains("qualcomm")) return "Qualcomm";
        if (n.contains("mtk") || n.contains("mediatek")) return "MediaTek";
        if (n.contains("exynos") || n.contains("slsi") || n.contains(".sec.")
                || n.contains("samsung")) return "Exynos";
        if (n.contains("kirin") || n.contains("hisi")) return "Kirin";
        if (n.contains("nvidia") || n.contains("nvenc") || n.contains("tegra")) return "NVIDIA";
        if (n.contains("intel")) return "Intel";
        if (n.contains("img") || n.contains("powervr")) return "Imagination";
        // Unknown hardware vendor — still a valid HW encoder, just unlabelled.
        return "Hardware";
    }
}
