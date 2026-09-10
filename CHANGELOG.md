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

### Changed

- The lite modules fetch `protoc-gen-pxf-java-meta` at protowire's release
  tag `v1.12.0` instead of a commit hash (`gradle.properties`
  `pxfJavaMeta.ref`); same plugin source, now a named release.

### Fixed

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
