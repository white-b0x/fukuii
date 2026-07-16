package com.chipprbots.fukuii.storage

import org.scalatest.funsuite.AnyFunSuite

class StorageProfileSpec extends AnyFunSuite:

  private val hashProfile = StorageProfile.ArchivalDApp
  private val pathProfile = StorageProfile.TipServer

  test("hash-keyed profile opens Namespace.Node, not the path-keyed trie CFs"):
    val opened = StorageProfile.namespacesFor(hashProfile)
    assert(
      opened.contains(Namespace.Node) &&
        !opened.contains(Namespace.StateTriePath) &&
        !opened.contains(Namespace.StorageTriePath),
      "a hash-keyed profile must open Namespace.Node and neither path-keyed trie CF"
    )

  test("path-keyed profile opens both path-keyed trie CFs, not Namespace.Node"):
    val opened = StorageProfile.namespacesFor(pathProfile)
    assert(
      !opened.contains(Namespace.Node) &&
        opened.contains(Namespace.StateTriePath) &&
        opened.contains(Namespace.StorageTriePath),
      "a path-keyed profile must open both path-keyed trie CFs and not Namespace.Node"
    )

  test("every non-scheme-specific namespace is open under both profiles"):
    val generic = Namespace.values.toSet -- Set(Namespace.Node, Namespace.StateTriePath, Namespace.StorageTriePath)
    val openedUnderHash = StorageProfile.namespacesFor(hashProfile)
    val openedUnderPath = StorageProfile.namespacesFor(pathProfile)
    generic.foreach { ns =>
      assert(
        openedUnderHash.contains(ns) && openedUnderPath.contains(ns),
        s"namespace $ns must be open under both the hash-keyed and path-keyed profiles"
      )
    }

  test("the two profiles' opened CF sets are disjoint exactly on the scheme-gated namespaces"):
    val openedUnderHash = StorageProfile.namespacesFor(hashProfile)
    val openedUnderPath = StorageProfile.namespacesFor(pathProfile)
    assert(
      openedUnderHash != openedUnderPath &&
        (openedUnderHash.union(openedUnderPath)) == Namespace.values.toSet,
      "the two profiles' opened CF sets must differ yet union to the full Namespace set"
    )
