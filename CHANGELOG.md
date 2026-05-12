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

### Added

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
