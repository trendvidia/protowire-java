---
name: Bug report
about: Report a defect — wrong output, crash, parse error on valid input, etc.
title: "bug: "
labels: bug
---

<!--
Cross-port issues (the same input behaves differently on multiple ports)
belong upstream at trendvidia/protowire, not here. See CONTRIBUTING.md.

Security issues (decoder crash/hang/OOM on adversarial input) go to
security@trendvidia.com instead. See SECURITY.md.
-->

## What happened

A clear description of the bug.

## How to reproduce

Smallest possible PXF / PB / SBE / envelope input + Java code that
triggers it. Inline if short, or attach as a Gist.

```java
ServerConfig.Builder b = ServerConfig.newBuilder();
Pxf.unmarshal(pxfBytes, b);
```

## What you expected

What you thought should happen.

## Versions

- `protowire-java` version (e.g. `0.70.0`):
- `protobuf-java` version (`./gradlew :pxf:dependencies | grep protobuf-java`):
- JDK + vendor (`java --version`):
- OS / arch (only if it might matter):
