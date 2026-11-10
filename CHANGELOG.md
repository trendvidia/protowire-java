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

## [2.0.1] — 2026-11-10

The first 2.0 release on Maven Central. It holds everything listed under 2.0.0
below, and nothing else changes for users. The v2.0.0 tag was cut, but its
publish stopped on two javadoc errors before any artifact was released, so no
2.0.0 artifacts exist. Release tags are protected, so that tag stays where it
is, and this release carries the fix.

### Fixed

- Two javadoc comments in `pxf-runtime` (`Format`, `MarshalOptions`) had an
  unterminated `{@code}` tag, which failed the javadoc task in the publish
  workflow. CI now builds javadoc on every push, so this is caught before a
  release (#253).

## [2.0.0] — 2026-11-10 (tagged, never published)

A major release, for two reasons. The full tier (`protowire-pb`, `-pxf`, `-sbe`,
`-envelope`, `-proto-annotations`) now needs protobuf-java 4.36.2, up from
3.25.5. And the PXF tree API changes in ways a compiler notices:

- `Ast.BadVal` is a new variant of the sealed `Ast.Value`.
- Every entry and value record gains an `end` component.
- `UnmarshalOptions` gains components.

Old constructors are kept, so code that builds these records compiles. Code that
switches over `Ast.Value` without a `default`, or takes these records apart with
a record pattern, must change. The lite tier's dependency (protobuf-javalite
4.36.2) is unchanged.

The release brings Java to protowire-go v1.17.0's feature set. It adds a tolerant
parse and keyed diagnostics for editors, an in-place `Rewriter`, the `@dataset`
writer, the `OnSecretField` and `Validator` hooks (in a new `protowire-check`
artifact), stream framing for pb, SBE and envelopes, and lite-tier `@dataset`
binding.

Both tiers match Go's verdicts and error texts on the spec corpus. A few
documents now parse differently, as Go's parser does:

- URL-safe base64 in `b"…"` is accepted.
- Timestamps follow Go's RFC 3339.
- A precision suffix on a map key is refused.

The Fixed entries below list each case. CI holds Java to seven tables that
protowire-go writes, regenerated against protowire-go main on every PR. No
wire-format change.

### Added

- **`Rewriter`: format-preserving edits to a PXF document, as protowire-go's `Rewriter`** (#209). It splices replacement bytes into the source, so everything outside an edit stays byte for byte as it was: comments, blank lines, key order, indentation, number and string spellings. It is the write path for tools that edit a document a person also edits by hand.
  - **Edits:** `set` and `remove` take a dotted path. `replaceValue` takes a node. `setSpan` takes raw bytes. `appendEntry` adds to a block.
  - **Keyed editors** (draft -01 §3.13) take an element key as an opaque atom: `setKeyed`, `removeKeyed`, `insertKeyedElement`, `removeKeyedElement`, `renameKeyedElement` and `moveKeyedElement`, with `keyedByField` for the anonymous list form.
  - **Output:** `bytes()` applies the edits, refuses overlapping ones, and parses the result before returning it. An edit the document cannot take throws `IllegalArgumentException` with Go's text, and a `BadVal` is refused as Go refuses it.
  - **Evidence:** `RewriterTest` holds it to Go on 408 cases, byte for byte, including each error's text. They are all of Go's rewriter tests, its property tests expanded over its rewrite corpus, and its anonymous-list comment matrix. `scripts/go-rewrite/main.go` writes Go's table from `rewrite/cases.txt`, and the conformance job reruns it against protowire-go main.

- **Every tree node carries its end, and the document its body offset, as protowire-go's** (#209). `Ast.Entry.end()` and `Ast.Value.end()` give the position just past the node's last byte, so a node spans `pos()` to `end()`, as Go's `EntrySpan` and `ValueSpan`. `Ast.Document.bodyOffset()` is the byte where the body begins, after the last directive.
  - **Record change:** each entry and value record gains an `end` component. Every existing constructor is kept, and sets `end` to the node's `pos`. A node built in code spans nothing, as Go's does. Code that takes these records apart with a record pattern must add the component.
  - **Evidence:** the tolerant-parse table (#208) now holds every node's span and the body offset to Go's, on its 1021 documents.

- **`Pxf.keyedDiagnostics(doc, desc)`: every keyed-repeated-field error in a parsed document, as protowire-go's `KeyedDiagnostics`** (#208; draft -01 §3.13). The decoder throws the first. Editor tooling runs `Pxf.parseTolerant`, binds a schema, and reports them all. They cover duplicate entry names, empty keys, a key-field binding that disagrees with or undoes its entry name, and a quoted entry name outside a keyed block.
  - **`KeyedException`, a `PxfException` with Go's `KeyedErrorKind`:** `kind()`, `field()` and `key()`. The decoder now throws it for each of these errors, with the same text as before, so a caller can tell the causes apart, as Go's `errors.As` does.
  - **Evidence:** `KeyedDiagnosticsTest` holds the decoder's error and the diagnostics to Go's, kind, position, field, key and text, on every `testdata/keyed/` document and Go's own key-binding tests, 39 in all. `scripts/go-keyed-diagnostics/main.go` writes Go's table, and the conformance job reruns it against protowire-go main.

- **`Parser.parseTolerant`: a parse that recovers from a broken document and returns every error, as protowire-go's `ParseTolerant`** (#208). It is for editor tooling, which needs a tree exactly while the buffer is broken: completion mid-keystroke, hover on a half-typed entry. It recovers where Go does: a missing value becomes an `Ast.BadVal`, an unclosed block or list closes at the end, and an unterminated string ends at the newline. It returns the tree with every error, in source order. A valid document gives `Parser.parse`'s tree and no error.
  - **New `Ast.Value` variant:** `Ast.BadVal` is added to the sealed `Ast.Value`, as decided on #208. **An exhaustive `switch` over `Ast.Value` with no `default` no longer compiles** until it handles `BadVal`. Only `parseTolerant` makes one. `Format` writes it as nothing, as Go does.
  - **Evidence:** `TolerantParseTest` holds Java to Go on 1021 documents: the same errors, at the same positions, and the same tree. They are Go's own tolerant-parse tests, every prefix of five documents typed top to bottom, a document for each recovery, and the whole spec corpus. One prefix is pinned against #247. `pxf-runtime/src/test/resources/tolerant/go.tsv` is Go's, from `scripts/go-tolerant/main.go`, and the conformance job reruns it against protowire-go main.

- **The lite tier binds `@dataset` rows: `LiteWireWriter.encodeRows` gives each row of a dataset as its message's PB bytes** (#212). Parse them with the row type's generated `parseFrom`. `LiteWireWriter.encodeRow` binds one row. A row binds as the full tier's `BindRow` and protowire-go's `BindRow` bind it: dotted columns, list, block and `null` cells, `(pxf.required)` and `(pxf.default)`. A row the type refuses fails with the same error at the row, naming its column, as `7:1: column int32_field: expected integer for field "int32_field"`. Before, lite parsed a dataset but could not bind it, so a row that broke its type went unnoticed.
  - The two tiers now share the step that writes a row as the body that binds it, `org.protowire.pxf.RowBody` in `:pxf-runtime`.
  - `scripts/go-verdicts/corpus.tsv` now covers `testdata/dataset/`: 43 more documents, each row bound with Go's `BindRow`. Both tiers meet Go's verdict and text on every one, and lite writes Go's bytes for each of the 26 rows Go accepts.
- **`Ast.Document.trailingComments`, `Ast.Block.trailingComments`, `Ast.BlockVal.trailingComments` and `Ast.DatasetRow.leadingComments`: the comments that no entry follows** (#200, protowire-go#279). The parser fills them in. Each record keeps its existing constructors, which leave the new list empty. Code that takes these records apart with a record pattern must add the new component.

- **`Ast.Assignment.equalsPos`, `Ast.Block.bracePos` and `Ast.TypeEntry.urlPos`: where an entry's `=`, its `{` and its type URL stand** (#222). The parser fills them in. Each record keeps its existing constructors, which set the new position to the entry's own `pos`. Code that takes these records apart with a record pattern must add the new component.

- **Stream encoders and decoders for pb, SBE and envelopes, as protowire-go's** (#207). Java can now read and write the message streams Go writes, byte for byte.
  - **pb:** `org.protowire.pb.Encoder` and `Decoder` frame each message with a varint length.
  - **SBE:** `Codec.newEncoder` and `Codec.newDecoder` frame each message with a Simple Open Framing Header (SOFH), encoding type 0xEB50.
  - **Envelope:** `org.protowire.envelope.Encoder` and `Decoder` reuse pb's framing.
  - **Errors:** a frame past the decoder's maximum (16 MiB by default), a varint that overflows, or a bad SOFH header throws a `FramingCorruptException` with Go's text. A stream that ends inside a frame throws `unexpected EOF`. Both are thrown again on every later call. A message that fails to decode fails alone, and the next frame still reads.
  - **Scope:** full tier only (decided on #205).

- **A `Validator` hook on decode, as protowire-go's `check` package** (#205).
  - **New module:** `check` (artifact `protowire-check`, package `org.protowire.check`) holds the seam: `Validator`, `Violation`, `Report`, `CheckException` (Go's `check.Error`, with the same message) and `Check.validate`. It has no dependencies, so validation engines plug in without the codecs depending on them.
  - **Where it runs:** pxf's `UnmarshalOptions.withValidator`, pb's `UnmarshalOptions.withValidator` and sbe's `Codec.withValidator` run it after a successful decode, as Go's decoders do. Dataset rows run it too.
  - **Outcome:** a report with violations fails the decode with a `CheckException` carrying the report, and an engine's exception propagates. On a clean pass, `Result.report()` returns the report.
  - **Record change:** the two option records gain a `validator` component, with the shorter constructors kept.

- **`UnmarshalOptions.withOnSecretField`: a hook that receives each `pxf.Secret` value in place of the message, as protowire-go's `OnSecretField`** (#206). A caller can route secrets to protected memory and keep them out of the decoded message.
  - **Where it's called:** in every form a Secret takes: the literal, a list element, a map value, and the block, which goes through Go's Secret block decoder with Go's errors. A dataset row's cells call it too.
  - **What it's given:** each value's dotted path in Go's spelling, as `servers[0].password` or `tenant_keys["acme"]`.
  - **What happens to the field:** the Secret is left present with `value` unset, and `Result` still reports `value` set. `hint` and `fingerprint` are stored as usual.
  - **Errors:** an exception from the hook aborts the decode as `pxf.Secret hook for "<path>": <message>`.
  - **Record change:** `UnmarshalOptions` gains the record component `onSecretField`, and the five-argument constructor is kept.
- **`Pxf.appendDatasetHeader` and `Pxf.appendDatasetRow` write `@dataset` tables, as protowire-go's `AppendDatasetHeader` and `AppendDatasetRow` do** (#204). Java could read a table but not write one.
  - **Header:** the columns are checked as the reader checks them, dotted paths included, and so is the schema. A `(pxf.required)` field missing from the columns is refused.
  - **Rows:** a set field is written as `Pxf.marshal` writes its value, and a list, map or block on one line. An unset field is an empty cell, except where reading one would not give the field back, in which case it follows Go's rules: a zero value, `[]` or `{}`, or `null`. An unset required field with presence is refused, and so is a row the reader would reject for a required sub-message.
  - **Evidence:** `DatasetWriterTest` holds 48 cases on protowire-go's own writer-test schemas to Go's header, row or error, word for word. It holds each row's read-back through `DatasetReader` to the message Go's reader scans from it. The conformance job reruns `scripts/go-dataset-write/main.go` against protowire-go main and diffs the table.
- **Helpers protowire-go exports, public in Java too** (#210):
  - `Format.formatValue(Ast.Value)` and `Format.appendValue(StringBuilder, Ast.Value)`: one value as its PXF literal, as Go's `FormatValue` and `AppendValue` write it.
  - `Pxf.keyFieldName`, `Pxf.keyField` and `Pxf.isKeyed`: the `(pxf.key)` annotation, as Go's `KeyFieldName`, `KeyField` and `IsKeyed` read it.
  - `Result.presentFields()`: the paths a document set, to a value or to null, as Go's `PresentFields`. `Pxf.applyDefault`'s javadoc used to point at a package-private method, and now names this one.
  - SBE `View.enumValue(name)`: an enum field's number, as Go's `View.Enum`.

- **Both tiers run the spec's whole per-port corpus, from one copy CI keeps equal to the spec** (#211). `testdata/` holds protowire's `testdata/{annotations,any,bigfloat,blocks,dataset,duplicates,duration,keyed,map-keys,nulls,repeated,strings}/` and `test.proto` byte for byte, schemas included. The full tier compiles the schemas as test protos. `lite-fixtures` generates them lite with their companions, adding the `java_package` that `protoc-gen-pxf-java-meta` requires at build time. `LiteCorpusTest` runs every fixture through `LiteWireWriter` and `LiteWireReader`. Before, the lite tier ran hand-typed copies of two directories and none of the rest, and the full tier ran no `duration` or `repeated` fixture. `scripts/check-spec-corpus.sh`, run by the conformance job, fails when a copy differs from spec main or when the spec has a corpus directory with no copy. A fixture a tier does not meet is listed against its issue, and a listed fixture that starts passing fails its test. The first run found the lite gaps #213 (Any `@type` blocks), #214 (a scalar on a repeated field), #215 (three error texts) and more of #191 (`IllegalArgumentException`), and both tiers' #189 cases from protowire#569.

- **The lite tier reads and writes fields of `google.protobuf.Any`, `Empty`, `FieldMask`, `Struct`, `Value`, `ListValue` and `NullValue`** (trendvidia/protowire#539). A lite schema with such a field did not compile, because its generated meta named `com.google.protobuf.AnyPxfMeta`, which nothing generates. `:pxf-runtime` now ships these companions in `org.protowire.pxf.wkt`, as `protoc-gen-pxf-java-meta`'s output for the four google files, and the plugin from trendvidia/protowire#550 on points there. They are written as ordinary blocks, the same text protowire-go writes. An Any is written as `type_url` and `value`, since the lite tier has no type resolver. `pxf-runtime/scripts/regen-wkt-companions.sh` regenerates the companions.

- **Producers refuse a message nested past `MaxNestingDepth`** (#167; HARDENING.md § Recursion, protowire#502). `Pxf.marshal` counts each `{` and `[` it writes, as a decoder of the text counts them. `Pb.marshal` counts each submessage and map entry. Past the limit, `Pxf.marshal` throws `PxfException` and `Pb.marshal` throws `IOException`, both with the message `nesting depth exceeds MaxNestingDepth=100`. Before, they wrote a document that a reader at the same limit refuses, and a reference cycle in a `Pb` struct overflowed the stack. The limit is per call: `MarshalOptions.withMaxNestingDepth` in `pxf`, and the new `org.protowire.pb.MarshalOptions` with `Pb.marshal(Object, MarshalOptions)`. An Any whose body would go past the limit is still written, as `type_url` and `value`. `check-decode --format pb-render` now reads its input with the stock reader's limit raised, so the corpus's 101-level rows reach the producer.
- **`Convert.xmlToProto` reads a `char` type's `characterEncoding`** (#173; draft `-01` § Character Encoding, protowire#494). A `char` becomes a `string` only when the encoding is absent (the FIX SBE default, US-ASCII) or names US-ASCII or UTF-8, by IANA name or alias, ignoring case and punctuation. Any other encoding becomes `bytes` of the same `(sbe.length)`, with a comment that says why. Before, every `char` was a `string`, so a schema declaring `ISO-8859-1` imported cleanly, and the first byte outside UTF-8 then failed the whole message. This follows protowire-go#248.
- **A generated lite `<Message>SbeCodec` takes per-call limits** (#148): `unmarshal(data, maxMessageSize, maxRepeatedCount)` and the same with a builder. This needs `protoc-gen-pxf-java-meta` from trendvidia/protowire#541 on. `check-decode-android` now decodes SBE through the generated codec, so the security gate tests the entry point a caller uses, not only `SbeWireCodec` underneath it.
- **`check-decode` and `check-decode-android` gain `--format pxf-pb`** (protowire#509), for the spec's new write gate. It reads a PXF document and writes the PB bytes on stdout. The full tier decodes with `unmarshalFull`, as `dump-envelope` does, and the harness sorts map entries by key before writing, because protobuf-java writes a `DynamicMessage`'s map entries in insertion order. On its first run the gate found 20 pairs that differ from protowire-go, filed as #156, #157 and #158.

- **`PxfMeta.implicitPresenceFields()`** (#156): the singular fields with no presence, which a writer leaves out at their zero value. The default is empty. `protoc-gen-pxf-java-meta` emits `IMPLICIT_PRESENCE_FIELDS` from trendvidia/protowire#532 on, so a companion generated by an older plugin keeps writing every zero.

### Changed

- **The full tier needs protobuf-java 4.x: it now depends on 4.36.2, up from 3.25.5** (#190). This is a dependency major bump for users of `protowire-pxf`, `protowire-pb`, `protowire-sbe`, `protowire-envelope` and `protowire-proto-annotations`, so it belongs in the release notes. Code generated with protoc 3.25.5 still runs on the 4.36.2 runtime. The lite tier was already on protobuf-javalite 4.36.2.

- **`Convert.protoToXml` writes a `bytes` field as a `uint8` array** (#179; draft `-01` § Fixed-Length Arrays). A `bytes` field with `(sbe.length) = N` was written as a `char` array, which `Convert.xmlToProto` reads back as a `string`, so a `bytes` field did not survive a round trip. It is now `primitiveType="uint8" length="N"`, declared once per length as `bytesN` beside the `strN` types. The wire bytes are the same. A `bytes` field of length 1 is written as `length="1"`, which FIX SBE reads as a single `uint8`, so it reads back as `uint32` with `(sbe.encoding) = "uint8"`: the same byte in the block, not `bytes`. This follows protowire-go#251.
- **`Convert.xmlToProto` refuses an array of a primitive wider than a byte** (#179). This narrows what imports. A type such as `primitiveType="int32" length="4"` used to import as one `int32`, which misplaced every later field in the block. No proto field holds such an array, so the import now throws `IllegalArgumentException` naming the type, the field and the message.
- **The lite tier prints nothing for a plain field whose wire value is zero** (#180). `LiteWireReader` used to print `f_int32 = 0` for `2800`, where protowire-go and the full tier print nothing: a plain proto3 field has no set state, so a zero on the wire means the same as no field. A field in `PxfMeta.implicitPresenceFields()` whose last value is its zero value is now left out. A `-0.0`, an `optional` field and a map entry's key and value keep their zeros. A companion from an older plugin has an empty table and prints as before.

- **Both tiers write PXF text to the same PB bytes as protowire-go** (#156, #157, #158; the spec's write gate, protowire#511). Each change below used to make a valid document write different bytes from Go, or accept one Go refuses:
  - A `float` or `double` literal that rounds to infinity is refused, as `invalid double: 1e400` or `invalid float: 3.4028236e38`. Draft `-01` § Numeric Literals admits infinity only as `inf`, `+inf` or `-inf`. Both tiers used to write it as infinity.
  - A `pxf.Decimal` negative zero keeps its sign: `-0` sets `negative`, and `-0.00` sets it with scale 2. Both tiers used to drop it on the way through `BigDecimal`, which has no negative zero. The lite tier also prints `{negative: true}` as `-0` now, as the full tier does.
  - The lite tier refuses a `pxf.Decimal` literal with an exponent (`1e3`, `1E-2`) as `invalid decimal`, as Go and the full tier do. It used to accept it.
  - The lite tier writes fields in field-number order, as protobuf says a serializer should. It used to write them in document order.
  - The lite tier leaves out a field with no presence (a proto3 plain scalar or enum) that holds its zero value, such as `f_int32 = 0` or `f_string = ""`. A `-0.0` is not a zero value, so it is still written, and so is a zero in a map entry, an `optional` field or a proto2 field. This needs a companion with `IMPLICIT_PRESENCE_FIELDS`; with an older one every zero is still written.
- **The lite tier's `LiteWireWriter` writes map entries in key order** (protowire#509): false before true, integers by value, strings by UTF-8 bytes, as protowire-go's deterministic writer does. It used to write them in document order. Protobuf leaves map order on the wire open, so readers are not affected, but bytes for a map with keys out of order change.

- **`Pb.marshal` writes null and zero big numbers as protowire-go does, and a `BigFloat` in the form a decoder holds it** (#159). A null `BigInteger` or `BigDecimal` element in a `List` threw `NullPointerException`; it is now an empty message, the zero value's record, as Go writes a nil `*big.Int` element, so the list keeps its length. A null `BigFloat` element was dropped, and a null `BigFloat` map value was written as an entry with no value field; both are an empty message now. A `BigFloat` is normalised before it is written: the mantissa at exactly `prec` bits (a wider one rounded half even, a narrower one widened), no exponent for a zero, and a prec-0 record is ±0 whatever its mantissa. So `{03, -1, 64}` (1.5) goes out as `{c000000000000000, -63, 64}`, as Go writes it, and a `+0` at prec 0 is omitted as a singular field. The value is unchanged; only its bytes change. A `BigFloat` whose prec needs more than `MaxNumericLiteralDigits` digits, or whose binary exponent after rounding is past `MaxBigFloatExponent`, is refused with an `IOException`, as Go refuses it. The rule is `BigFloats.normalize` in `:pxf-runtime`, which `:pb` now depends on; it is the form the PXF decoder already reads a BigFloat in.
- **`Pb.unmarshal` bounds a `BigFloat`'s prec, and reads prec 0 as zero at any exponent** (#159; protowire#499). A prec whose literal needs more than `MaxNumericLiteralDigits` digits (13604 bits and up at the default) used to decode, and is now refused as protowire-go refuses it. A prec-0 message with a mantissa and an exponent past `MaxBigFloatExponent` used to be refused, although its value is zero; it now decodes, as in Go.

- **`Pb.marshal` writes any NaN as the canonical quiet NaN** (#161; draft `-01` § Numeric Literals). A `double` or `float` field, singular or in a list, holding a NaN with a payload used to go out with that payload, because `CodedOutputStream.writeDouble` keeps the raw bits. Now it goes out as `0x7ff8000000000000` (or `0x7fc00000`), as protowire-go writes it from a struct. `Double.NaN` and `Float.NaN` already had those bits, so only a NaN a program built from bits changes. Generated and dynamic messages still write their fields as they are, and PXF `nan` already decoded to the canonical NaN in both tiers.

- **`Pb.marshal` writes a `BigDecimal` in reduced form, as protowire-go does** (#154; protowire#508). `new BigDecimal("1.00")` used to go out as unscaled 100 and scale 2, while Go writes unscaled 1 and no scale. Now `1.00` is written as `1`, `5E+2` as `500`, and a zero as an empty message. The number is the same; only its bytes change, so any golden file holding `Pb` output for a scaled `BigDecimal` will differ. A value whose reduced scale is past `MaxNumericLiteralDigits` (4096) is refused with an `IOException`, as Go refuses it, since every conformant reader rejects those bytes. Reading is unchanged and still accepts every form. Generated and dynamic messages and PXF text are not affected. `PbGoldenTest` gains Go-generated rows for each case, from protowire-go f8a01b1.

### Fixed

- **A timestamp literal is accepted, refused and read as protowire-go reads it** (#247). Java read timestamps with `ISO_OFFSET_DATE_TIME`, and Go with `time.Parse(time.RFC3339Nano, …)`. The two disagreed on 9 of 23 measured forms. `TimeFormats.parseRfc3339` now ports Go's layout parse, so the lexer and both tiers' decoders follow it.
  - **Now refused, as Go refuses them:**
    - an hours-only offset (`+02`), or one with seconds (`+02:00:30`);
    - a timestamp with no seconds (`10:30Z`);
    - a lowercase `t` or `z`;
    - a bare `.` before the zone.
  - **Now accepted, as Go accepts them:**
    - an offset past ±18:00, up to Go's `±24:60` (`+23:59`, `+24:00`), read as that instant;
    - a one-digit hour;
    - a fraction past nine digits, cut to nine.
  - **Evidence:** `TimestampGrammarTest` holds 58 literals to Go's verdict, and for each one Go accepts, to its Unix seconds and nanosecond. `scripts/go-timestamps/main.go` writes the table, and the conformance job reruns it against protowire-go main. The tolerant-parse prefix pinned against #247 now matches Go, and the pin is gone.

- **A `b"…"` literal takes the URL-safe base64 alphabet, padded or not, as protowire-go does** (#208; draft §3.7, RFC 4648 §5). `b"-_8="` used to be refused as `invalid base64 in bytes literal`; it decodes to `fb ff`. The standard alphabet was taken already.
- **Parse errors that differed from protowire-go's:**
  - A lone `@` used to be reported as `@`. It is now `"@" must be followed by a directive name`.
  - A lone `-` used to be reported as `-`. It is now `"-" must be followed by a digit or "inf"`.
  - A block nested past MaxNestingDepth is refused at its `{`, not at the token after it.
  - A map key with a precision suffix (`5p3: 1`) is refused by the parser, as Go's does: `map key 5: a key cannot carry a precision suffix (p3)`.
  - A `@dataset` header's column paths are checked after the whole list is read, so a malformed list reports its syntax error first, as Go does.
  - An unterminated bytes literal past MaxBytesLiteralLength says so, rather than `unterminated bytes literal`.
  - A `@proto b"…"` body that is not base64 is reported with Go's `illegal base64 data at input byte N`, not Java's decoder's text.
- **A comment before a block cell of a `@dataset` row belongs to the row, not to the cell's first entry,** as protowire-go files it (#208).

- **Formatting keeps a comment that no entry follows where it was, as protowire-go does** (#200, protowire-go#279). The parser kept a comment only when an entry came after it.
  - A comment at the end of a document was dropped. It is now written last.
  - A comment before a block's `}` was dropped, or moved out of the block onto the next entry: `m {\n  k: 1\n  # end of m\n}\ny = 2` came out with `# end of m` above `y = 2`. It now stays inside the block.
  - Comments around dataset rows were dropped. They are now written before the row they precede, and a comment inside a row before that row.
  - The schema-aware format (`Pxf.formatDocument(doc, desc)`) kept none of these when it rewrote a keyed block, nor the comment of a redundant key assignment it dropped last in a block. It keeps both now.
  - A comment inside a named directive's body was written a second time when an entry followed the directive: `@x { k = 1\n # in x\n }\ny = 2` came out with `# in x` again above `y = 2`. It is written once, in the body.
- **`DatasetReader` gives a row the comments before it and inside it, as `Parser.parse` does** (#200). It also leaves the comments before the next directive for `tail()`, where it used to drop them.
- **`DatasetReader` read a `//` or `/*` comment between rows as a stray `/` when a read ended between its two characters** (#200). It refused the table with `expected '(' to start a @dataset row or a directive, got '/'`. A one-byte stream always hit this, and a 4096-byte read could too. It now waits for the next read.

- **The lite tier reports three errors where protowire-go does** (#222). The texts already matched; the line:column was one token off.
  - `counts = { a = 1 }` is refused at the `=` (`1:14: unexpected '=' in map, use ':' for map entries`). It used to be refused at the entry, `1:12`.
  - `counts = { a { } }` is refused at the `{` (`1:14: expected ':' after map key, got {`), not at the entry.
  - An Any whose `@type` URL cannot be resolved is refused at the URL, as the full tier does: `testdata/any/err-unresolved.pxf` gives `5:11: cannot resolve Any type "any.v1.Nope": not found`, not `5:3`.
- **`null` on an editions scalar with EXPLICIT presence leaves it unset, as the draft says** (#190; draft `-01` § Booleans, Null, and Identifier Values). The full tier refused it: protobuf-java 3.25 resolves no editions features, so the field had no presence. On 4.36.2 it does, so the corpus's `nulls/editions-explicit.pxf` is accepted, as Go and every other port accept it, and `err-editions-implicit.pxf` is still refused.

- **The SBE codec refuses a value its `(sbe.encoding)` primitive or its field cannot hold, and a schema the 16-bit header cannot describe** (#197, #198; draft `-01` § SBE Annotations; protowire-go#250, #252). Both tiers share the fix: lite's generated templates check themselves when built, through the shared check in `:sbe-runtime`.
  - **Writing:** a value outside its primitive used to keep its low bits. `int32` 300 as `int8` was written as 44, and a `double` past float's range as `+Inf`. It is now refused, as `sbe: field ranges.v1.R.i8: value 300 is outside the range of int8, [-128, 127]`.
  - **Reading:** a wire value outside its field is refused the same way, as `wire value 2147483648 (uint32) does not fit a int32 field, [-2147483648, 2147483647]`.
  - **Mismatched signedness** is read and written by value. A `bool` as `int8` used to throw `ClassCastException`, and a `uint32` as `int8` threw `IllegalStateException`.
  - **Binding:** `(sbe.encoding)` on a string or bytes field, or between integer and floating point, is refused when the codec is built. So is anything past what a 16-bit header field states: an `(sbe.length)`, a block, a template id, a schema id or a version past 65535. A composite holding a oneof is refused too.
  - **Groups:** marshal refuses a group of more than 65535 entries, which used to be written with `numInGroup` 0.
  - **Texts:** each one is protowire-go's, naming the field by its full name. `FieldTemplate` and `GroupTemplate` gain `fullName`, through new constructors; the old ones still compile, and lite's generated templates name the field by its short name until the plugin passes the full one. The lite tier's message-level texts don't name the message, which its templates don't know.
- **The SBE codec names a template by schema id and template id together, as protowire-go does** (#196, #199; draft `-01` § Header and Block Length, § SBE Annotations; protowire-go#253, #257). A template id is unique only within a schema, and Java looked one up by its template id alone, so bytes from one schema could decode as another schema's message with no error. The changes:
  - **Decoding** (`Codec.unmarshal` and the lite tier's generated codecs, through `SbeWireCodec`) refuses a header whose schema id is not the message's: `sbe: schema ID mismatch: got 2, want 1 for template 7`.
  - **Binding:** `Codec.of` refuses two messages sharing a template id in one schema, with Go's text. One file passed twice still binds.
  - **`Codec.view`** finds the template by both ids, and refuses an id the header's schema doesn't have, as `sbe: unknown template ID 7 in schema 9`. Like Go's `View`, it now also checks `MaxMessageSize`, and a wire blockLength below the template's, which used to read fields from past the stated block.
  - The header errors are Go's, word for word: `template ID mismatch`, `data too short for header: 5 bytes`, `data too short for root block: need 12, have 10`, and `wire blockLength 0 < schema blockLength 4 for template 7`.
- **A schema that redefines a well-known type with the wrong fields is refused at bind time, and is an ordinary message where validation does not run** (#202; draft `-01` §wkt-shapes, protowire-go#218, protowire#487). `pxf.Secret` with no `value`, or a `google.protobuf.StringValue` whose `value` is an `int32`, used to bind. Decoding a document then threw `NullPointerException` or `ClassCastException`, and `Pxf.marshal` wrote `pw = 7` as a string wrapper's literal. `SchemaValidator` now reports `SchemaValidator.Kind.WELL_KNOWN_SHAPE`, new, in protowire-go's words, as `p.proto: message "pxf.Secret" redefines a well-known type: it has no field "value" (draft -01 §wkt-shapes)`. With `skipValidate`, and in `Pxf.marshal`, such a message is decoded and written as an ordinary block, as Go does. The shape is Go's table of field names, kinds and cardinality; field numbers are not checked, so copies of the canonical schemas still bind. A `pxf.Secret` copy may leave `hint` and `fingerprint` out, which used to throw `NullPointerException` when one was written. The lite tier takes well-known kinds from its companions, which the plugin assigns by name; trendvidia/protowire#598 tracks that.
- **A value of the wrong kind on a message field says what the field takes and what it got, as protowire-go does** (#202). `m = 5` said `expected '{' for message field "m"`; it now says `…, got integer ("5")`. A field of a well-known type that takes a literal names it: `ts = 5` says `expected a timestamp literal or '{' for message field "ts", got integer ("5")`, and likewise a duration, integer or numeric literal.
- **A BigFloat precision past what an int holds is refused with protowire-go's error, and a zero whose precision is past the digit bound is not written** (#201; protowire-go#229, #274; HARDENING § Arbitrary-precision magnitudes). Both tiers share the fix.
  - **Reading text.** With `MaxNumericLiteralDigits` raised far enough to admit such a suffix, `1.5p4294967295` used to hang, and `1p2147483648` or `1e-300p2147483647` threw `ArithmeticException` instead of a `PxfException`. Each is now refused within milliseconds, with Go's text and in Go's order:
    - a suffix past 2^31−1 bits: `precision p2147483648 exceeds the widest mantissa big.Float can hold, 2147483647 bits`;
    - a value whose stored exponent falls below int32: `is below the wire's exponent range`;
    - a value outside big.Float's range: `is above big.Float's range` or `is below big.Float's range`.
    A zero still takes any suffix the digit bound admits, as in Go.
  - **Writing text.** A zero whose precision is past the bound, such as `{prec: 13604}`, used to be written as `0p13604`, which every reader refuses. It is now refused with Go's `pxf.BigFloat prec 13604 bits renders to 4097 digits; MaxNumericLiteralDigits=4096`, in `Pxf.marshal` and in the lite PB-to-text reader. This one is reachable at the default limits. The writer now also checks the bound after rounding before the digit bound, as Go does.
  - **Divergence:** where Go would hold a mantissa of about 2^31 bits, Java refuses with `needs a <N>-bit integer, wider than a Java BigInteger holds (2147483647 bits)`. Examples are `1p2147483647`, `0.5p2147483647`, and writing a prec past 2^31 bits. A `BigInteger` cannot hold the numbers involved. This is reachable only with `MaxNumericLiteralDigits` raised past 646 million, or `MaxBigFloatExponent` raised near 2^31.
- **`DatasetReader.scan` and `BindRow.bindRow` bind a row under the caller's options, as protowire-go does** (#194, protowire-go#280). Each row used to be bound with `skipValidate` forced on, so a schema violation never came out of `scan`, though Go reports one. Now the schema is checked unless the caller skips it. The violation comes back as it is, without the row's position, because only a decode error is the row's. `MaxMessageSize` alone does not apply, since a row's body repeats the column names and is wider than the row: it is bound at the default, as in Go. A schema violation now reads `PXF schema violations:`, as Go words it, from `unmarshal` too. It used to read `PXF schema bind-time violations:`.
- **A comment after an entry on the same line stays that entry's trailing comment** (#193). `b = 1 # tc` used to lose it: formatting wrote `# tc` above the next entry, or dropped it at the end of a block or document. The parser now attaches it, as protowire-go's does, to an assignment, a map entry or a type entry, also after a list or block value. `Format.formatDocument` writes it back on the same line, byte for byte as Go's `FormatDocument`.
- **The lite tier writes fields in declaration order, as protowire-go does** (#224). `PxfMeta.fieldOrder()`, new, gives the field numbers in the order the message declares them. Its default is number order, and `LiteWireReader` emits entries in that order. With a companion from a plugin that emits `FIELD_ORDER` (trendvidia/protowire#596), a message such as `text.v1.Annotated`, whose oneof is numbered above fields declared after it, is written as Go writes it. A companion without the table writes in number order, as before. The plugin pin (`pxfJavaMeta.ref`) is now spec main a5789d6, so the companions this build generates carry the table.
- **Both writers write an unset `(pxf.default)` or `(pxf.required)` field so that it reads back** (#187; draft `-01` § Common Schema Layer, protowire-go#259, #263, #267, #268). A reader applies the default to an absent field and refuses an absent required one, so leaving such a field out turned a stored `0` into its default, or wrote a document the reader refuses. Now a field without presence is written as its zero value (`n = 0`, `rl = []`, `rm = {}`), and a message-typed field with a default as `null`. An `optional` or oneof scalar with a default is still left out and reads back as its default, as is a oneof member whose sibling is set. An unset required field with presence has no text, since a reader refuses it absent and refuses it null, so writing it fails with protowire-go's `pxf: anyback.v1.RequiredOptional.n is unset, and the field is (pxf.required)`. The full tier also stops writing `= null` for a `_null` mask path whose field can't hold null; it writes the value. The lite gate's `text.proto` copy gains `Annotated` and `Ordered`, so the spec's gates run them. With the Java exclusions removed, the full tier passes all four `Annotated` text cases, and both tiers pass the write gate. Lite still writes fields in number order, where Go uses declaration order, so `Annotated.set` and `.oneof-sibling-set` differ (#224). A lite companion from a plugin before `IMPLICIT_PRESENCE_FIELDS` treats every scalar as having presence.
- **A BigFloat whose value rounded to its precision is past MaxBigFloatExponent is not written** (#186; protowire-go#261). `1.2e+4932p3` is refused by every reader, and both tiers used to write it. The full tier now fails with protowire-go's `pxf.BigFloat binary exponent 16385 after rounding to prec 3 exceeds MaxBigFloatExponent=16384`. The lite tier fails when it writes the text, and still decodes the value from PB, as decoders must. The spec's `pb-render` row for `pb/bigfloat-rounds-past-bound.binpb` passes for both tiers.
- **The lite tier refuses to write text nested past MaxNestingDepth, counting lists as the parser does** (#203). A message nested 51 levels through a repeated field was written, and its own parser refused the result. `Format.marshalDocument`, new, writes the lite tier's text as protowire-go's `Marshal` lays it out: an empty list or map field on its line, and refused past the depth limit, or for a BigFloat literal no reader takes. `LitePxf.marshal` and `check-decode-android` use it.
- **The lite writer writes nothing for an empty list.** `rl = []` on a packed field used to write an empty record (`2a00`), where protobuf and Go write nothing.
- **The lite tier reads an Any block that opens with `@type`** (#213; draft `-01` "Any Blocks"). `payload { @type = "any.v1.Leaf"  tag = "t" }` used to be refused with `the lite tier has no type resolver`, though Go and the full tier read it. Now the URL's last segment is looked up in the `PxfRegistry` given to `LiteWireWriter.encode`, the rest of the block is written as that type into `value`, and the URL goes into `type_url` as written. The bytes are protowire-go's for every accepted `testdata/any/` document. An unknown URL is refused as `cannot resolve Any type "any.v1.Nope": not found`, and a missing registry as `…: no PxfRegistry (LiteWireWriter.encode(doc, meta, registry))`. Both are reported at the `@type` entry, where Go points at the URL (#222). A `@type` in a block that is not an Any gets Go's error.
- **The lite tier refuses a bad document with a positioned `PxfException`, never an `IllegalArgumentException`** (#191). An unknown field, a block on a scalar, a `:` entry in a message, a value of the wrong kind, an out-of-range integer, an unknown enum name, a non-block value on a message field, a `=` entry in a map, and `null` in a list each threw `IllegalArgumentException` with a message of lite's own, so a caller catching `PxfException` for bad input missed them. Each is now protowire-go's error, as `1:5: expected string for field "s"`. A missing `(pxf.required)` field is reported as Go reports it, at the document's start and by its path: `1:1: required field "items[1].name" is absent`. Errors in the caller's setup, such as a registry without a needed companion, are unchanged. A bytes field now takes only a `b"..."` literal, as in Go and the full tier; a plain string used to be written as its UTF-8. Where several required fields are missing, lite names the lowest-numbered one in the body it reaches first, which can differ from the one Go names. A `=` entry in a map is reported at the entry, where Go points at the `=`.
- **Both tiers refuse a bad document with protowire-go's error, word for word and at its position** (#215). `scripts/go-verdicts/corpus.tsv` holds Go's verdict on each of the 147 corpus documents the tiers run, written by protowire-go itself. `GoVerdictsTest` and `GoVerdictsLiteTest` hold each tier to it, and the conformance job reruns the generator against protowire-go main, so the table follows Go. Lite parses before it binds, so a document Go's `Parse` refuses is held to that error. The texts that changed:
  - Shared parser and lexer: a character no token starts with is named, as `unexpected character ';'` or `unexpected byte 0xff`, where a multi-byte character used to come out as one garbled byte. An entry that starts with one says `expected identifier, string, integer, or bool, got unexpected character ';'`. A raw newline in a string says `newline in string literal: write it as \n, or use a """triple-quoted""" string`. An unquoted `@type` URL names the token, as `got identifier ("any.v1.Leaf")`.
  - Full tier: `expected identifier, string, integer, or bool` and `expected map key` name the token as above. A value the lexer refuses reports the lexer's reason, so `d = 5seconds` says `invalid duration: 5seconds`, not `expected '{' for message field "d"`. A `null` that may not bind says why and how to allow it, for example `null is not allowed on scalar field "n": declare it optional, or as a wrapper type (google.protobuf.Int32Value or similar), to make it nullable`. An unresolved Any URL says `: not found` after it.
  - Full tier: an integer or bool key before `=` or `{`, as in `1 = 2` or `true { }`, is refused as Go refuses it. It used to read as an unknown field named `1`, which `withDiscardUnknown` skipped.
  - Lite: a bool map key error names the field, as `invalid bool map key f for field "by_flag"`. A repeated integer map key is shown as a number, `key 1`. Binding one oneof member twice says `oneof "k": field "km" conflicts with already-set field "km"`. Two members of one oneof are now a `PxfException` at the second, not an `IllegalArgumentException` (#191). `null` errors use the full tier's texts above; a companion from a plugin before `IMPLICIT_PRESENCE_FIELDS` words a `null` on a plain scalar as one on a proto2 `required` field, with the same verdict. A precision suffix on a non-BigFloat field names the field. A quoted entry name on an unkeyed repeated field is reported at the entry, not at the block.
- **The lite tier rejects a scalar or an assigned block on a repeated field** (#214; draft `-01` § Entries and Keys, protowire#337). `repeated_string = "a"` and `repeated_nested = { name = "n1" }` used to be written as one element each. A repeated field is written with a list, and Go and the full tier refuse both. The error is theirs, at the value: `expected '[' for repeated field "repeated_string"`. The keyed form of a keyed repeated field is still accepted.
- **Both tiers reject `null` on a field marked `(pxf.required)` or `@required`** (#189; trendvidia/protowire#569, protowire-go#268). A required field holds a value, so `null` is now refused as absence is, whatever the field's type and presence: an `optional` scalar, a wrapper, a message or a plain scalar. That holds in a body, a nested body and a `@dataset` cell, by `unmarshal` as well as `unmarshalFull`. Both used to accept `r = null` on `optional int32 r [(pxf.required) = true]`, and the full tier accepted it on a required wrapper too. The error is protowire-go's: `null is not allowed on required field "r": a required field holds a value`, and in a dataset `column r: ` comes first. The lite tier reads this off `requiredFields()`, so a companion from an older plugin gets the same verdict. `pxfJavaMeta.ref` moves to spec main (d682ef4), whose plugin also leaves required fields out of `NULLABLE_FIELDS`.
- **`Format.formatDocument` keeps every directive in document order** (#188; trendvidia/protowire#558). It used to drop `@proto`, and wrote every named directive before every dataset, so `@dataset t.v1.T (id)` / `(1)` followed by `@entry x { a = 1 }` came out with the `@entry` first. That order matters: an anonymous `@proto` binds the typed directive after it. `@type` is still written first, since it binds no `@proto`. The other directives follow in source order, each after its comments, with no blank line between them; a dataset with no type is written `@dataset (id)`. A document whose only directive is `@type` formats exactly as before. The output is byte for byte protowire-go's `FormatDocument` (protowire-go#262).
- **`Convert.xmlToProto` keeps the length of a `uint8` or `int8` array** (#179). `<type primitiveType="uint8" length="16"/>` imported as one `uint32` with `(sbe.encoding) = "uint8"`, so a 16-byte field was read as 1 byte and every later field in the block was misplaced. It now imports as `bytes` with `(sbe.length) = 16`, the same 16 bytes on the wire.
- **The SBE decoder rejects a string field that is not UTF-8** (#162; `docs/HARDENING.md` § UTF-8). Both tiers turned the bytes of an `(sbe.length)` `string` into a `String` with `new String(bytes, UTF_8)`, which puts U+FFFD in place of bad bytes and says nothing, so `41 42 FF FE` decoded as `"AB\uFFFD\uFFFD"`. `SbeWireCodec` and `View.stringValue` now decode strictly and throw `IllegalArgumentException` (`sbe: invalid UTF-8 in string field <name>`). A `bytes` field takes any bytes, as before. `check-decode-android` decodes `adversarial.v1.SbeText`, so the lite tier runs the spec's `sbe/utf8-*` rows.

- **An SBE emitter cuts an overlong string on a character boundary** (#166; draft -01 § SBE Annotations, protowire#495). Both tiers cut an `(sbe.length)` value at the byte limit, which can split a multi-byte character, and every strict decoder, this port's now included, then rejects the whole message. `ABCDEFGé` in a string field of length 8 was written `41 42 43 44 45 46 47 C3`. A string is now cut at the last whole character at or before the limit and NUL-padded: `41 42 43 44 45 46 47 00`. A `bytes` field is still cut at the limit. Only bytes that strict decoders refuse change.

- **The lite wire reader skips a field whose wire type does not match its kind** (#160). `LiteWireReader` read each known field with its kind's reader, whatever wire type the tag carried, so a mistyped field was misread and the rest of the message with it. `3a050d01000000`, an `Int64Value` whose field 1 arrives as fixed32, failed with `malformed wire: Protocol message contained an invalid tag (zero)`, where protowire-go and the full tier print `i64_w = 0`. Such a field is now skipped as an unknown field, as they do, in a message, a repeated field, a map entry and every well-known payload read as a literal (Timestamp, Duration, the wrappers, `pxf.BigInt`, `Decimal`, `BigFloat`, `Secret`). A repeated scalar field still takes both its packed and its unpacked record.

- **A lite map entry missing its key or value gets that field's zero value** (#160). An entry without its key or value, left out by the encoder or skipped as mistyped, came back as the string `""`: `by_int = { "": "x" }` where protowire-go prints `{ 0: "x" }`, and `stamps = { a: "" }` where it prints `{ a: 1970-01-01T00:00:00Z }`. An integer key is now `0`, a bool key `false`, a message value `{ }`, and a well-known value its own zero literal.

## [1.4.0] — 2026-11-02

A minor release, because it narrows what decodes. The lite tier catches up with
the full tier: keyed fields, the null rule, `@required` and `@default`,
`pxf.Secret`, a block for every well-known type, and the HARDENING limits. It also
fixes an out-of-memory crash on a crafted `pxf.Decimal` in `LiteWireReader`. Java's
text now matches protowire-go's in both tiers, so golden files will differ. No
wire-format change.

Generated lite companions need `protoc-gen-pxf-java-meta` from protowire v1.16.0
for keyed fields and the null rule.

### Changed

- **Float and Timestamp text matches protowire-go, in both tiers** (#147). A float
  is written as Go's `strconv.FormatFloat(v, 'g', -1, bits)` does: `100` rather
  than `100.0`, `1e+21` rather than `1.0E21`. A Timestamp's fraction is trimmed:
  `.5Z` rather than `.500Z`. The values are unchanged and the old forms still
  decode, but text comparisons and golden files will differ.

- **The lite tier writes messages and maps with the same text as the full tier
  and protowire-go** (#141). A nested message is `ttl {` rather than `ttl = {`,
  and a map is `labels = {` rather than `labels {`, with its keys sorted as the
  other tiers sort them. Of a map key repeated on the wire, the last is kept, as
  in protobuf; both used to be written, which then failed to read back. Every
  form still decodes and the values are unchanged, but text comparisons and
  golden files will differ. `Format.escape` is now public.

- **A Duration past about 292 years is written as a block** (#138), in both
  tiers, as a singular field, a list element or a map value:
  `dur { seconds = 9500000000 }`. A duration literal holds 64 bits of
  nanoseconds, and a longer value used to throw `ArithmeticException` from
  `Pxf.marshal` and `LiteWireReader`, though protobuf defines it up to about
  10,000 years. Literals for shorter values are unchanged, and a longer
  literal is still an error. `TimeFormats.fitsDurationLiteral` is the check.

- **Duration text now matches protowire-go byte for byte, in both tiers**
  (#136). `TimeFormats.formatGoDuration`, which `Pxf.marshal` and the lite
  tier's `LiteWireReader` share, writes what Go's `time.Duration.String()`
  does: 90 minutes is `1h30m0s` rather than `1h30m`, 1.5 seconds is `1.5s`
  rather than `1500ms`, and 1.5 microseconds is `1.5µs` rather than
  `1500ns`. The value is unchanged and the old forms still decode, but text
  comparisons and golden files holding Duration literals will differ.

- **The lite tier takes a block for every wrapper and big-number type** (#140),
  as a singular field, a list element or a map value, with no registry:
  `i32_w { value = 5 }`, `big_int { negative = true }`. Draft -01 allows both
  forms; these used to throw. Their fields, and those of a Duration or
  Timestamp block, no longer take `null`, as in the full tier.

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

- **`UnmarshalOptions.skipPostDecode`**, matching protowire-go's `SkipPostDecode`
  (#112). `unmarshalFull` then records presence but does not apply
  `(pxf.default)` or check `(pxf.required)`, for a caller that applies them
  itself, for example after merging layered documents. `Pxf.isRequired`,
  `Pxf.defaultLiteral` and `Pxf.applyDefault` are the helpers for that pass. The
  record gains a fifth component, so a record pattern naming its components needs
  updating; the existing constructors stay.
- **The lite tier applies the null rule to scalars** (protowire#477).
  `PxfMeta.nullableFields()` lists the singular fields that may hold `null`, and
  `LiteWireWriter` refuses it elsewhere: on a plain `int32`, on a proto2
  `required` field, and on an editions field with implicit presence. Its default
  accepts every singular field, so companions from an earlier plugin behave as
  before. **Text that wrote `null` on such a field now fails to decode** once its
  companions are regenerated.
- **The lite tier honours `@required` and `@default`** (protowire#478), which
  needs only a regenerated companion: the plugin fills the same tables from
  either spelling.
- **`pxf.Secret`'s string literal on the lite tier** (protowire#482).
  `PxfMeta.WKT_SECRET` marks the field; `LiteWireWriter` takes `pw = "x"` for a
  singular field, a list element or a map value, and `LiteWireReader` writes the
  literal unless `hint` or `fingerprint` is set. A companion for a Secret field
  references the constant, so it needs this runtime.
- **Keyed repeated fields on the lite tier** (protowire#323). `LiteWireWriter`
  decodes the keyed block form, in either spelling and with quoted entry names,
  and `LiteWireReader` writes it when every key is present, non-empty and
  distinct, as the descriptor tier does. `PxfMeta` gains `keyFields()`, field
  number → key field name, with an empty default. Without that table a field
  binds as plain repeated, so companions must come from a
  `protoc-gen-pxf-java-meta` that emits `KEY_FIELDS`. Generate them with the
  current plugin to use the form.

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
- **A map value of a well-known type takes its literal** (#126), as a field or
  list element does: `when: 2026-01-01T00:00:00Z`, for Timestamp, Duration, the
  wrappers, BigInt, Decimal and BigFloat. The block form still decodes. **The
  encoder now writes the literal, so text output changes for these maps**, and it
  matches protowire-go.
- **An integer outside its field's type is refused** (#126). `int32 x = 5000000000`
  stored 705032704, `uint32 x = -1` stored the bits of -1, and a `uint64` or
  `int64` beyond `long` threw `NumberFormatException`. The draft requires
  rejecting these, and protowire-go does. Both tiers now throw `PxfException`,
  also for a `(pxf.default)` and an enum number. **Text that relied on the
  truncation now fails to decode.**
- **Unsigned values print unsigned.** A `uint32` or `fixed32` at or above 2^31 and
  a `uint64` or `fixed64` at or above 2^63 were written as negative numbers, in
  fields, list elements and map keys. That text then failed to decode correctly.

### Fixed

- **`Pxf.marshal` no longer writes an Any body that changes the message or can't
  be read** (#150). With a `TypeResolver`, an Any gets `@type` and a body only if
  reading that body gives back its `value`, byte for byte, as in protowire-go.
  Otherwise it stays as `type_url` and `value`. That covers unknown fields at any
  level, an order a decoder wouldn't reproduce, a Timestamp no literal holds, a
  `(pxf.default)` or `(pxf.required)` field left unset, and a chain of Anys past
  `MaxNestingDepth`. Each body is now decoded once while marshalling.

- **The lite tier enforces the HARDENING limits** (protowire#503). A `pxf.Decimal`
  on the wire with a scale past `MaxNumericLiteralDigits` is refused:
  `LiteWireReader` used to try to write out every digit and ran out of memory on a
  message of a few bytes. `LiteWireWriter` had no limits, and now applies
  `MaxRepeatedCount`, `MaxNumericLiteralDigits` and `MaxBigFloatExponent`, with new
  `encode` overloads taking `DecodeLimits`. Documents past those limits now fail.
- **The lite tier handles a repeated enum** (protowire#503). Proto3 packs one by
  default, and both `LiteWireWriter` and `LiteWireReader` threw on it.

- **An unknown enum value is written as its number** (#147). It used to be
  written as `UNKNOWN_ENUM_VALUE_Color_99`, which no port could read back.
- **`nan`, `inf`, `+inf` and `-inf` decode on a float field** (#147), as draft -01
  § Numeric Literals requires. Both tiers refused them, though the full tier wrote
  them, so such a value did not survive a round trip. The lite tier also wrote
  `NaN` and `Infinity`, and now writes the same text as the full tier.

- **The lite tier writes a negative Duration with one sign** (#138). `-1.5s`
  went on the wire as seconds -2, nanos +500000000, which protobuf defines as
  invalid and the draft forbids; it is now seconds -1, nanos -500000000.
- **The lite tier reads a Duration or Timestamp block without a registry**
  (#138). `ttl { seconds = 5 }` threw `IllegalStateException` asking for a
  `PxfRegistry`, on generated companions too.

- **The lite tier refuses a oneof sibling of a keyed entry's key as the descriptor
  tier does** (#128). The error is now a `PxfException` with the same message,
  naming the entry, rather than an `IllegalArgumentException` from the oneof
  check. `other = null` is refused the same way.
- **The lite tier refuses a block on a plain repeated field** (protowire#323).
  `items { id = "a" }` used to add one element; it is now the error the
  descriptor tier gives, `repeated field "items" must use list syntax`.
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
