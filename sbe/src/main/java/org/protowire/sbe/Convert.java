// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.EnumDescriptor;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** {@code sbe2proto} and {@code proto2sbe} converters. */
public final class Convert {
    private Convert() {}

    // --- XML → .proto ---------------------------------------------------

    public static String xmlToProto(byte[] xml) {
        XmlSchema schema = XmlSchema.parse(xml);
        Map<String, XmlSchema.XmlType> typeMap = new HashMap<>();
        Map<String, XmlSchema.XmlComposite> compMap = new HashMap<>();
        Map<String, XmlSchema.XmlEnum> enumMap = new HashMap<>();

        for (String prim : List.of("int8","int16","int32","int64","uint8","uint16","uint32","uint64","float","double","char")) {
            XmlSchema.XmlType t = new XmlSchema.XmlType();
            t.name = prim; t.primitiveType = prim;
            typeMap.put(prim, t);
        }
        for (XmlSchema.XmlType t : schema.types) typeMap.put(t.name, t);
        for (XmlSchema.XmlComposite c : schema.composites) compMap.put(c.name, c);
        for (XmlSchema.XmlEnum e : schema.enums) enumMap.put(e.name, e);

        StringBuilder b = new StringBuilder();
        b.append("syntax = \"proto3\";\n\n");
        if (schema.pkg != null && !schema.pkg.isEmpty()) b.append("package ").append(schema.pkg).append(";\n\n");
        b.append("import \"sbe/annotations.proto\";\n\n");
        b.append("option (sbe.schema_id) = ").append(schema.id).append(";\n");
        b.append("option (sbe.version) = ").append(schema.version).append(";\n\n");

        for (XmlSchema.XmlEnum e : schema.enums) writeProtoEnum(b, e);

        for (XmlSchema.XmlComposite c : schema.composites) {
            if ("messageHeader".equals(c.name) || "groupSizeEncoding".equals(c.name)) continue;
            writeProtoComposite(b, c);
        }

        for (XmlSchema.XmlMessage m : schema.messages) writeProtoMessage(b, m, typeMap, compMap, enumMap);
        return b.toString();
    }

    static void writeProtoEnum(StringBuilder b, XmlSchema.XmlEnum e) {
        b.append("enum ").append(e.name).append(" {\n");
        String prefix = camelToScreamingSnake(e.name);
        for (XmlSchema.XmlValidValue v : e.values) {
            String name = prefix + "_" + camelToScreamingSnake(v.name);
            b.append("  ").append(name).append(" = ").append(v.value).append(";\n");
        }
        b.append("}\n\n");
    }

    static void writeProtoComposite(StringBuilder b, XmlSchema.XmlComposite c) {
        b.append("message ").append(c.name).append(" {\n");
        int n = 1;
        for (XmlSchema.XmlType t : c.types) {
            String[] mt = resolveTypeToProto(t.primitiveType, t.length);
            String name = camelToSnake(t.name);
            if (!mt[1].isEmpty()) b.append("  ").append(mt[0]).append(' ').append(name).append(" = ").append(n).append(" [").append(mt[1]).append("];\n");
            else b.append("  ").append(mt[0]).append(' ').append(name).append(" = ").append(n).append(";\n");
            n++;
        }
        for (XmlSchema.XmlRef r : c.refs) {
            b.append("  ").append(r.type).append(' ').append(camelToSnake(r.name)).append(" = ").append(n++).append(";\n");
        }
        b.append("}\n\n");
    }

    static void writeProtoMessage(StringBuilder b, XmlSchema.XmlMessage m,
                                  Map<String, XmlSchema.XmlType> typeMap,
                                  Map<String, XmlSchema.XmlComposite> compMap,
                                  Map<String, XmlSchema.XmlEnum> enumMap) {
        b.append("message ").append(m.name).append(" {\n");
        b.append("  option (sbe.template_id) = ").append(m.id).append(";\n");
        for (XmlSchema.XmlField f : m.fields) writeProtoField(b, f, typeMap, compMap, enumMap, "  ");
        for (XmlSchema.XmlGroup g : m.groups) writeProtoGroup(b, g, typeMap, compMap, enumMap, "  ");
        b.append("}\n\n");
    }

    static void writeProtoField(StringBuilder b, XmlSchema.XmlField f,
                                Map<String, XmlSchema.XmlType> typeMap,
                                Map<String, XmlSchema.XmlComposite> compMap,
                                Map<String, XmlSchema.XmlEnum> enumMap,
                                String indent) {
        String name = camelToSnake(f.name);
        if (enumMap.containsKey(f.type) || compMap.containsKey(f.type)) {
            b.append(indent).append(f.type).append(' ').append(name).append(" = ").append(f.id).append(";\n");
            return;
        }
        XmlSchema.XmlType t = typeMap.get(f.type);
        if (t != null) {
            String[] mt = resolveTypeToProto(t.primitiveType, t.length);
            if (!mt[1].isEmpty()) b.append(indent).append(mt[0]).append(' ').append(name).append(" = ").append(f.id).append(" [").append(mt[1]).append("];\n");
            else b.append(indent).append(mt[0]).append(' ').append(name).append(" = ").append(f.id).append(";\n");
            return;
        }
        b.append(indent).append(f.type).append(' ').append(name).append(" = ").append(f.id).append(";\n");
    }

    static void writeProtoGroup(StringBuilder b, XmlSchema.XmlGroup g,
                                Map<String, XmlSchema.XmlType> typeMap,
                                Map<String, XmlSchema.XmlComposite> compMap,
                                Map<String, XmlSchema.XmlEnum> enumMap,
                                String indent) {
        String msgName = singularPascal(g.name);
        b.append(indent).append("message ").append(msgName).append(" {\n");
        for (XmlSchema.XmlField f : g.fields) writeProtoField(b, f, typeMap, compMap, enumMap, indent + "  ");
        b.append(indent).append("}\n");
        b.append(indent).append("repeated ").append(msgName).append(' ').append(camelToSnake(g.name))
                .append(" = ").append(g.id).append(";\n");
    }

    static String[] resolveTypeToProto(String primitive, int length) {
        return switch (primitive) {
            case "int8"   -> new String[]{"int32", "(sbe.encoding) = \"int8\""};
            case "int16"  -> new String[]{"int32", "(sbe.encoding) = \"int16\""};
            case "int32"  -> new String[]{"int32", ""};
            case "int64"  -> new String[]{"int64", ""};
            case "uint8"  -> new String[]{"uint32", "(sbe.encoding) = \"uint8\""};
            case "uint16" -> new String[]{"uint32", "(sbe.encoding) = \"uint16\""};
            case "uint32" -> new String[]{"uint32", ""};
            case "uint64" -> new String[]{"uint64", ""};
            case "float"  -> new String[]{"float", ""};
            case "double" -> new String[]{"double", ""};
            case "char"   -> new String[]{"string", "(sbe.length) = " + (length > 0 ? length : 1)};
            default -> new String[]{primitive, ""};
        };
    }

    // --- .proto → XML ---------------------------------------------------

    public static String protoToXml(FileDescriptor... files) {
        if (files.length == 0) throw new IllegalArgumentException("sbe: no files provided");
        FileDescriptor fd = files[0];
        Integer schemaId = SbeAnnotations.fileUint32(fd, SbeAnnotations.EXT_SCHEMA_ID);
        if (schemaId == null) throw new IllegalArgumentException("sbe: file " + fd.getName() + " missing (sbe.schema_id)");
        Integer version = SbeAnnotations.fileUint32(fd, SbeAnnotations.EXT_VERSION);

        TreeSet<Integer> strLengths = new TreeSet<>();
        List<Descriptor> composites = new ArrayList<>();
        Set<String> compSeen = new HashSet<>();
        List<EnumDescriptor> enums = new ArrayList<>();
        Set<String> enumSeen = new HashSet<>();

        for (EnumDescriptor ed : fd.getEnumTypes()) { enums.add(ed); enumSeen.add(ed.getFullName()); }

        for (Descriptor md : fd.getMessageTypes()) {
            Integer tid = SbeAnnotations.messageUint32(md, SbeAnnotations.EXT_TEMPLATE_ID);
            if (tid != null) collectXmlTypes(md, strLengths, composites, compSeen, enums, enumSeen);
            else if (compSeen.add(md.getFullName())) composites.add(md);
        }

        StringBuilder b = new StringBuilder();
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        b.append("<sbe:messageSchema xmlns:sbe=\"http://fixprotocol.io/2016/sbe\"\n");
        b.append("                   package=\"").append(fd.getPackage()).append("\"\n");
        b.append("                   id=\"").append(schemaId).append("\"\n");
        b.append("                   version=\"").append(version == null ? 0 : version).append("\"\n");
        b.append("                   byteOrder=\"littleEndian\">\n");
        b.append("    <types>\n");
        b.append("        <composite name=\"messageHeader\">\n");
        b.append("            <type name=\"blockLength\" primitiveType=\"uint16\"/>\n");
        b.append("            <type name=\"templateId\" primitiveType=\"uint16\"/>\n");
        b.append("            <type name=\"schemaId\" primitiveType=\"uint16\"/>\n");
        b.append("            <type name=\"version\" primitiveType=\"uint16\"/>\n");
        b.append("        </composite>\n");
        b.append("        <composite name=\"groupSizeEncoding\">\n");
        b.append("            <type name=\"blockLength\" primitiveType=\"uint16\"/>\n");
        b.append("            <type name=\"numInGroup\" primitiveType=\"uint16\"/>\n");
        b.append("        </composite>\n");
        for (int l : strLengths) b.append("        <type name=\"str").append(l).append("\" primitiveType=\"char\" length=\"").append(l).append("\"/>\n");
        for (EnumDescriptor ed : enums) writeXmlEnum(b, ed);
        for (Descriptor md : composites) writeXmlComposite(b, md);
        b.append("    </types>\n");
        for (Descriptor md : fd.getMessageTypes()) {
            Integer tid = SbeAnnotations.messageUint32(md, SbeAnnotations.EXT_TEMPLATE_ID);
            if (tid != null) writeXmlMessage(b, md, tid);
        }
        b.append("</sbe:messageSchema>\n");
        return b.toString();
    }

    static void collectXmlTypes(Descriptor md, TreeSet<Integer> strLens, List<Descriptor> composites,
                                Set<String> compSeen, List<EnumDescriptor> enums, Set<String> enumSeen) {
        for (EnumDescriptor ed : md.getEnumTypes()) {
            if (enumSeen.add(ed.getFullName())) enums.add(ed);
        }
        for (FieldDescriptor f : md.getFields()) {
            if (f.getType() == FieldDescriptor.Type.STRING || f.getType() == FieldDescriptor.Type.BYTES) {
                Integer len = SbeAnnotations.fieldUint32(f, SbeAnnotations.EXT_LENGTH);
                if (len != null) strLens.add(len);
            }
            if (f.getJavaType() == FieldDescriptor.JavaType.ENUM) {
                EnumDescriptor ed = f.getEnumType();
                if (enumSeen.add(ed.getFullName())) enums.add(ed);
            }
            if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                if (f.isRepeated()) collectXmlTypes(f.getMessageType(), strLens, composites, compSeen, enums, enumSeen);
                else {
                    Descriptor sub = f.getMessageType();
                    if (compSeen.add(sub.getFullName())) {
                        composites.add(sub);
                        collectXmlTypes(sub, strLens, composites, compSeen, enums, enumSeen);
                    }
                }
            }
        }
    }

    static void writeXmlEnum(StringBuilder b, EnumDescriptor ed) {
        String name = ed.getName();
        b.append("        <enum name=\"").append(name).append("\" encodingType=\"uint8\">\n");
        for (EnumValueDescriptor v : ed.getValues()) {
            String stripped = stripEnumPrefix(v.getName(), name);
            b.append("            <validValue name=\"").append(stripped).append("\">").append(v.getNumber()).append("</validValue>\n");
        }
        b.append("        </enum>\n");
    }

    static void writeXmlComposite(StringBuilder b, Descriptor md) {
        b.append("        <composite name=\"").append(md.getName()).append("\">\n");
        List<FieldDescriptor> sorted = new ArrayList<>(md.getFields());
        sorted.sort(Comparator.comparingInt(FieldDescriptor::getNumber));
        for (FieldDescriptor f : sorted) {
            String fieldName = snakeToCamel(f.getName());
            SbeTypeInfo info = protoFieldToSbe(f);
            if (info.length > 0) b.append("            <type name=\"").append(fieldName).append("\" primitiveType=\"").append(info.primitiveType).append("\" length=\"").append(info.length).append("\"/>\n");
            else b.append("            <type name=\"").append(fieldName).append("\" primitiveType=\"").append(info.primitiveType).append("\"/>\n");
        }
        b.append("        </composite>\n");
    }

    static void writeXmlMessage(StringBuilder b, Descriptor md, int templateId) {
        b.append("    <sbe:message name=\"").append(md.getName()).append("\" id=\"").append(templateId).append("\">\n");
        List<FieldDescriptor> sorted = new ArrayList<>(md.getFields());
        sorted.sort(Comparator.comparingInt(FieldDescriptor::getNumber));
        for (FieldDescriptor f : sorted) {
            if (f.isRepeated() && f.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                writeXmlGroup(b, f, "        ");
            } else {
                writeXmlField(b, f, "        ");
            }
        }
        b.append("    </sbe:message>\n");
    }

    static void writeXmlField(StringBuilder b, FieldDescriptor fd, String indent) {
        String fn = snakeToCamel(fd.getName());
        if (fd.getJavaType() == FieldDescriptor.JavaType.ENUM) {
            b.append(indent).append("<field name=\"").append(fn).append("\" id=\"").append(fd.getNumber()).append("\" type=\"").append(fd.getEnumType().getName()).append("\"/>\n");
            return;
        }
        if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE && !fd.isRepeated()) {
            b.append(indent).append("<field name=\"").append(fn).append("\" id=\"").append(fd.getNumber()).append("\" type=\"").append(fd.getMessageType().getName()).append("\"/>\n");
            return;
        }
        SbeTypeInfo info = protoFieldToSbe(fd);
        if (info.length > 0) b.append(indent).append("<field name=\"").append(fn).append("\" id=\"").append(fd.getNumber()).append("\" type=\"str").append(info.length).append("\"/>\n");
        else b.append(indent).append("<field name=\"").append(fn).append("\" id=\"").append(fd.getNumber()).append("\" type=\"").append(info.xmlType).append("\"/>\n");
    }

    static void writeXmlGroup(StringBuilder b, FieldDescriptor fd, String indent) {
        b.append(indent).append("<group name=\"").append(snakeToCamel(fd.getName())).append("\" id=\"").append(fd.getNumber()).append("\">\n");
        Descriptor md = fd.getMessageType();
        List<FieldDescriptor> sorted = new ArrayList<>(md.getFields());
        sorted.sort(Comparator.comparingInt(FieldDescriptor::getNumber));
        for (FieldDescriptor f : sorted) writeXmlField(b, f, indent + "    ");
        b.append(indent).append("</group>\n");
    }

    record SbeTypeInfo(String primitiveType, String xmlType, int length) {}

    static SbeTypeInfo protoFieldToSbe(FieldDescriptor fd) {
        String enc = SbeAnnotations.fieldString(fd, SbeAnnotations.EXT_ENCODING);
        if (enc != null) return new SbeTypeInfo(enc, enc, 0);
        if (fd.getType() == FieldDescriptor.Type.STRING || fd.getType() == FieldDescriptor.Type.BYTES) {
            Integer len = SbeAnnotations.fieldUint32(fd, SbeAnnotations.EXT_LENGTH);
            return new SbeTypeInfo("char", "char", len == null ? 0 : len);
        }
        return switch (fd.getType()) {
            case BOOL -> new SbeTypeInfo("uint8", "uint8", 0);
            case INT32, SINT32, SFIXED32 -> new SbeTypeInfo("int32", "int32", 0);
            case INT64, SINT64, SFIXED64 -> new SbeTypeInfo("int64", "int64", 0);
            case UINT32, FIXED32 -> new SbeTypeInfo("uint32", "uint32", 0);
            case UINT64, FIXED64 -> new SbeTypeInfo("uint64", "uint64", 0);
            case FLOAT  -> new SbeTypeInfo("float", "float", 0);
            case DOUBLE -> new SbeTypeInfo("double", "double", 0);
            default -> new SbeTypeInfo("uint8", "uint8", 0);
        };
    }

    // --- name conversion -------------------------------------------------

    static String camelToSnake(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) {
                    char prev = s.charAt(i - 1);
                    if (Character.isLowerCase(prev)) out.append('_');
                    else if (i + 1 < s.length() && Character.isLowerCase(s.charAt(i + 1))) out.append('_');
                }
                out.append(Character.toLowerCase(c));
            } else out.append(c);
        }
        return out.toString();
    }

    static String snakeToCamel(String s) {
        StringBuilder out = new StringBuilder();
        boolean upperNext = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_') { upperNext = i > 0; continue; }
            if (upperNext) { out.append(Character.toUpperCase(c)); upperNext = false; }
            else out.append(c);
        }
        return out.toString();
    }

    static String camelToScreamingSnake(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isUpperCase(c) && i > 0 && Character.isLowerCase(s.charAt(i - 1))) out.append('_');
            out.append(Character.toUpperCase(c));
        }
        return out.toString();
    }

    static String screamingSnakeToPascal(String s) {
        String[] parts = s.toLowerCase().split("_");
        StringBuilder out = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            out.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return out.toString();
    }

    static String stripEnumPrefix(String value, String enumName) {
        String prefix = camelToScreamingSnake(enumName) + "_";
        if (value.startsWith(prefix)) return screamingSnakeToPascal(value.substring(prefix.length()));
        return screamingSnakeToPascal(value);
    }

    static String singularPascal(String s) {
        if (s.isEmpty()) return s;
        if (s.endsWith("ies") && s.length() > 3) s = s.substring(0, s.length() - 3) + "y";
        else if (s.endsWith("s") && !s.endsWith("ss") && s.length() > 1) s = s.substring(0, s.length() - 1);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
