package org.protowire.sbe.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lookup-by-name index over a {@link MessageTemplate}'s fields and groups.
 * Drives {@link View}'s field-name-keyed accessors. Built by the
 * descriptor-driven {@code TemplateBuilder} in {@code :sbe} from the
 * field names, and (in the future) by the codegen plugin's lite-mode
 * SBE emit.
 */
public final class ViewSchema {
    public final Map<String, FieldTemplate> fields = new HashMap<>();
    public final List<GroupInfo> groupOrder = new ArrayList<>();

    public record GroupInfo(String name, ViewSchema schema) {}
}
