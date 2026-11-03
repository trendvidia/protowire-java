#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 TrendVidia, LLC.
#
# Regenerates org.protowire.pxf.wkt: the PxfMeta and PxfEnum companions of the
# google/protobuf types that PXF reads as ordinary messages (Any, Empty,
# FieldMask, Struct, Value, ListValue, NullValue). protoc-gen-pxf-java-meta
# points a field of one of these types here, since no build runs it over the
# google files themselves (trendvidia/protowire#539). The files are the
# plugin's own output for those files, with only the package line changed.
#
# Usage: regen-wkt-companions.sh <protowire checkout> [--check]
#   --check  fail if the checked-in files differ from fresh output.
set -euo pipefail

spec=${1:?usage: $0 <protowire checkout> [--check]}
mode=${2:-}
here=$(cd "$(dirname "$0")/.." && pwd)
dest=$here/src/main/java/org/protowire/pxf/wkt
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT

(cd "$spec" && go build -o "$work/protoc-gen-pxf-java-meta" ./cmd/protoc-gen-pxf-java-meta)
mkdir -p "$work/out"
protoc --plugin=protoc-gen-pxf-java-meta="$work/protoc-gen-pxf-java-meta" \
  --pxf-java-meta_out="$work/out" \
  google/protobuf/any.proto google/protobuf/empty.proto \
  google/protobuf/field_mask.proto google/protobuf/struct.proto

mkdir -p "$work/wkt"
for f in "$work"/out/com/google/protobuf/*.java; do
  sed 's/^package com\.google\.protobuf;$/package org.protowire.pxf.wkt;/' "$f" > "$work/wkt/$(basename "$f")"
done

if [[ $mode == --check ]]; then
  diff -r "$work/wkt" "$dest"
  echo "org.protowire.pxf.wkt matches the plugin's output"
else
  rm -rf "$dest" && mkdir -p "$dest"
  cp "$work"/wkt/*.java "$dest/"
  ls "$dest"
fi
