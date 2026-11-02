// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf.android;

import org.protowire.pxf.PxfMeta;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fields of a well-known type (draft -01 § Well-Known Type Shapes), which
 * the lite writer binds a block against. No generated companion or registry
 * entry describes these types, so their shapes are held here. Every field is a
 * plain proto3 scalar, so none may hold {@code null}.
 */
final class WellKnownMeta implements PxfMeta {
    // FieldDescriptorProto.Type integers.
    private static final int K_DOUBLE = 1;
    private static final int K_FLOAT  = 2;
    private static final int K_INT64  = 3;
    private static final int K_UINT64 = 4;
    private static final int K_INT32  = 5;
    private static final int K_BOOL   = 8;
    private static final int K_STRING = 9;
    private static final int K_BYTES  = 12;
    private static final int K_UINT32 = 13;

    private record Field(String name, int kind) {}

    private static WellKnownMeta shape(String fullName, Field... fields) {
        return new WellKnownMeta(fullName, List.of(fields));
    }

    private static WellKnownMeta wrapper(String simpleName, int kind) {
        return shape("google.protobuf." + simpleName, new Field("value", kind));
    }

    private static final WellKnownMeta SECONDS_NANOS = shape("seconds and nanos",
        new Field("seconds", K_INT64), new Field("nanos", K_INT32));

    private static final Map<Integer, WellKnownMeta> BY_KIND = Map.ofEntries(
        Map.entry(WKT_TIMESTAMP, SECONDS_NANOS),
        Map.entry(WKT_DURATION, SECONDS_NANOS),
        Map.entry(WKT_STRING_VALUE, wrapper("StringValue", K_STRING)),
        Map.entry(WKT_BYTES_VALUE, wrapper("BytesValue", K_BYTES)),
        Map.entry(WKT_BOOL_VALUE, wrapper("BoolValue", K_BOOL)),
        Map.entry(WKT_INT32_VALUE, wrapper("Int32Value", K_INT32)),
        Map.entry(WKT_INT64_VALUE, wrapper("Int64Value", K_INT64)),
        Map.entry(WKT_UINT32_VALUE, wrapper("UInt32Value", K_UINT32)),
        Map.entry(WKT_UINT64_VALUE, wrapper("UInt64Value", K_UINT64)),
        Map.entry(WKT_FLOAT_VALUE, wrapper("FloatValue", K_FLOAT)),
        Map.entry(WKT_DOUBLE_VALUE, wrapper("DoubleValue", K_DOUBLE)),
        Map.entry(WKT_BIG_INT, shape("pxf.BigInt",
            new Field("abs", K_BYTES), new Field("negative", K_BOOL))),
        Map.entry(WKT_DECIMAL, shape("pxf.Decimal",
            new Field("unscaled", K_BYTES), new Field("scale", K_INT32), new Field("negative", K_BOOL))),
        Map.entry(WKT_BIG_FLOAT, shape("pxf.BigFloat",
            new Field("mantissa", K_BYTES), new Field("exponent", K_INT32),
            new Field("prec", K_UINT32), new Field("negative", K_BOOL))));

    /** The shape of well-known kind {@code wkt}, or null if it has none here. */
    static PxfMeta of(int wkt) {
        return BY_KIND.get(wkt);
    }

    private final String fullName;
    private final Map<String, Integer> fieldNumbers = new LinkedHashMap<>();
    private final Map<Integer, Integer> fieldKinds = new HashMap<>();

    /** Fields are numbered from 1, in order: every shape above is declared so. */
    private WellKnownMeta(String fullName, List<Field> fields) {
        this.fullName = fullName;
        for (int i = 0; i < fields.size(); i++) {
            fieldNumbers.put(fields.get(i).name(), i + 1);
            fieldKinds.put(i + 1, fields.get(i).kind());
        }
    }

    @Override public String                fullName()          { return fullName; }
    @Override public Map<String, Integer>  fieldNumbers()      { return fieldNumbers; }
    @Override public Map<Integer, Integer> fieldKinds()        { return fieldKinds; }
    @Override public Map<Integer, Integer> wireTypes()         { return Map.of(); }
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
    @Override public Set<Integer>          nullableFields()    { return Set.of(); }
}
