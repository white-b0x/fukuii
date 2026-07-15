package com.chipprbots.fukuii.storage

import org.scalatest.flatspec.AnyFlatSpec

class EphemDataSourceSpec extends AnyFlatSpec with DataSourceContractBehaviors:
  (it should behave).like(dataSourceContract(() => EphemDataSource()))
