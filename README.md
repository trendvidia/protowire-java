# protowire4java

Native Java port of [github.com/trendvidia/protowire](https://github.com/trendvidia/protowire) — a protobuf-backed serialization toolkit.

Java 21, Gradle multi-module, `protobuf-gradle-plugin`, JUnit 5.

## Modules

| Module | Java package | Notes |
|--------|--------------|-------|
| `:pb` | `com.trendvidia.protowire.pb` | Schema-free struct ↔ proto3 binary marshaling. Field numbers come from the `@ProtoField(N)` annotation — the Java analogue of Go's `protowire:"N"` struct tag. |
| `:pxf` | `com.trendvidia.protowire.pxf` | PXF text ↔ `Message` / `DynamicMessage`. Two-tier decoder split: `Pxf.parse()` returns an AST with comments; `Pxf.unmarshal` / `Pxf.unmarshalFull` use a fused fast decoder. |
| `:sbe` | `com.trendvidia.protowire.sbe` | FIX SBE binary codec, driven by SBE annotations on `.proto` schemas. `Codec.marshal`, `Codec.unmarshal`, and a zero-allocation `View`. Includes `Convert.xmlToProto` / `Convert.protoToXml`. |
| `:envelope` | `com.trendvidia.protowire.envelope` | Standard API response envelope, generated from `envelope/v1/envelope.proto`, with `Envelopes` builders + queries. |
| `:proto-annotations` | `com.trendvidia.protowire.proto.{pxf,sbe}` | Compiled proto annotations: `pxf.required`, `pxf.default`, `pxf.BigInt/Decimal/BigFloat`, `sbe.schema_id/template_id/length/encoding`. |
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
import com.trendvidia.protowire.pxf.Pxf;
import com.trendvidia.protowire.pxf.Result;

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
