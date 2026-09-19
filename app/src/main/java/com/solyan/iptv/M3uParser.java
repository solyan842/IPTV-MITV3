package com.solyan.iptv;

import java.io.BufferedReader;
import java.io.StringReader;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class M3uParser {
    private static final Pattern GROUP = Pattern.compile("group-title=\\\"([^\\\"]*)\\\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern VLC_UA = Pattern.compile("#EXTVLCOPT:http-user-agent=(.*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VLC_REF = Pattern.compile("#EXTVLCOPT:http-referrer=(.*)", Pattern.CASE_INSENSITIVE);

    private M3uParser() {}

    public static List<Channel> parse(String text) throws Exception {
        List<Channel> out = new ArrayList<>();
        BufferedReader br = new BufferedReader(new StringReader(text));
        String line;
        String name = null;
        String group = "";
        Map<String, String> pendingHeaders = new LinkedHashMap<>();

        while ((line = br.readLine()) != null) {
            line = clean(line);
            if (line.isEmpty()) continue;

            if (line.startsWith("#EXTINF:")) {
                int comma = line.lastIndexOf(',');
                name = comma >= 0 ? line.substring(comma + 1).trim() : "Kênh TV";
                Matcher gm = GROUP.matcher(line);
                group = gm.find() ? gm.group(1).trim() : "";
                pendingHeaders.clear();
                continue;
            }

            Matcher ua = VLC_UA.matcher(line);
            if (ua.matches()) {
                pendingHeaders.put("User-Agent", ua.group(1).trim());
                continue;
            }
            Matcher ref = VLC_REF.matcher(line);
            if (ref.matches()) {
                pendingHeaders.put("Referer", ref.group(1).trim());
                continue;
            }

            if (!line.startsWith("#") && isStreamUrl(line)) {
                ParsedUrl p = parseKodiUrlHeaders(line);
                Map<String, String> headers = new LinkedHashMap<>(pendingHeaders);
                headers.putAll(p.headers);
                String displayName = name == null || name.isEmpty() ? hostName(p.url) : name;
                out.add(new Channel(displayName, p.url, group, headers));
                name = null;
                group = "";
                pendingHeaders.clear();
            }
        }
        return out;
    }

    public static boolean looksLikePlaylistUrl(String url) {
        String s = url.toLowerCase(Locale.US);
        return s.contains(".m3u") && !s.contains(".m3u8");
    }

    private static boolean isStreamUrl(String s) {
        String x = s.toLowerCase(Locale.US);
        return x.startsWith("http://") || x.startsWith("https://") || x.startsWith("rtmp://") || x.startsWith("rtsp://");
    }

    private static String clean(String s) {
        return s.replace("\uFEFF", "").replace("\u200B", "").replace("\u3164", "").trim();
    }

    private static String hostName(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            return u.getHost() == null ? "Stream" : u.getHost();
        } catch (Exception e) {
            return "Stream";
        }
    }

    private static ParsedUrl parseKodiUrlHeaders(String input) {
        int pipe = input.indexOf('|');
        if (pipe < 0) return new ParsedUrl(input, new LinkedHashMap<String, String>());

        String url = input.substring(0, pipe).replaceAll("[?&]$", "");
        String tail = input.substring(pipe + 1);
        Map<String, String> headers = new LinkedHashMap<>();
        for (String kv : tail.split("&")) {
            int eq = kv.indexOf('=');
            if (eq <= 0) continue;
            String k = kv.substring(0, eq).trim();
            String v = kv.substring(eq + 1).trim();
            try { v = URLDecoder.decode(v, "UTF-8"); } catch (Exception ignored) {}
            if (k.equalsIgnoreCase("referer") || k.equalsIgnoreCase("referrer")) headers.put("Referer", v);
            else if (k.equalsIgnoreCase("user-agent")) headers.put("User-Agent", v);
            else if (k.equalsIgnoreCase("origin")) headers.put("Origin", v);
        }
        return new ParsedUrl(url, headers);
    }

    private static final class ParsedUrl {
        final String url;
        final Map<String, String> headers;
        ParsedUrl(String url, Map<String, String> headers) { this.url = url; this.headers = headers; }
    }
}
