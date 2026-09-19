package com.solyan.iptv;

import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class HardwareCodecSelector implements MediaCodecSelector {
    public static final HardwareCodecSelector INSTANCE = new HardwareCodecSelector();

    private HardwareCodecSelector() {}

    @Override
    public List<MediaCodecInfo> getDecoderInfos(
            String mimeType,
            boolean requiresSecureDecoder,
            boolean requiresTunnelingDecoder) throws MediaCodecUtil.DecoderQueryException {

        List<MediaCodecInfo> all = MediaCodecUtil.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder);

        if (mimeType == null || !mimeType.startsWith("video/")) return all;

        ArrayList<MediaCodecInfo> hw = new ArrayList<>();
        ArrayList<MediaCodecInfo> sw = new ArrayList<>();
        for (MediaCodecInfo info : all) {
            if (isSoftwareCodec(info.name)) sw.add(info);
            else hw.add(info);
        }

        ArrayList<MediaCodecInfo> ordered = new ArrayList<>(all.size());
        ordered.addAll(hw);
        ordered.addAll(sw);
        return ordered;
    }

    public static boolean isSoftwareCodec(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(Locale.US);
        return n.startsWith("omx.google.")
                || n.startsWith("c2.android.")
                || n.contains("ffmpeg")
                || n.contains("sw.decoder")
                || n.contains("software");
    }
}
