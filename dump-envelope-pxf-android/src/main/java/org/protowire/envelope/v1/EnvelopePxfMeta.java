// Hand-authored mirror of what protoc-gen-pxf-java-meta would emit for
// envelope.proto's Envelope message. Lives here (in :dump-envelope-pxf-android)
// rather than next to the lite-generated Envelope class because the codegen
// plugin from the canonical repo isn't yet wired into protowire-java's build.
// When that wiring lands, this file is a candidate for replacement by the
// auto-generated equivalent.
package org.protowire.envelope.v1;

import java.util.Map;
import java.util.Set;

import org.protowire.pxf.PxfMeta;

public final class EnvelopePxfMeta implements PxfMeta {
    private EnvelopePxfMeta() {}

    public static final EnvelopePxfMeta INSTANCE = new EnvelopePxfMeta();

    public static final Map<String, Integer> FIELD_NUMBERS = Map.ofEntries(
        Map.entry("status", 1),
        Map.entry("transport_error", 2),
        Map.entry("data", 3),
        Map.entry("error", 4)
    );

    public static final Map<Integer, Integer> FIELD_KINDS = Map.ofEntries(
        Map.entry(1, 5  /* INT32 */),
        Map.entry(2, 9  /* STRING */),
        Map.entry(3, 12 /* BYTES */),
        Map.entry(4, 11 /* MESSAGE */)
    );

    public static final Map<Integer, Integer> WIRE_TYPES = Map.ofEntries(
        Map.entry(1, 0 /* varint */),
        Map.entry(2, 2 /* LEN */),
        Map.entry(3, 2 /* LEN */),
        Map.entry(4, 2 /* LEN */)
    );

    public static final Set<Integer>          REPEATED_FIELDS    = Set.of();
    public static final Set<Integer>          PACKED_FIELDS      = Set.of();
    public static final Map<Integer, String>  MESSAGE_TYPES      = Map.of(4, "org.protowire.envelope.v1.AppError");
    public static final Map<Integer, String>  ENUM_TYPES         = Map.of();
    public static final Set<Integer>          REQUIRED_FIELDS    = Set.of();
    public static final Map<Integer, String>  DEFAULTS           = Map.of();
    public static final int                   SBE_TEMPLATE_ID    = -1;
    public static final Map<Integer, Integer> SBE_FIELD_LENGTHS  = Map.of();
    public static final Map<Integer, String>  SBE_FIELD_ENCODINGS = Map.of();
    public static final Map<Integer, String>  ONEOF_OF           = Map.of();
    public static final Map<Integer, PxfMeta> NESTED_METAS       = Map.of(4, AppErrorPxfMeta.INSTANCE);
    public static final Set<Integer>          MAP_FIELDS         = Set.of();

    @Override public Map<String, Integer>  fieldNumbers()      { return FIELD_NUMBERS; }
    @Override public Map<Integer, Integer> fieldKinds()        { return FIELD_KINDS; }
    @Override public Map<Integer, Integer> wireTypes()         { return WIRE_TYPES; }
    @Override public Set<Integer>          repeatedFields()    { return REPEATED_FIELDS; }
    @Override public Set<Integer>          packedFields()      { return PACKED_FIELDS; }
    @Override public Map<Integer, String>  messageTypes()      { return MESSAGE_TYPES; }
    @Override public Map<Integer, String>  enumTypes()         { return ENUM_TYPES; }
    @Override public Set<Integer>          requiredFields()    { return REQUIRED_FIELDS; }
    @Override public Map<Integer, String>  defaults()          { return DEFAULTS; }
    @Override public int                   sbeTemplateId()     { return SBE_TEMPLATE_ID; }
    @Override public Map<Integer, Integer> sbeFieldLengths()   { return SBE_FIELD_LENGTHS; }
    @Override public Map<Integer, String>  sbeFieldEncodings() { return SBE_FIELD_ENCODINGS; }
    @Override public Map<Integer, String>  oneofOf()           { return ONEOF_OF; }
    @Override public String                fullName()          { return "envelope.v1.Envelope"; }
    @Override public Map<Integer, PxfMeta> nestedMetas()       { return NESTED_METAS; }
    @Override public Set<Integer>          mapFields()         { return MAP_FIELDS; }
}
