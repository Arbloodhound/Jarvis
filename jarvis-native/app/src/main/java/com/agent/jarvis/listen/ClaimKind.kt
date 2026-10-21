package com.agent.jarvis.listen

/**
 * Which librarian notes become durable verified claims, and under what claim kind.
 *
 * Only durable knowledge is promoted: a FACT (something true about the world) and a PERSON (who
 * someone is) go to the claim store through the verify + NLI gate; PLAN/TODO are time-bound tasks,
 * and OBSERVATION/SUMMARY keep their own evidence as notes — none of those are promoted. The claim
 * `kind` reuses Muse's bank categories (MemoryClaimScoring.KINDS): a PERSON fact is `world`
 * knowledge, a plain FACT is `fact`.
 */
object ClaimKind {
    fun of(kind: NoteKind): String? = when (kind) {
        NoteKind.FACT -> "fact"
        NoteKind.PERSON -> "world"
        else -> null
    }

    /** The note kinds that promote to a claim. */
    fun promotes(kind: NoteKind): Boolean = of(kind) != null
}
