package io.app.enclose.export

/**
 * A minimal JSON reader/writer, hand-rolled for the reason [GpxImporter] records
 * about XML: `org.json` is stubbed out in the mockable `android.jar`, so anything
 * built on it **cannot be unit tested in this project**. The backup format is the
 * one file in the app that has to survive a reinstall and be read back by a
 * version nobody has written yet, so it is the last place to accept a codec no
 * test can reach. [GeoExporter] uses `org.json` and correspondingly has no tests;
 * that is the trade being avoided here.
 *
 * Values are plain Kotlin, so callers need no wrapper types:
 *
 * | JSON | Kotlin |
 * |---|---|
 * | object | `Map<String, Any?>` (insertion-ordered) |
 * | array | `List<Any?>` |
 * | string | `String` |
 * | number | `Long` when it has no fraction or exponent, otherwise `Double` |
 * | true/false | `Boolean` |
 * | null | `null` |
 *
 * Reading is strict — a file that is not JSON is a file the user picked by
 * mistake, and guessing at it would restore something nobody exported.
 */
object Json {

    /** Thrown by [parse] on anything that isn't well-formed JSON. */
    class JsonException(message: String) : Exception(message)

    /**
     * Nesting depth allowed while parsing.
     *
     * The parser is recursive, so depth is stack, and the file comes from
     * outside the app. A backup nests four deep; 64 is past anything this format
     * can legitimately produce and well short of what would overflow.
     */
    private const val MAX_DEPTH = 64

    // --- writing -------------------------------------------------------------

    /**
     * [value] as JSON text, compact.
     *
     * Non-finite doubles are written as `null` rather than as the `NaN` and
     * `Infinity` literals JSON has no room for: a file that cannot be parsed at
     * all is a worse answer to one bad number than a field that reads as
     * missing, and missing is exactly what the decoder has a default for.
     */
    fun write(value: Any?): String = StringBuilder().also { writeTo(it, value) }.toString()

    private fun writeTo(out: StringBuilder, value: Any?) {
        when (value) {
            null -> out.append("null")
            is Boolean -> out.append(if (value) "true" else "false")
            is String -> writeString(out, value)
            is Double -> if (value.isFinite()) out.append(value) else out.append("null")
            is Float -> writeTo(out, value.toDouble())
            is Number -> out.append(value.toString())
            is Map<*, *> -> {
                out.append('{')
                var first = true
                for ((key, entry) in value) {
                    if (!first) out.append(',')
                    first = false
                    writeString(out, key.toString())
                    out.append(':')
                    writeTo(out, entry)
                }
                out.append('}')
            }
            is List<*> -> {
                out.append('[')
                value.forEachIndexed { index, item ->
                    if (index > 0) out.append(',')
                    writeTo(out, item)
                }
                out.append(']')
            }
            else -> throw JsonException("Cannot write ${value::class.java.name} as JSON")
        }
    }

    private fun writeString(out: StringBuilder, value: String) {
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else ->
                    // Control characters only. Everything above them is left as
                    // itself and carried by UTF-8 — territory names are written
                    // by people, and \u-escaping every accented letter would
                    // triple the size of a file for no gain.
                    if (c < ' ') out.append("\\u").append("%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
    }

    // --- reading -------------------------------------------------------------

    /** Parse [text], or throw [JsonException] describing where it went wrong. */
    fun parse(text: String): Any? {
        val reader = Reader(text)
        reader.skipWhitespace()
        val value = reader.readValue(depth = 0)
        reader.skipWhitespace()
        if (!reader.atEnd()) reader.fail("unexpected trailing content")
        return value
    }

    private class Reader(private val text: String) {
        private var at = 0

        fun atEnd(): Boolean = at >= text.length

        fun fail(message: String): Nothing = throw JsonException("$message at offset $at")

        fun skipWhitespace() {
            while (at < text.length && text[at].isJsonWhitespace()) at++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) fail("nested too deeply")
            if (atEnd()) fail("unexpected end of input")
            return when (val c = text[at]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c in '0'..'9') readNumber() else fail("unexpected '$c'")
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            at++ // '{'
            // LinkedHashMap: the file reads in the order it was written, which is
            // the difference between a backup somebody can eyeball and a blob.
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (!atEnd() && text[at] == '}') {
                at++
                return out
            }
            while (true) {
                skipWhitespace()
                if (atEnd() || text[at] != '"') fail("expected a key")
                val key = readString()
                skipWhitespace()
                if (atEnd() || text[at] != ':') fail("expected ':'")
                at++
                skipWhitespace()
                out[key] = readValue(depth + 1)
                skipWhitespace()
                if (atEnd()) fail("unterminated object")
                when (text[at]) {
                    ',' -> at++
                    '}' -> {
                        at++
                        return out
                    }
                    else -> fail("expected ',' or '}'")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            at++ // '['
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (!atEnd() && text[at] == ']') {
                at++
                return out
            }
            while (true) {
                skipWhitespace()
                out.add(readValue(depth + 1))
                skipWhitespace()
                if (atEnd()) fail("unterminated array")
                when (text[at]) {
                    ',' -> at++
                    ']' -> {
                        at++
                        return out
                    }
                    else -> fail("expected ',' or ']'")
                }
            }
        }

        private fun readString(): String {
            at++ // opening quote
            val out = StringBuilder()
            while (true) {
                if (atEnd()) fail("unterminated string")
                when (val c = text[at++]) {
                    '"' -> return out.toString()
                    '\\' -> {
                        if (atEnd()) fail("unterminated escape")
                        when (val esc = text[at++]) {
                            '"' -> out.append('"')
                            '\\' -> out.append('\\')
                            '/' -> out.append('/')
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000C')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (at + 4 > text.length) fail("truncated \\u escape")
                                val hex = text.substring(at, at + 4)
                                val code = hex.toIntOrNull(16) ?: fail("bad \\u escape")
                                // Surrogates are appended as they come: a pair
                                // written as two escapes rebuilds itself as a
                                // Kotlin string, which is UTF-16 already.
                                out.append(code.toChar())
                                at += 4
                            }
                            else -> fail("unknown escape '\\$esc'")
                        }
                    }
                    else -> {
                        if (c < ' ') fail("unescaped control character")
                        out.append(c)
                    }
                }
            }
        }

        private fun readNumber(): Any {
            val start = at
            if (!atEnd() && text[at] == '-') at++
            var fractional = false
            while (!atEnd()) {
                val c = text[at]
                when {
                    c in '0'..'9' -> at++
                    c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-' -> {
                        fractional = true
                        at++
                    }
                    else -> break
                }
            }
            val raw = text.substring(start, at)
            if (raw.isEmpty() || raw == "-") fail("expected a number")
            // Longs where the text is integral, so an id or a timestamp survives
            // a round trip exactly rather than passing through a double's 53
            // bits — epoch millis are past that already in 2286, and a rowid can
            // be anything at all.
            if (!fractional) {
                raw.toLongOrNull()?.let { return it }
            }
            return raw.toDoubleOrNull() ?: fail("malformed number '$raw'")
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, at)) fail("expected '$literal'")
            at += literal.length
            return value
        }

        private fun Char.isJsonWhitespace(): Boolean =
            this == ' ' || this == '\n' || this == '\r' || this == '\t'
    }
}

// --- typed accessors ---------------------------------------------------------
//
// Every read from a parsed backup goes through these. They take a default and
// never throw, because a field that is missing, null, or the wrong type all mean
// the same thing to a restore — "this backup doesn't say" — and that is what a
// column default is for. It is also what lets a file written by an older version
// restore into a newer schema without a special case per release.

internal fun Any?.asObject(): Map<String, Any?>? {
    @Suppress("UNCHECKED_CAST")
    return this as? Map<String, Any?>
}

internal fun Any?.asArray(): List<Any?> = this as? List<Any?> ?: emptyList()

internal fun Map<String, Any?>.objects(key: String): List<Map<String, Any?>> =
    this[key].asArray().mapNotNull { it.asObject() }

internal fun Map<String, Any?>.str(key: String, fallback: String = ""): String =
    this[key] as? String ?: fallback

internal fun Map<String, Any?>.strOrNull(key: String): String? = this[key] as? String

internal fun Map<String, Any?>.bool(key: String, fallback: Boolean = false): Boolean =
    this[key] as? Boolean ?: fallback

internal fun Map<String, Any?>.long(key: String, fallback: Long = 0L): Long =
    when (val v = this[key]) {
        is Long -> v
        is Double -> v.toLong()
        else -> fallback
    }

internal fun Map<String, Any?>.longOrNull(key: String): Long? =
    when (val v = this[key]) {
        is Long -> v
        is Double -> v.toLong()
        else -> null
    }

internal fun Map<String, Any?>.int(key: String, fallback: Int = 0): Int =
    long(key, fallback.toLong()).toInt()

internal fun Map<String, Any?>.double(key: String, fallback: Double = 0.0): Double =
    when (val v = this[key]) {
        is Double -> v
        is Long -> v.toDouble()
        else -> fallback
    }

internal fun Map<String, Any?>.doubleOrNull(key: String): Double? =
    when (val v = this[key]) {
        is Double -> v
        is Long -> v.toDouble()
        else -> null
    }
