package com.chipprbots.fukuii.trie

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import com.chipprbots.fukuii.bytes.Hex

/** The `ethereum/tests/TrieTests` reference fixtures — the byte-exact state-root consensus gate. Runs the actual JSON
  * fixtures (plain and `*_secureTrie`), asserting every published root byte-for-byte.
  */
class TrieReferenceVectorsSpec extends AnyFlatSpec with Matchers:

  private val fixtureSubPath = ".claude/repo-references/ethereum/tests/TrieTests"

  /** Resolve the fixture dir by walking up from the working directory — robust to forked tests running with `cwd` at
    * the module base (`modules/trie`) rather than the repo root.
    */
  private val fixtureDir: File =
    Iterator
      .iterate(new File(System.getProperty("user.dir")))(_.getParentFile)
      .takeWhile(_ != null)
      .map(new File(_, fixtureSubPath))
      .find(_.isDirectory)
      .getOrElse(fail(s"could not locate $fixtureSubPath by walking up from ${System.getProperty("user.dir")}"))

  private def readFixture(name: String): TrieJson.J =
    val f = new File(fixtureDir, name)
    require(f.exists(), s"fixture not found: ${f.getAbsolutePath}")
    TrieJson.parse(new String(Files.readAllBytes(f.toPath), StandardCharsets.UTF_8))

  private def decodeBytes(s: String): Array[Byte] =
    if s.startsWith("0x") || s.startsWith("0X") then Hex.decode(s.substring(2))
    else s.getBytes(StandardCharsets.UTF_8)

  private def rootOf(pairs: Seq[(Array[Byte], Option[Array[Byte]])], secure: Boolean): String =
    val storage = new InMemoryMptStorage
    val base = ByteArraySerializable.rawByteArraySerializable
    val kEnc: ByteArrayEncoder[Array[Byte]] = if secure then HashByteArraySerializable(base) else base
    var trie = MerklePatriciaTrie[Array[Byte], Array[Byte]](storage)(using kEnc, base)
    pairs.foreach {
      case (k, Some(v)) => trie = trie.put(k, v)
      case (k, None)    => trie = trie.remove(k)
    }
    "0x" + Hex.toHexString(trie.getRootHash.toArray)

  /** `in` is an ordered array of `[key, value|null]`. */
  private def orderedPairs(in: TrieJson.J): Seq[(Array[Byte], Option[Array[Byte]])] = in match
    case TrieJson.JArr(items) =>
      items.map {
        case TrieJson.JArr(List(TrieJson.JStr(k), TrieJson.JStr(v))) => decodeBytes(k) -> Some(decodeBytes(v))
        case TrieJson.JArr(List(TrieJson.JStr(k), TrieJson.JNull))   => decodeBytes(k) -> None
        case other                                                   => fail(s"bad ordered entry: $other")
      }
    case other => fail(s"expected an array for ordered `in`, got $other")

  /** `in` is an unordered object of `key -> value`. */
  private def unorderedPairs(in: TrieJson.J): Seq[(Array[Byte], Option[Array[Byte]])] = in match
    case TrieJson.JObj(fields) =>
      fields.map {
        case (k, TrieJson.JStr(v)) => decodeBytes(k) -> Some(decodeBytes(v));
        case (k, other)            => fail(s"bad value for $k: $other")
      }
    case other => fail(s"expected an object for unordered `in`, got $other")

  private def runFixture(name: String, secure: Boolean, ordered: Boolean): Unit =
    readFixture(name) match
      case TrieJson.JObj(tests) =>
        tests.foreach { case (testName, TrieJson.JObj(body)) =>
          val bodyMap = body.toMap
          val in = bodyMap("in")
          val expectedRoot = bodyMap("root") match
            case TrieJson.JStr(r) => r.toLowerCase
            case other            => fail(s"$name/$testName: bad root $other")
          val pairs = if ordered then orderedPairs(in) else unorderedPairs(in)
          withClue(s"$name / $testName: ") {
            rootOf(pairs, secure).toLowerCase shouldBe expectedRoot
          }
        }
      case other => fail(s"$name: expected top-level object, got $other")

  "MerklePatriciaTrie" should "match every root in trietest.json (plain, ordered)" in {
    runFixture("trietest.json", secure = false, ordered = true)
  }

  it should "match every root in trietest_secureTrie.json (secure, ordered)" in {
    runFixture("trietest_secureTrie.json", secure = true, ordered = true)
  }

  it should "match every root in trieanyorder.json (plain, unordered)" in {
    runFixture("trieanyorder.json", secure = false, ordered = false)
  }

  it should "match every root in trieanyorder_secureTrie.json (secure, unordered)" in {
    runFixture("trieanyorder_secureTrie.json", secure = true, ordered = false)
  }

  it should "match every root in hex_encoded_securetrie_test.json (secure, hex-encoded, unordered)" in {
    runFixture("hex_encoded_securetrie_test.json", secure = true, ordered = false)
  }

/** A minimal recursive-descent JSON reader for the fixed TrieTests fixture grammar (objects, arrays, strings, `null`,
  * booleans). No external JSON dependency on the `trie` classpath; the fixtures contain no string escapes.
  */
object TrieJson:
  sealed trait J
  final case class JObj(fields: List[(String, J)]) extends J
  final case class JArr(items: List[J]) extends J
  final case class JStr(value: String) extends J
  final case class JBool(value: Boolean) extends J
  case object JNull extends J

  def parse(input: String): J =
    val p = new Parser(input)
    val v = p.parseValue()
    p.skipWs()
    v

  final private class Parser(s: String):
    private var i = 0

    def skipWs(): Unit = while i < s.length && s(i).isWhitespace do i += 1

    def parseValue(): J =
      skipWs()
      s(i) match
        case '{' => parseObj()
        case '[' => parseArr()
        case '"' => JStr(parseStr())
        case 'n' => expect("null"); JNull
        case 't' => expect("true"); JBool(true)
        case 'f' => expect("false"); JBool(false)
        case c   => sys.error(s"Unexpected JSON character '$c' at index $i")

    private def parseObj(): JObj =
      i += 1 // consume '{'
      val fields = List.newBuilder[(String, J)]
      skipWs()
      if s(i) == '}' then i += 1
      else
        var more = true
        while more do
          skipWs()
          val key = parseStr()
          skipWs()
          require(s(i) == ':', s"expected ':' at $i")
          i += 1
          val value = parseValue()
          fields += (key -> value)
          skipWs()
          s(i) match
            case ',' => i += 1
            case '}' => i += 1; more = false
            case c   => sys.error(s"expected ',' or '}' at $i, got '$c'")
      JObj(fields.result())

    private def parseArr(): JArr =
      i += 1 // consume '['
      val items = List.newBuilder[J]
      skipWs()
      if s(i) == ']' then i += 1
      else
        var more = true
        while more do
          items += parseValue()
          skipWs()
          s(i) match
            case ',' => i += 1
            case ']' => i += 1; more = false
            case c   => sys.error(s"expected ',' or ']' at $i, got '$c'")
      JArr(items.result())

    private def parseStr(): String =
      require(s(i) == '"', s"expected '\"' at $i")
      i += 1
      val sb = new StringBuilder
      while s(i) != '"' do
        if s(i) == '\\' then
          i += 1
          s(i) match
            case '"'   => sb.append('"')
            case '\\'  => sb.append('\\')
            case '/'   => sb.append('/')
            case 'n'   => sb.append('\n')
            case 't'   => sb.append('\t')
            case other => sb.append(other)
          i += 1
        else
          sb.append(s(i))
          i += 1
      i += 1 // consume closing '"'
      sb.toString

    private def expect(literal: String): Unit =
      require(s.regionMatches(i, literal, 0, literal.length), s"expected '$literal' at $i")
      i += literal.length
