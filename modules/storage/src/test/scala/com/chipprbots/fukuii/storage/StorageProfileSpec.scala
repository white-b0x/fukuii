package com.chipprbots.fukuii.storage

import org.scalatest.funsuite.AnyFunSuite

class StorageProfileSpec extends AnyFunSuite:

  private val hashProfile = StorageProfile.ArchivalDApp
  private val pathProfile = StorageProfile.TipServer

  test("hash-keyed profile opens Namespace.Node, not the path-keyed trie CFs"):
    val opened = StorageProfile.namespacesFor(hashProfile)
    assert(opened.contains(Namespace.Node))
    assert(!opened.contains(Namespace.StateTriePath))
    assert(!opened.contains(Namespace.StorageTriePath))

  test("path-keyed profile opens both path-keyed trie CFs, not Namespace.Node"):
    val opened = StorageProfile.namespacesFor(pathProfile)
    assert(!opened.contains(Namespace.Node))
    assert(opened.contains(Namespace.StateTriePath))
    assert(opened.contains(Namespace.StorageTriePath))

  test("every non-scheme-specific namespace is open under both profiles"):
    val generic = Namespace.values.toSet -- Set(Namespace.Node, Namespace.StateTriePath, Namespace.StorageTriePath)
    val openedUnderHash = StorageProfile.namespacesFor(hashProfile)
    val openedUnderPath = StorageProfile.namespacesFor(pathProfile)
    generic.foreach { ns =>
      assert(openedUnderHash.contains(ns))
      assert(openedUnderPath.contains(ns))
    }

  test("the two profiles' opened CF sets are disjoint exactly on the scheme-gated namespaces"):
    val openedUnderHash = StorageProfile.namespacesFor(hashProfile)
    val openedUnderPath = StorageProfile.namespacesFor(pathProfile)
    assert(openedUnderHash != openedUnderPath)
    assert((openedUnderHash.union(openedUnderPath)) == Namespace.values.toSet)
