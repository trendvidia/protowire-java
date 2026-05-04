# protowire-java

Native Java port of [github.com/trendvidia/protowire](https://github.com/trendvidia/protowire) — a protobuf-backed serialization toolkit.

Java 21, Gradle multi-module, `protobuf-gradle-plugin`, JUnit 5.

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

## Limitations & open gaps

The Java port is built on `com.google.protobuf:protobuf-java`'s `DynamicMessage` reflection API. A few things that fall out of that choice or are explicit deferred work:

- **Reflection-driven hot path.** `DynamicMessage` field access goes through `Descriptors.FieldDescriptor` lookups; on tight benchmarks this is meaningfully slower than codegen-bound libraries. Latency-sensitive callers should profile before committing — and ideas for a `protoc-java`-style codegen path are welcome.
- **Java 17+ minimum.** The build uses sealed types, records, and pattern switches; we won't backport to 8 or 11. JVM languages targeting older bytecode (Kotlin / Scala / Clojure on legacy LTS) are not supported.
- **No standalone Java CLI.** The shared CLI lives in [trendvidia/protowire/cmd/protowire](https://github.com/trendvidia/protowire/tree/main/cmd/protowire) and JVM users invoke it as a binary; there is no in-JVM CLI surface to call from build plugins yet. A Maven / Gradle plugin would be welcome.
- **SBE XML schema interop.** `proto2sbe` is shipped; consuming a hand-authored XML schema to produce `.proto` is open work. The shared CLI handles this in Go today.

## Contributing & governance

This repository is part of the `protowire-*` family and is governed by [**Steward**](https://github.com/trendvidia/steward) — the meritocratic, AI-driven governance engine that runs all of the ports. Voting weight is per-directory expertise, the constitution is public in [`governance.pxf`](https://github.com/trendvidia/steward/blob/main/governance.pxf), and Steward routes draft / first-time PRs through a [private mentorship pipeline](https://github.com/trendvidia/steward#-private-mentorship-mode) so initial contributions get private feedback rather than public-review friction.

If any of the items above sound interesting, pull requests are welcome. New contributors start at zero trust and accumulate influence by shipping merged PRs in the directories they actually work on — the [escrow pipeline](https://github.com/trendvidia/steward#%EF%B8%8F-the-escrow-pipeline-zero-trust-onboarding) auto-routes large first-time PRs through 2–3 sandbox issues before unlocking them for community review.

See the [Steward README](https://github.com/trendvidia/steward) for a longer walkthrough of vector reputation, escrow, and the immune system.
