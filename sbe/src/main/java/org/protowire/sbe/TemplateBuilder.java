package org.protowire.sbe;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.protowire.sbe.Codec.*;

final class TemplateBuilder {
    private TemplateBuilder() {}

    static MessageTemplate build(Descriptor md, int schemaId, int version) {
        Integer tid = SbeAnnotations.messageUint32(md, SbeAnnotations.EXT_TEMPLATE_ID);
        if (tid == null) throw new IllegalStateException("sbe: " + md.getFullName() + " missing template_id");

        List<FieldTemplate> fields = new ArrayList<>();
        List<GroupTemplate> groups = new ArrayList<>();

        int offset = 0;
        for (FieldDescriptor fd : sortedFields(md)) {
            if (fd.isMapField()) throw new IllegalArgumentException("sbe: map field " + md.getFullName() + "." + fd.getName() + " not supported");
            OneofDescriptor oo = fd.getContainingOneof();
            if (oo != null && !oo.isSynthetic()) throw new IllegalArgumentException("sbe: oneof field " + md.getFullName() + "." + fd.getName() + " not supported");

            if (fd.isRepeated() && fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                groups.add(buildGroup(fd));
                continue;
            }
            if (fd.isRepeated()) throw new IllegalArgumentException("sbe: repeated scalar " + md.getFullName() + "." + fd.getName() + " not supported");

            if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                int[] sz = new int[]{0};
                List<FieldTemplate> sub = buildComposite(fd.getMessageType(), sz);
                fields.add(new FieldTemplate(fd, offset, sz[0], "", sub));
                offset += sz[0];
                continue;
            }
            int[] szRef = new int[1];
            String enc = fieldEncodingSize(fd, szRef);
            fields.add(new FieldTemplate(fd, offset, szRef[0], enc, null));
            offset += szRef[0];
        }

        ViewSchema vs = buildViewSchema(fields, groups);
        return new MessageTemplate(tid, schemaId, version, offset, List.copyOf(fields), List.copyOf(groups), vs);
    }

    static GroupTemplate buildGroup(FieldDescriptor fd) {
        Descriptor md = fd.getMessageType();
        List<FieldTemplate> fields = new ArrayList<>();
        int offset = 0;
        for (FieldDescriptor f : sortedFields(md)) {
            if (f.isMapField() || f.isRepeated()) throw new IllegalArgumentException("sbe: nested repeated/map in group " + md.getFullName());
            if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                int[] sz = new int[1];
                List<FieldTemplate> sub = buildComposite(f.getMessageType(), sz);
                fields.add(new FieldTemplate(f, offset, sz[0], "", sub));
                offset += sz[0];
                continue;
            }
            int[] szRef = new int[1];
            String enc = fieldEncodingSize(f, szRef);
            fields.add(new FieldTemplate(f, offset, szRef[0], enc, null));
            offset += szRef[0];
        }
        return new GroupTemplate(fd, offset, List.copyOf(fields));
    }

    static List<FieldTemplate> buildComposite(Descriptor md, int[] outSize) {
        List<FieldTemplate> fields = new ArrayList<>();
        int offset = 0;
        for (FieldDescriptor f : sortedFields(md)) {
            if (f.isRepeated() || f.isMapField()) throw new IllegalArgumentException("sbe: composite contains list/map field " + f.getName());
            if (f.getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                int[] sz = new int[1];
                List<FieldTemplate> sub = buildComposite(f.getMessageType(), sz);
                fields.add(new FieldTemplate(f, offset, sz[0], "", sub));
                offset += sz[0];
                continue;
            }
            int[] szRef = new int[1];
            String enc = fieldEncodingSize(f, szRef);
            fields.add(new FieldTemplate(f, offset, szRef[0], enc, null));
            offset += szRef[0];
        }
        outSize[0] = offset;
        return List.copyOf(fields);
    }

    static String fieldEncodingSize(FieldDescriptor fd, int[] outSize) {
        String override = SbeAnnotations.fieldString(fd, SbeAnnotations.EXT_ENCODING);
        if (override != null) {
            return switch (override) {
                case ENC_INT8, ENC_UINT8 -> { outSize[0] = 1; yield override; }
                case ENC_INT16, ENC_UINT16 -> { outSize[0] = 2; yield override; }
                case ENC_INT32, ENC_UINT32, ENC_FLOAT -> { outSize[0] = 4; yield override; }
                case ENC_INT64, ENC_UINT64, ENC_DOUBLE -> { outSize[0] = 8; yield override; }
                default -> throw new IllegalArgumentException("unknown sbe encoding: " + override);
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
                if (len == null) throw new IllegalArgumentException("string/bytes field " + fd.getName() + " requires (sbe.length)");
                outSize[0] = len;
                yield ENC_CHAR;
            }
            default -> throw new IllegalArgumentException("unsupported proto type: " + fd.getType());
        };
    }

    static ViewSchema buildViewSchema(List<FieldTemplate> fields, List<GroupTemplate> groups) {
        ViewSchema vs = new ViewSchema();
        for (FieldTemplate ft : fields) {
            vs.fields.put(ft.fd.getName(), ft);
            if (ft.composite != null) ft.compositeView = buildViewSchema(ft.composite, List.of());
        }
        for (GroupTemplate gt : groups) {
            ViewSchema gvs = buildViewSchema(gt.fields, List.of());
            vs.groupOrder.add(new ViewSchema.GroupInfo(gt.fd.getName(), gvs));
        }
        return vs;
    }

    static List<FieldDescriptor> sortedFields(Descriptor md) {
        List<FieldDescriptor> out = new ArrayList<>(md.getFields());
        out.sort(Comparator.comparingInt(FieldDescriptor::getNumber));
        return out;
    }
}
