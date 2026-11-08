#!/usr/bin/env bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 TrendVidia, LLC.
#
# Checks testdata/ against the spec repo's testdata/:
#
#   1. every directory under testdata/ is the spec's directory byte for byte,
#      and testdata/test.proto is the spec's;
#   2. every spec testdata directory is either vendored here (and so run by
#      both tiers: pxf's SpecCorpusTest and lite-fixtures' LiteCorpusTest
#      each fail on a directory they have no driver for) or listed below
#      with the reason it is not.
#
# Usage: scripts/check-spec-corpus.sh <protowire checkout>
# The conformance job runs it against protowire main.

set -euo pipefail

spec="${1:?usage: $0 <protowire checkout>}/testdata"
here="$(cd "$(dirname "$0")/.." && pwd)/testdata"
[[ -d "$spec" ]] || { echo "no testdata/ in $1" >&2; exit 2; }

# Spec directories with no copy here, and what covers them instead.
declare -A NOT_VENDORED=(
  [adversarial]="the spec's cross_security_check.sh, run by this repo's conformance job"
  [text]="the spec's cross_text_check.sh, run by this repo's conformance job"
  [write]="the spec's cross_write_check.sh, run by this repo's conformance job"
  [envelope]="the spec's cross_envelope_check.sh"
  [values]="pb's PbGoldenTest, through a byte copy the conformance job compares"
  [docs]="cmd/pxf tooling; neither Java tier has it"
  [fmt]="cmd/pxf .proto formatting; neither Java tier has it"
  [lint]="cmd/pxf lint; neither Java tier has it"
  [openapi]="cmd/pxf openapi; neither Java tier has it"
  [proto-imports]="the cmd/pxf .proto compiler; neither Java tier has it"
  [schema-extensions]="the v1.2 .proto compiler; neither Java tier has it"
  [stock-image]="the cmd/pxf .proto compiler; neither Java tier has it"
)

fail=0
for d in "$here"/*/; do
  name="$(basename "$d")"
  if [[ ! -d "$spec/$name" ]]; then
    echo "testdata/$name: the spec has no testdata/$name" >&2
    fail=1
  elif ! diff -r "$spec/$name" "$here/$name"; then
    echo "testdata/$name differs from the spec's: copy it again" >&2
    fail=1
  fi
done
if ! cmp "$spec/test.proto" "$here/test.proto"; then
  echo "testdata/test.proto differs from the spec's" >&2
  fail=1
fi

for d in "$spec"/*/; do
  name="$(basename "$d")"
  [[ -d "$here/$name" || -n "${NOT_VENDORED[$name]:-}" ]] && continue
  echo "spec testdata/$name is neither vendored in testdata/ nor listed in $0" >&2
  fail=1
done

if [[ "$fail" == 0 ]]; then
  echo "testdata/: $(ls -d "$here"/*/ | wc -l | tr -d ' ') directories match the spec's"
fi
exit "$fail"
