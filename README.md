# protowire-java

[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/org.protowire/protowire-pxf?label=Maven%20Central)](https://central.sonatype.com/namespace/org.protowire)
[![CI](https://github.com/trendvidia/protowire-java/actions/workflows/ci.yml/badge.svg)](https://github.com/trendvidia/protowire-java/actions/workflows/ci.yml)

Native Java port of [protowire](https://protowire.org) — a protobuf-backed
serialization toolkit. Implements the canonical wire format defined in
[`trendvidia/protowire`](https://github.com/trendvidia/protowire) and
verified for byte-equivalence against eight sibling ports (Go, C++, Rust,
TypeScript, Python, C#, Swift, Dart).

Java 21, Gradle multi-module, `protobuf-gradle-plugin`, JUnit 5.

## Install

Maven:

```xml
<dependency>
  <groupId>org.protowire</groupId>
  <artifactId>protowire-pxf</artifactId>
  <version>0.75.0</version>
</dependency>
```

Gradle (Kotlin DSL):

```kotlin
implementation("org.protowire:protowire-pxf:0.75.0")
// or pick the modules you need:
//   protowire-pb, protowire-pxf, protowire-sbe, protowire-envelope, protowire-proto-annotations
```

All published artifacts share the `0.75.x` line; ports at the same minor
implement the same wire contract.

## Modules

| Module | Java package | Notes |
|--------|--------------|-------|
| `:pb` | `org.protowire.pb` | Schema-free struct ↔ proto3 binary marshaling. Field numbers come from the `@ProtoField(N)` annotation — the Java analogue of Go's `protowire:"N"` struct tag. |
| `:pxf` | `org.protowire.pxf` | PXF text ↔ `Message` / `DynamicMessage`. Two-tier decoder split: `Pxf.parse()` returns an AST with comments; `Pxf.unmarshal` / `Pxf.unmarshalFull` use a fused fast decoder. |
| `:sbe` | `org.protowire.sbe` | FIX SBE binary codec, driven by SBE annotations on `.proto` schemas. `Codec.marshal`, `Codec.unmarshal`, and a zero-allocation `View`. Includes `Convert.xmlToProto` / `Convert.protoToXml`. |
| `:envelope` | `org.protowire.envelope` | Standard API response envelope, generated from `envelope/v1/envelope.proto`, with `Envelopes` builders + queries. |
| `:proto-annotations` | `org.protowire.proto.{pxf,sbe}` | Compiled proto annotations: `pxf.required`, `pxf.default`, `pxf.BigInt/Decimal/BigFloat`, `sbe.schema_id/template_id/length/encoding`. |
| `:dump-envelope`, `:bench-pxf`, `:bench-sbe` | (test harnesses) | Per-port binaries used by the cross-port runner scripts in the spec repo. Not part of the public library API. |

## Build & test

```sh
./gradlew build       # compile + test all modules
./gradlew :pxf:test   # only PXF
```

Java 21 toolchain is required (configured automatically via Gradle).

## PXF API

### Decode

```java
import org.protowire.pxf.Pxf;
import org.protowire.pxf.Result;

// schema-bound (compiled-in proto)
ServerConfig.Builder b = ServerConfig.newBuilder();
Pxf.unmarshal(pxfBytes, b);

// dynamic
DynamicMessage msg = Pxf.unmarshal(pxfBytes, descriptor);

// presence tracking
Result r = Pxf.unmarshalFull(pxfBytes, b);
r.isSet("name");
r.isNull("email");
r.isAbsent("role");
```

### Encode

```java
byte[] out = Pxf.marshal(msg);

// with options
byte[] out = MarshalOptions.defaults()
    .withTypeUrl("infra.v1.ServerConfig")
    .withEmitDefaults(true)
    .marshal(msg);
```

### Parse → AST → format (comment-preserving)

```java
Ast.Document doc = Pxf.parse(pxfBytes);
byte[] formatted = Pxf.formatDocument(doc);
```

The AST is a sealed hierarchy of records (`Ast.Document`, `Ast.Entry`, `Ast.Value`); pattern matching is used throughout the formatter and decoder.

### Directives and `@dataset` (Result accessors)

PXF documents can carry [`@<name>` directives, `@entry` bundles, and `@dataset` rows](https://github.com/trendvidia/protowire#directives) at the document root alongside (or instead of) a message body. `unmarshalFull` captures all three on `Result`:

```java
Result r = Pxf.unmarshalFull(pxfBytes, b);

for (Ast.Directive d : r.directives()) {
    // d.name(), d.prefixes() (zero-or-more), d.type() (back-compat:
    // populated when prefixes.size()==1), d.body() raw inner bytes of
    // `{ ... }` — typically handed back to Pxf.unmarshalFull against a
    // chosen message, chameleon's @header pattern.
}

for (Ast.DatasetDirective t : r.datasets()) {
    // t.type(), t.columns(), t.rows() List<DatasetRow>.
    // Each row.cells().get(i) is:
    //   null               — empty cell (field absent, pxf.default applies)
    //   Ast.NullVal        — explicit null (field cleared per §3.9)
    //   any other Ast.Value — field set to that value
}
```

`r.directives()` excludes `@type` and `@dataset` (those have their own accessors). Order is preserved.

### `DatasetReader`: streaming `@dataset` consumption

For datasets too large to materialize, read rows from an `InputStream` with working-set memory bounded by the size of the largest single row — not by the row sequence:

```java
try (var in = Files.newInputStream(Path.of("trades.pxf"))) {
    var tr = new DatasetReader(in);
    String typ = tr.type();
    List<String> cols = tr.columns();
    List<Ast.Directive> hdrs = tr.directives();    // side-channel directives before the @dataset header

    Ast.DatasetRow row;
    while ((row = tr.next()) != null) {
        // row.cells(): List<Ast.Value> with the three-state mapping above.
    }
}
```

`NewDatasetReader` throws `NoSuchElementException` if the input ends before any `@dataset` directive. Multi-table documents chain via `tr.tail()`, which returns an `InputStream` of the buffered-but-unconsumed bytes followed by the remaining source:

```java
var tr1 = new DatasetReader(src);
// ... iterate tr1.next() until it returns null ...
var tr2 = new DatasetReader(tr1.tail());
```

Per-row arity and v1 cell-grammar errors (`[...]` / `{...}` cells, dotted columns) surface as the offending row is consumed, not deferred to end-of-input — see [draft §3.4.4 "Streaming consumption"](https://github.com/trendvidia/protowire/blob/main/docs/draft-trendvidia-protowire-00.txt).

### `scan` and `BindRow`: per-row binding

`DatasetReader.scan(builder)` reads the next row and binds its cells to the message by column name; returns `false` when the row sequence is exhausted:

```java
var tr = new DatasetReader(in);
while (true) {
    var b = Trade.newBuilder();
    if (!tr.scan(b)) break;
    process(b.build());
}
```

`BindRow.bindRow(builder, columns, row)` is the same logic exposed standalone, for callers iterating `Result.datasets()[i].rows()` on the materializing path:

```java
Ast.Document doc = Pxf.parse(pxfBytes);
for (Ast.DatasetDirective tbl : doc.datasets()) {
    for (Ast.DatasetRow row : tbl.rows()) {
        var b = Trade.newBuilder();
        BindRow.bindRow(b, tbl.columns(), row);
        process(b.build());
    }
}
```

Both honor the three-state cell semantics (empty / `null` / value), bind WKT timestamps and durations, resolve enums by name, and clear wrappers / oneof / `optional` fields on a `null` cell — the implementation routes through the existing `unmarshal` pipeline so every decoder branch is exercised.

### Schema reserved-name check

A protobuf schema bound for PXF use MUST NOT declare a field, oneof, or enum value named `null`, `true`, or `false` — those identifiers lex as PXF value keywords and produce silently-unreachable bindings. The check runs by default at the top of every `Pxf.unmarshal*` call:

```java
// Decoder throws PxfException if the schema is non-conformant.
Pxf.unmarshal(pxfBytes, b);

// Inspect / pre-validate explicitly:
List<SchemaValidator.Violation> violations =
    SchemaValidator.validateFile(fd);          // or validateDescriptor(desc)
for (var v : violations) System.out.println(v);

// Bypass per-call validation (advanced — for callers who pre-validated):
UnmarshalOptions.defaults()
    .withSkipValidate(true)
    .unmarshal(pxfBytes, b);
```

The check is case-sensitive: `NULL`, `True`, `FALSE` lex as ordinary identifiers and are accepted. Synthetic oneofs introduced for proto3 `optional` are skipped (their name is `_<fieldname>`, never reserved). See [draft §3.13](https://github.com/trendvidia/protowire/blob/main/docs/draft-trendvidia-protowire-00.txt) for the rule.

## Two-tier decoder split

Mirrors the Go module and the C++ port:

- **AST path** — `Parser.parse()` produces an `Ast.Document` with comments attached. Used when comment preservation is needed.
- **Fast path** — `FastDecoder` fuses lexing + decoding in a single pass, writing directly into a `Message.Builder` with no intermediate AST allocation. Used by `Pxf.unmarshal` and `Pxf.unmarshalFull`.

## Feature coverage

PXF (`:pxf`):

- ✅ Lexer: single/triple strings, base64 bytes, RFC 3339 timestamps, Go-style durations, comments, dedent.
- ✅ Schema-bound encoder + decoder via Java protobuf reflection (descriptors / `DynamicMessage`).
- ✅ Scalars, enums, repeated, maps, nested messages, oneof.
- ✅ Well-known types: `Timestamp`, `Duration`, all wrapper types (sugar form).
- ✅ Field-presence tracking (`unmarshalFull` returns a `Result`).
- ✅ `google.protobuf.Any` sugar (block syntax with `@type =`).
- ✅ `pxf.BigInt` / `pxf.Decimal` / `pxf.BigFloat` sugar inside PXF text.
- ✅ `_null` `FieldMask` discovery and emission across binary round-trips.
- ✅ `(pxf.required)` / `(pxf.default)` annotation enforcement in `unmarshalFull`.
- ✅ AST-preserving `formatDocument`.
- ✅ **`@<name>` named directives** at document root with raw-body extraction (`Ast.Directive`, `Result.directives()`).
- ✅ **`@entry` bundle directive** (zero-or-more prefix list; four permitted shapes per draft §3.4.3).
- ✅ **`@dataset` directive** (the protowire-native CSV replacement) — `Ast.DatasetDirective`, `Ast.DatasetRow`, three-state cells, parser enforces row arity + dotted-column rejection + list/block-cell rejection + standalone-constraint.
- ✅ **Streaming `DatasetReader`** over `InputStream` for datasets too large to materialize. Working-set memory bounded by largest single row.
- ✅ **Per-row binding** via `DatasetReader.scan(Message.Builder)` and standalone `BindRow.bindRow(...)`.
- ✅ **Schema reserved-name check** (`SchemaValidator.validateFile` / `validateDescriptor`) catches schemas declaring fields/oneofs/enum values named `null`/`true`/`false`. Runs by default on every `unmarshal*` call; `UnmarshalOptions.withSkipValidate(true)` opts out.

SBE (`:sbe`):

- ✅ Codec construction from `FileDescriptor`s, schema/version/template-id discovery via SBE annotations.
- ✅ `marshal` / `unmarshal` for proto messages, including composites and repeating groups.
- ✅ Type-narrowing via `(sbe.encoding)` overrides.
- ✅ Zero-allocation `View` / `GroupView`.
- ✅ XML schema parsing and `Convert.xmlToProto` / `Convert.protoToXml`.

`pb` (`:pb`):

- ✅ Wire format for all proto3 scalar types, repeated, embedded messages.
- ✅ `BigInteger` / `BigDecimal` byte-backed types matching `pxf.BigInt`/`Decimal` schemas.
- ✅ Unknown-field skipping on decode.

`envelope` (`:envelope`):

- ✅ Generated `Envelope`/`AppError`/`FieldError` from proto plus `Envelopes` builders + queries.

## GraalVM native-image

Every published jar carries
[reachability metadata](https://www.graalvm.org/latest/reference-manual/native-image/metadata/)
under `META-INF/native-image/org.protowire/<artifact>/`, so the
`native-image` agent doesn't warn about missing hints when consumers
build a native binary.

`:pb` consumers using the schema-free `@ProtoField`-driven marshaler
must register their own DTO classes for reflection — see
[`pb/src/main/resources/META-INF/native-image/org.protowire/protowire-pb/native-image.properties`](pb/src/main/resources/META-INF/native-image/org.protowire/protowire-pb/native-image.properties)
for the rationale and template.

## Command-line tool

The `protowire` CLI is shared across every port and lives in the spec repo at [github.com/trendvidia/protowire/cmd/protowire](https://github.com/trendvidia/protowire/tree/main/cmd/protowire). Install:

```sh
go install github.com/trendvidia/protowire/cmd/protowire@latest
```

Java users use this library for in-process encode/decode and the shared CLI for command-line operations. There is no separate Java CLI binary.

## Wire compatibility

Verified by the `exampleFixtureRoundTrip` test in `pxf/src/test/java/.../PxfTest.java`: the same `testdata/example.pxf` from the Go module is parsed, marshaled, and re-parsed; the two `DynamicMessage` instances must compare equal.

## Why a native Java JAR (vs a wrapper)

For Kotlin, Scala, Clojure, and any other JVM language, the native Java JAR is consumed directly. A wrapper around a non-JVM implementation would not be.
