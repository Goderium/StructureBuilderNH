package com.Goderium.StructureBuilderNH.common.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Small server-thread subscriptions; opening one browser never replaces another observer. */
final class BrowserListeners {
    private final List<Consumer<Blueprint.Section>> listeners = new ArrayList<>();

    void add(Consumer<Blueprint.Section> listener) { if (!listeners.contains(listener)) listeners.add(listener); }
    boolean remove(Consumer<Blueprint.Section> listener) { listeners.remove(listener); return listeners.isEmpty(); }
    void changed(Blueprint.Section section) {
        for (int i = 0; i < listeners.size(); i++) listeners.get(i).accept(section);
    }
}
