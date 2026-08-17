package io.app.enclose.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The codec the backup file rests on. It is hand-rolled because `org.json` can't
 * be unit tested in this project, which makes these tests the only thing standing
 * between a user and a backup that won't open — so they cover the awkward halves
 * (escapes, number widths, malformed input) rather than the happy path alone.
 */
class JsonTest {

    @Test
    fun `objects keep the order they were written in`() {
        val text = Json.write(linkedMapOf("b" to 1L, "a" to 2L, "c" to 3L))

        assertEquals("""{"b":1,"a":2,"c":3}""", text)
        assertEquals(listOf("b", "a", "c"), Json.parse(text).asObject()!!.keys.toList())
    }

    @Test
    fun `strings survive quotes, backslashes and newlines`() {
        // The real case: territory geometry is stored as a JSON string and goes
        // into the backup as one, so every quote in it is escaped twice over.
        val nasty = """{"lat":37.9,"lng":23.7} "quoted" \ back \\ slash""" + "\n\ttab"

        val round = Json.parse(Json.write(mapOf("ringJson" to nasty))).asObject()!!

        assertEquals(nasty, round.str("ringJson"))
    }

    @Test
    fun `control characters are escaped and read back`() {
        val text = Json.write(mapOf("k" to "a\u0001b"))

        assertTrue(text.contains("\\u0001"))
        assertEquals("a\u0001b", Json.parse(text).asObject()!!.str("k"))
    }

    @Test
    fun `non-ascii is carried as itself`() {
        val greek = "Πλατεία Ομονοίας"

        val text = Json.write(mapOf("name" to greek))

        assertTrue(text.contains(greek))
        assertEquals(greek, Json.parse(text).asObject()!!.str("name"))
    }

    /** Ids and epoch millis are Longs; passing them through a Double loses them. */
    @Test
    fun `whole numbers round trip as Long`() {
        val big = 9_007_199_254_740_993L // 2^53 + 1: the first Long a Double can't hold

        val round = Json.parse(Json.write(mapOf("at" to big))).asObject()!!

        assertEquals(big, round["at"])
        assertEquals(big, round.long("at"))
    }

    @Test
    fun `fractional numbers round trip as Double`() {
        val round = Json.parse(Json.write(mapOf("lat" to 37.98380123))).asObject()!!

        assertEquals(37.98380123, round.double("lat"), 1e-12)
    }

    @Test
    fun `exponent notation parses`() {
        val parsed = Json.parse("""{"tiny":1.0E-5,"big":2e3}""").asObject()!!

        assertEquals(1.0e-5, parsed.double("tiny"), 1e-12)
        assertEquals(2000.0, parsed.double("big"), 1e-9)
    }

    /**
     * A NaN would make the whole file unreadable, and one bad number must not
     * cost somebody their walking history.
     */
    @Test
    fun `non-finite doubles are written as null rather than as invalid JSON`() {
        val text = Json.write(mapOf("a" to Double.NaN, "b" to Double.POSITIVE_INFINITY))

        assertEquals("""{"a":null,"b":null}""", text)
        val parsed = Json.parse(text).asObject()!!
        assertNull(parsed["a"])
        assertEquals(7.0, parsed.double("a", 7.0), 0.0) // falls back, as a missing field does
    }

    @Test
    fun `nesting and empties`() {
        val value = mapOf(
            "list" to listOf(1L, "two", true, null, listOf<Any?>(), mapOf<String, Any?>()),
        )

        val text = Json.write(value)

        assertEquals("""{"list":[1,"two",true,null,[],{}]}""", text)
        assertEquals(6, Json.parse(text).asObject()!!["list"].asArray().size)
    }

    @Test
    fun `whitespace between tokens is ignored`() {
        val parsed = Json.parse("  {\n  \"a\" : [ 1 , 2 ]\t}\n ").asObject()!!

        assertEquals(listOf(1L, 2L), parsed["a"].asArray())
    }

    @Test
    fun `unicode escapes are decoded`() {
        assertEquals("A±", Json.parse(""""\u0041\u00b1"""") as String)
    }

    @Test
    fun `malformed input is refused rather than guessed at`() {
        val bad = listOf(
            "",
            "{",
            "{\"a\"}",
            "{\"a\":}",
            "{\"a\":1,}",
            "[1,2",
            "\"unterminated",
            "{}{}",
            "nope",
            "{'a':1}",
            "\"bad escape \\q\"",
        )

        bad.forEach { text ->
            assertThrows("expected '$text' to be refused", Json.JsonException::class.java) {
                Json.parse(text)
            }
        }
    }

    /** The parser recurses, and the file it reads comes from outside the app. */
    @Test
    fun `absurd nesting is refused instead of overflowing the stack`() {
        val deep = "[".repeat(500) + "]".repeat(500)

        assertThrows(Json.JsonException::class.java) { Json.parse(deep) }
    }

    /**
     * Every accessor takes a default, because "missing", "null" and "the wrong
     * type" all mean the same thing to a restore — and that is what lets an old
     * backup open in a new build.
     */
    @Test
    fun `accessors fall back instead of throwing`() {
        val m = Json.parse("""{"s":1,"n":null,"b":"yes","d":"nope"}""").asObject()!!

        assertEquals("fallback", m.str("s", "fallback"))
        assertEquals("fallback", m.str("absent", "fallback"))
        assertNull(m.strOrNull("n"))
        assertTrue(m.bool("b", fallback = true))
        assertEquals(3.5, m.double("d", 3.5), 0.0)
        assertEquals(9L, m.long("absent", 9L))
        assertNull(m.longOrNull("absent"))
        assertEquals(emptyList<Any?>(), m["absent"].asArray())
        assertNull(m["s"].asObject())
    }

    /** Ints written by one build are read as Longs by another; both must work. */
    @Test
    fun `numeric accessors accept either width`() {
        val m = Json.parse("""{"whole":5,"fraction":5.7}""").asObject()!!

        assertEquals(5L, m.long("whole"))
        assertEquals(5.0, m.double("whole"), 0.0)
        assertEquals(5, m.int("fraction"))
        assertEquals(5.7, m.double("fraction"), 1e-9)
    }
}
