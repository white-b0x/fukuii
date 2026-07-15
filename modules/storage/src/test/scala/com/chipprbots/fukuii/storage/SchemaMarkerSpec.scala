package com.chipprbots.fukuii.storage

import org.scalatest.funsuite.AnyFunSuite

class SchemaMarkerSpec extends AnyFunSuite:

  private val hashProfile = StorageProfile.ArchivalDApp
  private val pathProfile = StorageProfile.TipServer

  test("a fresh datadir records the marker and a matching reopen succeeds"):
    val ds = EphemDataSource()
    val opened = StorageProfile.namespacesFor(hashProfile)
    SchemaMarker.ensureCompatible(ds, opened, hashProfile)
    // Reopening with the identical profile + CF set is a no-op success, not a rewrite.
    SchemaMarker.ensureCompatible(ds, opened, hashProfile)

  test("reopening a datadir under a different profile is rejected with a typed error"):
    val ds = EphemDataSource()
    SchemaMarker.ensureCompatible(ds, StorageProfile.namespacesFor(hashProfile), hashProfile)
    val ex = intercept[SchemaMarker.SchemaMismatchException] {
      SchemaMarker.ensureCompatible(ds, StorageProfile.namespacesFor(pathProfile), pathProfile)
    }
    assert(ex.getMessage.contains("marker mismatch"))

  test("an opened CF set that doesn't match the resolved profile is rejected before the marker is even consulted"):
    val ds = EphemDataSource()
    val wrongOpenSet = StorageProfile.namespacesFor(pathProfile) // doesn't match hashProfile
    val ex = intercept[SchemaMarker.SchemaMismatchException] {
      SchemaMarker.ensureCompatible(ds, wrongOpenSet, hashProfile)
    }
    assert(ex.getMessage.contains("column-family set"))
    // And the marker CF was never written, since the CF-set check fails first.
    assert(ds.get(Namespace.SchemaMeta, IndexedSeq(0.toByte)).isEmpty)

  test("SchemaMarker encode/decode round-trips every axis"):
    val marker = SchemaMarker(StorageFormat.V1, 7, pathProfile)
    assert(SchemaMarker.decode(SchemaMarker.encode(marker).toIndexedSeq) == marker)
