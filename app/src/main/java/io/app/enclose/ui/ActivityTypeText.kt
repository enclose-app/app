package io.app.enclose.ui

import androidx.annotation.StringRes
import io.app.enclose.R
import io.app.enclose.tracking.ActivityType

/**
 * Picks the string for this activity type. Each sentence comes whole per type
 * rather than with the noun spliced in, because articles, gender and case follow
 * the noun in most languages — see strings_panel.xml. Unannotated on purpose:
 * it picks plurals as well as strings.
 *
 * [ActivityType] itself carries no text, so the anti-cheat that defines it stays
 * free of anything a translator touches.
 */
internal fun ActivityType.pick(walk: Int, run: Int, ride: Int): Int =
    when (this) {
        ActivityType.WALK -> walk
        ActivityType.RUN -> run
        ActivityType.BIKE -> ride
    }

/** Chip label: Walk, Run, Bike. */
internal val ActivityType.labelRes: Int
    @StringRes get() = pick(R.string.activity_walk, R.string.activity_run, R.string.activity_bike)

/** While recording: Walking, Running, Cycling. */
internal val ActivityType.activeLabelRes: Int
    @StringRes get() = pick(R.string.activity_walking, R.string.activity_running, R.string.activity_cycling)
