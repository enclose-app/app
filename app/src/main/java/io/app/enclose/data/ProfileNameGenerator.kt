package io.app.enclose.data

import kotlin.random.Random

/**
 * Generates a random guest identity (first + last name) so a fresh, not-yet-
 * signed-in user still has a friendly name. ~20 first names × ~20 last names.
 */
object ProfileNameGenerator {

    private val firstNames = listOf(
        "Alex", "Riley", "Jordan", "Casey", "Morgan", "Taylor", "Jamie", "Quinn",
        "Avery", "Rowan", "Sage", "Skyler", "Charlie", "Finley", "Harper", "Reese",
        "Emerson", "Dakota", "Parker", "Hayden",
    )

    private val lastNames = listOf(
        "Walker", "Strider", "Rambler", "Wayfarer", "Trekker", "Rover", "Pathfinder",
        "Voyager", "Wanderer", "Roamer", "Ranger", "Nomad", "Drifter", "Traveler",
        "Pacer", "Marcher", "Hiker", "Explorer", "Scout", "Pilgrim",
    )

    /** A random (first, last) pair. */
    fun random(rng: Random = Random.Default): Pair<String, String> =
        firstNames.random(rng) to lastNames.random(rng)
}
