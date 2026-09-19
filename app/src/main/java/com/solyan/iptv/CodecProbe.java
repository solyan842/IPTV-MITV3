package com.solyan.iptv;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;

public final class CodecProbe {
    private CodecProbe() {}

    public static String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
          .append(" · Android ").append(Build.VERSION.RELEASE)
          .append(" API ").append(Build.VERSION.SDK_INT);

        List<String> avc = findVideoDecoders("video/avc");
        List<String> hevc = findVideoDecoders("video/hevc");
        if (!hevc.isEmpty()) sb.append(" · HEVC HW: ").append(hevc.get(0));
        if (!avc.isEmpty()) sb.append(" · AVC HW: ").append(avc.get(0));
        return sb.toString();
    }

    public static List<String> findVideoDecoders(String mime) {
        ArrayList<String> hw = new ArrayList<>();
        try {
            int count = MediaCodecList.getCodecCount();
            for (int i = 0; i < count; i++) {
                MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
                if (info.isEncoder()) continue;
                String name = info.getName();
                if (HardwareCodecSelector.isSoftwareCodec(name)) continue;
                for (String type : info.getSupportedTypes()) {
                    if (mime.equalsIgnoreCase(type)) {
                        hw.add(name);
                        break;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return hw;
    }

    public static boolean decoderClaims1080p60(String mime) {
        if (Build.VERSION.SDK_INT < 21) return false;
        try {
            int count = MediaCodecList.getCodecCount();
            for (int i = 0; i < count; i++) {
                MediaCodecInfo info = MediaCodecList.getCodecInfoAt(i);
                if (info.isEncoder() || HardwareCodecSelector.isSoftwareCodec(info.getName())) continue;
                for (String type : info.getSupportedTypes()) {
                    if (!mime.equalsIgnoreCase(type)) continue;
                    MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
                    if (caps == null || caps.getVideoCapabilities() == null) continue;
                    try {
                        if (caps.getVideoCapabilities().areSizeAndRateSupported(1920, 1080, 60.0)) return true;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }
}
