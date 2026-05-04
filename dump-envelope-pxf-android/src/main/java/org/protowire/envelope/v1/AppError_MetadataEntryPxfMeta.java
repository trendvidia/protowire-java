// Hand-authored mirror of what protoc-gen-pxf-java-meta would emit for the
// synthetic AppError.MetadataEntry submessage that protoc generates for
// `map<string, string> metadata = 5;`. The runtime's writeMap reads this
// entry's PxfMeta to discover the (key=1, value=2) shape it needs to
// dispatch through. See EnvelopePxfMeta header for context.
package org.protowire.envelope.v1;

import java.util.Map;
import java.util.Set;

import org.protowire.pxf.PxfMeta;

public final class AppError_MetadataEntryPxfMeta implements PxfMeta {
    private AppError_MetadataEntryPxfMeta() {}

    public static final AppError_MetadataEntryPxfMeta INSTANCE = new AppError_MetadataEntryPxfMeta();

    public static final Map<String, Integer> FIELD_NUMBERS = Map.ofEntries(
        Map.entry("key", 1),
        Map.entry("value", 2)
    );

    public static final Map<Integer, Integer> FIELD_KINDS = Map.ofEntries(
        Map.entry(1, 9),
        Map.entry(2, 9)
    );

    public static final Map<Integer, Integer> WIRE_TYPES = Map.ofEntries(
        Map.entry(1, 2),
        Map.entry(2, 2)
    );

    public static final Set<Integer>          REPEATED_FIELDS    = Set.of();
    public static final Set<Integer>          PACKED_FIELDS      = Set.of();
    public static final Map<Integer, String>  MESSAGE_TYPES      = Map.of();
    public static final Map<Integer, String>  ENUM_TYPES         = Map.of();
    public static final Set<Integer>          REQUIRED_FIELDS    = Set.of();
    public static final Map<Integer, String>  DEFAULTS           = Map.of();
    public static final int                   SBE_TEMPLATE_ID    = -1;
    public static final Map<Integer, Integer> SBE_FIELD_LENGTHS  = Map.of();
    public static final Map<Integer, String>  SBE_FIELD_ENCODINGS = Map.of();
    public static final Map<Integer, String>  ONEOF_OF           = Map.of();
    public static final Map<Integer, PxfMeta> NESTED_METAS       = Map.of();
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
    @Override public String                fullName()          { return "envelope.v1.AppError.MetadataEntry"; }
    @Override public Map<Integer, PxfMeta> nestedMetas()       { return NESTED_METAS; }
    @Override public Set<Integer>          mapFields()         { return MAP_FIELDS; }
}
