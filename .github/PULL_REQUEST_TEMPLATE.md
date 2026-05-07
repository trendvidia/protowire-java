<!--
For changes that touch wire-format behaviour: please open the upstream
PR in trendvidia/protowire FIRST. This port implements the spec; it
shouldn't lead spec changes. See CONTRIBUTING.md.
-->

## Summary

What this PR changes, in 1–3 sentences.

## Why

Link to the issue or upstream spec change that motivated this.

## Scope

- [ ] Wire-impacting code (`pxf/`, `sbe/`, `pb/`, `envelope/`, `proto-annotations/`)
- [ ] Test harness / benches (`bench-*/`, `dump-envelope/`)
- [ ] Build / CI / repo plumbing
- [ ] Documentation only

## Test plan

- [ ] `./gradlew build` passes (compile + test all 8 modules)
- [ ] `./gradlew jacocoTestReport` — coverage didn't regress
- [ ] If wire-impacting: cross-port harness re-run locally via
      [`scripts/cross_*.sh`](https://github.com/trendvidia/protowire/tree/main/scripts) in the spec repo
- [ ] If protocol-touching: matching upstream spec PR linked above
