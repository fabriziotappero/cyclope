package com.fab.cyclope

import java.util.PriorityQueue
import kotlin.math.abs

data class InclinationSection(
    val startIndex: Int,
    val endIndex: Int,
    val percent: Double,
    val color: Int
)

// A candidate section [start, end] and the position where splitting it would hurt the straight-line fit the most.
private class Candidate(val start: Int, val end: Int, val splitAt: Int, val deviation: Double)

/*
 * Splits the altitude profile into at most maxSections sections and colors each one by its average slope.
 *
 * Method:
 * 1. Start with one section spanning the whole track.
 * 2. For a section, draw a straight line (chord) from its start to its end. The point inside the section whose
 *    altitude is vertically farthest from that chord is the split candidate; that distance is the section's deviation.
 * 3. Keep all sections in a priority queue ordered by deviation. Repeatedly take the section with the largest
 *    deviation and split it at its candidate point into two sections, which are queued with their own deviation.
 * 4. Stop at maxSections, or earlier if every remaining section is a perfectly straight line.
 * 5. Each section's slope is (end altitude - start altitude) / horizontal distance; colorForPercent maps it to a color.
 *
 * Why it works:
 * - A peak or valley is the point farthest from the chord of the section containing it, so those splits happen first.
 * - A long climb without any peak inside is still split where its gradient changes (for example flat then steep),
 *   because the profile bends away from the chord there. This keeps a short steep ramp from being averaged into a
 *   long gentle section.
 * - A section with a larger deviation is always split before one with a smaller deviation, so the available sections
 *   go where the profile is least straight, and long steady stretches stay as single sections.
 *
 * Limits:
 * - A short steep ramp inside an otherwise nearly straight stretch may have too small a deviation to win one of the
 *   maxSections splits and stays averaged into its neighbour. Raising maxSections reduces this.
 * - The slope of a section is its average; it can contain steeper parts than its color suggests. The marker
 *   label on the altitude plot shows this same average.
 */
fun buildInclinationSections(
    altitudes: List<Double?>,
    cumulativeDistancesKm: List<Double>,
    maxSections: Int,
    colorForPercent: (Double) -> Int
): List<InclinationSection> {
    if (cumulativeDistancesKm.size != altitudes.size) return emptyList()
    val validIndices = altitudes.indices.filter { altitudes[it] != null }
    if (validIndices.size < 2) return emptyList()
    val alt = validIndices.map { altitudes[it]!! }
    val dist = validIndices.map { cumulativeDistancesKm[it] }

    fun slopePercent(startPos: Int, endPos: Int): Double {
        val runKm = dist[endPos] - dist[startPos]
        if (runKm <= 0.0) return 0.0
        return (alt[endPos] - alt[startPos]) / (runKm * 1000.0) * 100.0
    }

    fun candidate(start: Int, end: Int): Candidate {
        val runKm = dist[end] - dist[start]
        var splitAt = -1
        var maxDeviation = 0.0
        if (runKm > 0.0) {
            for (p in start + 1 until end) {
                val chordAlt = alt[start] + (alt[end] - alt[start]) * (dist[p] - dist[start]) / runKm
                val deviation = abs(alt[p] - chordAlt)
                if (deviation > maxDeviation) {
                    maxDeviation = deviation
                    splitAt = p
                }
            }
        }
        return Candidate(start, end, splitAt, maxDeviation)
    }

    val queue = PriorityQueue<Candidate>(compareByDescending { it.deviation })
    queue.add(candidate(0, alt.lastIndex))
    val limit = maxSections.coerceAtLeast(1)
    val finished = mutableListOf<Candidate>()
    var count = 1
    while (queue.isNotEmpty() && count < limit) {
        val next = queue.poll()!!
        if (next.splitAt < 0) {
            finished.add(next)
            continue
        }
        queue.add(candidate(next.start, next.splitAt))
        queue.add(candidate(next.splitAt, next.end))
        count++
    }
    finished.addAll(queue)
    finished.sortBy { it.start }

    return finished.map { section ->
        val percent = slopePercent(section.start, section.end)
        InclinationSection(
            startIndex = validIndices[section.start],
            endIndex = validIndices[section.end],
            percent = percent,
            color = colorForPercent(percent)
        )
    }
}
