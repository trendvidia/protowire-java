package com.trendvidia.protowire.pb;

import java.lang.reflect.Field;
import java.util.List;

enum FieldKind {
    SCALAR, LIST;

    static FieldKind classify(Field f) {
        if (List.class.isAssignableFrom(f.getType())) return LIST;
        return SCALAR;
    }
}
