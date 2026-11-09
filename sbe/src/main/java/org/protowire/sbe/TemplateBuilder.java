// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;


import org.protowire.sbe.runtime.SbeKinds;
import org.protowire.sbe.runtime.SbeTemplateCheck;

import static org.protowire.sbe.runtime.SbeConstants.*;

/**
 * Compiles a protobuf {@link Descriptor} into the descriptor-free
 * {@link MessageTemplate} that the wire codec consumes. {@code field
 * number} + {@code kind} (the {@code FieldDescriptorProto.Type} integer)
 * are baked into each {@link FieldTemplate} / {@link GroupTemplate} —
 * the runtime never reaches back through a {@link FieldDescriptor} after
 * this step.
 */
final class TemplateBuilder {
    private TemplateBuilder() {}

    static MessageTemplate build(Descriptor md, int schemaId, int version) {
        Integer tid = SbeAnnotations.messageUint32(md, SbeAnnotations.EXT_TEMPLATE_ID);
        if (tid == null) throw new IllegalStateException("sbe: " + md.getFullName() + " missing template_id");
        if (Integer.toUnsignedLong(tid) > SbeKinds.MAX_UINT16) {
            throw new IllegalArgumentException("sbe: message " + md.getFullName() + ": (sbe.template_id) = " + Integer.toUnsignedString(tid)
                    + " is past " + SbeKinds.MAX_UINT16 + ", the largest templateId SBE can state");
        }

        List<FieldTemplate> fields = new ArrayList<>();
        List<GroupTemplate> groups = new ArrayList<>();

        int offset = 0;
        for (FieldDescriptor fd : sortedFields(md)) {
            String at = md.getFullName() + "." + fd.getName();
            if (fd.isMapField()) throw new IllegalArgumentException("sbe: map field " + at + " not supported");
            OneofDescriptor oo = fd.getContainingOneof();
            if (oo != null && !oo.isSynthetic()) throw new IllegalArgumentException("sbe: oneof field " + at + " not supported");

            if (fd.isRepeated() && fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                groups.add(buildGroup(fd));
                continue;
            }
            if (fd.isRepeated()) throw new IllegalArgumentException("sbe: repeated scalar field " + at + " not supported; wrap in a message");

            FieldTemplate ft;
            if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                int[] sz = new int[1];
                List<FieldTemplate> sub = buildComposite(fd.getMessageType(), sz, "sbe: composite " + at + ": ");
                ft = new FieldTemplate(fd.getNumber(), fd.getName(), fd.getFullName(), kindOf(fd), offset, sz[0], "", sub);
            } else {
                int[] szRef = new int[1];
                String enc = fieldEncodingSize(fd, szRef, "sbe: field " + at + ": ");
                ft = new FieldTemplate(fd.getNumber(), fd.getName(), fd.getFullName(), kindOf(fd), offset, szRef[0], enc, null);
            }
            SbeTemplateCheck.rootField(ft);
            fields.add(ft);
            offset += ft.size;
        }

        ViewSchema vs = buildViewSchema(fields, groups);
        return new MessageTemplate(tid, schemaId, version, offset, List.copyOf(fields), List.copyOf(groups), vs);
    }

    static GroupTemplate buildGroup(FieldDescriptor fd) {
        Descriptor md = fd.getMessageType();
        List<FieldTemplate> fields = new ArrayList<>();
        int offset = 0;
        for (FieldDescriptor f : sortedFields(md)) {
            if (f.isMapField()) throw new IllegalArgumentException("sbe: map field in group " + md.getFullName() + " not supported");
            if (f.isRepeated()) throw new IllegalArgumentException("sbe: nested repeated field in group " + md.getFullName() + " not supported");
            String at = md.getFullName() + "." + f.getName();
            FieldTemplate ft;
            if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                int[] sz = new int[1];
                List<FieldTemplate> sub = buildComposite(f.getMessageType(), sz, "sbe: composite in group " + at + ": ");
                ft = new FieldTemplate(f.getNumber(), f.getName(), f.getFullName(), kindOf(f), offset, sz[0], "", sub);
            } else {
                int[] szRef = new int[1];
                String enc = fieldEncodingSize(f, szRef, "sbe: group field " + at + ": ");
                ft = new FieldTemplate(f.getNumber(), f.getName(), f.getFullName(), kindOf(f), offset, szRef[0], enc, null);
            }
            SbeTemplateCheck.groupField(ft);
            fields.add(ft);
            offset += ft.size;
        }
        return new GroupTemplate(fd.getNumber(), fd.getName(), fd.getFullName(), offset, List.copyOf(fields));
    }

    /**
     * A composite's fields. {@code prefix} is protowire-go's wrapping at the
     * outermost composite ({@code sbe: composite M.f: }); a nested composite's
     * errors are wrapped by it alone.
     */
    static List<FieldTemplate> buildComposite(Descriptor md, int[] outSize, String prefix) {
        List<FieldTemplate> fields = new ArrayList<>();
        int offset = 0;
        for (FieldDescriptor f : sortedFields(md)) {
            if (f.isRepeated() || f.isMapField()) {
                throw new IllegalArgumentException(prefix + "composite " + md.getFullName() + " contains list/map field " + f.getName());
            }
            // A composite is a fixed layout of all its fields; a oneof sets
            // one of them (protowire-go#250, #198).
            OneofDescriptor oo = f.getContainingOneof();
            if (oo != null && !oo.isSynthetic()) {
                throw new IllegalArgumentException(prefix + "composite " + md.getFullName() + " contains oneof field " + f.getName());
            }
            String at = md.getFullName() + "." + f.getName();
            if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                int[] sz = new int[1];
                List<FieldTemplate> sub = buildComposite(f.getMessageType(), sz, prefix);
                fields.add(new FieldTemplate(f.getNumber(), f.getName(), f.getFullName(), kindOf(f), offset, sz[0], "", sub));
                offset += sz[0];
                continue;
            }
            int[] szRef = new int[1];
            String enc = fieldEncodingSize(f, szRef, prefix + "composite field " + at + ": ");
            fields.add(new FieldTemplate(f.getNumber(), f.getName(), f.getFullName(), kindOf(f), offset, szRef[0], enc, null));
            offset += szRef[0];
        }
        outSize[0] = offset;
        return List.copyOf(fields);
    }

    /** The SBE primitive and its size for a scalar field; errors start with {@code prefix}, as Go wraps them. */
    static String fieldEncodingSize(FieldDescriptor fd, int[] outSize, String prefix) {
        String override = SbeAnnotations.fieldString(fd, SbeAnnotations.EXT_ENCODING);
        if (override != null) {
            return switch (override) {
                case ENC_INT8, ENC_UINT8 -> { outSize[0] = 1; yield override; }
                case ENC_INT16, ENC_UINT16 -> { outSize[0] = 2; yield override; }
                case ENC_INT32, ENC_UINT32, ENC_FLOAT -> { outSize[0] = 4; yield override; }
                case ENC_INT64, ENC_UINT64, ENC_DOUBLE -> { outSize[0] = 8; yield override; }
                default -> {
                    // A string or bytes field, or a crossing between integer
                    // and floating point, is the template check's to name.
                    if (fd.getType() == FieldDescriptor.Type.STRING || fd.getType() == FieldDescriptor.Type.BYTES) {
                        outSize[0] = 0;
                        yield override;
                    }
                    throw new IllegalArgumentException(prefix + "unknown encoding \"" + override + "\"");
                }
            };
        }
        return switch (fd.getType()) {
            case BOOL -> { outSize[0] = 1; yield ENC_UINT8; }
            case INT32, SINT32, SFIXED32 -> { outSize[0] = 4; yield ENC_INT32; }
            case INT64, SINT64, SFIXED64 -> { outSize[0] = 8; yield ENC_INT64; }
            case UINT32, FIXED32 -> { outSize[0] = 4; yield ENC_UINT32; }
            case UINT64, FIXED64 -> { outSize[0] = 8; yield ENC_UINT64; }
            case FLOAT  -> { outSize[0] = 4; yield ENC_FLOAT; }
            case DOUBLE -> { outSize[0] = 8; yield ENC_DOUBLE; }
            case ENUM   -> { outSize[0] = 1; yield ENC_UINT8; }
            case STRING, BYTES -> {
                Integer len = SbeAnnotations.fieldUint32(fd, SbeAnnotations.EXT_LENGTH);
                String kind = fd.getType() == FieldDescriptor.Type.STRING ? "string" : "bytes";
                if (len == null) throw new IllegalArgumentException(prefix + kind + " field requires (sbe.length) annotation");
                if (Integer.toUnsignedLong(len) > SbeKinds.MAX_UINT16) {
                    throw new IllegalArgumentException(prefix + "(sbe.length) = " + Integer.toUnsignedString(len) + " is past "
                            + SbeKinds.MAX_UINT16 + ", the largest blockLength SBE can state");
                }
                outSize[0] = len;
                yield ENC_CHAR;
            }
            default -> throw new IllegalArgumentException(prefix + "unsupported proto kind " + SbeKinds.kindName(kindOf(fd)));
        };
    }

    static ViewSchema buildViewSchema(List<FieldTemplate> fields, List<GroupTemplate> groups) {
        ViewSchema vs = new ViewSchema();
        for (FieldTemplate ft : fields) {
            vs.fields.put(ft.name, ft);
            if (ft.composite != null) ft.compositeView = buildViewSchema(ft.composite, List.of());
        }
        for (GroupTemplate gt : groups) {
            ViewSchema gvs = buildViewSchema(gt.fields, List.of());
            vs.groupOrder.add(new ViewSchema.GroupInfo(gt.name, gvs));
        }
        return vs;
    }

    static List<FieldDescriptor> sortedFields(Descriptor md) {
        List<FieldDescriptor> out = new ArrayList<>(md.getFields());
        out.sort(Comparator.comparingInt(FieldDescriptor::getNumber));
        return out;
    }

    /** Maps a {@link FieldDescriptor.Type} to the stable wire-of-the-meta-graph integer. */
    static int kindOf(FieldDescriptor fd) {
        return fd.getType().toProto().getNumber();
    }
}
