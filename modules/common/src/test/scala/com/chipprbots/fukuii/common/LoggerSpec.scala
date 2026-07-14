package com.chipprbots.fukuii.common

import org.scalatest.funsuite.AnyFunSuite

class LoggerSpec extends AnyFunSuite:

  private class EagerSubject extends Logger:
    def loggerName: String = log.underlying.getName
    def emit(): Unit = log.debug("hello")

  private class LazySubject extends LazyLogger:
    def emit(): Unit = log.debug("lazy hello")

  test("Logger mix-in provides a usable, class-named logger"):
    val subject = new EagerSubject
    assert(subject.loggerName == subject.getClass.getName)
    subject.emit()

  test("LazyLogger mix-in provides a usable logger"):
    val subject = new LazySubject
    subject.emit()
