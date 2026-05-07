// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import com.google.protobuf.Descriptors.FieldDescriptor;

import java.util.List;

final class FieldTemplate {
    final FieldDescriptor fd;
    final int offset;
    final int size;
    final String encoding;
    final List<FieldTemplate> composite;
    ViewSchema compositeView;

    FieldTemplate(FieldDescriptor fd, int offset, int size, String encoding, List<FieldTemplate> composite) {
        this.fd = fd;
        this.offset = offset;
        this.size = size;
        this.encoding = encoding;
        this.composite = composite;
    }
}
