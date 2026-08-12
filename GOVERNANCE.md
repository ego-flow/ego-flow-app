# Governance

## Project stewardship

EgoFlow is maintained by the EgoFlow GitHub organization. Organization owners designate
maintainers, repository administrators, and release managers. Maintainers are responsible for
technical direction, review, security coordination, community conduct, and release claims.

## Decisions

Routine changes are decided through Issues and pull-request review. Maintainers seek consensus and
record material scope, compatibility, licensing, security, and release decisions in repository
documentation. When consensus is not possible, the maintainers responsible for the affected area
make the decision and record the rationale.

At least one maintainer approval and passing required checks are expected before merge. A maintainer
must not approve a provenance-sensitive change solely because its code works.

## App-specific rights gate

This repository is mixed, source-available material and is not part of EgoFlow's MIT/OSI claim.
Code pull requests require a maintainer-approved Issue and the provenance statement in
[CONTRIBUTING.md](CONTRIBUTING.md). Maintainers may restrict changes to files with established
rights, preserve or add upstream notices, or decline a contribution whose redistribution basis is
unclear.

## Releases

Release managers verify supported scope, tests/builds, license and notice boundaries, SBOM data,
security findings, documentation, and artifact hashes before updating a release ref. The first
candidate is finalized as a single `v0.0.1` commit; `main`, the `v0.0.1` branch, and the annotated
tag must identify the reviewed tree. The existing `extentos` branch is maintained separately and is
not part of this rewrite.

Security fixes may be prepared privately before coordinated disclosure. Force pushes outside the
explicitly approved first-release rewrite require a separately recorded maintainer decision.

## Changes to governance

Propose governance changes through a public Issue and pull request unless doing so would disclose a
security or conduct matter. Material changes require maintainer consensus and a documented reason.
