// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf.android;

import org.protowire.pxf.PxfMeta;

import java.util.Map;
import java.util.Set;

/**
 * The fields of {@code google.protobuf.Duration} and {@code Timestamp}, which
 * share a shape: int64 seconds = 1, int32 nanos = 2. The lite writer binds a
 * block of either type against this, since no generated companion or registry
 * entry describes them.
 */
final class SecondsNanosMeta implements PxfMeta {
    static final SecondsNanosMeta INSTANCE = new SecondsNanosMeta();

    private static final int K_INT64 = 3;
    private static final int K_INT32 = 5;

    private SecondsNanosMeta() {}

    @Override public String                fullName()          { return "seconds and nanos"; }
    @Override public Map<String, Integer>  fieldNumbers()      { return Map.of("seconds", 1, "nanos", 2); }
    @Override public Map<Integer, Integer> fieldKinds()        { return Map.of(1, K_INT64, 2, K_INT32); }
    @Override public Map<Integer, Integer> wireTypes()         { return Map.of(1, 0, 2, 0); }
    @Override public Set<Integer>          repeatedFields()    { return Set.of(); }
    @Override public Set<Integer>          packedFields()      { return Set.of(); }
    @Override public Map<Integer, String>  messageTypes()      { return Map.of(); }
    @Override public Map<Integer, String>  enumTypes()         { return Map.of(); }
    @Override public Set<Integer>          requiredFields()    { return Set.of(); }
    @Override public Map<Integer, String>  defaults()          { return Map.of(); }
    @Override public int                   sbeTemplateId()     { return -1; }
    @Override public Map<Integer, Integer> sbeFieldLengths()   { return Map.of(); }
    @Override public Map<Integer, String>  sbeFieldEncodings() { return Map.of(); }
    @Override public Map<Integer, String>  oneofOf()           { return Map.of(); }
}
