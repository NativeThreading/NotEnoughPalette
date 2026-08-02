package com.github.uright008.nep;

import net.minecraft.core.IdMap;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Minimal {@link IdMap<String>} for unit tests: ids are assigned by first-seen
 * insertion order (identity semantics, matching NEP's {@code ==}-based palette
 * lookups — never {@code equals}).
 */
final class TestIdMap implements IdMap<String> {

    private final List<String> byId = new ArrayList<>();

    TestIdMap(String... values) {
        for (String v : values) {
            add(v);
        }
    }

    /** Returns the id, assigning a new one on first sight. */
    int add(String value) {
        int existing = byId.indexOf(value);
        if (existing >= 0) {
            return existing;
        }
        byId.add(value);
        return byId.size() - 1;
    }

    @Override
    public int getId(String value) {
        int id = byId.indexOf(value);
        return id >= 0 ? id : byId.size();
    }

    @Override
    public String byId(int id) {
        return id >= 0 && id < byId.size() ? byId.get(id) : null;
    }

    @Override
    public int size() {
        return byId.size();
    }

    @Override
    public Iterator<String> iterator() {
        return byId.iterator();
    }

    /** A block-state registry with the given distinct values. */
    static TestIdMap blockStates(String... values) {
        return new TestIdMap(values);
    }
}
