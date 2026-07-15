package com.chipprbots.fukuii.storage

import org.scalatest.funsuite.AnyFunSuite

class NamespaceSpec extends AnyFunSuite:

  test("every namespace has a unique id (frozen on-disk contract)"):
    assert(Namespace.values.map(_.id).distinct.length == Namespace.values.length)

  test("byId round-trips every namespace"):
    Namespace.values.foreach(ns => assert(Namespace.byId(ns.id) == ns))

  test("the SNAP crash-resume frontier CFs are reserved under Profile.Snap (L2-F1)"):
    val snapNamespaces = Set(Namespace.HealingFrontier, Namespace.BfsQueue, Namespace.SnapSyncProgress)
    snapNamespaces.foreach(ns => assert(ns.includeInDatabaseFormat(Namespace.Profile.Snap)))
    Namespace.values.toSet
      .diff(snapNamespaces)
      .foreach(ns => assert(!ns.includeInDatabaseFormat(Namespace.Profile.Snap)))

  test("the path-keyed trie CFs are reserved under Profile.PathScheme"):
    val pathSchemeNamespaces = Set(Namespace.StateTriePath, Namespace.StorageTriePath)
    pathSchemeNamespaces.foreach(ns => assert(ns.includeInDatabaseFormat(Namespace.Profile.PathScheme)))

  test("every namespace belongs to Profile.Base"):
    Namespace.values.foreach(ns => assert(ns.includeInDatabaseFormat(Namespace.Profile.Base)))
