# Governance

`protowire-java` is governed under the same constitution as the rest of
the `protowire-*` stack. The machine-readable source of truth lives in
the upstream spec repo at
[`governance.pxf`](https://github.com/trendvidia/protowire/blob/main/governance.pxf);
the human-readable preamble is at
[`GOVERNANCE.md`](https://github.com/trendvidia/protowire/blob/main/GOVERNANCE.md).

This file is a short pointer-doc. If anything below disagrees with the
upstream constitution, the upstream wins.

## Domain ownership

This repo's only domain vector is
[`protowire-java`](https://github.com/trendvidia/protowire/blob/main/governance.pxf)
under the upstream `port-libraries` umbrella. Approval requirements:

| Path | Reviewer authority |
|---|---|
| `pb/`, `pxf/`, `sbe/`, `envelope/` | port maintainers (`@trendvidia/maintainers`) |
| `proto-annotations/` | upstream spec maintainers — the proto annotations are part of the wire contract and may not diverge from the spec repo |
| `bench-pxf/`, `bench-sbe/`, `dump-envelope/` | port maintainers; these feed the cross-port harness in `trendvidia/protowire/scripts/cross_*.sh` and must keep their JSON output schema stable |
| `.github/`, `build.gradle.kts`, `settings.gradle.kts` | port maintainers |

## What's enforced today vs (roadmap)

The Steward agent that enforces the constitution programmatically is
**rolling out**. Until it is live:

- Pull requests are reviewed by human maintainers.
- The `0.70.x` release line implements the wire contract documented in
  [`docs/grammar.ebnf`](https://github.com/trendvidia/protowire/blob/main/docs/grammar.ebnf)
  + [`docs/HARDENING.md`](https://github.com/trendvidia/protowire/blob/main/docs/HARDENING.md);
  cross-port wire-equivalence is verified locally via the upstream
  `scripts/cross_*.sh` harnesses, not yet by CI here.
- Reputation-weighted voting, automatic escrow for risky changes, and
  the `manifesto.blocked_module_globs` restriction are all `(roadmap)`
  per the upstream `governance.pxf`.

## Stable surfaces

Everything `public` in the following packages is part of this port's
SemVer contract:

- `org.protowire.pb`
- `org.protowire.pxf`
- `org.protowire.sbe`
- `org.protowire.envelope`

Anything in a `internal` subpackage, or marked `@org.jspecify.annotations.NullUnmarked`
internal helpers, is not stable.

The wire contract — what bytes a given proto message produces — is
governed by the **upstream** spec, not this port. Bumping the wire
contract requires a coordinated PR landing in every sibling port; see
[`STABILITY.md`](https://github.com/trendvidia/protowire/blob/main/STABILITY.md)
upstream.
