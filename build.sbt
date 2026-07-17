enablePlugins(JavaAppPackaging)

import scala.sys.process.Process
import NativePackagerHelper._
import com.github.sbt.git.SbtGit.git

// Necessary for the nix build, please do not remove.
val nixBuild = sys.props.isDefinedAt("nix")

// Enable dev mode: disable certain flags, etc.
val fukuiiDev = sys.props.get("fukuiiDev").contains("true") || sys.env.get("FUKUII_DEV").contains("true")

// Scala 3 has a different optimizer, no explicit optimization flags needed
lazy val scala3OptimizationsForProd = Seq.empty[String]

// Releasing. https://github.com/olafurpg/sbt-ci-release
inThisBuild(
  List(
    homepage := Some(url("https://github.com/chippr-robotics/fukuii")),
    scmInfo := Some(
      ScmInfo(url("https://github.com/chippr-robotics/fukuii"), "git@github.com:chippr-robotics/fukuii.git")
    ),
    licenses := List("Apache-2.0" -> url("http://www.apache.org/licenses/LICENSE-2.0")),
    developers := List(),
    scalaVersion := `scala-3`, // must be in inThisBuild for scalafixSemanticdb.revision
    semanticdbEnabled := true, // required for scalafix semantic rules
    semanticdbVersion := scalafixSemanticdb.revision,
    // Add reliable resolvers to avoid transient HTTP 503 errors
    resolvers ++= Seq(
      Resolver.mavenCentral,
      "Typesafe Releases".at("https://repo.typesafe.com/typesafe/releases/"),
      "Sonatype OSS Releases".at("https://oss.sonatype.org/content/repositories/releases/"),
      "Sonatype OSS Snapshots".at("https://oss.sonatype.org/content/repositories/snapshots/"),
      "Hyperledger Besu".at("https://hyperledger.jfrog.io/artifactory/besu-maven/")
    )
  )
)

// https://github.com/sbt/sbt/issues/3570
updateOptions := updateOptions.value.withGigahorse(false)

// artifact name will include scala version
crossPaths := true

// patch for error on 'early-semver' problems
ThisBuild / evictionErrorLevel := Level.Info

val `scala-3` = "3.3.8" // 3.3.8 released 2026-06-11, latest 3.3 LTS patch
val supportedScalaVersions = List(`scala-3`) // Scala 3 only

// Base scalac options
val baseScalacOptions = Seq(
  "-unchecked",
  "-deprecation",
  "-feature",
  "-encoding",
  "utf-8"
)

// Scala 3 warning and feature options
val scala3Options = Seq(
  "-source:future", // Enforce Scala 3 future syntax (import x.* required, not import x._)
  "-language:adhocExtensions", // Allow extending non-open classes (TestKit, Pekko test patterns)
  "-Wunused:all", // Enable unused warnings for Scala 3 (required for scalafix)
  "-Wconf:id=E198:error", // Ratchet step 4/4: unused symbols are build errors (Scala 3: id=E198, cat=unused is not valid)
  "-Wconf:cat=feature:s", // Suppress adhocExtensions feature warnings (extending non-open Pekko/library classes)
  "-Wconf:cat=unchecked:error", // Ratchet step 4/4: unchecked patterns are build errors
  "-Wconf:id=E176:error", // Ratchet step 4/4: value-discard / non-Unit-statement is a build error (paired with scala3ValueDiscardOptions)
  "-Wconf:id=E175:error", // Ratchet step 4/4: value-discard (companion of E176) is a build error
  "-Ykind-projector", // Scala 3 replacement for kind-projector plugin
  "-Xmax-inlines:64" // Increase inline depth limit for complex boopickle/circe derivations
)

// Strict value-discard warnings (scapegoat-successor; the sbt-2 cutover dropped sbt-scapegoat, no
// sbt-2 artifact exists — see project/plugins.sbt). Applied to BOTH Compile and Test (below).
// They flag discarded non-Unit expression results (~scapegoat "ignored computation" bugs). These
// were historically Compile-only because ScalaTest specs idiomatically discarded mid-block
// `assert(...)` Assertion values (E176) — but that pattern was refactored out across every module
// (single trailing / `&&`-combined assertions), so Test is now gated too, per the warning-ratchet
// rule (refactor the pattern, never `-Wconf`-suppress it). Promoted to a hard build error via
// `-Wconf:id=E176:error` / `id=E175:error` in scala3Options above (same mechanism as E198/unchecked;
// -Xfatal-warnings is NOT in this build's option set, so the -Wconf:id:error rule is the actual gate).
val scala3ValueDiscardOptions = Seq(
  "-Wvalue-discard", // flags discarded non-Unit expression results ~ scapegoat-class "ignored computation" bugs
  "-Wnonunit-statement" // flags non-Unit statements whose value is silently dropped in a block
)

def commonSettings(projectName: String): Seq[sbt.Def.Setting[?]] = Seq(
  name := projectName,
  organization := "com.chipprbots",
  scalaVersion := `scala-3`,
  // Override Scala library version to prevent SIP-51 errors with mixed Scala patch versions
  scalaModuleInfo ~= (_.map(_.withOverrideScalaVersion(true))),
  (Test / testOptions) += Tests
    .Argument(TestFrameworks.ScalaTest, "-l", "IntegrationTest"), // network-dependent tests excluded by default
  (Test / testOptions) += Tests
    .Argument(
      TestFrameworks.ScalaTest,
      "-l",
      "ResourceHeavy"
    ), // compute/memory-bound tests excluded by default; run via testResourceHeavy
  (Test / testOptions) += Tests
    .Argument(
      TestFrameworks.ScalaTest,
      "-l",
      "FlakyTest"
    ), // known-intermittent tests excluded from every tier — never a gate; fix, don't opt back in
  // Configure scalacOptions for Scala 3
  scalacOptions := {
    val base = baseScalacOptions
    val optimizations = if (fukuiiDev) Seq.empty else scala3OptimizationsForProd
    base ++ scala3Options ++ optimizations
  },
  // Test is NOT re-appended here: sbt's config delegation chain (Test extends Runtime
  // extends Compile) means Test/scalacOptions, when not itself explicitly set, already
  // resolves to Compile/scalacOptions above — so a second `Test / scalacOptions ++=` here
  // read-then-appended the SAME two flags again, doubling them in every module's effective
  // Test scalacOptions (`sbt show <module>/Test/scalacOptions` — each of -Wvalue-discard/
  // -Wnonunit-statement listed twice) and firing `Flag ... set repeatedly` on every
  // Test/compile. Deleting this line does not drop the flags from Test — delegation still
  // carries Compile's (already-augmented) list down to Test/Integration/etc.
  (Compile / scalacOptions) ++= scala3ValueDiscardOptions,
  (Compile / console / scalacOptions) ~= (_.filterNot(
    Set(
      "-Xfatal-warnings"
    )
  )),
  (Compile / doc / scalacOptions) := baseScalacOptions ++ Seq(
    "-no-link-warnings" // Suppress link resolution warnings for F-bounded polymorphism issues
  ),
  scalacOptions ~= (options => if (fukuiiDev) options.filterNot(_ == "-Xfatal-warnings") else options),
  Test / parallelExecution := true,
  Test / fork := true, // Fork JVM for tests to ensure clean shutdown and avoid resource leak issues
  Test / javaOptions ++= Seq(
    "-Dpekko.coordinated-shutdown.exit-jvm=off", // Prevent CoordinatedShutdown from calling System.exit
    "-Dpekko.coordinated-shutdown.run-by-actor-system-terminate=on", // Ensure proper shutdown on ActorSystem.terminate
    "-Dpekko.jvm-shutdown-hooks=off" // Disable Pekko JVM shutdown hooks that may interfere with test cleanup
  ),
  Test / testForkedParallel := false, // Run tests sequentially in the forked JVM to avoid resource contention
  Test / logBuffered := false, // Stream forked JVM test output immediately rather than buffering per-suite
  (Test / testOptions) += Tests.Argument("-oDG"),
  // Ensure JUnit XML report directory exists after `sbt clean` deletes it (forked JVM writes there)
  (Test / testOptions) += {
    val reportsDir = (Test / target).value / "test-reports"
    Tests.Setup(() => IO.createDirectory(reportsDir))
  },
  // sbt 2's built-in `test` task delegates to `testQuick` (see Defaults.scala:1269), which is a
  // no-op once target/streams/ has on-disk tracking that a suite last succeeded against its current
  // sources — a warm dev machine or CI cache reports exit 0 with ZERO tests executed. Redefine
  // `test` itself to always run `testOnly *` so a bare `sbt <module>/test` can never silently
  // false-green. See the historical NOTE at the `testAll` alias in this file for the full
  // empirical trail (`sbt inspect <module>/Test/test`) that led to this fix.
  (Test / test) := (Test / testOnly).toTask(" *").value,
  // Only publish selected libraries.
  (publish / skip) := true
)

val publishSettings = Seq(
  publish / skip := false,
  crossScalaVersions := supportedScalaVersions // Scala 3 only
)

// Adding an "it" config because in `Dependencies.scala` some are declared with `% "it,test"`
// which would fail if the project didn't have configuration to add to.
val Integration = config("it").extend(Test)

// ===========================================================================
// The module DAG (bottom → top). Each `.dependsOn` edge points DOWN a layer;
// an upward edge is a compile error. See .local/docs/phase4/target-architecture.md.
// ===========================================================================

// L0 FOUNDATION (leaves)
lazy val bytes = project
  .in(file("modules/bytes"))
  .configs(Integration)
  .settings(commonSettings("fukuii-bytes"))
  .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++=
      Dependencies.pekkoUtil ++
        Dependencies.testing
  )

lazy val common = project
  .in(file("modules/common"))
  .configs(Integration)
  .settings(commonSettings("fukuii-common"))
  .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++=
      Dependencies.cats ++
        Dependencies.logging ++
        Dependencies.testing
  )

lazy val crypto = project
  .in(file("modules/crypto"))
  .configs(Integration)
  .dependsOn(bytes)
  .settings(commonSettings("fukuii-crypto"))
  .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++=
      Dependencies.pekkoUtil ++
        Dependencies.crypto ++
        Dependencies.testing
  )

lazy val rlp = project
  .in(file("modules/rlp"))
  .configs(Integration)
  .dependsOn(bytes)
  .settings(commonSettings("fukuii-rlp"))
  .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++=
      Dependencies.pekkoUtil ++
        Dependencies.testing
  )

// L1 DOMAIN (pure value types)
lazy val domain = project
  .in(file("modules/domain"))
  .configs(Integration)
  .dependsOn(bytes, crypto, rlp, common)
  .settings(commonSettings("fukuii-domain"))
  .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++=
      Dependencies.cats ++
        Dependencies.enumeratum ++
        Dependencies.testing
  )

// L2 STORAGE & STATE
lazy val storage = project
  .in(file("modules/storage"))
  .configs(Integration)
  .dependsOn(domain, common)
  .settings(commonSettings("fukuii-storage"))
  .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++=
      Dependencies.cats ++
        Dependencies.fs2 ++
        Dependencies.rocksDb ++
        Dependencies.scaffeine ++
        Dependencies.testing
  )

lazy val trie = project
  .in(file("modules/trie"))
  .configs(Integration)
  .dependsOn(domain, crypto, storage)
  .settings(commonSettings("fukuii-trie"))
  .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++=
      Dependencies.cats ++
        Dependencies.testing
  )

// L3 EVM
lazy val evm = project
  .in(file("modules/evm"))
  .configs(Integration)
  .dependsOn(domain, crypto, rlp)
  .settings(commonSettings("fukuii-evm"))
  .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++=
      Dependencies.cats ++
        Dependencies.testing
  )

// L4 EXECUTION
lazy val execution = project
  .in(file("modules/execution"))
  .configs(Integration)
  .dependsOn(evm, trie, storage, domain)
  .settings(commonSettings("fukuii-execution"))
  .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++=
      Dependencies.cats ++
        Dependencies.testing
  )

// L5 CONSENSUS (pow/pos as internal packages for now)
lazy val consensus = project
  .in(file("modules/consensus"))
  .configs(Integration)
  .dependsOn(execution, evm, domain)
  .settings(commonSettings("fukuii-consensus"))
  .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++=
      Dependencies.cats ++
        Dependencies.testing
  )

// L6 NETWORKING
lazy val network = project
  .in(file("modules/network"))
  .configs(Integration)
  .dependsOn(domain, crypto, rlp, common)
  .settings(commonSettings("fukuii-network"))
  .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++=
      Dependencies.pekko ++
        Dependencies.cats ++
        Dependencies.scodec ++
        Dependencies.netty ++
        Dependencies.testing
  )

// L7 SYNC
lazy val sync = project
  .in(file("modules/sync"))
  .configs(Integration)
  .dependsOn(network, consensus, execution, storage, trie)
  .settings(commonSettings("fukuii-sync"))
  .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++=
      Dependencies.pekko ++
        Dependencies.cats ++
        Dependencies.testing
  )

// L9 RPC
lazy val rpc = project
  .in(file("modules/rpc"))
  .configs(Integration)
  .dependsOn(domain, execution, consensus, sync, network, storage)
  .settings(commonSettings("fukuii-rpc"))
  .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
  .settings(publishSettings)
  .settings(
    libraryDependencies ++=
      Dependencies.pekko ++
        Dependencies.pekkoHttp ++
        Dependencies.circe ++ // sole JSON codec — json4s consolidated out, see Dependencies.scala
        // GraphQL: Caliban + caliban-pekko-http (successor to Sangria, removed) — dep-add
        // deferred to when this module is actually built, see Dependencies.scala
        Dependencies.cats ++
        Dependencies.testing
  )

// L10 NODE ASSEMBLY (composition root — aggregates and depends on everything)
lazy val node = {
  val Benchmark = config("benchmark").extend(Test)
  val Evm = config("evm").extend(Test)
  val Rpc = config("rpcTest").extend(Test)

  val malletDeps = Seq(
    Dependencies.scopt
  ).flatten ++ Seq(
    Dependencies.jline,
    Dependencies.jna
  )

  val dep =
    Seq(
      Dependencies.pekko,
      Dependencies.pekkoHttp,
      Dependencies.apacheCommons,
      Dependencies.apacheHttpClient,
      Dependencies.boopickle,
      Dependencies.cats,
      Dependencies.circe,
      Dependencies.cli,
      Dependencies.dependencies,
      Dependencies.enumeratum,
      Dependencies.fs2,
      Dependencies.guava,
      Dependencies.logging,
      Dependencies.micrometer,
      Dependencies.network,
      Dependencies.prometheus,
      Dependencies.rocksDb,
      Dependencies.scaffeine,
      Dependencies.scopt,
      Dependencies.testing
    ).flatten ++ malletDeps

  val node = project
    .in(file("."))
    .configs(Integration, Benchmark, Evm, Rpc)
    .enablePlugins(BuildInfoPlugin)
    .aggregate(bytes, common, crypto, rlp, domain, storage, trie, evm, execution, consensus, network, sync, rpc)
    .dependsOn(bytes, common, crypto, rlp, domain, storage, trie, evm, execution, consensus, network, sync, rpc)
    .settings(
      buildInfoKeys ++= Seq[BuildInfoKey](
        name,
        version,
        scalaVersion,
        sbtVersion,
        // engine_getClientVersionV1 requires `commit` to be exactly 8 hex chars
        // (4 bytes per execution-apis spec); 7 trips Lighthouse's length check.
        BuildInfoKey.action("gitHeadCommit")(git.gitHeadCommit.?.value.flatten.map(_.take(8)).getOrElse("unknown")),
        BuildInfoKey.action("gitCurrentBranch") {
          val branch = git.gitCurrentBranch.?.value.getOrElse("")
          if (branch != null && branch.nonEmpty) branch else "unknown"
        },
        BuildInfoKey.action("gitCurrentTags")(git.gitCurrentTags.?.value.getOrElse(Seq.empty).mkString(",")),
        BuildInfoKey.action("gitDescribedVersion")(git.gitDescribedVersion.?.value.flatten.getOrElse("unknown")),
        BuildInfoKey.action("gitUncommittedChanges")(git.gitUncommittedChanges.?.value.getOrElse(false)),
        // Under sbt 2 / Scala 3.8.4, a scoped `SettingKey` (`Compile / libraryDependencies`) no
        // longer picks up sbt-buildinfo's implicit SettingKey->BuildInfoKey conversion the way a
        // bare key does — made explicit via BuildInfoKey.action, matching the other custom keys above.
        BuildInfoKey.action("libraryDependencies")((Compile / libraryDependencies).value)
      ),
      buildInfoPackage := "com.chipprbots.fukuii",
      (Test / fork) := true,
      (Compile / buildInfoOptions) += BuildInfoOption.ToMap
    )
    .settings(
      // node is the composition root at `.in(file("."))`, but its own sources live under
      // modules/node/ alongside every other module. Point the source dirs there rather than
      // at the default ./src/main so the repo root stays free of a bare src/ tree.
      (Compile / unmanagedSourceDirectories) := Seq(baseDirectory.value / "modules" / "node" / "src" / "main" / "scala"),
      (Test / unmanagedSourceDirectories) := Seq(baseDirectory.value / "modules" / "node" / "src" / "test" / "scala")
    )
    .settings(commonSettings("fukuii")*)
    .settings(inConfig(Integration)(scalafixConfigSettings(Integration)))
    .settings(inConfig(Evm)(scalafixConfigSettings(Evm)))
    .settings(inConfig(Rpc)(scalafixConfigSettings(Rpc)))
    .settings(
      libraryDependencies ++= dep
    )
    .settings(
      executableScriptName := name.value
    )
    // Defaults.testSettings defines a local `test` key per config that also delegates to
    // `testQuick` (the same sbt-2 false-green bug fixed above for the `Test` config) — each of
    // these configs gets its own bare-config-scoped override so `sbt node/<config>/test` is safe too.
    .settings(
      inConfig(Integration)(
        Defaults.testSettings :+ (Test / parallelExecution := false) :+
          (Integration / test := (Integration / testOnly).toTask(" *").value)
      )*
    )
    .settings(
      inConfig(Benchmark)(
        Defaults.testSettings :+ (Test / parallelExecution := true) :+
          (Benchmark / test := (Benchmark / testOnly).toTask(" *").value)
      )*
    )
    .settings(
      inConfig(Evm)(
        Defaults.testSettings :+ (Test / parallelExecution := true) :+
          (Evm / test := (Evm / testOnly).toTask(" *").value)
      )*
    )
    .settings(
      inConfig(Rpc)(
        Defaults.testSettings :+ (Test / parallelExecution := true) :+
          (Rpc / test := (Rpc / testOnly).toTask(" *").value)
      )*
    )
    .settings(
      // Packaging
      maintainer := "chippr-robotics@github.com",
      (Compile / mainClass) := Some("com.chipprbots.fukuii.node.Main"),
      (Compile / discoveredMainClasses) := Seq(),
      // Keep sbt-native-packager Docker builds on the same supported runtime as the hand-written Dockerfiles.
      dockerBaseImage := "eclipse-temurin:25-jre-noble",
      // Use a wildcard classpath ("lib/*") instead of enumerating every jar by name to stay
      // under cmd.exe's ~8KB command-line limit on Windows.
      scriptClasspath := Seq("*"),
      // Assembly configuration
      (assembly / mainClass) := Some("com.chipprbots.fukuii.node.Main"),
      (assembly / assemblyJarName) := s"fukuii-assembly-${version.value}.jar",
      (assembly / assemblyMergeStrategy) := {
        case PathList("META-INF", "MANIFEST.MF")                                       => MergeStrategy.discard
        case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".SF"))  => MergeStrategy.discard
        case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".DSA")) => MergeStrategy.discard
        case PathList("META-INF", xs @ _*) if xs.lastOption.exists(_.endsWith(".RSA")) => MergeStrategy.discard
        case PathList("META-INF", "io.netty.versions.properties")                      => MergeStrategy.first
        case PathList("META-INF", "native", xs @ _*)                                   => MergeStrategy.first
        case PathList("META-INF", "native-image", xs @ _*)                             => MergeStrategy.first
        case PathList("META-INF", "versions", xs @ _*)                                 => MergeStrategy.first
        case "module-info.class"                                                       => MergeStrategy.discard
        case "reference.conf"                                                          => MergeStrategy.concat
        case "application.conf"                                                        => MergeStrategy.concat
        case x if x.endsWith(".proto")                                                 => MergeStrategy.first
        case x if x.contains("pekko")                                                  => MergeStrategy.first
        case x if x.contains("akka")                                                   => MergeStrategy.first
        case _                                                                         => MergeStrategy.first
      }
    )

  node
}

// Scoverage configuration
coverageEnabled := false // Disabled by default, enable with `sbt coverage`
coverageMinimumStmtTotal := 70
coverageFailOnMinimum := true
coverageHighlighting := true
coverageExcludedPackages := Seq(
  "com\\.chipprbots\\.fukuii\\.BuildInfo" // BuildInfo generated code
).mkString(";")
coverageExcludedFiles := Seq(
  ".*/src_managed/.*", // All managed sources
  ".*/target/.*/src_managed/.*" // Target managed sources
).mkString(";")

// ===========================================================================
// Command aliases (rewritten for the new module DAG)
// ===========================================================================

addCommandAlias(
  "compile-all",
  """; bytes / compile
    |; bytes / Test / compile
    |; common / compile
    |; common / Test / compile
    |; crypto / compile
    |; crypto / Test / compile
    |; rlp / compile
    |; rlp / Test / compile
    |; domain / compile
    |; domain / Test / compile
    |; storage / compile
    |; storage / Test / compile
    |; trie / compile
    |; trie / Test / compile
    |; evm / compile
    |; evm / Test / compile
    |; execution / compile
    |; execution / Test / compile
    |; consensus / compile
    |; consensus / Test / compile
    |; network / compile
    |; network / Test / compile
    |; sync / compile
    |; sync / Test / compile
    |; rpc / compile
    |; rpc / Test / compile
    |; compile
    |; Test / compile
    |; Evm / compile
    |; It / compile
    |; RpcTest / compile
    |; Benchmark / compile
    |""".stripMargin
)

// prepare PR
addCommandAlias(
  "pp",
  """; compile-all
    |; bytes / scalafmtAll
    |; common / scalafmtAll
    |; crypto / scalafmtAll
    |; rlp / scalafmtAll
    |; domain / scalafmtAll
    |; storage / scalafmtAll
    |; trie / scalafmtAll
    |; evm / scalafmtAll
    |; execution / scalafmtAll
    |; consensus / scalafmtAll
    |; network / scalafmtAll
    |; sync / scalafmtAll
    |; rpc / scalafmtAll
    |; scalafmtAll
    |; test
    |""".stripMargin
)

// format all modules
addCommandAlias(
  "formatAll",
  """; compile-all
    |; bytes / scalafixAll
    |; bytes / scalafmtAll
    |; common / scalafixAll
    |; common / scalafmtAll
    |; crypto / scalafixAll
    |; crypto / scalafmtAll
    |; rlp / scalafixAll
    |; rlp / scalafmtAll
    |; domain / scalafixAll
    |; domain / scalafmtAll
    |; storage / scalafixAll
    |; storage / scalafmtAll
    |; trie / scalafixAll
    |; trie / scalafmtAll
    |; evm / scalafixAll
    |; evm / scalafmtAll
    |; execution / scalafixAll
    |; execution / scalafmtAll
    |; consensus / scalafixAll
    |; consensus / scalafmtAll
    |; network / scalafixAll
    |; network / scalafmtAll
    |; sync / scalafixAll
    |; sync / scalafmtAll
    |; rpc / scalafixAll
    |; rpc / scalafmtAll
    |; scalafixAll
    |; scalafmtAll
    |""".stripMargin
)

// check modules formatting
addCommandAlias(
  "formatCheck",
  """; compile-all
    |; scalafmtCheckAll
    |; scalafixAll --check
    |""".stripMargin
)

// testAll
// NOTE (sbt-2 gotcha, FIXED AT THE SOURCE — verified empirically via `sbt inspect bytes/Test/test`):
// in sbt 2.0.2 the built-in `test` task ITSELF is redefined to delegate to `testQuick` — its
// "Provided by" is `<scope> / test`, but `Dependencies:` shows only `<scope> / testQuick`, and its
// description reads "Executes the tests that ... were not run or whose transitive dependencies
// changed" — i.e. `test` IS `testQuick` for every scope (`Test /`, `Integration /`, etc). Once
// `target/streams/` has on-disk tracking that a suite last succeeded against its current sources
// (the normal state on a warm dev machine or CI cache), `test` used to silently report exit 0 with
// ZERO tests executed ("No tests to run for .../testQuick") in ~1 second — a false green, not a
// real pass.
//
// `commonSettings` (above) now redefines `Test / test := (Test / testOnly).toTask(" *").value` for
// every module, and the `node` project's `Integration`/`Benchmark`/`Evm`/`Rpc` configs get the same
// config-scoped override — so a bare `sbt <module>/test` (or `<module>/<config>/test`) is safe
// again and can no longer silently no-op. `sbt inspect <module>/Test/test` now shows
// `Dependencies: <module> / Test / testOnly` with no `testQuick` in the chain.
//
// The aliases below still spell out `Test / testOnly *` per module rather than switching to the
// now-safe bare `test` — this is a deliberate choice, not leftover caution: the tag-filtered
// variants a few sections down (`testEssential`/`testStandard`) MUST use `testOnly ... -- -l Tag`
// syntax (bare `test` cannot take ScalaTest tag-filter arguments), so keeping every alias in this
// file on the same `testOnly` idiom avoids a two-styles-that-do-the-same-thing split. `pp`'s final
// step was changed from `testQuick` to plain `test` instead, specifically to exercise the new safe
// default end-to-end on every `pp` run.
addCommandAlias(
  "testAll",
  """; compile-all
    |; bytes / Test / testOnly *
    |; common / Test / testOnly *
    |; crypto / Test / testOnly *
    |; rlp / Test / testOnly *
    |; domain / Test / testOnly *
    |; storage / Test / testOnly *
    |; trie / Test / testOnly *
    |; evm / Test / testOnly *
    |; execution / Test / testOnly *
    |; consensus / Test / testOnly *
    |; network / Test / testOnly *
    |; sync / Test / testOnly *
    |; rpc / Test / testOnly *
    |; Test / testOnly *
    |; It / testOnly *
    |""".stripMargin
)

// testCoverage - Run tests with coverage
addCommandAlias(
  "testCoverage",
  """; coverage
    |; testAll
    |; coverageReport
    |; coverageAggregate
    |""".stripMargin
)

// testCoverageOff - Run tests without coverage (cleanup)
addCommandAlias(
  "testCoverageOff",
  """; coverageOff
    |; testAll
    |""".stripMargin
)

// ===== Test Tagging Commands (ADR-017) =====

// testEssential - Tier 1: Essential tests (< 5 minutes)
// See the testAll NOTE above re: `test` ≡ `testQuick` in sbt 2 — the module-level runs below use
// `testOnly *` to force real execution instead of the bare `Test / test` false-green no-op.
//
// SECOND false-green, distinct from the test≡testQuick bug (verified empirically): `testOnly --
// -l SomeTag` (an EMPTY test-selector list before `--`, only framework args after it) silently
// matches ZERO tests — "No tests to run" — even though the sbt `inspect` description for `testOnly`
// promises "all tests if no arguments provided." That promise only holds for a *fully* bare
// `testOnly` (no `--` at all); once a `--` is present, ScalaTest's runner requires an explicit
// selector before it. A bare `*` wildcard before `--` restores "all tests, minus these tags."
addCommandAlias(
  "testEssential",
  """; compile-all
    |; Test / testOnly * -- -l SlowTest -l IntegrationTest -l SyncTest -l DisabledTest
    |; bytes / Test / testOnly *
    |; common / Test / testOnly *
    |; crypto / Test / testOnly *
    |; rlp / Test / testOnly *
    |""".stripMargin
)

// testStandard - Tier 2: Standard tests (< 30 minutes)
// See the testEssential NOTE above re: `testOnly -- -l Tag` needing an explicit `*` wildcard
// before `--` — otherwise it silently matches zero tests instead of "all tests minus these tags."
addCommandAlias(
  "testStandard",
  """; compile-all
    |; Test / testOnly * -- -l BenchmarkTest -l EthereumTest -l SyncTest -l DisabledTest
    |""".stripMargin
)

// testComprehensive - Tier 3: Comprehensive tests (< 3 hours)
addCommandAlias(
  "testComprehensive",
  """; compile-all
    |; testAll
    |; It / testOnly
    |""".stripMargin
)

// ===========================================================================
// L4 reference-test tier — the CI-runnable counterpart to the opt-in local survey
// (BlockchainTestDriverSpec's own doc comment). Points the harness at the SHA-pinned
// `vendor/reference-tests/{ethereum-tests,etc-tests}` submodules (vendored `d2c258cf1`)
// instead of a Claude-tooling-local `.claude/repo-references` checkout, so the gate is
// reproducible for any contributor / CI runner that ran `git submodule update --init`.
//
// Skip-safe by construction: BlockchainTestDriverSpec's own `corpusDir()` cancels
// (never fails) unless `-Dfukuii.bt.survey=<dir>` names an EXISTING directory — a fresh
// clone that hasn't initialized the submodules gets a clean skip, not a red build.
//
// Each alias is a `session clear ; set … += ; testOnly ; session clear` bracket, matching
// the exact `set`/`testOnly` incantation documented in BlockchainTestDriverSpec's
// scaladoc. The `session clear` bracket matters because sbt 2 keeps a persistent
// background server per project directory — a bare `sbt <alias>` on a machine with an
// already-running server is a THIN CLIENT to it, so a `set` from a prior invocation
// would otherwise silently outlive that invocation and leak `-Dfukuii.bt.survey=...`
// into the next unrelated `sbt` command (verified empirically: it turned a subsequent
// plain `execution/testOnly *` unit-suite run into an accidental ETC-corpus run). The
// leading `session clear` makes each alias idempotent against a prior failed run that
// didn't reach its own trailing cleanup (chained `;` commands stop at the first failure,
// so the trailing `session clear` is best-effort, not guaranteed, on a FAILING run).
addCommandAlias(
  "referenceTestEth",
  """; session clear
    |; set execution / Test / javaOptions += "-Dfukuii.bt.survey=" + ((ThisBuild / baseDirectory).value / "vendor" / "reference-tests" / "ethereum-tests" / "BlockchainTests").getAbsolutePath
    |; execution / testOnly *BlockchainTestDriverSpec
    |; session clear
    |""".stripMargin
)

addCommandAlias(
  "referenceTestEtc",
  """; session clear
    |; set execution / Test / javaOptions += "-Dfukuii.bt.survey=" + ((ThisBuild / baseDirectory).value / "vendor" / "reference-tests" / "etc-tests" / "BlockchainTests").getAbsolutePath
    |; execution / testOnly *BlockchainTestDriverSpec
    |; session clear
    |""".stripMargin
)

// Local convenience only — runs both corpora in one sbt session. CI drives each corpus as
// its own `sbt referenceTestEth` / `sbt referenceTestEtc` process (see
// .github/workflows/blockchain-tests-reference.yml) rather than this alias.
addCommandAlias(
  "referenceTest",
  """; referenceTestEth
    |; referenceTestEtc
    |""".stripMargin
)

// Scapegoat is DROPPED for the sbt-2 cutover: sbt-scapegoat (com.sksamuel.scapegoat) has no
// sbt-2-compatible plugin artifact, so its settings would fail meta-build resolution. See the
// dropped-plugin note in project/plugins.sbt. Re-add scapegoatVersion/scapegoatReports/etc. once
// upstream ships an sbt-2 port.
