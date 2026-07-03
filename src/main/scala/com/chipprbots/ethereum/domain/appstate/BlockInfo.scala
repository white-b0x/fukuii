package com.chipprbots.ethereum.domain.appstate

import org.apache.pekko.util.ByteString

import com.chipprbots.ethereum.domain.BlockNumber

case class BlockInfo(hash: ByteString, number: BlockNumber)
