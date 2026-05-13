// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.pxf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Field-presence metadata + side-channel directives produced by
 * {@link Pxf#unmarshalFull}. Tracks set, null, and absent fields by
 * dotted path (e.g. {@code "name"}, {@code "nested.value"}), plus the
 * {@code @<name>} directives and {@code @dataset} directives the decoder
 * saw at the document root.
 */
public final class Result {
    private final Set<String> nullFields = new HashSet<>();
    private final Set<String> presentFields = new HashSet<>();
    private final List<Ast.Directive> directives = new ArrayList<>();
    private final List<Ast.DatasetDirective> datasets = new ArrayList<>();
    private final List<Ast.ProtoDirective> protos = new ArrayList<>();

    Result() {}

    void markNull(String path) {
        nullFields.add(path);
        presentFields.add(path);
    }

    void markPresent(String path) {
        presentFields.add(path);
    }

    void addDirective(Ast.Directive d) { directives.add(d); }
    void addDataset(Ast.DatasetDirective t) { datasets.add(t); }
    void addProto(Ast.ProtoDirective p) { protos.add(p); }

    public boolean isNull(String path)    { return nullFields.contains(path); }
    public boolean isAbsent(String path)  { return !presentFields.contains(path); }
    public boolean isSet(String path)     { return presentFields.contains(path) && !nullFields.contains(path); }
    public List<String> nullFields()      { return List.copyOf(nullFields); }
    Set<String> presentFieldsView()       { return presentFields; }

    /**
     * Returns the {@code @<name> *(prefix) [{ ... }]} directives the decoder
     * saw at the document root, in source order. Excludes the {@code @type}
     * and {@code @dataset} directives (which have their own accessors).
     * Callers typically iterate and hand each {@link Ast.Directive#body()}
     * back to {@link Pxf#unmarshalFull} against a chosen message —
     * chameleon's {@code @header} consumption pattern.
     */
    public List<Ast.Directive> directives() { return List.copyOf(directives); }

    /**
     * Returns the {@code @dataset} directives the decoder saw at the
     * document root, in source order. Per draft §3.4.4 a document with
     * any table MUST NOT carry {@code @type} or top-level body entries
     * — the parser and decoder enforce that. Each
     * {@link Ast.DatasetDirective#rows()} entry is one cell-tuple; cells
     * may be {@code null} (empty cell ⇒ field absent), {@link Ast.NullVal}
     * (explicit null ⇒ field cleared), or any other {@link Ast.Value}
     * (field set).
     */
    public List<Ast.DatasetDirective> datasets() { return List.copyOf(datasets); }

    /**
     * Returns the {@code @proto} directives the decoder saw at the
     * document root, in source order (draft §3.4.5). Each directive
     * carries one of four body shapes (anonymous, named, source,
     * descriptor); callers inspect {@link Ast.ProtoDirective#shape()}
     * and decode {@link Ast.ProtoDirective#body()} accordingly.
     */
    public List<Ast.ProtoDirective> protos() { return List.copyOf(protos); }
}
