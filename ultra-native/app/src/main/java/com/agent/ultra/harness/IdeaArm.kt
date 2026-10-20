package com.agent.ultra.harness

/**
 * How a For-You idea maps to a bandit arm (policy "ideas"): the domain is the idea's primary topic
 * (first word of its title, matching the topic Proactivity derives), the lane is a feasibility band.
 * Pure, JVM-tested, so the arm an idea rewards/penalises is stable and checkable.
 */
object IdeaArm {
    fun domain(title: String): String =
        title.trim().lowercase().split(Regex("\\s+")).firstOrNull { it.isNotBlank() } ?: "general"

    fun lane(feasibility: Double): String = when {
        feasibility >= 0.66 -> "high"
        feasibility >= 0.33 -> "med"
        else -> "low"
    }
}
