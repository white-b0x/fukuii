# Dependencies domain — scope stub

**Scope:** Per-dependency conventions (which API surface of a given library is sanctioned
for use in fukuii, which is banned/superseded) and supply-chain currency discipline (LTS
pin freshness, CVE-response cadence, artifact-swap review).

**Owning specialist:** `sentinel` — the sole gated path for any dependency change
(`build.sbt`, `project/Dependencies.scala`, plugins, resolvers). Other agents STOP and
route dependency questions here rather than proposing bumps themselves.

**Authority:** vendored dependency source under `.claude/repo-references/` where a specific
library is vendored (e.g. `scalafix`, `scapegoat`, `pekko`, `pekko-connectors`,
`pekko-http`); upstream release notes / advisory pages for libraries not vendored in-repo.

**Status:** not yet authored. `.agents/protocols/tooling/dependency-currency.md` currently
carries this content as a protocol; migrating it here is a follow-on pass (see
`../README.md`'s per-domain migration plan) owned by `sentinel`.
