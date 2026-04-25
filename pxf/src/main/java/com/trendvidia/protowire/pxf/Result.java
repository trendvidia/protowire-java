package com.trendvidia.protowire.pxf;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Field-presence metadata produced by {@link Pxf#unmarshalFull}. Tracks set, null, and absent
 * fields by dotted path (e.g. {@code "name"}, {@code "nested.value"}).
 */
public final class Result {
    private final Set<String> nullFields = new HashSet<>();
    private final Set<String> presentFields = new HashSet<>();

    Result() {}

    void markNull(String path) {
        nullFields.add(path);
        presentFields.add(path);
    }

    void markPresent(String path) {
        presentFields.add(path);
    }

    public boolean isNull(String path)    { return nullFields.contains(path); }
    public boolean isAbsent(String path)  { return !presentFields.contains(path); }
    public boolean isSet(String path)     { return presentFields.contains(path) && !nullFields.contains(path); }
    public List<String> nullFields()      { return List.copyOf(nullFields); }
    Set<String> presentFieldsView()       { return presentFields; }
}
