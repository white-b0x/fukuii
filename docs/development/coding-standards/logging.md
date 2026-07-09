# Logging — scope stub

**Scope:** Preferred logging API, log-level selection, message format, SLF4J usage
patterns, and the debug-instrumentation ban on `src/main` (no `println`/
`System.err.println`/`printStackTrace`, no temp `logback-test.xml` DEBUG loggers left in
the tree).

**Owning specialist:** cross-cutting — no single owning specialist; `prism` reviews on
non-consensus code, domain specialists review within their own subtree.

**Authority:** SLF4J API docs; fukuii's own established usage (this is largely an
in-house convention, not upstream-authority-derived, so citation style differs from the
`scala3`/`pekko` domains — cite fukuii precedent file:line rather than a vendored repo).

**Status:** not yet authored. `.agents/protocols/code-style/logging-standards.md`
currently carries this content as a protocol; migrating it here is a follow-on pass (see
`README.md`'s per-domain migration plan).
