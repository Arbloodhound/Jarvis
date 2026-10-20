package com.agent.ultra.agent

/**
 * What the agent actually knows about a routine it was taught.
 *
 * An agent that always sounds equally confident is not trustworthy, because
 * nothing it says distinguishes the task it has done forty times from the one
 * it has only watched once. This is the difference, stated from counted facts.
 *
 * ## Everything here is a count, never a judgement
 *
 * No score, no percentage, no "high confidence". Each field is something that
 * was recorded when it happened: how many hops of the route have a door the
 * agent found for itself, how many times it walked the whole thing, how many
 * times it tried and did not finish, and whether a door it had learned has
 * since moved.
 *
 * That last one is the useful one and it is the reason this is not simply a run
 * counter. A remembered door that is no longer on the screen is evidence the
 * app changed under us — the only honest moment to say "watch me do this again"
 * rather than searching and hoping.
 */
data class Competence(
    /** Hops whose door the agent has found and can go straight to. */
    val hopsKnown: Int,
    /** Hops in the route: one fewer than the number of screens. */
    val hopsTotal: Int,
    /** Walks that reached the last screen. */
    val walksCompleted: Int,
    /** Walks attempted, finished or not. */
    val walksAttempted: Int,
    /** A door that had been learned was somewhere else. 0 means never. */
    val doorsMoved: Int,
) {

    /** Has the agent ever done this itself, rather than only watched it? */
    val everWalked: Boolean get() = walksAttempted > 0

    /** Can it go straight there, with no searching at all? */
    val knowsTheWholeWay: Boolean get() = hopsTotal > 0 && hopsKnown == hopsTotal

    /**
     * How this reads to a person, in one line.
     *
     * Written so the weak cases sound weak. "You showed me this but I have
     * never done it myself" is the sentence that makes the strong version worth
     * anything, and it is the one an agent inclined to sound capable would
     * quietly drop.
     */
    fun describe(): String {
        val parts = mutableListOf<String>()

        parts += when {
            // Knowing a door is itself evidence of having walked that hop —
            // there is no other way to find one. Reporting "never walked this"
            // while holding the doors is conservative and also incoherent, and
            // an agent whose account of itself contradicts its own data is the
            // exact thing this class exists to prevent. Older routines have
            // doors recorded from before walks were counted, so this is a real
            // case rather than a hypothetical one.
            hopsKnown == 0 && !everWalked ->
                "you showed me this, but I have never walked it myself"
            hopsKnown == 0 -> "I have tried this and not worked out any of the " +
                "${steps(hopsTotal)} yet"
            knowsTheWholeWay && walksCompleted > 0 ->
                "I have walked this ${times(walksCompleted)} and know all " +
                    "${steps(hopsTotal)}"
            knowsTheWholeWay -> "I know all ${steps(hopsTotal)}"
            else -> "I know $hopsKnown of the ${steps(hopsTotal)}, and will search for the rest"
        }

        val unfinished = walksAttempted - walksCompleted
        if (unfinished > 0 && everWalked) {
            parts += if (walksCompleted == 0) "it has not finished yet"
            else "${times(unfinished)} it did not finish"
        }

        if (doorsMoved > 0) {
            parts += "something I had learned has moved since, so the app has changed"
        }

        return parts.joinToString("; ")
    }

    private fun times(n: Int) = if (n == 1) "once" else "$n times"

    private fun steps(n: Int) = if (n == 1) "1 step" else "$n steps"

    companion object {
        /** Read from a stored route and its recorded history. */
        fun of(
            route: List<ScreenJourney.Waypoint>,
            walksCompleted: Int,
            walksAttempted: Int,
            doorsMoved: Int,
        ): Competence {
            // Hop 0 is where the route starts; there is no door into it.
            val hops = (route.size - 1).coerceAtLeast(0)
            val known = route.drop(1).count { it.via.isNotBlank() }
            return Competence(known, hops, walksCompleted, walksAttempted, doorsMoved)
        }
    }
}
