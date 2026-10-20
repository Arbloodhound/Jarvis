package com.agent.jarvis.listen

/**
 * Whether a spoken command needs the last 15 minutes of overheard transcript in front of it.
 * "Turn on the flashlight" or "what's the weather" don't: the transcript only made the brain read
 * hundreds of tokens before answering. "What did he just say", "summarise that conversation",
 * "reply to her" do. Questions about older memory get the memory search anyway (MemoryBrief).
 * Pure, JVM-tested.
 */
object TranscriptNeed {
    private val REFERS = Regex(
        "\\b(said|says|saying|say|told|telling|talking|talked|talk|conversation|discuss\\w*|mention\\w*|" +
            "heard|hear|just now|earlier|before|that|this|those|these|him|her|them|they|he|she|his|their|" +
            "who|summar\\w*|recap|meeting|call|chat|argument|story)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun needed(command: String): Boolean =
        REFERS.containsMatchIn(command) || com.agent.jarvis.agent.MemoryBrief.wants(command).search
}
