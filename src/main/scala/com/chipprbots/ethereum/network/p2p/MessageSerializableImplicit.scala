package com.chipprbots.ethereum.network.p2p

import compiletime.asMatchable

abstract class MessageSerializableImplicit[T <: Message](val msg: T) extends MessageSerializable:

  override def equals(that: Any): Boolean =
    that.asMatchable match // Any: java.lang.Object.equals — no typed alternative
      case that: MessageSerializableImplicit[?] => that.msg.equals(msg)
      case _                                    => false

  override def hashCode(): Int = msg.hashCode()

  override def toShortString: String = msg.toShortString
