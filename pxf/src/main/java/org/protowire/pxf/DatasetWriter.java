// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;
import com.google.protobuf.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Writes {@code @dataset} tables, as protowire-go's
 * {@code AppendDatasetHeader} and {@code AppendDatasetRow} do (#204;
 * encoding/pxf/dataset_write.go, v1.17.0). See {@link Pxf#appendDatasetRow}
 * for the rules a cell follows; each is Go's, so a table this writes reads
 * back through {@link DatasetReader} to the messages written.
 */
final class DatasetWriter {
    private DatasetWriter() {}

    /** One resolved column: its name as written and the fields from the row type down to the leaf. */
    private record Column(String name, List<FieldDescriptor> path, List<FieldDescriptor> siblings) {
        FieldDescriptor leaf() { return path.get(path.size() - 1); }

        /** The message holding the leaf, or null when a parent on the way is unset. */
        Message parentIn(Message m) {
            for (int i = 0; i < path.size() - 1; i++) {
                FieldDescriptor fd = path.get(i);
                if (!m.hasField(fd)) return null;
                m = (Message) m.getField(fd);
            }
            return m;
        }

        /** The dotted names of the parents: "a" and "a.b" for "a.b.c". */
        List<String> prefixes() {
            List<String> out = new ArrayList<>();
            for (int i = name.indexOf('.'); i >= 0; i = name.indexOf('.', i + 1)) out.add(name.substring(0, i));
            return out;
        }
    }

    static StringBuilder appendHeader(StringBuilder dst, Descriptor desc, List<String> columns) {
        columns(desc, columns);
        StringBuilder sb = new StringBuilder("@dataset ").append(desc.getFullName()).append(" (");
        sb.append(String.join(", ", columns)).append(")\n");
        return dst.append(sb);
    }

    static StringBuilder appendRow(StringBuilder dst, Message msg, List<String> columns) {
        Descriptor desc = msg.getDescriptorForType();
        List<Column> cols = columns(desc, columns);
        Encoder enc = new Encoder(MarshalOptions.defaults(), nullSet(msg));
        Set<String> filled = new HashSet<>(); // path prefixes with a non-empty cell
        enc.buf.append('(');
        for (int i = 0; i < cols.size(); i++) {
            Column c = cols.get(i);
            if (i > 0) enc.buf.append(", ");
            Message parent = c.parentIn(msg);
            if (parent == null) continue; // a parent is unset: an empty cell, and it reads back unset
            int before = enc.buf.length();
            try {
                writeCell(enc, parent, c.leaf(), c.name(), c.siblings());
            } catch (PxfException e) {
                throw error("pxf: @dataset column \"" + c.name() + "\": " + rawMessage(e));
            }
            if (enc.buf.length() > before) filled.addAll(c.prefixes());
        }
        enc.buf.append(")\n");
        checkRowPresence(desc, cols, filled);
        return dst.append(enc.buf);
    }

    // -- columns --------------------------------------------------------------

    /** {@code columns} resolved against {@code desc}, refusing what a row cannot carry; see {@link Pxf#appendDatasetHeader}. */
    private static List<Column> columns(Descriptor desc, List<String> columns) {
        if (columns.isEmpty()) throw error("pxf: @dataset needs at least one column");
        // A schema the reader would refuse is refused here instead of
        // written: a field with a reserved name makes the header unreadable,
        // and a oneof with two defaulted members would get two null cells.
        String violations = SchemaValidator.asMessage(SchemaValidator.validateDescriptor(desc));
        if (violations != null) throw error("pxf: @dataset " + desc.getFullName() + ": " + violations);
        // The reader's rules for column paths (protowire#342), with its
        // messages, so the writer cannot write a header its reader rejects.
        for (int i = 0; i < columns.size(); i++) {
            String problem = Parser.columnPathProblem(columns.subList(0, i), columns.get(i));
            if (problem != null) throw error("pxf: " + problem);
        }
        String problem = BindRow.columnSchemaProblem(desc, columns);
        if (problem != null) throw error("pxf: " + problem);

        List<List<FieldDescriptor>> paths = new ArrayList<>();
        for (String c : columns) {
            List<FieldDescriptor> path = new ArrayList<>();
            Descriptor md = desc;
            String[] segs = c.split("\\.", -1);
            for (int k = 0; k < segs.length; k++) {
                FieldDescriptor fd = md.findFieldByName(segs[k]); // columnSchemaProblem resolved every segment
                path.add(fd);
                if (k < segs.length - 1) md = fd.getMessageType();
            }
            paths.add(List.copyOf(path));
        }
        List<Column> cols = new ArrayList<>();
        for (int i = 0; i < columns.size(); i++) {
            List<FieldDescriptor> p = paths.get(i);
            List<FieldDescriptor> siblings = new ArrayList<>();
            for (List<FieldDescriptor> o : paths) {
                if (o.size() == p.size() && o.subList(0, o.size() - 1).equals(p.subList(0, p.size() - 1))) {
                    siblings.add(o.get(o.size() - 1));
                }
            }
            cols.add(new Column(columns.get(i), p, List.copyOf(siblings)));
        }
        for (FieldDescriptor fd : desc.getFields()) {
            if (!Annotations.isRequired(fd)) continue;
            if (cols.stream().noneMatch(c -> c.path().get(0) == fd)) {
                throw error("pxf: @dataset columns omit \"" + fd.getName() + "\", which is (pxf.required): every row would be rejected");
            }
        }
        return cols;
    }

    // -- cells ----------------------------------------------------------------

    /** The cell for {@code fd} of {@code m}, in a row whose columns under {@code m} are {@code cols}; see {@link Pxf#appendDatasetRow}. */
    private static void writeCell(Encoder enc, Message m, FieldDescriptor fd, String path, List<FieldDescriptor> cols) {
        boolean isMsg = fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE;
        if (enc.nullSetContains(path) && FastDecoder.nullable(fd)) {
            enc.buf.append("null");
            return;
        }
        boolean has = fd.isRepeated() ? m.getRepeatedFieldCount(fd) > 0 : m.hasField(fd);
        if (has) {
            if (fd.isRepeated()) {
                writeValueCell(enc, m, fd);
            } else if (!isMsg) {
                enc.writeScalar(fd, m.getField(fd));
            } else if (!Encoder.hasLiteral((Message) m.getField(fd))) {
                writeValueCell(enc, m, fd);
            } else {
                enc.writeLiteral((Message) m.getField(fd));
            }
            return;
        }
        boolean required = Annotations.isRequired(fd);
        boolean hasDefault = Annotations.getDefault(fd) != null;
        if (!hasDefault && !required) return; // empty: absent, and absent is what it reads back as
        if (fd.isRepeated()) {
            enc.buf.append(fd.isMapField() ? "{}" : "[]"); // only required gets here: a default is refused on these
            return;
        }
        if (!fd.hasPresence()) {
            enc.writeScalar(fd, m.getField(fd)); // the zero value, explicitly
            return;
        }
        if (required) throw error("unset, and the field is (pxf.required)");
        OneofDescriptor od = fd.getRealContainingOneof();
        if (od != null) {
            FieldDescriptor set = m.getOneofFieldDescriptor(od);
            // A member written in this row keeps the default off.
            if (set != null && cols.contains(set)) return;
        }
        // An unset scalar with presence is an empty cell, which reads back
        // as its default, as protowire-go writes it.
        if (!isMsg) return;
        enc.buf.append("null");
    }

    /**
     * A list, map or block value as one cell (protowire#341): the text
     * marshal writes for the field, on one line, so a table keeps one row per
     * line. The field is encoded at no indent, the field name that leads it
     * is cut, and its newlines become spaces, which is safe because marshal
     * never writes a raw newline inside a token.
     */
    private static void writeValueCell(Encoder enc, Message m, FieldDescriptor fd) {
        // No indent, as protowire-go's cell encoder has none: each newline
        // then becomes one space.
        Encoder inner = new Encoder(MarshalOptions.defaults().withIndent(""), enc.nullSet());
        if (fd.isMapField()) inner.encodeMap(m, fd, 0);
        else if (fd.isRepeated()) inner.encodeList(m, fd, 0);
        else inner.encodeMessageField(fd, (Message) m.getField(fd), 0);
        String out = inner.buf.toString();
        out = trimPrefix(out, fd.getName());
        out = trimPrefix(out, " = ");
        out = trimPrefix(out, " ");
        if (out.endsWith("\n")) out = out.substring(0, out.length() - 1);
        enc.buf.append(out.replace('\n', ' '));
    }

    private static String trimPrefix(String s, String prefix) {
        return s.startsWith(prefix) ? s.substring(prefix.length()) : s;
    }

    /**
     * Refuses a row the reader would reject for a missing (pxf.required)
     * field below the root, the one way a dotted column can make a row
     * unreadable. A sub-message is present on reading exactly when a cell
     * under it is non-empty (protowire#342), so one present in the message
     * whose cells all come out empty reads back absent: accepted, unless it
     * is required. And a sub-message that reads back present needs each of
     * its required fields as a column, or under one.
     */
    private static void checkRowPresence(Descriptor desc, List<Column> cols, Set<String> filled) {
        check(desc, "", cols, filled);
    }

    private static void check(Descriptor md, String at, List<Column> cols, Set<String> filled) {
        for (FieldDescriptor fd : md.getFields()) {
            String path = at.isEmpty() ? fd.getName() : at + "." + fd.getName();
            boolean exact = false, prefix = false;
            for (Column c : cols) {
                if (c.name().equals(path)) {
                    exact = true;
                    prefix = false;
                    break;
                }
                if (c.name().startsWith(path + ".")) prefix = true;
            }
            boolean required = Annotations.isRequired(fd);
            if (prefix && !filled.contains(path) && required) {
                throw error("pxf: @dataset columns under \"" + path + "\" are all empty in this row, so it reads back unset,"
                        + " and it is (pxf.required)");
            }
            if (!at.isEmpty() && required && !exact && !prefix) {
                throw error("pxf: @dataset \"" + at + "\" reads back present, and its (pxf.required) field \"" + path
                        + "\" is not a column: the row would be rejected");
            }
            if (prefix && filled.contains(path)) check(fd.getMessageType(), path, cols, filled);
        }
    }

    // -- helpers ----------------------------------------------------------------

    /** The row's {@code _null} mask paths, as protowire-go's readNullMask reads them. */
    private static Set<String> nullSet(Message msg) {
        FieldDescriptor nfd = WellKnown.findNullMaskField(msg.getDescriptorForType());
        if (nfd == null || !msg.hasField(nfd)) return Collections.emptySet();
        Message fm = (Message) msg.getField(nfd);
        FieldDescriptor pathsFd = fm.getDescriptorForType().findFieldByName("paths");
        Set<String> out = new HashSet<>();
        for (int i = 0; i < fm.getRepeatedFieldCount(pathsFd); i++) out.add((String) fm.getRepeatedField(pathsFd, i));
        return out;
    }

    private static PxfException error(String msg) { return new PxfException(Position.UNKNOWN, msg); }

    /** A PxfException's message without the position it leads with. */
    private static String rawMessage(PxfException e) {
        String m = e.getMessage();
        String lead = e.position() + ": ";
        return m.startsWith(lead) ? m.substring(lead.length()) : m;
    }
}
