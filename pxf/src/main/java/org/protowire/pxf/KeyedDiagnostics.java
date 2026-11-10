// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.OneofDescriptor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link Pxf#keyedDiagnostics}: every keyed-repeated-field violation in a
 * parsed document (draft -01 §3.13), as positioned diagnostics. A port of
 * protowire-go's {@code KeyedDiagnostics} walker (#208).
 */
final class KeyedDiagnostics {
    private KeyedDiagnostics() {}

    /**
     * The key-field checks for the immediate body of one element of a keyed
     * repeated field, as the decoder's {@code KeyedElem}: an explicit
     * assignment to the key field must not be empty, and in the named form
     * must agree with the entry name, which no other binding may undo.
     */
    private record Elem(String field, FieldDescriptor keyFd, String entryName, boolean named) {
        KeyedException bindingConflict(FieldDescriptor fd, boolean isNull, Position pos) {
            if (!named) return null;
            if (fd.getName().equals(keyFd.getName())) {
                return isNull ? KeyedException.nullOnKey(pos, field, keyFd.getName(), entryName) : null;
            }
            OneofDescriptor oo = fd.getRealContainingOneof();
            if (oo != null && oo.equals(keyFd.getRealContainingOneof())) {
                return KeyedException.oneofSibling(pos, field, fd.getName(), fd.getContainingOneof().getName(), keyFd.getName(), entryName);
            }
            return null;
        }

        KeyedException checkExplicitKey(String value, Position pos) {
            if (value.isEmpty()) return KeyedException.emptyExplicitKey(pos, field, keyFd.getName());
            if (named && !value.equals(entryName)) return KeyedException.keyConflict(pos, field, keyFd.getName(), value, entryName);
            return null;
        }
    }

    static List<KeyedException> of(Ast.Document doc, Descriptor desc) {
        if (doc == null || desc == null) return List.of();
        List<KeyedException> out = new ArrayList<>();
        entries(doc.entries(), desc, null, out);
        // In source order; List.sort is stable.
        out.sort(Comparator.comparingInt(e -> e.position().offset()));
        return List.copyOf(out);
    }

    private static boolean isList(FieldDescriptor fd) {
        return fd.isRepeated() && !fd.isMapField();
    }

    private static boolean isMessage(FieldDescriptor fd) {
        return fd.getType() == FieldDescriptor.Type.MESSAGE;
    }

    private static void add(List<KeyedException> out, KeyedException e) {
        if (e != null) out.add(e);
    }

    private static void entries(List<Ast.Entry> entries, Descriptor desc, Elem ke, List<KeyedException> out) {
        for (Ast.Entry e : entries) {
            if (e instanceof Ast.Assignment n) {
                if (n.keyQuoted()) {
                    out.add(KeyedException.quotedNameUnkeyed(n.pos(), n.key()));
                    continue;
                }
                FieldDescriptor fd = desc.findFieldByName(n.key());
                if (fd == null) continue;
                if (ke != null) add(out, ke.bindingConflict(fd, n.value() instanceof Ast.NullVal, n.pos()));
                if (ke != null && fd.getName().equals(ke.keyFd().getName()) && n.value() instanceof Ast.StringVal sv) {
                    add(out, ke.checkExplicitKey(sv.value(), sv.pos()));
                }
                value(n.value(), fd, out);
            } else if (e instanceof Ast.Block n) {
                if (n.nameQuoted()) {
                    out.add(KeyedException.quotedNameUnkeyed(n.pos(), n.name()));
                    continue;
                }
                FieldDescriptor fd = desc.findFieldByName(n.name());
                if (fd == null) continue;
                if (ke != null) add(out, ke.bindingConflict(fd, false, n.pos()));
                FieldDescriptor keyFd = Annotations.keyField(fd);
                if (keyFd != null) {
                    keyedBlock(n.entries(), fd, keyFd, out);
                    continue;
                }
                if (isList(fd) && isMessage(fd)) {
                    // The block form on a repeated field with no (pxf.key):
                    // the shape is an ordinary schema error, but a quoted
                    // entry name inside it is this diagnostic.
                    for (Ast.Entry be : n.entries()) {
                        if (be instanceof Ast.Assignment ba && ba.keyQuoted()) {
                            out.add(KeyedException.quotedNameUnkeyed(ba.pos(), ba.key()));
                        } else if (be instanceof Ast.Block bb && bb.nameQuoted()) {
                            out.add(KeyedException.quotedNameUnkeyed(bb.pos(), bb.name()));
                        }
                    }
                    continue;
                }
                if (isMessage(fd) && !fd.isRepeated()) entries(n.entries(), fd.getMessageType(), null, out);
            }
        }
    }

    /** Descends into an assignment's value by the field's shape. */
    private static void value(Ast.Value v, FieldDescriptor fd, List<KeyedException> out) {
        if (fd.isMapField()) {
            FieldDescriptor valueFd = fd.getMessageType().findFieldByName("value");
            if (!(v instanceof Ast.BlockVal bv) || !isMessage(valueFd)) return;
            for (Ast.Entry e : bv.entries()) {
                if (e instanceof Ast.MapEntry me && me.value() instanceof Ast.BlockVal inner) {
                    entries(inner.entries(), valueFd.getMessageType(), null, out);
                }
            }
            return;
        }
        if (isList(fd)) {
            if (!isMessage(fd)) return;
            FieldDescriptor keyFd = Annotations.keyField(fd);
            if (v instanceof Ast.ListVal lv) {
                Elem elem = keyFd != null ? new Elem(fd.getName(), keyFd, "", false) : null;
                for (Ast.Value el : lv.elements()) {
                    if (el instanceof Ast.BlockVal bv) entries(bv.entries(), fd.getMessageType(), elem, out);
                }
            } else if (v instanceof Ast.BlockVal bv && keyFd != null) {
                // The `name = { ... }` spelling of the keyed block form.
                keyedBlock(bv.entries(), fd, keyFd, out);
            }
            return;
        }
        if (isMessage(fd) && v instanceof Ast.BlockVal bv) entries(bv.entries(), fd.getMessageType(), null, out);
    }

    /** One keyed block's entries: empty and duplicate names, then each body with its key-field context. */
    private static void keyedBlock(List<Ast.Entry> entries, FieldDescriptor fd, FieldDescriptor keyFd, List<KeyedException> out) {
        Set<String> seen = new HashSet<>();
        for (Ast.Entry e : entries) {
            String name;
            Position pos;
            List<Ast.Entry> body;
            if (e instanceof Ast.Block n) {
                name = n.name();
                pos = n.pos();
                body = n.entries();
            } else if (e instanceof Ast.Assignment n && n.value() instanceof Ast.BlockVal bv) {
                // A non-block value on a named entry is an ordinary decode
                // error, not a keyed diagnostic.
                name = n.key();
                pos = n.pos();
                body = bv.entries();
            } else {
                continue;
            }
            if (name.isEmpty()) {
                out.add(KeyedException.emptyName(pos, fd.getName()));
            } else if (!seen.add(name)) {
                out.add(KeyedException.duplicateKey(pos, fd.getName(), name));
            }
            entries(body, fd.getMessageType(), new Elem(fd.getName(), keyFd, name, true), out);
        }
    }
}
