package com.chipprbots.fukuii.storage

import java.nio.file.Files

private[storage] object RocksDbTestConfig:
  def apply(statisticsEnabled: Boolean = false): RocksDbConfig =
    val dbPath = Files.createTempDirectory("fukuii-storage-test").toAbsolutePath.toString
    new RocksDbConfig:
      override val createIfMissing: Boolean = true
      override val paranoidChecks: Boolean = true
      override val path: String = dbPath
      override val maxThreads: Int = 1
      override val maxOpenFiles: Int = 32
      override val verifyChecksums: Boolean = true
      override val levelCompaction: Boolean = true
      override val blockSize: Long = 16384
      override val blockCacheSize: Long = 8L * 1024 * 1024
      override val enableStatistics: Boolean = statisticsEnabled
