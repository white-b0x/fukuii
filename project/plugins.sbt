logLevel := sbt.Level.Warn

// Fix dependency conflict for scala-xml
ThisBuild / libraryDependencySchemes += "org.scala-lang.modules" %% "scala-xml" % "always"

// Override problematic transitive dependencies
ThisBuild / dependencyOverrides ++= Seq(
  "org.apache.httpcomponents" % "httpcore" % "4.4.16",
  "org.scala-lang.modules" %% "scala-xml" % "2.3.0"
)

// Add required plugin resolvers
resolvers += "Sonatype OSS Releases".at("https://oss.sonatype.org/content/repositories/releases/")

// sbt-scapegoat (com.sksamuel.scapegoat, last release 1.2.13) has NO sbt-2-compatible artifact
// published (verified: no `_sbt2_3` coordinate on Maven Central, upstream repo shows no sbt-2 work
// in flight). It is opt-in tooling (only invoked via the `scapegoat`/`runScapegoat` tasks, not part
// of compile/test/package), so dropping it does not block the cutover, but declaring it here would
// hard-fail meta-build resolution under sbt 2. DROPPED — see build.sbt for the paired removal of
// scapegoatVersion/scapegoatReports/etc. settings and the `runScapegoat` alias. Re-add when upstream
// ships an sbt-2 port; the underlying `scalac-scapegoat-plugin` compiler plugin is already
// Scala-3.8.4-published, so re-wiring should be quick once the sbt orchestration plugin catches up.
// addSbtPlugin("com.sksamuel.scapegoat" % "sbt-scapegoat" % "1.2.13")

// sbt-kanela-runner (io.kamon) has NO sbt-2 artifact and Kamon/Kanela instrumentation is already
// fully removed from build.sbt (no `javaAgents` wiring, no enablePlugins call) — this was dead
// plugin config even before the cutover. DROPPED.
// addSbtPlugin("io.kamon" % "sbt-kanela-runner" % "2.1.0")

// sbt-javaagent (now com.github.sbt, moved from com.lightbend.sbt) has an sbt-2-ready 0.2.0 build,
// but it was only ever wired here to support the Kanela javaagent above — no `enablePlugins(JavaAgent)`
// or `javaAgents +=` call exists anywhere in build.sbt. DROPPED as unused; re-add
// `addSbtPlugin("com.github.sbt" % "sbt-javaagent" % "0.2.0")` if a real javaagent need appears.

// sbt-api-mappings (com.thoughtworks.sbt-api-mappings) DOES have an sbt-2 artifact, but only as an
// untagged sbt-dynver snapshot build (`3.0.3+10-a67392d4`, not a clean release version) — the vendor
// tagged v3.0.3 on GitHub (2026-06-13) but has not cut a matching clean Maven release for the sbt-2
// cross-build. It only wires cosmetic scaladoc `apiMappings` (no compile/test/package impact).
// DROPPED rather than pin to a dynver snapshot string; re-add once a clean sbt-2 release lands.
// addSbtPlugin("com.thoughtworks.sbt-api-mappings" % "sbt-api-mappings" % "3.0.3")

addSbtPlugin("com.eed3si9n" % "sbt-buildinfo" % "0.13.1")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.7.0")
addSbtPlugin("com.github.sbt" % "sbt-git" % "2.1.0")
addSbtPlugin("com.github.sbt" % "sbt-native-packager" % "1.11.7")
addSbtPlugin("com.eed3si9n" % "sbt-assembly" % "2.3.1")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.6.1")
addSbtPlugin("org.scoverage" % "sbt-scoverage" % "2.4.4")
addSbtPlugin("com.github.sbt" % "sbt-ci-release" % "1.12.0")
addSbtPlugin("ch.epfl.scala" % "sbt-scalafix" % "0.14.7")
