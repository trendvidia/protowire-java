// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe.runtime;

import org.protowire.sbe.FieldTemplate;
import org.protowire.sbe.GroupTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.protowire.sbe.runtime.SbeConstants.ENC_CHAR;
import static org.protowire.sbe.runtime.SbeKinds.*;

/**
 * The bind-time checks protowire-go's {@code NewCodec} makes, on the
 * descriptor-free templates both tiers build (#197, #198; protowire-go#250,
 * #252; draft -01 § SBE Annotations). {@code MessageTemplate}'s constructor
 * runs them, so the lite tier's generated templates refuse a schema the
 * full tier refuses, when its codec class loads.
 *
 * <p>Each error is Go's: the field by its full name, in the context Go
 * names it in (a root field, a group field, a composite and the field
 * inside it). The full tier's {@code TemplateBuilder} also runs the field
 * checks as it builds, so a schema with several problems names the first
 * by field number, as Go does.
 */
public final class SbeTemplateCheck {
    private SbeTemplateCheck() {}

    /** A message template's own fields and groups, in field-number order. */
    public static void message(int templateId, int schemaId, int version,
                               List<FieldTemplate> fields, List<GroupTemplate> groups) {
        // The full tier names the message and the file; a lite template does
        // not know them.
        if (templateId > MAX_UINT16 || templateId < 0) {
            throw new IllegalArgumentException("sbe: (sbe.template_id) = " + Integer.toUnsignedString(templateId) + " is past "
                    + MAX_UINT16 + ", the largest templateId SBE can state");
        }
        if (schemaId > MAX_UINT16 || schemaId < 0) {
            throw new IllegalArgumentException("sbe: (sbe.schema_id) = " + Integer.toUnsignedString(schemaId) + " is past "
                    + MAX_UINT16 + ", the largest schemaId SBE can state");
        }
        if (version > MAX_UINT16 || version < 0) {
            throw new IllegalArgumentException("sbe: (sbe.version) = " + Integer.toUnsignedString(version) + " is past "
                    + MAX_UINT16 + ", the largest version SBE can state");
        }
        List<Object> all = new ArrayList<>(fields);
        all.addAll(groups);
        all.sort(Comparator.comparingInt(o -> o instanceof FieldTemplate f ? f.fieldNumber : ((GroupTemplate) o).fieldNumber));
        for (Object o : all) {
            if (o instanceof FieldTemplate ft) rootField(ft);
            else for (FieldTemplate ft : ((GroupTemplate) o).fields) groupField(ft);
        }
    }

    /** A field of the root block. */
    public static void rootField(FieldTemplate ft) {
        if (ft.composite != null) composite(ft, "sbe: composite " + ft.fullName + ": ");
        else field(ft, "sbe: field " + ft.fullName + ": ");
    }

    /** A field of a repeating group's entry. */
    public static void groupField(FieldTemplate ft) {
        if (ft.composite != null) composite(ft, "sbe: composite in group " + ft.fullName + ": ");
        else field(ft, "sbe: group field " + ft.fullName + ": ");
    }

    /** A composite's fields, then the composite's own end in its block. */
    private static void composite(FieldTemplate ft, String prefix) {
        compositeFields(ft, prefix);
        end(ft, prefix);
    }

    /**
     * Go wraps a composite's errors once, at the outermost composite: a
     * field inside, at any depth, is {@code composite field <name>: …}, and
     * so is a nested composite's own end.
     */
    private static void compositeFields(FieldTemplate ft, String prefix) {
        for (FieldTemplate sf : ft.composite) {
            if (sf.composite != null) {
                compositeFields(sf, prefix);
                end(sf, prefix + "composite field " + sf.fullName + ": ");
            } else {
                field(sf, prefix + "composite field " + sf.fullName + ": ");
            }
        }
    }

    private static void field(FieldTemplate ft, String prefix) {
        String enc = ft.encoding;
        // (sbe.encoding) narrows a numeric field, and says nothing of
        // crossing between integers and floating point (protowire-go#252).
        if ((ft.kind == K_STRING || ft.kind == K_BYTES) && !ENC_CHAR.equals(enc)) {
            String k = kindName(ft.kind);
            throw new IllegalArgumentException(prefix + "(sbe.encoding) = \"" + enc + "\" on a " + k + " field; it narrows a numeric"
                    + " field, and a " + k + " field takes (sbe.length)");
        }
        if ((ft.kind == K_FLOAT || ft.kind == K_DOUBLE) && isIntEncoding(enc)) {
            throw new IllegalArgumentException(prefix + "(sbe.encoding) = \"" + enc + "\" is an integer primitive and the field is "
                    + kindName(ft.kind));
        }
        if (ft.kind != K_FLOAT && ft.kind != K_DOUBLE && isFloatEncoding(enc)) {
            throw new IllegalArgumentException(prefix + "(sbe.encoding) = \"" + enc + "\" is a floating-point primitive and the field is "
                    + kindName(ft.kind));
        }
        if (ENC_CHAR.equals(enc) && ft.size > MAX_UINT16) {
            throw new IllegalArgumentException(prefix + "(sbe.length) = " + ft.size + " is past " + MAX_UINT16
                    + ", the largest blockLength SBE can state");
        }
        end(ft, prefix);
    }

    /** A field that ends past what a uint16 blockLength can state; every offset in the block is then a uint16 too. */
    private static void end(FieldTemplate ft, String prefix) {
        long end = (long) ft.offset + ft.size;
        if (end > MAX_UINT16) {
            throw new IllegalArgumentException(prefix + "ends at byte " + end + " of its block, past " + MAX_UINT16
                    + ", the largest blockLength SBE can state");
        }
    }
}
