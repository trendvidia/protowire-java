// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import com.google.protobuf.Descriptors.MethodDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.Descriptors.ServiceDescriptor;
import com.google.protobuf.Message;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Retired option numbers (#91; protowire-go#98). Before protowire v1.12.0
 * the family's extensions lived in an unregistered 50000–59999 range it
 * had squatted; they moved to the registered block 1314–1363 in one
 * coordinated change (protowire#244), and the old numbers are dead:
 * STABILITY.md promise 3 names them so that a descriptor compiled before
 * the move is diagnosable rather than merely unknown. Without this, such
 * a descriptor fails silently — {@code (pxf.required)} stops being
 * enforced, {@code (pxf.default)} stops applying — and the schema binds
 * documents it should reject.
 *
 * <p>The check is scoped two ways, because 50000-and-up is where every
 * project that never registered its numbers puts them: a retired number
 * counts only on the Options kind the retired allocation used, and only
 * in a file that imports one of protowire's own annotation files — the
 * import a schema needs in order to have written the option at all.
 */
final class RetiredNumbers {
    private RetiredNumbers() {}

    /** What a retired option number used to be and where it went. */
    private record Retired(String was, int now, String file) {}

    private static final Map<Integer, Retired> FILE_OPTIONS = Map.of(
            50100, new Retired("(sbe.schema_id)", 1319, "sbe/annotations.proto"),
            50101, new Retired("(sbe.version)", 1320, "sbe/annotations.proto"),
            50400, new Retired("the file_annotations carrier", 1327, "protowire/schema/v1/descriptor.proto"),
            50401, new Retired("the functions carrier", 1328, "protowire/schema/v1/descriptor.proto"),
            50402, new Retired("the annotation_decls carrier", 1329, "protowire/schema/v1/descriptor.proto"),
            50403, new Retired("the type_decls carrier", 1330, "protowire/schema/v1/descriptor.proto"),
            50404, new Retired("the source_map carrier", 1331, "protowire/schema/v1/descriptor.proto"));
    private static final Map<Integer, Retired> MESSAGE_OPTIONS = Map.of(
            50200, new Retired("(sbe.template_id)", 1321, "sbe/annotations.proto"),
            50400, new Retired("the message_annotations carrier", 1327, "protowire/schema/v1/descriptor.proto"),
            51001, new Retired("a protocheck message constraint", 1348, "protocheck's annotations"));
    private static final Map<Integer, Retired> FIELD_OPTIONS = Map.of(
            50000, new Retired("(pxf.required)", 1314, "pxf/annotations.proto"),
            50001, new Retired("(pxf.default)", 1315, "pxf/annotations.proto"),
            50002, new Retired("(pxf.key)", 1316, "pxf/annotations.proto"),
            50300, new Retired("(sbe.length)", 1322, "sbe/annotations.proto"),
            50301, new Retired("(sbe.encoding)", 1323, "sbe/annotations.proto"),
            50400, new Retired("the field_annotations carrier", 1327, "protowire/schema/v1/descriptor.proto"),
            51000, new Retired("a protocheck field constraint", 1347, "protocheck's annotations"));
    private static final Map<Integer, Retired> ONEOF_OPTIONS = Map.of(
            50400, new Retired("the oneof_annotations carrier", 1327, "protowire/schema/v1/descriptor.proto"),
            51002, new Retired("a protocheck oneof constraint", 1349, "protocheck's annotations"));
    /** Enum, enum value, service and method options carried only the annotation carrier. */
    private static final Map<Integer, Retired> CARRIER_ONLY = Map.of(
            50400, new Retired("the *_annotations carrier", 1327, "protowire/schema/v1/descriptor.proto"));

    /** The imports that mark a file as compiled against protowire's annotations, in either era. */
    private static final Set<String> PROTOWIRE_ANNOTATION_FILES = Set.of(
            "pxf/annotations.proto", "sbe/annotations.proto",
            "protowire/schema/v1/annotations.proto", "protowire/schema/v1/descriptor.proto");

    static boolean importsProtowire(FileDescriptor fd) {
        for (FileDescriptor dep : fd.getDependencies()) {
            if (PROTOWIRE_ANNOTATION_FILES.contains(dep.getName())) return true;
        }
        return false;
    }

    /** Appends a violation for every retired option number the file's elements carry. */
    static void walk(String path, FileDescriptor fd, List<SchemaValidator.Violation> out) {
        scan(path, path, "FileOptions", fd.getOptions(), FILE_OPTIONS, out);
        for (Descriptor m : fd.getMessageTypes()) walkMessage(path, m, out);
        for (EnumDescriptor e : fd.getEnumTypes()) walkEnum(path, e, out);
        for (ServiceDescriptor s : fd.getServices()) {
            scan(path, s.getFullName(), "ServiceOptions", s.getOptions(), CARRIER_ONLY, out);
            for (MethodDescriptor m : s.getMethods()) scan(path, m.getFullName(), "MethodOptions", m.getOptions(), CARRIER_ONLY, out);
        }
    }

    private static void walkMessage(String path, Descriptor md, List<SchemaValidator.Violation> out) {
        scan(path, md.getFullName(), "MessageOptions", md.getOptions(), MESSAGE_OPTIONS, out);
        for (FieldDescriptor f : md.getFields()) scan(path, f.getFullName(), "FieldOptions", f.getOptions(), FIELD_OPTIONS, out);
        for (OneofDescriptor o : md.getOneofs()) scan(path, o.getFullName(), "OneofOptions", o.getOptions(), ONEOF_OPTIONS, out);
        for (EnumDescriptor e : md.getEnumTypes()) walkEnum(path, e, out);
        for (Descriptor n : md.getNestedTypes()) walkMessage(path, n, out);
    }

    private static void walkEnum(String path, EnumDescriptor e, List<SchemaValidator.Violation> out) {
        scan(path, e.getFullName(), "EnumOptions", e.getOptions(), CARRIER_ONLY, out);
        for (EnumValueDescriptor v : e.getValues()) scan(path, v.getFullName(), "EnumValueOptions", v.getOptions(), CARRIER_ONLY, out);
    }

    /**
     * Scans one Options message's unknown fields for the numbers in
     * {@code table}. Only unknown fields are read: nothing declares the
     * retired numbers any more, so that is where they land.
     */
    private static void scan(String path, String element, String kind, Message opts,
                             Map<Integer, Retired> table, List<SchemaValidator.Violation> out) {
        for (int num : opts.getUnknownFields().asMap().keySet()) {
            Retired r = table.get(num);
            if (r == null) continue;
            out.add(new SchemaValidator.Violation(path, element, Integer.toString(num), SchemaValidator.Kind.RETIRED_NUMBER,
                    "option number " + num + " on " + kind + " was " + r.was() + " before protowire v1.12.0 and is " + r.now()
                            + " now; the descriptor predates the registered extension block and must be recompiled against the current "
                            + r.file()));
        }
    }
}
