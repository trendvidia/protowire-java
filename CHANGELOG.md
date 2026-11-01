# Changelog

All notable changes to `protowire-java` are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

The version number is kept aligned with the rest of the `protowire-*`
stack — releases bump in lockstep across language ports when the wire
format changes.

> **Note on versions and coordinates.** Earlier internal builds used
> `0.1.0` / `0.2.0` under the `com.trendvidia.protowire` Maven group;
> the renumbering to `0.70.0` and the move to the `org.protowire` group
> are one-time realignments ahead of the first coordinated public
> release. No published artifacts are affected — earlier numbers were
> never tagged on Maven Central.

## [Unreleased]

### Fixed

- **`(pxf.default)` and `(pxf.required)` apply in every message body** on
  `unmarshalFull` (#120): an assignment-form field, a list element, a map value,
  a keyed entry and an Any body. Before, only a block-form singular field got
  them. On `one = { pick_i = 1 }`, a sibling's default destroyed the oneof arm
  the document chose. **`Result.presentFields` changes**: a field in such a body
  is now reported at its own path, such as `items[0].id` or `by_key["k"].id`,
  as protowire-go does. It used to be reported at the parent's prefix.
- **A keyed entry's name counts as the document supplying the key field**
  (protowire-go#214, draft -01 §3.13). `kd { alpha {} }` keeps `alpha` when the
  key field has a `(pxf.default)`, a `(pxf.required)` key is satisfied, a default
  on a oneof sibling of the key stays off, and `Result` reports `kd[0].name` as
  set. The change for #120 above already does this; it is now pinned by test.
- **`@required` and `@default` are honoured** (#111). A schema written in the
  annotation form lowers to the 1327 carrier, which this port did not read, so
  it bound without error and without enforcement. Both spellings now give the
  same answer, and the bind-time checks, including required-with-default,
  apply to both. Two spellings of one default that disagree are a bind-time
  violation, and so is an `@default` that reduces to no single literal. This
  narrows accepted schema input.
- **`pxf.BigFloat` is binary, as the proto says** (#104, protowire#393, #406,
  #410). This port read and wrote `exponent` as a power of ten, so the bytes it
  wrote for a BigFloat decoded to a different number in every other port, and
  the two int32 extremes made `Pxf.marshal` throw. **Bytes this port wrote for
  a BigFloat before this release are not readable as the value they meant**,
  the same situation as the `Pb` fix in 1.2.0. Text is `1.5p53`: enough digits
  to recover the value, then the precision. The lite tier shares the code,
  through the new `BigFloats`.

### Changed

- **A keyed entry's body can't undo its entry name** (#124, protowire#480).
  Inside a named entry, `null` on the key field, or any binding of another
  member of the key field's oneof, is now a decode error. Before,
  `choices { alpha { other = "x" } }` lost the key with no error. The anonymous
  form and an agreeing `name = "alpha"` stay legal. Only a key that is optional
  or in a oneof can reach these documents, and `Pxf.marshal` never wrote them.
- **`MaxBigFloatExponent` (16384)** is enforced on a literal, a marshalled
  value and the PB wire (protowire#281), and is a sixth `--limit` for
  `check-decode`. `DecodeLimits` and `pb.UnmarshalOptions` gain it, keeping
  their previous constructors.
- **`MaxNumericLiteralDigits` counts mantissa digits only** (protowire#410):
  leading zeros, an exponent and a precision suffix do not count. A
  `pxf.Decimal` literal's scale is bounded by the same limit, as on the wire.
- **A number may carry a precision suffix** (`1.5p64`). It is rejected,
  naming the field, anywhere but a `pxf.BigFloat`. `Token`, `Ast.IntVal` and
  `Ast.FloatVal` gain `prec`, keeping their previous constructors.
- `WellKnown.setBigFloat(Builder, BigDecimal)` and `parseBigFloat` are
  deprecated: a `BigDecimal` has no precision or negative zero to keep.

These narrow what a decoder accepts, so a document that relied on one of
them now fails. Each is a settled rule of draft -01, and protowire-kotlin
inherits it.

- **A singular field bound twice in one message body is rejected** (#103,
  protowire#347 option a). A second scalar used to replace the first, and a
  second block replaced the first too. `null` counts as a binding. Repeated
  fields still concatenate.
- **A map key bound twice in one message body is rejected** (#105,
  protowire#396 option b), inside one literal or across two bindings of the
  field. Keys compare as values, so `1` and `"1"` are one key of an
  integer-keyed map. A `DynamicMessage` decode used to keep both entries.
  Two bindings with distinct keys still merge.
- **`null` binds only to a field with explicit presence** (#102,
  protowire#339, #379, #380). A plain proto3 scalar, a proto2 `required`
  field, a repeated field and a map field now reject it, in a body and in a
  `@dataset` cell.
- **A simple string holds no raw line feed** (#102, protowire#350), through
  the decoder, `Pxf.parse` and every `@dataset` path.
- **The lite tier applies the rules its tables can decide**: both
  bound-twice rules, and `null` on a list, a map or a map value. It cannot
  yet tell a plain scalar from an `optional` one, so `null` on a scalar stays
  accepted there (trendvidia/protowire#477).

- **`BindRow` applies `(pxf.default)` and `(pxf.required)`**, as its javadoc
  always said. It used `Pxf.unmarshal`, which skips both.
- **`DatasetReader` rejects a truncated row and junk between rows** (protowire#336).
  It used to end the row sequence early and report success, so a cut-off
  stream read as a shorter valid one. The sequence now ends only at end of
  input or at a directive.
- **A row's decode error is reported at the row in its document and names the
  column**, on both dataset paths. `DatasetReader` used to report positions
  relative to the row (#102).
- **`Pxf.marshal` writes a `pxf.Secret` as its string literal** when `hint` and
  `fingerprint` are empty (#122), and as a block otherwise. Text output changes
  for those values. It decodes to the same message on this port, on
  protowire-go, and on any reader that follows `pxf/secret.proto`, but a reader
  built on an earlier version of this port refuses `pw = "x"`.

### Added

- **`pxf.Secret` takes its string literal** (#122). `pw = "x"` now decodes in a
  singular field, a list element, a map value and a `@dataset` cell, as well as
  `pw { value = "x" }`.
- `Pb` maps `org.protowire.pb.BigFloat` to `pxf.BigFloat`, with the exponent
  bound checked before the fields are handed over.
- **The Any block's type entry** (#100, protowire#354): `@type = "<url>"` as
  the first entry of a block bound to `google.protobuf.Any`, on a singular
  field in either spelling, a list element or a map value. `Pxf.parse` takes
  it as the first entry of any block, as `Ast.TypeEntry`. **`Ast.Entry` gains
  a permitted subtype**, so an exhaustive `switch` over it needs a new arm.
  With a resolver set, an Any block without a type entry is now its own
  fields (`type_url`, `value`), where it used to be an error. The lite tier
  has no resolver and rejects the entry naming that reason.
- **`@dataset` value cells** (#99, protowire#341): a cell holds a list or a
  block, and binds as `column = value` would in a body, on the materializing
  and the streaming path.
- **`@dataset` dotted column paths** (#101, protowire#342): `(id, desc.energy,
  desc.tempo.min_bpm)`. A row's cells group into one block per prefix, so a
  prefix with no non-empty cell leaves its sub-message absent. A header that
  repeats a path (`(a, a)` included), names a field beside a path inside it,
  or has an empty segment is rejected by the parse. The rules that need the row
  type are checked when a row binds, so a table with no rows passes them.
- `BindRow.bindRow` overload and `DatasetReader` constructor taking
  `UnmarshalOptions`, for a cell holding an Any block with a type entry.
- The `testdata/{any,bigfloat,blocks,dataset,duplicates,nulls,strings}/` corpora are vendored and
  run on every build. A `,` or `;` between block entries was already rejected
  (#106), and this now holds that in place. The two editions fixtures in
  `testdata/nulls/` do not run: the protobuf-java 3.25.5 runtime reads no
  field features, so it reports EXPLICIT scalars as having no presence. A test
  pins that.

## [1.3.0] — 2026-10-31

### Fixed

- **`(pxf.required)` with `(pxf.default)` on one field is a bind-time
  violation** (#108), as draft -01 "Required and Default" requires
  (protowire#453). Such a schema used to bind, and a document omitting
  the field had a meaning that could differ from other engines.
  `SchemaValidator` now reports it as `REQUIRED_OPTION` over the import
  closure, like the other bind-time checks. `(pxf.required) = false`
  beside a default stays accepted. This narrows accepted schema input.

### Changed

- **The vendored shared schemas are re-synced with [trendvidia/protos](https://github.com/trendvidia/protos) v0.1.2** ([trendvidia/protowire#447](https://github.com/trendvidia/protowire/issues/447)). They gain protos' file options, and `envelope.proto`'s `go_package` now names a published package. Declarations and field numbers are unchanged, and so is this port's public API.

- `check-decode`'s `Tree` gains `branches` and `tags`, matching
  protowire's `adversarial.proto` (protowire#449). Without them PB
  skipped both fields as unknown, and the `pb/deep-map-101` and
  `pb/deep-scalar-map-101` rows failed because nothing decoded a map
  entry. The decoders needed no change: both already count a map's `{`
  and every PB map entry.

## [1.2.0] — 2026-09-10

Minor release: the port adopts protowire v1.11 and v1.12 across the
family's gates — the bind-time placement checks scoped to the import
closure, keyed repeated fields, the map-key grammar, the per-call
mandatory limits — and its `pb` codec is measured against the Go
reference for the first time. The Java rows of protowire's
`cross_security_check.sh` (35/35) and `cross_envelope_check.sh` (all
three dumpers held to the `zero-map-entry` golden) pass with no
declared omissions. No public API is removed; every new record component
keeps its previous constructor. Two things a consumer should read before
upgrading: `Pb` bytes written by earlier versions for any non-zero
integer, packed list or `Decimal` scale are not readable by the fixed
codec (they never were by any other port — see "Changed"), and accepted
schema input narrows where a `(pxf.*)` annotation sits where no
implementation could honor it (see "Added", #54).

### Added

- **A descriptor compiled against a retired extension number is diagnosed
  as stale** (#91; STABILITY.md promise 3, protowire-go#98).
  `SchemaValidator.Kind` gains `RETIRED_NUMBER`: an option at one of the
  numbers protowire retired when it moved into its registered block
  (`50000`–`50002` for PXF, the SBE, carrier and protocheck ranges), on
  the Options kind the retired allocation used, in a file that imports
  one of protowire's annotation files, is a violation naming the number,
  what it was, what it is now and which file to recompile against — so
  a pre-v1.12.0 descriptor fails to bind instead of silently losing
  every annotation. A third party's `50001` in a file that never imports
  protowire is that party's business.
- **Keyed repeated fields** (#50, draft `-01` §3.13, protowire#116). A
  `repeated <Message>` field carrying `(pxf.key) = "<string field>"`
  may be written as a block of named blocks — entry name = key-field
  value, entry order = list order, the key field not repeated inside the
  entry — and equivalently as `name = { … }`. The grammar accepts a
  string at entry-name position everywhere (`field_entry = (identifier |
  string), (assignment_tail | block_tail)`): `Ast.Assignment` gains
  `keyQuoted` and `Ast.Block` gains `nameQuoted` (the five-argument
  constructors stay and mean bare), and `Format` reproduces the
  spelling. The schema layer: `FastDecoder` binds the keyed block (a
  duplicate entry name, an empty name, a disagreeing explicit key
  assignment, and an empty key in the anonymous form are decode errors;
  a quoted entry name outside a keyed field's block is rejected);
  `Encoder` emits the keyed form whenever every element's key is
  present, non-empty and distinct, entry names bare when identifier-safe
  and quoted otherwise, and falls back to the anonymous list otherwise;
  new `KeyedCanonicalizer` and `Pxf.formatDocument(doc, descriptor)` are
  the schema-aware `fmt` (anonymous → keyed when eligible, `name = {`
  → `name {`, identifier-safe quoted names unquoted, redundant agreeing
  key assignments dropped). The spec's sixteen `testdata/keyed/` fixtures
  are vendored and pass: five accept, two fmt pairs (and their fixed
  points), six reject. The lite tier rejects a quoted entry name until
  its `PxfMeta` carries `(pxf.key)` (trendvidia/protowire#323).
- **Bind-time placement checks, scoped to the import closure** (#54,
  protowire v1.11; draft `-01` §3.13.1 "Schema Placement", §6.1.1
  "Default Placement", §6.1.2 "Oneof Members", §3.15 "Scope of Bind-Time
  Checks"). `SchemaValidator` rejects, at bind time and independently of
  any document: `(pxf.key)` anywhere but on a repeated message-typed
  field whose value names a singular string field of the element;
  `(pxf.default)` on a `repeated` field, a `map<K,V>`, a group, or a
  message type outside Timestamp, Duration, the nine `*Value` wrappers,
  `pxf.BigInt`, `pxf.Decimal`, `pxf.BigFloat`; more than one
  `(pxf.default)` per oneof; `(pxf.required)` on a oneof member. All
  four checks — the reserved-name rule included — now cover the bound
  file **and its transitive imports**, a file reached twice checked once,
  each violation attributed to the file that declares it. `Kind` gains
  `KEY_OPTION`, `DEFAULT_OPTION`, `REQUIRED_OPTION`; `Violation` gains a
  `detail` component (the four-argument constructor stays); results are
  memoized per descriptor at two grains, bounded, so the wider check is
  faster than the single-file walk it replaces (median decode of
  `testdata/bench-test.pxf` 11.3 µs vs 13.0 µs, three runs each).
  `proto-annotations` gains `string key = 1316`. **This narrows accepted
  schema input:** a placement no implementation could honor now fails
  every file that transitively imports it (the spec's STABILITY.md,
  v1.11); the diagnostic names the field; migration is deleting or moving
  the annotation. The decoder's message header is now `PXF schema
  bind-time violations:`. The lite tier has no descriptors to walk; the
  codegen-time home for its checks is tracked on #54.

- **Per-call decoder limits** (#79, draft `-01` § Mandatory Limits,
  HARDENING.md § Mandatory limits). Every decode entry point now enforces
  `MaxMessageSize` (64 MiB, checked before the first byte is read),
  `MaxBytesLiteralLength` (a `b"…"` literal refused from its length before
  it is decoded) and `MaxRepeatedCount` (a counter on repeated and map
  elements; an SBE group's wire-declared `numInGroup` before any entry is
  allocated), alongside the existing `MaxNestingDepth` and
  `MaxNumericLiteralDigits` — and each is configurable per call:
  - `:pxf-runtime` — `Limits` gains `MAX_MESSAGE_SIZE`,
    `MAX_BYTES_LITERAL_LENGTH`, `MAX_REPEATED_COUNT`; new record
    `DecodeLimits` (`defaults()`, `with*`, `with(name, value)` by
    HARDENING name); `Parser.parse(byte[], DecodeLimits)`.
  - `:pxf` — `UnmarshalOptions` gains a `limits` component
    (`withLimits`; the three-argument constructor stays and means
    defaults); `WellKnown.parseBigInt` / `parseDecimal` / `parseBigFloat`
    gain a digit-cap overload.
  - `:pb` — `Pb.MAX_MESSAGE_SIZE`, `Pb.MAX_REPEATED_COUNT`; new record
    `org.protowire.pb.UnmarshalOptions`; `Pb.unmarshal(data, dest, opts)`
    and `Pb.unmarshal(data, cls, opts)`.
  - `:sbe-runtime` / `:sbe` — `SbeConstants.MAX_MESSAGE_SIZE`,
    `MAX_REPEATED_COUNT`; `SbeWireCodec.unmarshal(…, maxMessageSize,
    maxRepeatedCount)`; `Codec.withLimits(maxMessageSize, maxRepeatedCount)`
    returning a codec over the same templates.
  - `:pxf-android` — `LiteWireReader.toAst(wire, meta, [registry,]
    DecodeLimits)`.
  - `check-decode` accepts `--limit NAME=VALUE` (repeatable) and gains
    the SBE leg, so the corpus proves each cap with a small fixture.

- `Ast.MapEntry` gains a `keyQuoted` component: whether the document
  wrote the key as a string literal (`"true": …`) rather than bare
  (`true: …`). The five-argument constructor stays and means bare, so
  existing constructions compile unchanged. `Format.needsQuoting` and
  `Format.identSafeEntryName` are now public: the one identifier-safe
  rule string map keys and keyed entry names share.

### Fixed

- A `pxf.Decimal` with a negative scale is written as `unscaled` followed
  by `-scale` zeros (#85): `25`, scale `-3` is `25000`, as
  `pxf/bignum.proto` defines the value and protowire-go writes it; this
  port dropped the scale and wrote `25`. Bounded by
  `MaxNumericLiteralDigits` on both sides, as before.
- **The SBE decoder rejects a malformed root block or group header
  instead of reading past it** (HARDENING.md § SBE steps 2–4, found while
  adding the limits): a wire `blockLength` below the template's (root or
  group entry), a group whose `numInGroup × blockLength` outruns the
  bytes present (the product is bounded without being formed, since
  `0xFFFF × 0xFFFF` overflows `int`), and a zero entry block with a
  non-zero count are each an `IllegalArgumentException` naming the
  group. The corpus's `sbe/short-block-length`, `group-count-overflow`
  and `group-zero-blocklength-nonzero-count` rows used to pass only
  because `check-decode` had no SBE leg and rejected everything.

- **`Format` keeps the quotes on a map key spelled like a keyword or an
  integer** (#82, protowire#306 option 2; draft `-01` § Entries and Keys,
  "Canonical spelling of map keys"). A string-keyed `{ "true": "v" }`
  formatted to `{ true: "v" }`, which since protowire#284 is a bool key
  and no longer binds on a string map — formatting turned a document that
  binds into one that does not; `"123"` → `123` was the same shape. The
  parser records whether a key was quoted and the formatter reproduces
  that spelling wherever changing it would change what the key denotes:
  a quoted key is unquoted only when identifier-safe and not `null` /
  `true` / `false`; a bare key stays bare, including a bare `true` on a
  bool map and a bare `404` on an integer map (every formatter in the
  family used to quote the integer). The marshaller now writes `"true":`
  for the string key it used to write bare. The lite tier gets the same
  flag, which settles the two map-key verdicts #76 left open there: a
  quoted `"1"` on a bool map is not a bool literal, a bare `true` on a
  string map is a bool key.
- **A dotted string map key is written bare** (#83, protowire#313): the
  identifier-safe test is the identifier production, dots included, so
  the marshaller writes `a.b:` (it wrote `"a.b":`) and the formatter
  unquotes a quoted `"a.b"`; keys that fail ident-start (`".e"`,
  `"1.5"`) stay quoted. The spec's three `fmt-*` map-key fixture pairs
  are vendored and driven end to end (format, fixed point, bind,
  marshaller spelling).
- **A map key binds exactly the spellings the grammar admits** (#76,
  draft `-01` §entries-and-keys `map-key = identifier / string / integer
  / bool`, protowire#284). A bool key was read with
  `Boolean.parseBoolean`, so `1`, `t`, `TRUE`, `yes`, `"1"` and `"0"` all
  bound the key `false`, silently, and a bare `true` was rejected. Now a
  bool key is the keyword `true` / `false`, the integers `1` / `0`, or the
  quoted literals `"true"` / `"false"`, and every other spelling is
  `invalid bool map key t for field "by_flag": a bool key is true, false,
  0, 1, "true" or "false"`. The keyword on a string-keyed map is an error
  (`write "true" for the string`); integral keys parse to the field's
  width and signedness (`uint32` / `uint64` / `fixed*` keys above the
  signed range used to fail). The AST parser accepts the keyword as a
  map key (with the `:` tail only), so `Format` and the lite tier see it;
  `LiteWireWriter` binds the same spellings, except that it cannot yet
  tell a quoted `"1"` from a bare `1` (the AST records the quoted flag
  with #82). The spec's `testdata/map-keys` bool fixtures are vendored
  under `pxf/src/test/resources/map-keys` and all sixteen verdicts pass.
- **The lexer reads fractional and `µs` duration literals** (#55):
  `1.5ms`, `1.234567ms`, `312.5µs`, `1h30m0.5s`, `-1.5s` — the forms the
  Go, Rust, C++ and TypeScript encoders write for any Duration that is
  not a whole multiple of its largest unit (draft `-01` §3.3
  `duration-segment = 1*DIGIT [ "." 1*DIGIT ] time-unit`, `micro-us =
  %xC2.B5 %x73`). The number path used to take the float branch on `.`
  before looking for a unit, and its unit scan was ASCII-only. Pinned
  unchanged: `1.5` FLOAT, `1.5x` FLOAT + IDENT, `1.ms` FLOAT `1.` + IDENT,
  `1.5e3ms` FLOAT + IDENT, `5min` invalid, U+03BC GREEK SMALL LETTER MU not
  a unit. Behind the token, `TimeFormats.parseGoDuration` is now exact
  (it went through a `double`, so `1.001ms` read as 1000999 ns) and
  `formatGoDuration` no longer writes `--2562047h-47m…` for
  `Long.MIN_VALUE`. The lite tier shares both and inherits the fix.
- **The recursion counter starts at the root as depth 0** (#80,
  protowire#301): `FastDecoder`, `Pb.unmarshal` and `LiteWireReader`
  counted the root as depth 1 and refused the hundredth level. A document
  whose deepest point is exactly `MaxNestingDepth` (100) descents — every
  `{` or `[` in PXF, every submessage or map entry in PB — is now
  accepted and one more rejected, matching the AST parser and
  protowire-go ≥ v1.6. The corpus rows `pxf/deep-nesting-100`,
  `pxf/deep-nesting-lists-100` and `pb/deep-submessage-100` pass.
- **`MaxNumericLiteralDigits` is enforced** (#81, HARDENING.md § Mandatory
  limits): a PXF numeric literal with more than 4096 digits is rejected
  before any big-number parser sees it (`numeric literal has 5000 digits;
  MaxNumericLiteralDigits=4096`), one with exactly 4096 is accepted; the
  check sits in `WellKnown.parseBigInt` / `parseDecimal` / `parseBigFloat`
  so a `(pxf.default)` literal is under the same limit. The same constant
  bounds a `pxf.Decimal`'s `scale` on both signs: `Pb.unmarshal` refuses
  it before constructing the `BigDecimal`, and `WellKnown.readDecimalStr`
  before materialising `|scale|` digits (it used to prepend them one
  string at a time — 2^31 of them for a five-byte input). New constants
  `Limits.MAX_NUMERIC_LITERAL_DIGITS` and `Pb.MAX_NUMERIC_LITERAL_DIGITS`;
  `check-decode` links `adversarial.v1.BigNumHolder` for the pb rows.
  The Java row of protowire's `cross_security_check.sh` is 23/23.
- **A oneof member's `(pxf.default)` no longer destroys the arm the
  document chose** (#53). `FastDecoder.postDecode` read "absent" per
  field, but setting any member of a oneof clears the others, so a
  member is absent precisely when a sibling was chosen — `a = "written"`
  decoded to `b = "bbb"`, case `b`, the written value cleared. Both
  `(pxf.default)` and `(pxf.required)` now read the oneof's presence
  (draft `-01` §annotation-extensions "Oneof Members"): a default applies
  only when no member is present, a member bound to `null` counts as
  present, and a document that chooses a sibling of a `(pxf.required)`
  member is no longer rejected. A proto3 `optional` field's synthetic
  oneof is excluded, so its default keeps applying. This is the one
  place decoded output changes: identical to protowire-go ≥ v1.4.0.
  The bind-time halves of that section — at most one default per oneof,
  no `(pxf.required)` on a member — are #54.
- **`(pxf.default)` on a `repeated` or `map` field is a `PxfException`**
  (#52), `default values not supported for repeated field "tags"`, instead
  of a `ClassCastException` leaking out of protobuf-java's `setField`.
  The map message names the field rather than protobuf's synthetic
  `*Entry` type.
- A `(pxf.default)` literal the field cannot hold (`"abc"` on an `int32`,
  bad base64 on `bytes`, a malformed timestamp) is reported as
  `invalid (pxf.default) literal "abc" for field "n"` instead of a raw
  `NumberFormatException` / `IllegalArgumentException` /
  `DateTimeParseException`. A bool default binds exactly `true` or
  `false`; `"yes"` used to bind as `false` silently.

### Changed


- **`Pb` conforms to the reference on the wire** (#77, #78; STABILITY.md
  promise 2). The `pb` module zigzag-encoded every `int` / `long` — in
  fields, list elements, map keys and values — where protowire-go's
  `encoding/pb`, protobuf-go, protoc and the C++, TypeScript, C# and
  Swift ports write a plain varint (`int32` / `int64`), sign-extended to
  ten bytes for a negative value. Zigzag and plain agree on exactly one
  value, zero, so every non-zero integer this module wrote differed from
  the family (`30` was `3c`, not `1e`) and Go's `-1` read here as
  `Long.MIN_VALUE`. Now byte-identical to protowire-go v1.7.0 on the same
  struct, pinned by `PbGoldenTest` against a checked-in Go golden
  (`pb/src/test/resources/golden/`, generator beside it):
  - signed ints are plain varints; `@ProtoField(zigzag = true)` opts a
    field into `sint32` / `sint64`, the analogue of Go's `zigzag` tag
    option, inherited by list elements and map keys / values;
  - a `List` of numeric or `boolean` elements is packed, zeros included;
    other element types are one record per element, a zero element as
    its zero record; the reader accepts packed and unpacked input;
  - a map entry always carries its `key` and its `value`, zero-valued or
    not (protowire#295): the gate's `zero-map-entry` vector is
    `22062a040a001200`, where this port wrote `22022a00`; an entry lacking
    either field still reads as the zero value;
  - `pxf.Decimal.scale` is the plain `int32` varint `bignum.proto`
    declares (protowire-go#92): `3.1415` is scale `4` on the wire, not `8`.

  **Compatibility.** This is a fix, not a break — the port conforming to
  the reference — but bytes written by earlier versions of this module for
  any non-zero integer, packed list or `Decimal` scale were never readable
  by any other port and are not readable by the fixed one either. Data
  persisted through `Pb.marshal` before this release must be re-encoded
  from source; the descriptor-driven modules (`:pxf`, `:envelope`,
  protobuf-java generated code) are unaffected.
- `dump-envelope`, `dump-envelope-android` and `dump-envelope-pxf-android`
  gain `--vector NAME` (protowire#295): the spec repo's
  `testdata/envelope/NAME.textproto` encoded by the port's own pb codec
  (`:pb` for the JVM dumper, protobuf-javalite for the lite ones), for the
  gate to compare with a golden; an unknown name exits 3 with
  `not-implemented: NAME`.
- The lite modules fetch `protoc-gen-pxf-java-meta` at protowire's release
  tag `v1.12.0` instead of a commit hash (`gradle.properties`
  `pxfJavaMeta.ref`); same plugin source, now a named release.

## [1.1.0] — 2026-09-07

Minor release: the lite tier (protobuf-javalite) reaches Maven Central,
and the decoders conform to protowire's `docs/HARDENING.md`. No public
API is removed; the one behaviour change is that `Pb.unmarshal` now
rejects invalid UTF-8 in `String` fields (below).

### Added

- **Lite tier** (#60, PRs #61, #65, #66, #68, #69). Six new artifacts
  under `org.protowire`, all at `1.1.0`:
  - `protowire-pxf-runtime` — the descriptor-free half of `:pxf`
    (`Ast`, `Lexer`, `Parser`, `Format`, `Result`, `Position`, `Token`,
    `TokenKind`, `PxfException`, `Limits`) plus the lite-tier metadata
    contract (`PxfMeta`, `PxfRegistry`, `SimplePxfRegistry`, `PxfEnum`,
    `TimeFormats`). No dependency on protobuf-java. Packages are
    unchanged; `protowire-pxf` depends on it with `api`, so existing
    consumers get it transitively.
  - `protowire-sbe-runtime` — `View`, `ViewSchema`, `MessageTemplate`,
    `FieldTemplate`, `GroupTemplate` and `XmlSchema` move here under their
    existing `org.protowire.sbe` names (no source change for
    `protowire-sbe` consumers, which get the jar transitively), plus a
    descriptor-free wire codec in `org.protowire.sbe.runtime`
    (`SbeWireCodec`, `SbeFieldReader`, `SbeFieldWriter`, `SbeConstants`)
    for generated lite code.
  - `protowire-pxf-android` — PXF for protobuf-javalite messages:
    `LiteWireWriter` (PXF AST → wire bytes), `LiteWireReader` (wire bytes
    → PXF AST) and the `LitePxf` convenience, driven by `PxfMeta` classes
    that protowire's `protoc-gen-pxf-java-meta` plugin generates.
    Applies `(pxf.required)` and `(pxf.default)` from the registered
    extension numbers 1314–1318.
  - `protowire-envelope-android` — the `envelope/v1` messages compiled
    for javalite, with their `PxfMeta`.
  - `protowire-pb-android`, `protowire-sbe-android` — javalite-facing
    entry points that re-export `protobuf-javalite` and, for SBE,
    `protowire-sbe-runtime`.
- `:check-decode` (#64): the HARDENING conformance driver protowire's
  `scripts/cross_security_check.sh` runs against this port. Not published.
- Lite-tier harnesses for the spec repo's cross-port scripts
  (`dump-envelope-android`, `dump-envelope-pxf-android`,
  `bench-pxf-android`, `bench-sbe-android`) and the two codegen
  integration-test modules. Not published.

### Changed

- Building the lite modules compiles protowire's Go plugin
  `protoc-gen-pxf-java-meta`, from a sibling `../protowire` checkout when
  present and otherwise from a shallow fetch at the commit pinned in
  `gradle.properties` (`pxfJavaMeta.ref`). CI installs Go for it (#65).
- `org.protowire.pxf` and `org.protowire.sbe` are each split across two
  jars (`protowire-pxf` / `protowire-pxf-runtime`, `protowire-sbe` /
  `protowire-sbe-runtime`). Classpath consumers are unaffected; JPMS
  consumers cannot put both halves on the module path as named modules.
- `gradlew.bat` is committed with CRLF line endings via `.gitattributes`,
  so a checkout is no longer perpetually dirty on macOS/Linux (#67).

### Fixed

- **HARDENING.md conformance of the decoders** (#63). `MaxNestingDepth=100`
  is now enforced everywhere protowire-java recurses on input: the AST
  parser (`{` / `[` nesting), the descriptor-driven `FastDecoder`
  (submessage blocks, list elements, map values), `Pb.unmarshal`
  (submessages and map entries, with the counter threaded through every
  nested `CodedInputStream`) and the lite tier's `LiteWireReader`. Depth
  overflow is a `PxfException` / `IOException`, never a
  `StackOverflowError`. String fields are decoded as strict UTF-8: a
  `\xHH` or octal escape that does not form valid UTF-8 is an ILLEGAL
  token (`invalid UTF-8 in string literal`), and the PB readers use
  `readStringRequireUtf8` instead of the lossy `readString`. The new
  `org.protowire.pxf.Limits` class carries the shared constant. The
  Java row of protowire's `cross_security_check.sh` is now 16/16.
- `LiteWireReader.toAst` reports malformed wire input (truncated record,
  invalid UTF-8) as a `PxfException` with the protobuf diagnostic instead
  of an `IllegalStateException("CodedInputStream read failed")`.
- `check-decode` exits 2 with `crash: <Error>` on `StackOverflowError` /
  `OutOfMemoryError` instead of reporting them as a clean reject, so a
  missing depth cap can no longer pass the corpus's 100k-level row.

## [1.0.1] — 2026-05-13

Patch release fixing the Maven Central publish for v1.0.0. Two
javadoc `{@link Result#tables()}` references — in
`BindRow` and `ResultAccessorsTest` — survived the v1.0 rename
and broke the strict javadoc check inside the publish workflow.
Updated to `{@link Result#datasets()}`. No code or wire-format
change versus v1.0.0; the v1.0.0 tag exists in git but its
Maven Central artifact was never published.

## [1.0.0] — 2026-05-13

First major-version cut. Implements the three one-time spec changes
from the protowire v1.0 freeze line in lockstep with `protowire`,
`protowire-go`, and `protowire-typescript`. **Breaking** — there is
no alias period; v1.0 is itself the major bump. Maven group remains
`org.protowire`; the published artifact is `protowire-pxf` (and the
sibling modules `pb`, `sbe`, `envelope`, `proto-annotations`).

### v1.0 spec changes

Three one-time spec changes from the protowire v1.0 freeze line
(STABILITY.md). **Breaking** — there is no alias period; v1.0 is itself
the major bump.

- `@table` directive renamed to `@dataset` (draft §3.4.4). Public API
  follows: `Ast.TableDirective` → `Ast.DatasetDirective`, `Ast.TableRow`
  → `Ast.DatasetRow`, `TableReader` → `DatasetReader`,
  `Document.tables()` → `Document.datasets()`, `Result.tables()` →
  `Result.datasets()`. Source files `TableReader.java`,
  `TableReaderTest.java`, `TableParserTest.java` renamed accordingly.
  Decoder semantics unchanged.

- `@proto` directive added (draft §3.4.5). New `Ast.ProtoDirective`
  record + `Ast.ProtoShape` enum (`ANONYMOUS`, `NAMED`, `SOURCE`,
  `DESCRIPTOR`). Four body shapes lexically distinguished:
  `@proto { ... }` (anonymous), `@proto pkg.Type { ... }` (named),
  `@proto """..."""` (source), `@proto b"..."` (descriptor). Exposed
  via `Document.protos()` and `Result.protos()`. Descriptor form is
  the MUST-support shape per spec; this port supports all four.

- Reserved directive names expanded from 5 to 13 (draft §3.4.6).
  Decoder rejects `@table`, `@datasource`, `@view`, `@procedure`,
  `@function`, `@permissions` as spec-reserved (future-allocated).
  `SchemaValidator.FUTURE_RESERVED_DIRECTIVES` exposes the set.

`@dataset`'s row message type is now optional in the AST. When
omitted, the directive consumes the typed binding of a preceding
anonymous `@proto` per draft §3.4.4 Anonymous binding.

`Lexer.repositionTo(int)` added for the parser's `@proto` brace-body
skip (interior is protobuf source, not PXF, so the lexer hops past
the body rather than tokenising it).

## [0.75.0]

Catch-up release. First tagged version after v0.70.0; brings the Java
port up to the v0.75.0 protowire spec in four merged feature batches
(parser-side v0.72/v0.73 grammar, schema reserved-name check, Result
accessors, TableReader streaming + Scan/BindRow). Aligns the
`protowire-java` version number with the rest of the `protowire-*`
stack, which is at v0.75.0 across `protowire` and `protowire-go`.
Wire format unchanged across all four batches.

### Added

- **`TableReader` streaming + `scan(Message.Builder)` + `BindRow` helper.**
  Companion to `protowire-go` v0.74 (`pxf.TableReader`) and v0.75
  (`TableReader.scan` / `BindRow`). Reads rows from an
  {@link java.io.InputStream} one at a time with working-set memory bounded
  by the size of the largest single row — the shape consumers need for
  CSV-replacement datasets that don't fit in memory.

  ```java
  try (var tr = new TableReader(in)) {
      while (true) {
          var b = AllTypes.newBuilder();
          if (!tr.scan(b)) break;
          process(b.build());
      }
  }
  ```

  Cell-state semantics match `BindRow`: a `null` cell leaves the field
  absent (`pxf.default` applied, `pxf.required` errors), an `Ast.NullVal`
  cell clears wrappers / optional / oneof per §3.9, any other value sets
  the field. WKT timestamps and durations, enum-by-name, proto3 wrappers
  all bind correctly because `BindRow` re-uses the existing unmarshal
  pipeline (format-and-reparse).

  Implementation: byte-level row-boundary scanner pulls bytes from the
  source `InputStream` on demand and slices one `( ... )` row range at a
  time, which is then handed to `Parser.parseTableRow` for cell decoding.
  The scanner is string / bytes-literal / line-comment / block-comment
  aware so embedded parens or `)` inside literals don't trip it. Header
  parsing reuses `Parser.parse()` against the buffered header prefix, so
  the standalone constraint and dotted-column rejection get the same
  enforcement the materializing path uses. Header byte budget caps at
  64 KiB — fail-fast against a `TableReader` pointed at a giant
  body-only document with no `@table` ever.

  Multi-table documents chain via `tr.tail()`, which returns an
  `InputStream` yielding the bytes the reader buffered but didn't consume
  followed by the remaining source.

  Public API additions:
  - `TableReader(InputStream)`, `type()`, `columns()`, `directives()`,
    `tail()`, `next()`, `scan(Message.Builder)`
  - `BindRow.bindRow(Message.Builder, List<String>, Ast.TableRow)`

  Errors are sticky: once `next()` or `scan` throws, subsequent calls
  rethrow the same exception (matches the Go port's contract).

  Tests in `TableReaderTest` (24 cases): basic streaming, three cell
  states, side-channel directives before header, sticky errors,
  list/block cells rejected mid-stream, strings / block + line comments
  with embedded parens, byte-at-a-time `InputStream` (adversarial for
  buffer boundaries), multi-table via `tail()`, equivalence with the
  materializing path, oversized-header rejection, `scan` happy path,
  `scan` empty-cell-leaves-field-at-zero, `null`-on-wrapper clearing,
  WKT timestamp binding, `BindRow` against the materializing path,
  arity mismatch, non-leaf-cell rejection.

- **`Result.directives()` / `Result.tables()` accessors.** `FastDecoder`
  used to consume named directives and `@table` directives at the
  document head without storing them (PR #35 parser-side port did the
  minimum needed for the body decode path to succeed on documents that
  carry them). This PR replaces that skip-and-discard with capture: when
  `unmarshalFull` is called, the decoder builds full `Ast.Directive` and
  `Ast.TableDirective` records and exposes them via the new
  `Result.directives()` and `Result.tables()` accessors. Plain
  `unmarshal` still skips for efficiency (no `Result` to attach to).

  This is the same shape `protowire-go`'s `Result.directives()` /
  `Result.tables()` ship: chameleon-style consumers iterate
  `result.directives()` and hand each `Directive.body()` back to
  `unmarshalFull` against a chosen message; CSV-replacement consumers
  iterate `result.tables()` and walk per-row `cells`.

  Cell-state semantics in captured rows match the spec (§3.4.4):
  `null` entries in `TableRow.cells` ⇒ absent (empty cell between
  commas), `Ast.NullVal` ⇒ present-but-null, any other `Ast.Value` ⇒
  present.

  Bug fix folded in: the prior `skipNamedDirective` re-seated the
  lexer past a directive's closing `}` without recomputing line/col,
  so post-block tokens reported the pre-block position. Replaced
  with `Lexer.lineColAt(int)` that scans for the correct line/col
  from the byte offset.

  Public API additions: `Result.directives()`, `Result.tables()`.

  Tests in `ResultAccessorsTest`: multi-directive order preserved,
  prefix-list exposed (zero / one / two-prefix shapes), `@table`
  rows captured with byte-for-byte cell-state mapping, all 9 cell
  value variants round-trip, empty-doc has empty directives/tables.

- **PXF schema reserved-name check** (draft §3.13). New
  `SchemaValidator.validateFile(FileDescriptor)` /
  `SchemaValidator.validateDescriptor(Descriptor)` walk a protobuf
  descriptor and return every message-field, oneof, or enum-value name
  that case-sensitively collides with `null`, `true`, or `false` — names
  that lex as PXF value keywords and produce silently-unreachable
  bindings. Each violation is a `SchemaValidator.Violation` record
  carrying `(file, element, name, kind)`; the kind is one of `FIELD`,
  `ONEOF`, `ENUM_VALUE`.

  Runs by default at the top of every `unmarshal` / `unmarshalFull`
  call and rejects non-conformant schemas with a `PxfException` before
  any decoding happens. Callers that have already validated their
  descriptors (registry-load passes, codegen pre-screening) can set
  `UnmarshalOptions.skipValidate(true)` to bypass the per-call recheck.

  The check is case-sensitive: identifiers like `NULL`, `True`,
  `FALSE` lex as ordinary identifiers and are accepted. Synthetic
  oneofs introduced for proto3 `optional` are skipped (their name is
  `_<fieldname>`, never in the reserved set, and they'd double-count
  an already-reported field violation).

  Public API additions: `SchemaValidator`, `SchemaValidator.Violation`,
  `SchemaValidator.Kind`, `UnmarshalOptions.skipValidate()`,
  `UnmarshalOptions.withSkipValidate(boolean)`.

  Tests construct adversarial schemas via `FileDescriptorProto` and
  `FileDescriptor.buildFrom` (protoc's Java code generator rejects
  some of these names at codegen, but the validator operates on the
  descriptor tier so it catches schemas built from registry-loaded
  or hand-constructed `FileDescriptorProto` values too). Covers all
  three element kinds, nested messages, case-sensitivity (uppercase
  accepted), stable sort order, decoder integration via `unmarshal`,
  and the `skipValidate` bypass.

  Note: protobuf-java's `EnumValueDescriptor.getFullName()` scopes
  enum values under their enum (`trades.v1.Side.null`); Go's
  protoreflect lifts them to the enclosing package
  (`trades.v1.null`). Both readings are valid; the validator rule
  ("the bare name collides with a PXF keyword") is identical.

- **PXF parser-side support for v0.72/v0.73 grammar features.** Brings the
  Java parser tier up to the v0.73.0 protowire spec for three grammar
  additions:

  - **v0.72 named directives** (`@<name> [<type>] [{ ... }]` at document
    root). The lexer recognizes `@<name>` for any identifier (previously
    only `@type` parsed; everything else was `ILLEGAL`). New `AT_DIRECTIVE`
    token kind, new `Ast.Directive` record, new `Document.directives()`
    accessor. The block's raw bytes are exposed via `Directive.body()` for
    sub-parsing against a consumer-supplied message — same shape as
    `protowire-go`'s `Result.directives()`.

  - **v0.73 `@entry` + zero-or-more prefix list.** `named_directive` now
    accepts zero, one, or more prefix identifiers between `@<name>` and
    the optional `{ ... }` block (was just one). The parser uses one-token
    lookahead so an IDENT followed by `=` or `:` (a body field key)
    doesn't get eaten as a directive prefix. `Directive.prefixes` exposes
    the full list; the legacy `Directive.type` is preserved (populated
    when `prefixes.size() == 1`) for back-compat with v0.72-era consumers.

  - **v0.73 `@table` directive.** The protowire-native CSV replacement:
    `@table <type> ( col1, col2, ... )` header followed by zero-or-more
    parenthesized row tuples. New `AT_TABLE`/`LPAREN`/`RPAREN` token kinds,
    new `Ast.TableDirective` and `Ast.TableRow` records, new
    `Document.tables()` accessor. Three-state cells: `null` in the cells
    list ⇒ absent (empty cell), `NullVal` ⇒ present-but-null, any other
    `Value` ⇒ present. Parser enforces: row arity, dotted-column
    rejection, list/block-cell rejection, and the standalone constraint
    (a `@table` document MUST NOT carry `@type` or top-level field
    entries). Timestamp lexer's terminator set grew `)` so values like
    `2026-05-12T10:00:00Z` inside a row don't eat the closing paren.

  This is a **parser-side port only**. `FastDecoder` was updated to
  *consume* the new directive forms gracefully (so existing decode flows
  don't break on documents that carry them), but does not yet surface
  directives or tables on `Result`. Follow-up PRs:

  - `Result.directives()` / `Result.tables()` accessor wiring on the
    decode path.
  - Schema reserved-name check (`SchemaValidator`).
  - `TableReader` streaming over `InputStream`.
  - `TableReader.scan(Message.Builder)` + `BindRow` helper.

  Public API additions: `Ast.Directive`, `Ast.TableDirective`,
  `Ast.TableRow`, `TokenKind.AT_DIRECTIVE`, `TokenKind.AT_TABLE`,
  `TokenKind.LPAREN`, `TokenKind.RPAREN`, `Document.directives()`,
  `Document.tables()`, `Position.offset()`.

  **Backward-compat note on `Position`:** the record gained a third
  component `offset` (0-based byte index into the input, used by directive
  body extraction). Existing callers using `.line()` / `.column()`
  accessors are unaffected; callers constructing `new Position(line, col)`
  go through a back-compat constructor that defaults offset to 0.

  Motivating use case: the JetBrains plugin embeds this parser as
  `protowire-pxf.jar`. Before this PR it shows red squiggles on every
  `@<name>` / `@entry` / `@table` document; after the jar is refreshed,
  those forms highlight cleanly.

## [0.70.0]

Initial public release. The version number aligns this port with the rest
of the `protowire-*` stack, which targets the 0.70.x series for the first
coordinated public release.

### Added

- **Maven Central publishing** under the `org.protowire` namespace.
  Artifacts: `org.protowire:protowire-{pb,pxf,sbe,envelope,proto-annotations}:0.70.0`.
  Sources jars and Javadoc jars are signed and uploaded automatically
  via `.github/workflows/publish.yml` on every `v*.*.*` tag.
- **GraalVM native-image reachability metadata** (`META-INF/native-image/org.protowire/<artifact>/`)
  in every published jar — silences the "missing reachability metadata"
  warning at native-image build time. Consumers of `:pb` still need to
  register their own `@ProtoField`-annotated DTOs (documented in the
  per-module `native-image.properties`).
- **CI**: `./gradlew build` (compile + test all 8 modules) on every PR
  and push to `main`, plus weekly CodeQL SAST.
- **Per-module Jacoco coverage** — every test task is finalised by a
  `jacocoTestReport`; CI uploads the per-module reports as artifacts.
- **Governance scaffolding**: `LICENSE` (MIT), `CONTRIBUTING.md`,
  `SECURITY.md` (security@trendvidia.com), `GOVERNANCE.md`,
  `CODE_OF_CONDUCT.md`, `.github/CODEOWNERS`, issue + PR templates,
  Dependabot for Gradle + GitHub Actions.

### Changed (breaking)

- **Maven group renamed** from `com.trendvidia.protowire` to `org.protowire`.
  Java packages renamed in lockstep:
  - `com.trendvidia.protowire.pxf.*` → `org.protowire.pxf.*`
  - `com.trendvidia.protowire.pb.*` → `org.protowire.pb.*`
  - `com.trendvidia.protowire.sbe.*` → `org.protowire.sbe.*`
  - `com.trendvidia.protowire.envelope.*` → `org.protowire.envelope.*`
  - `com.trendvidia.protowire.proto.{pxf,sbe}.*` → `org.protowire.proto.{pxf,sbe}.*`
- **PXF parser stricter on key forms**, mirroring the upstream grammar
  tightening in
  [`trendvidia/protowire@8262bbb`](https://github.com/trendvidia/protowire/commit/8262bbb)
  (`docs/grammar.ebnf`, `docs/draft-trendvidia-protowire-00.txt`):
  - `=` (field assignment) and `{ … }` (submessage) now require an
    identifier key. Inputs like `123 = 234` or `child { 123 = 123 }`
    are now parse errors with
    `"field assignment with '=' requires an identifier key, got integer
    (\"123\"); use ':' for map entries"`.
  - `:` (map entry) is rejected at document top level — the document
    represents a proto message, never a `map<K,V>`. Use `=` for
    top-level field assignments. Map literals (`field = { 1: "x" }`)
    still work because `:` remains valid inside `{ … }` blocks.
