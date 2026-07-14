import sbt._

object Dependencies {

  // Apache Pekko - Scala 3 compatible fork of Akka
  private val pekkoVersion = "1.6.0"
  private val pekkoHttpVersion = "1.3.0"

  val pekkoUtil: Seq[ModuleID] =
    Seq(
      "org.apache.pekko" %% "pekko-actor" % pekkoVersion
    )

  val pekko: Seq[ModuleID] =
    Seq(
      "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
      "org.apache.pekko" %% "pekko-actor-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
      "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % "it,test",
      "org.apache.pekko" %% "pekko-actor-testkit-typed" % pekkoVersion % "it,test",
      "org.apache.pekko" %% "pekko-stream" % pekkoVersion
    )

  val pekkoHttp: Seq[ModuleID] =
    Seq(
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-cors" % pekkoHttpVersion,
      "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % "it,test"
      // JSON marshalling is circe (see `circe` below), not json4s — pekko-http-circe when the
      // rpc module's JSON-RPC/WS/IPC marshalling is actually built.
    )

  val circe: Seq[ModuleID] = {
    val circeVersion = "0.14.15"

    Seq(
      "io.circe" %% "circe-core" % circeVersion,
      "io.circe" %% "circe-generic" % circeVersion,
      "io.circe" %% "circe-parser" % circeVersion
      // NOTE: circe-generic-extras is deprecated and not available for Scala 3
      // Functionality has been integrated into circe-generic in 0.14.x
      // See: https://github.com/circe/circe-generic-extras/issues/276
    )
  }

  // GraphQL (EIP-1767 execution-layer GraphQL endpoint): Sangria (Scala-2-era) removed —
  // successor is Caliban 3.1.2 + caliban-pekko-http (Scala-3-native; caliban-cats for
  // cats-effect interop), per MOD-14. Not added here: the dep-add is deferred to when the rpc
  // layer is actually built, through the sentinel dependency-add gate (owner: conduit).

  val boopickle = Seq("io.suzaku" %% "boopickle" % "1.5.0") // Updated for Scala 3 support

  val rocksDb = Seq(
    "org.rocksdb" % "rocksdbjni" % "10.10.1.1" // Confirmed current: this IS the latest published artifact (10.10.1, without the JNI-patch suffix, is an older sibling, not a newer version — resolves the prior VERIFY flag)
  )

  val enumeratum: Seq[ModuleID] = Seq(
    "com.beachape" %% "enumeratum" % "1.9.8",
    "com.beachape" %% "enumeratum-cats" % "1.9.8",
    "com.beachape" %% "enumeratum-scalacheck" % "1.9.8" % Test
  )

  val testing: Seq[ModuleID] = Seq(
    "org.scalatest" %% "scalatest" % "3.2.20" % "it,test",
    "org.scalamock" %% "scalamock" % "7.5.5" % "it,test", // 7.3.3 -> 7.5.5, current stable
    // scalatestplus module names are versioned to match the paired library's major.minor —
    // scalacheck-1-18/mockito-5-12 were stale pairings vs the actual scalacheck 1.19.0 /
    // mockito-core 5.23.0 pins below; corrected to the matching modules (also now at 3.2.20.0,
    // matching the scalatest pin above exactly instead of trailing at 3.2.19.0).
    "org.scalatestplus" %% "scalacheck-1-19" % "3.2.20.0" % "test",
    "org.scalatestplus" %% "mockito-5-23" % "3.2.20.0" % "it,test",
    "org.mockito" % "mockito-core" % "5.23.0" % "it,test",
    "org.scalacheck" %% "scalacheck" % "1.19.0" % "it,test"
  )

  val cats: Seq[ModuleID] = {
    val catsVersion = "2.13.0"
    val catsEffectVersion = "3.7.0"
    Seq(
      "org.typelevel" %% "mouse" % "1.4.0",
      "org.typelevel" %% "cats-core" % catsVersion,
      "org.typelevel" %% "cats-effect" % catsEffectVersion
    )
  }

  // Monix removed - fully migrated to Cats Effect 3 IO and fs2.Stream
  val monix = Seq.empty[ModuleID]

  val fs2: Seq[ModuleID] = {
    val fs2Version = "3.13.0" // requires cats-effect 3.6+ (satisfied — cats-effect pinned to 3.7.0 above)
    Seq(
      "co.fs2" %% "fs2-core" % fs2Version,
      "co.fs2" %% "fs2-io" % fs2Version,
      "co.fs2" %% "fs2-reactive-streams" % fs2Version // For interop if needed
    )
  }

  // Scalanet is now vendored locally in scalanet/ directory
  // See scalanet/ATTRIBUTION.md for details
  val network: Seq[ModuleID] = Seq.empty

  // Dependencies for scalanet module
  val scodec: Seq[ModuleID] = Seq(
    "org.scodec" %% "scodec-core" % "2.3.3", // Stable with Scala 3 support, current
    "org.scodec" %% "scodec-bits" % "1.2.5" // 1.2.1 -> 1.2.5, current stable patch line
  )

  val netty: Seq[ModuleID] = {
    val nettyVersion = "4.1.136.Final" // Security: CVE-2026-42578 (HttpProxyHandler CRLF/header injection, fixed 4.1.133.Final) + CVE-2026-44249 (IpSubnetFilterRule IPv6 subnet-filter bypass, fixed 4.1.135.Final)
    Seq(
      "io.netty" % "netty-handler" % nettyVersion,
      "io.netty" % "netty-handler-proxy" % nettyVersion, // For Socks5ProxyHandler
      "io.netty" % "netty-transport" % nettyVersion,
      "io.netty" % "netty-codec" % nettyVersion
    )
  }

  // Joda Time for DateTime (used in scalanet TLS extension)
  val jodaTime: Seq[ModuleID] = Seq(
    "joda-time" % "joda-time" % "2.14.2" // 2.12.7 -> 2.14.2, current stable
  )

  // IP math library for IP address range operations (used in scalanet)
  val ipmath: Seq[ModuleID] = Seq(
    "com.github.jgonian" % "commons-ip-math" % "1.32"
  )

  val logging = Seq(
    // Security: CVE-2026-13006 (Janino <if>-condition expression-injection ACE, denylist bypass
    // via Unicode escapes; affects logback-core <=1.5.36, fixed 1.5.37 by removing Janino
    // conditional-expression support entirely) + a HardenedObjectInputStream Throwable-whitelist
    // hardening fix in 1.5.38 (same deserialization-safety subsystem as the related
    // CVE-2026-9828). fukuii carries Janino as a direct dependency (below), satisfying
    // CVE-2026-13006's "Janino on the classpath" precondition, so this is a real exposure, not
    // theoretical. Bumped straight to 1.5.38 (vendor's latest) rather than stopping at the
    // minimum-fix 1.5.37, since 1.5.38 is itself a security-hardening patch for the same
    // deserialization-safety subsystem — both bypass the routine maintenance cooldown.
    "ch.qos.logback" % "logback-classic" % "1.5.38", // 1.5.34 -> 1.5.38 (CVE-2026-13006 fix + follow-on hardening)
    "com.typesafe.scala-logging" %% "scala-logging" % "3.9.6",
    "net.logstash.logback" % "logstash-logback-encoder" % "8.1", // 9.x requires Jackson 3, blocked by a transitive Jackson 2.x elsewhere in the graph — NOT json4s (removed; json4s-native's POM carries no Jackson dependency, that attribution was wrong) or circe (uses its own JSON AST, no Jackson). Actual puller not identified this pass — re-verify once rpc/node have real code and a resolvable classpath
    "org.codehaus.janino" % "janino" % "3.1.12",
    "org.typelevel" %% "log4cats-core" % "2.8.0",
    "org.typelevel" %% "log4cats-slf4j" % "2.8.0"
  )

  val crypto = Seq(
    "org.bouncycastle" % "bcprov-jdk18on" % "1.84",
    "org.bouncycastle" % "bcpkix-jdk18on" % "1.84",
    "io.consensys.protocols" % "jc-kzg-4844" % "2.1.6", // EIP-4844/7594 KZG ops (c-kzg-4844 JNI bindings, PeerDAS cell proofs). 2.0.0 (2024-08-22) -> 2.1.6 (2026-02-26, ~5mo old): pin was ~2yr stale; deliberately not 2.1.8 (published 2026-07-10, inside the resolution-age cooldown window with no CVE motivating an override)
    "org.hyperledger.besu" % "bls12-381" % "1.0.0" // EIP-2537 BLS12-381 precompiles (gnark/Constantine backends) — confirmed current: JFrog besu-maven metadata shows 1.0.0 as the latest real release (1.0.1-SNAPSHOT is not a release)
  )

  val scopt = Seq("com.github.scopt" %% "scopt" % "4.1.0") // Updated for Scala 3 support

  val cli = Seq("com.monovore" %% "decline" % "2.6.2")

  val apacheCommons = Seq(
    "commons-io" % "commons-io" % "2.22.0"
  )

  val apacheHttpClient = Seq(
    "org.apache.httpcomponents.client5" % "httpclient5" % "5.6.2" // For JupnP UPnP transport without URLStreamHandlerFactory. 5.6.1 -> 5.6.2, current stable patch
  )

  val jline = "org.jline" % "jline" % "3.30.13" // 4.x is a major API change — deferred

  val jna = "net.java.dev.jna" % "jna" % "5.19.1"

  val dependencies = Seq(
    jline,
    "org.scala-lang.modules" %% "scala-parser-combinators" % "2.4.0",
    "org.scala-sbt.ipcsocket" % "ipcsocket" % "1.6.3",
    "org.xerial.snappy" % "snappy-java" % "1.1.10.8",
    "org.web3j" % "core" % "4.14.0" % Test,
    "io.vavr" % "vavr" % "1.0.1",
    "org.jupnp" % "org.jupnp" % "3.0.4",
    "org.jupnp" % "org.jupnp.support" % "3.0.4",
    "org.jupnp" % "org.jupnp.tool" % "3.0.4",
    "javax.servlet" % "javax.servlet-api" % "4.0.1"
  )

  val guava: Seq[ModuleID] = {
    val version = "33.6.0-jre"
    Seq(
      "com.google.guava" % "guava" % version,
      "com.google.guava" % "guava-testlib" % version % "test"
    )
  }

  // Prometheus Java client 1.x (replaces legacy simpleclient 0.x)
  val prometheus: Seq[ModuleID] = {
    val version = "1.3.10" // 1.8.0 is a major bump — deferred
    Seq(
      "io.prometheus" % "prometheus-metrics-core" % version,
      "io.prometheus" % "prometheus-metrics-instrumentation-jvm" % version,
      "io.prometheus" % "prometheus-metrics-exporter-httpserver" % version
    )
  }

  val micrometer: Seq[ModuleID] = {
    val provider = "io.micrometer"
    val version = "1.16.6" // 1.17.0 is a minor bump — deferred
    Seq(
      // Required to compile metrics library https://github.com/micrometer-metrics/micrometer/issues/1133#issuecomment-452434205
      "com.google.code.findbugs" % "jsr305" % "3.0.2" % Optional,
      provider % "micrometer-core" % version,
      provider % "micrometer-registry-jmx" % version,
      provider % "micrometer-registry-prometheus" % version
    )
  }

  // UNUSED — not referenced by any module's libraryDependencies in build.sbt; superseded by the
  // `micrometer`/`prometheus` vals above, both wired into the `node` module.
  val kamon: Seq[ModuleID] = {
    val provider = "io.kamon"
    val version = "2.8.1"
    Seq(
      provider %% "kamon-prometheus" % version
    )
  }

  val scaffeine: Seq[ModuleID] = Seq(
    "com.github.blemale" %% "scaffeine" % "5.3.0" % "compile",
    "com.github.ben-manes.caffeine" % "caffeine" % "3.2.4"
  )

}
