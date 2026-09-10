// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The schema-aware half of {@code pxf fmt}: rewrites a parsed document to
 * the canonical keyed form of draft -01 §3.13 against its message schema
 * (#50). Per keyed repeated field binding it
 *
 * <ul>
 *   <li>converts an eligible anonymous list binding (every element with
 *       exactly one non-empty, distinct explicit key assignment) to the
 *       keyed block form, removing the now-implicit key assignments;</li>
 *   <li>normalizes {@code name = { … }} entry spellings to
 *       {@code name { … }};</li>
 *   <li>unquotes quoted entry names that are identifier-safe;</li>
 *   <li>drops redundant (agreeing) explicit key-field assignments inside
 *       named entries.</li>
 * </ul>
 *
 * <p>Bindings that are not eligible for the keyed form — duplicate keys,
 * absent or empty keys — are left in the anonymous form, and entries that
 * don't resolve against the schema are left untouched, so formatting an
 * invalid document never destroys information. Callers typically follow
 * with {@link Format#formatDocument}; the pair is the reference
 * {@code pxf fmt} pipeline (protowire-go's {@code CanonicalizeKeyed}).
 * The AST is immutable, so a new document is returned.
 */
public final class KeyedCanonicalizer {
    private KeyedCanonicalizer() {}

    public static Ast.Document canonicalize(Ast.Document doc, Descriptor desc) {
        if (doc == null || desc == null) return doc;
        return new Ast.Document(doc.typeUrl(), doc.directives(), doc.datasets(), doc.protos(),
                canonEntries(doc.entries(), desc), doc.leadingComments());
    }

    private static List<Ast.Entry> canonEntries(List<Ast.Entry> entries, Descriptor desc) {
        List<Ast.Entry> out = new ArrayList<>(entries.size());
        for (Ast.Entry e : entries) {
            if (e instanceof Ast.Assignment a && !a.keyQuoted()) {
                FieldDescriptor fd = desc.findFieldByName(a.key());
                out.add(fd == null ? a : canonAssignment(a, fd));
            } else if (e instanceof Ast.Block b && !b.nameQuoted()) {
                FieldDescriptor fd = desc.findFieldByName(b.name());
                if (fd == null) { out.add(b); continue; }
                FieldDescriptor keyFd = Annotations.keyField(fd);
                if (keyFd != null) {
                    out.add(canonKeyedEntries(b, fd, keyFd));
                } else if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE && !fd.isRepeated()) {
                    out.add(new Ast.Block(b.pos(), b.name(), canonEntries(b.entries(), fd.getMessageType()),
                            b.leadingComments(), b.trailingComment(), false));
                } else {
                    out.add(b);
                }
            } else {
                out.add(e); // quoted name outside a keyed block: invalid, leave untouched
            }
        }
        return List.copyOf(out);
    }

    private static Ast.Entry canonAssignment(Ast.Assignment a, FieldDescriptor fd) {
        if (fd.isMapField()) {
            if (a.value() instanceof Ast.BlockVal bv && fd.getMessageType().findFieldByName("value").getJavaType() == FieldDescriptor.JavaType.MESSAGE) {
                Descriptor valDesc = fd.getMessageType().findFieldByName("value").getMessageType();
                List<Ast.Entry> es = new ArrayList<>(bv.entries().size());
                for (Ast.Entry e : bv.entries()) {
                    if (e instanceof Ast.MapEntry me && me.value() instanceof Ast.BlockVal inner) {
                        es.add(new Ast.MapEntry(me.pos(), me.key(), new Ast.BlockVal(inner.pos(), canonEntries(inner.entries(), valDesc)),
                                me.leadingComments(), me.trailingComment(), me.keyQuoted()));
                    } else {
                        es.add(e);
                    }
                }
                return withValue(a, new Ast.BlockVal(bv.pos(), List.copyOf(es)));
            }
            return a;
        }
        if (fd.isRepeated()) {
            if (fd.getJavaType() != FieldDescriptor.JavaType.MESSAGE) return a;
            FieldDescriptor keyFd = Annotations.keyField(fd);
            if (a.value() instanceof Ast.ListVal lv) {
                if (keyFd != null) return canonAnonymousKeyed(a, lv, fd, keyFd);
                List<Ast.Value> elems = new ArrayList<>(lv.elements().size());
                for (Ast.Value v : lv.elements()) {
                    elems.add(v instanceof Ast.BlockVal bv ? new Ast.BlockVal(bv.pos(), canonEntries(bv.entries(), fd.getMessageType())) : v);
                }
                return withValue(a, new Ast.ListVal(lv.pos(), List.copyOf(elems)));
            }
            if (a.value() instanceof Ast.BlockVal bv && keyFd != null) {
                // `children = { ... }` → `children { ... }`.
                Ast.Block blk = new Ast.Block(a.pos(), a.key(), bv.entries(), a.leadingComments(), a.trailingComment(), false);
                return canonKeyedEntries(blk, fd, keyFd);
            }
            return a;
        }
        if (fd.getJavaType() == FieldDescriptor.JavaType.MESSAGE && a.value() instanceof Ast.BlockVal bv) {
            return withValue(a, new Ast.BlockVal(bv.pos(), canonEntries(bv.entries(), fd.getMessageType())));
        }
        return a;
    }

    private static Ast.Assignment withValue(Ast.Assignment a, Ast.Value v) {
        return new Ast.Assignment(a.pos(), a.key(), v, a.leadingComments(), a.trailingComment(), a.keyQuoted());
    }

    /**
     * Normalizes the entries of a keyed block: assignment-spelled entries
     * become blocks, identifier-safe quoted names are unquoted, redundant
     * agreeing key assignments are dropped, and entry bodies are
     * canonicalized recursively.
     */
    private static Ast.Block canonKeyedEntries(Ast.Block b, FieldDescriptor fd, FieldDescriptor keyFd) {
        String keyName = keyFd.getName();
        List<Ast.Entry> out = new ArrayList<>(b.entries().size());
        for (Ast.Entry e : b.entries()) {
            Ast.Block eb = null;
            if (e instanceof Ast.Block nb) {
                eb = nb;
            } else if (e instanceof Ast.Assignment a && a.value() instanceof Ast.BlockVal bv) {
                eb = new Ast.Block(a.pos(), a.key(), bv.entries(), a.leadingComments(), a.trailingComment(), a.keyQuoted());
            }
            if (eb == null) { out.add(e); continue; } // malformed entry; leave untouched
            boolean quoted = eb.nameQuoted() && !Format.identSafeEntryName(eb.name());
            List<Ast.Entry> body = canonEntries(dropKeyAssignments(eb.entries(), keyName, eb.name()), fd.getMessageType());
            out.add(new Ast.Block(eb.pos(), eb.name(), body, eb.leadingComments(), eb.trailingComment(), quoted));
        }
        return new Ast.Block(b.pos(), b.name(), List.copyOf(out), b.leadingComments(), b.trailingComment(), false);
    }

    /**
     * Removes {@code keyName = "entryName"} assignments — the redundant
     * agreeing spelling of an entry's key. Disagreeing or non-string
     * assignments are kept (the document is invalid; formatting must not
     * silently change its meaning). Leading comments of a dropped
     * assignment move to the next surviving entry.
     */
    private static List<Ast.Entry> dropKeyAssignments(List<Ast.Entry> entries, String keyName, String entryName) {
        List<Ast.Entry> out = new ArrayList<>(entries.size());
        List<Ast.Comment> pending = new ArrayList<>();
        for (Ast.Entry e : entries) {
            if (e instanceof Ast.Assignment a && !a.keyQuoted() && a.key().equals(keyName)
                    && a.value() instanceof Ast.StringVal sv && sv.value().equals(entryName)) {
                pending.addAll(a.leadingComments());
                continue;
            }
            if (!pending.isEmpty()) {
                List<Ast.Comment> merged = new ArrayList<>(pending);
                merged.addAll(e.leadingComments());
                e = switch (e) {
                    case Ast.Assignment a -> new Ast.Assignment(a.pos(), a.key(), a.value(), List.copyOf(merged), a.trailingComment(), a.keyQuoted());
                    case Ast.Block bl -> new Ast.Block(bl.pos(), bl.name(), bl.entries(), List.copyOf(merged), bl.trailingComment(), bl.nameQuoted());
                    case Ast.MapEntry me -> new Ast.MapEntry(me.pos(), me.key(), me.value(), List.copyOf(merged), me.trailingComment(), me.keyQuoted());
                };
                pending.clear();
            }
            out.add(e);
        }
        return out;
    }

    /**
     * Converts an eligible anonymous list binding of a keyed repeated field
     * to the keyed block form. Ineligible bindings (non-block elements,
     * absent / empty / duplicate / non-string keys) stay anonymous; their
     * element bodies are still canonicalized.
     */
    private static Ast.Entry canonAnonymousKeyed(Ast.Assignment a, Ast.ListVal lv, FieldDescriptor fd, FieldDescriptor keyFd) {
        String keyName = keyFd.getName();
        List<String> keys = new ArrayList<>();
        List<Ast.BlockVal> blocks = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean eligible = true;
        for (Ast.Value v : lv.elements()) {
            if (!(v instanceof Ast.BlockVal bv)) { eligible = false; break; }
            String key = explicitKeyOf(bv.entries(), keyName);
            if (key == null || key.isEmpty() || !seen.add(key)) { eligible = false; break; }
            keys.add(key);
            blocks.add(bv);
        }
        if (!eligible) {
            List<Ast.Value> elems = new ArrayList<>(lv.elements().size());
            for (Ast.Value v : lv.elements()) {
                elems.add(v instanceof Ast.BlockVal bv ? new Ast.BlockVal(bv.pos(), canonEntries(bv.entries(), fd.getMessageType())) : v);
            }
            return withValue(a, new Ast.ListVal(lv.pos(), List.copyOf(elems)));
        }
        List<Ast.Entry> entries = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            String key = keys.get(i);
            List<Ast.Entry> body = canonEntries(dropKeyAssignments(blocks.get(i).entries(), keyName, key), fd.getMessageType());
            entries.add(new Ast.Block(blocks.get(i).pos(), key, body, List.of(), "", !Format.identSafeEntryName(key)));
        }
        return new Ast.Block(a.pos(), a.key(), List.copyOf(entries), a.leadingComments(), a.trailingComment(), false);
    }

    /**
     * The value of the single explicit string assignment to {@code keyName}
     * among {@code entries}, or null when there is no such assignment, more
     * than one, a quoted-key spelling, or a non-string value.
     */
    private static String explicitKeyOf(List<Ast.Entry> entries, String keyName) {
        String key = null;
        for (Ast.Entry e : entries) {
            if (!(e instanceof Ast.Assignment a) || !a.key().equals(keyName)) continue;
            if (!(a.value() instanceof Ast.StringVal sv) || a.keyQuoted() || key != null) return null;
            key = sv.value();
        }
        return key;
    }
}
