// SPDX-License-Identifier: MIT
// Copyright (c) 2026 TrendVidia, LLC.
package org.protowire.sbe;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ViewSchema {
    final Map<String, FieldTemplate> fields = new HashMap<>();
    final List<GroupInfo> groupOrder = new ArrayList<>();

    record GroupInfo(String name, ViewSchema schema) {}
}
