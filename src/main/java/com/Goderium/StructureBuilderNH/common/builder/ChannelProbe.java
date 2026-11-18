package com.Goderium.StructureBuilderNH.common.builder;

import java.util.Set;

/** Captures native channel reads while this thread is walking the blueprint's private world. */
public final class ChannelProbe {
    private static final ThreadLocal<Set<String>> ACTIVE = new ThreadLocal<>();

    private ChannelProbe() {}

    static void begin(Set<String> channels) {
        ACTIVE.set(channels);
    }

    static void end() {
        ACTIVE.remove();
    }

    public static void record(String name) {
        Set<String> channels = ACTIVE.get();
        if (channels != null && name != null && !name.isEmpty()) channels.add(name);
    }
}
