package com.Goderium.StructureBuilderNH.common.builder;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Native channel reads, interned per immutable cell; fixed positions allocate no temporary sets. */
final class BrowserChannels {
    private static final String[] NONE = new String[0];
    private static final class Scope { BrowserChannels channels; }
    private static final ThreadLocal<Scope> ACTIVE = ThreadLocal.withInitial(Scope::new);
    private final Map<Blueprint.Cell, String[]> cells = new IdentityHashMap<>();
    private final Map<List<String>, String[]> combinations = new HashMap<>();
    private final Set<String> observed = new TreeSet<>();
    private final Set<String> readOnlyObserved = Collections.unmodifiableSet(observed);
    private String[] scratch = new String[8];
    private int count;

    void begin() { count = 0; ACTIVE.get().channels = this; }
    void end() { ACTIVE.get().channels = null; Arrays.fill(scratch, 0, count, null); count = 0; }

    static void record(String name) {
        BrowserChannels active = ACTIVE.get().channels;
        if (active != null && name != null && !name.isEmpty() && !"chisel".equals(name)) active.add(name);
    }

    private void add(String name) {
        for (int i = 0; i < count; i++) if (name.equals(scratch[i])) return;
        if (count == 256) return;
        if (count == scratch.length) scratch = Arrays.copyOf(scratch, count * 2);
        scratch[count++] = name;
        observed.add(name);
    }

    void put(Blueprint.Cell old, Blueprint.Cell cell) {
        if (ACTIVE.get().channels != this) return;
        String[] previous = cells.get(cell);
        String[] overlapping = old == null ? NONE : names(old);
        if (previous != null) for (String name : previous) add(name);
        for (String name : overlapping) add(name);
        if (previous != null && previous.length == count) return;
        if (count == 0) return;
        String[] names = Arrays.copyOf(scratch, count);
        Arrays.sort(names);
        List<String> key = Arrays.asList(names);
        String[] interned = combinations.get(key);
        if (interned == null) { combinations.put(key, names); interned = names; }
        cells.put(cell, interned);
    }

    String[] names(Blueprint.Cell cell) { return cells.getOrDefault(cell, NONE); }
    Set<String> observed() { return readOnlyObserved; }
}
