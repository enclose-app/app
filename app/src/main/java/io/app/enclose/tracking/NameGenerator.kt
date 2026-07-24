package io.app.enclose.tracking

import kotlin.random.Random

/**
 * Produces a suggested territory name: two evocative words plus a 6-digit
 * number, e.g. "Golden Harbor 204815". Themed around walking / exploration.
 */
object NameGenerator {

    private val firstWords = listOf(
        "Golden", "Silent", "Wandering", "Hidden", "Misty", "Wild", "Amber",
        "Northern", "Crimson", "Quiet", "Emerald", "Restless", "Ancient",
        "Rambling", "Bright", "Lonely", "Winding", "Frosted", "Verdant", "Bold",
    )

    private val secondWords = listOf(
        "Harbor", "Meadow", "Ridge", "Hollow", "Commons", "Quarter", "Trail",
        "Grove", "Crossing", "Terrace", "Bluff", "Wharf", "Passage", "Green",
        "Fields", "Landing", "Bend", "Heath", "Loop", "Reach",
    )

    fun random(): String {
        val a = firstWords.random()
        val b = secondWords.random()
        val n = Random.nextInt(0, 1_000_000)
        return "%s %s %06d".format(a, b, n)
    }
}
