package com.solyan.iptv;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Channel {
    public final String name;
    public final String url;
    public final String group;
    public final Map<String, String> headers;

    public Channel(String name, String url, String group, Map<String, String> headers) {
        this.name = name;
        this.url = url;
        this.group = group;
        this.headers = headers == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    @Override public String toString() {
        if (group == null || group.trim().isEmpty()) return name;
        return name + "\n" + group;
    }
}
