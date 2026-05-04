package org.protowire.pb;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

enum FieldKind {
    SCALAR, LIST, MAP;

    static FieldKind classify(Field f) {
        if (List.class.isAssignableFrom(f.getType())) return LIST;
        if (Map.class.isAssignableFrom(f.getType())) return MAP;
        return SCALAR;
    }
}
