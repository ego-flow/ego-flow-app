# Security Policy

## Supported scope

Security reports are accepted for the current `main` release candidate and the eventual `v0.0.1`
app artifact. The iOS Experimental source is not a supported product, but reports about exposed
credentials, unsafe data transmission, or vulnerabilities in that retained source are still useful.

This pre-1.0 project provides security fixes on a best-effort basis and does not promise a fixed
support lifetime or response SLA.

## Report a vulnerability privately

Do not open a public Issue for a suspected vulnerability.

1. Use [GitHub private vulnerability reporting](https://github.com/ego-flow/ego-flow-app/security/advisories/new).
2. If GitHub private reporting is unavailable, email <egoflow3@gmail.com> with the subject
   `[SECURITY][ego-flow-app]`.

Include the affected version or commit, platform, prerequisites, reproducible steps, impact, and a
minimal proof of concept. Redact tokens, passwords, signing keys, private endpoints, user identity,
and personal camera/microphone data. Do not attach an unredacted recording or dataset; ask the
maintainers to arrange an appropriate transfer only if it is essential.

The maintainers aim to acknowledge a complete report within five business days, validate and
prioritize it, coordinate a fix and disclosure, and credit the reporter when requested and safe.

## In-scope examples

- authentication or authorization bypass;
- exposure of Meta credentials, server tokens, signing material, or recordings;
- unsafe storage or logging of secrets and personal data;
- malicious stream, media, or server input causing code execution or unintended access;
- insecure transport or dependency behavior that affects the supported Android client.

Requests for credentials, attempts to access other users' data, denial-of-service testing against
shared infrastructure, social engineering, and publication before a fix is available are not
authorized by this policy.

## Third-party components

Meta Wearables DAT, Android dependencies, and other third-party components have their own security
and disclosure processes. Report an EgoFlow integration defect to EgoFlow; report a defect solely
in a third-party product to that provider. The maintainers may coordinate both reports when the
boundary is unclear.
