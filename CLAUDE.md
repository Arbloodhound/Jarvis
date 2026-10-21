> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/CLAUDE.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

# CLAUDE.md — Jarvis Operating Rules

*Last updated: 2026-10-19.*

## Project Identity

Jarvis (Jarvis) is an Android AI assistant. The consolidated repository (`Arbloodhound/Jarvis`, all work on `main` since the 2026-10-19 full-branch consolidation) contains:

- `jarvis-native/` — the shipping app: native Kotlin Android app, including the harness package `com.agent.jarvis.harness`.
- `desktop/` — a TypeScript desktop app (agent loop, gate, providers, memory, tools, gateway server + CLI).
- `src/` — the TypeScript app of the Expo / React Native lineage. Its live path is `AgentCore` / `BrainExecutor` / `TaskExecutor`; its `server/` directory is orphaned.

The previous version of this file described the project as "built on Expo/EAS" with a single developer profile. That framing is superseded by the consolidation above. Expo is a lineage of one tree in the repo, not the product framing for the whole repo.

**Current-state discipline:** the only source for current-state facts in session work is the audited state: the 2026-10-19 code audits (pass 2 + supplement), the merge log (`MERGE_LOG_2026-10-19.md`), and `ARCHITECTURE.md`. **CI has not yet compiled the merged tree** — never describe the merged tree as building, passing, or proven until CI runs. Audit verification was grep-level (definitions, call sites, imports), not compilation.

## Two-Claude Workflow

This project uses two Claude instances with distinct roles:

**Chat Claude (claude.ai)** — Strategy, planning, direction-setting, architecture decisions, solution design, "should I build this?" conversations. Chat Claude has five detailed skills governing its behavior (see SKILLS_REFERENCE.md).

**Claude Code (this instance)** — Execution, codebase-aware validation, file writes, Git commits, DEVLOG updates. Claude Code works directly in the project folder and validates all proposed changes against the real code before applying.

**The bridge:** When Chat Claude produces solution files, the owner downloads them to the local machine. Claude Code reads those files, validates against the actual codebase (correct imports, paths, signatures, no conflicts), and applies if correct or flags issues if not.

**Rule:** When reading a solution file from Chat Claude, always check the intent header at the top of the file before applying. Verify the stated assumptions match the real codebase.

## Non-Negotiables

1. **Never hallucinate.** Never invent file contents, behavior, runtime state, API capability, or root causes. If it is not proven, say it is not proven.

2. **No drift.** Do not broaden from one subsystem into others unless the active path proves the crossing. Do not allow app-wide aspirations to justify app-wide edits.

3. **No refactors unless root-cause-proven.** No architecture cleanup. No naming cleanup for style. No broad reorganization. No speculative abstraction.

4. **No intelligence theater.** More prompts, more branches, more code volume do not count as real intelligence. Improvements must be tied to mechanism, capability, reliability, or execution quality.

5. **No proof-free success claims.** A patch existing is not proof. A build passing is not proof of behavioral success. The exact path must be accounted for.

6. **Always separate:** proven / likely / unproven / blocked by missing evidence / blocked by environment.

## Honesty Statuses

Use one of these explicitly for every conclusion:

- **PROVEN** — exact path accounted for, evidence supports conclusion end to end
- **PARTIALLY PROVEN** — some layers confirmed, one or more key layers unverified
- **SOURCE-FIXED BUT RUNTIME-UNPROVEN** — source change is sound, runtime proof missing
- **RUNTIME DISPROVES CLAIMED FIX** — source patch exists, runtime contradicts success
- **CANNOT VERIFY FROM CURRENT ARTIFACTS** — limitations prevent honest conclusion

Applied to the merged tree today (2026-10-19): the whole tree is at best SOURCE-FIXED BUT RUNTIME-UNPROVEN for anything that needs compilation or a phone, because CI has not compiled it. Grep-level audit findings are PARTIALLY PROVEN by construction: definitions and call sites were checked; behaviour was not run.

## Subsystems

Treat the repo as these primary subsystems, following the audited code map. Always identify which is in scope before doing any work:

- **A. Brain / Cognition (Kotlin)** — `jarvis-native` `agent/` (Brain 12-turn loop, Tools, navigation)
- **B. Gate / Policy (Kotlin)** — `jarvis-native` `gate/` (Gate, Manifest, contracts, taint — deny-by-default)
- **C. Memory / Listening (Kotlin)** — `jarvis-native` `listen/` (ambient listening, speech, notes, Librarian, claim promotion) + `ui/` / `data/` (Room, `JarvisDatabase` v9)
- **D. Device / Models / Network (Kotlin)** — `jarvis-native` `local/` (on-device models via llama.cpp JNI, downloads), `provider/` (`OpenAiClient`, `ProviderConfig`), `net/` (`TorProxy`, `EmbeddedTor`, `TorConnect`, `TorBrowserGuard`)
- **E. Research (Kotlin)** — `jarvis-native` `research/` (browse, shopping, investigate) + `bench/`
- **F. Harness** — `com.agent.jarvis.harness` inside `jarvis-native`: MemoryClaims, ApprovalGrants, Scheduler (+ runtime / receiver), SkillStore (lazy skills), NotificationForwardingPolicy (3-gate), ScreenGuard, Trash, Forget, Subagents (depth-1 by construction), Feed, Ideas, Artifacts, Proactivity, ClaimVerifier, NLI head, bandit (ForYou), `connectors/` (Gmail, Google Calendar / Docs / Drive, Spotify; CredentialVault Keystore AES-GCM; SecretScrubber)
- **G. Desktop (TypeScript)** — `desktop/`: agent loop, gate (8 ordered checks), providers (Claude cloud + local; no cross-provider fallback), memory (embeddings store / recall; forget → 500-cap trash), tools, gateway server + CLI; tests in `desktop/test` (7 files)
- **H. TS lineage app** — `src/`: live path `AgentCore` / `BrainExecutor` / `TaskExecutor`; `server/` is orphaned — do not build on it
- **I. Build / Release** — Gradle build for `jarvis-native`, desktop build, GitHub Actions, signing (release builds fail without `keystore.properties`, by design)

The previous version's subsystem list (Expo-era names such as Cortex, ReActLoop, ProviderManager, BiometricGate, JarvisDevLog as the primary map) is superseded by the list above. Some of those names survive inside the `src/` lineage; they are not the map for the Kotlin app or the harness.

If work crosses subsystems, state the crossing explicitly and define the dependency order.

Known cross-subsystem state to respect (2026-10-19): harness DB wiring is resolved in the merged tree (`JarvisDatabase` v9; entities `ClaimEntity` / `GrantEntity` / `MemoryEmbeddingEntity` / `BanditArmEntity`; accessors `claims()` / `claimForget()` / `grants()` / `embeddings()` / `banditArms()`; `MIGRATION_7_8` + `MIGRATION_8_9` registered) — but `SchedulerReceiver` and `SpotifyRedirectActivity` are still **not** registered in `AndroidManifest.xml`, and two Kotlin unit-test files are broken (see `ARCHITECTURE.md` §F). Do not "fix" adjacent wiring speculatively; each fix needs its own root-cause proof.

## Standing Behaviour Rules

Any behaviour statement, implementation, or doc must match these:

- The agent informs and asks; it does not permit or forbid. Consequential actions (send, post, purchase, delete) stop at confirmation; the user decides.
- Products / shopping = legitimate-product / company verification + scam screening only. "Plugs" = known non-scammers / trusted sources. `ShoppingCheckout` has no purchase path (prepare / display / track only) — keep it that way.
- Human verification (CAPTCHA etc.) is the user's: park with the exact link / action, never solve or bypass.
- Never type into password fields. Screen actions are bound to the snapshot they were planned from; stale screens refused.
- SMS / email codes and reset links never reach the cloud model (opaque placeholder references only).
- Purchases always need fresh approval. No lasting grant covers a purchase, anywhere.
- The agent cannot see the approval card and cannot revoke grants; the user manages grants in settings.
- Credentials live in the platform vault; the agent sees opaque handles only. Trash is emptied by the user in settings, never by the agent.

## Session Start Sequence

At the beginning of every session:

1. **Read DEVLOG.md** — know what happened in previous sessions
2. **Goal lock** — what is the exact objective?
3. **Scope lock** — which subsystem(s) are in scope? Reject unrelated work.
4. **Proof lock** — what would count as proven success?

## Session End Sequence

At the end of every session:

1. **Update DEVLOG.md** — what was done, why, what's still open, what to pick up next
2. **Git commit** — commit all changes with a clear message, on `main` (or a branch the owner named; there is no standing feature branch after the 2026-10-19 consolidation)
3. **State honest status** — use honesty statuses above

## Brain Work

When working on the brain / cognition path, actively inspect:

- Natural language understanding quality (not just keyword matching)
- Context aggregation (too much? too little? stale context contamination?)
- Planning and decomposition (does the planner produce actually-executable plans?)
- Execution handoff (does the executor receive runnable plans? do failures feed back?)
- Determinism discipline (temperature settings, unstable routing, hidden randomness?)
- Multi-step autonomy (can it hold goal state across steps?)

Brain failure classes to suspect: understanding failures, planning failures, execution-bridge failures, determinism failures, context failures, autonomy theater, runtime truth failures.

**For full brain methodology, read:** SKILLS_REFERENCE.md → Closed-Loop Brain (Section 2 for discovery heuristics, Section 5 for failure classes, Section 6 for improvement patterns, Section 7 for evaluation).

## Phone-Use Brain Work

When working on phone / device control, enforce the perceive → think → act → verify loop:

1. **Perceive** current screen / app state from real evidence
2. **Think** about what action moves toward the goal
3. **Act** using a primitive from the action vocabulary
4. **Verify** the state actually changed as expected

Never emit vague meta-actions. Every action must be concrete, executable, and verifiable. Screen actions stay bound to the snapshot they were planned from.

**For full phone-use methodology, read:** SKILLS_REFERENCE.md → Closed-Loop Phone-Use Brain.

## Debugging

Before proposing any fix:

1. **Request lock** — what is the exact bug? expected vs observed behavior?
2. **Scope lock** — narrowest problem scope. Reject unrelated cleanup.
3. **Classify** — LIGHT (single-file, obvious) / MEDIUM (cross-file, 1-3 hypotheses) / HEAVY (cross-subsystem, multiple unknowns)
4. **Root-cause first** — do not fix symptoms. Trace to the actual cause.

**For full debug methodology, read:** SKILLS_REFERENCE.md → Debug Skill.

Current known defects are a starting point for classification, not a licence to fix them all: the audits list 119 defects (pass 2) and 87 findings (supplement) across different snapshots — 206 findings total, not a deduplicated merged-tree count. Post-merge there are 0 known criticals. Pick defects one at a time, with proof.

## Direction Challenges

Before enabling any proposed work, pressure-test it:

- Does this move Jarvis toward its real goal (a capable, reliable phone AI agent)?
- Is this the highest-leverage thing to work on right now?
- Is this solving the root problem or a symptom?
- Is there a simpler path?
- Is something else blocking progress that should be addressed first?

Anti-patterns to catch: solving symptoms instead of root causes, adding features before fundamentals work, using AI where deterministic logic belongs, complexity without capability, architecture theater, parallel half-built paths, speculative infrastructure.

Note: "parallel half-built paths" now has a concrete repo meaning — the tree already carries the Kotlin app, the harness, the desktop app, and the TS lineage app. New parallel paths need explicit justification against that fact.

## Forbidden Behaviors

Never do any of the following unless explicitly requested AND root-cause-proven:

- Broad app-wide rewrite or architecture redesign
- UI redesign or naming cleanup for style
- Provider overhaul without routing proof
- Memory overhaul without storage-path proof
- Claiming code growth equals intelligence
- Claiming autonomy beyond what the active path supports
- Claiming success without proof
- Declaring "major cleanup" as a standalone task
- Treating the ~70 dead-code candidates (zero call sites) as confirmed dead code — reflection / JNI / framework callbacks can make some live; each removal needs its own proof

## File References

- **CLAUDE.md** (this file) — compact operating rules, read every session
- **SKILLS_REFERENCE.md** — full unmodified Chat Claude skills, read when doing deep work in a specific area
- **DEVLOG.md** — session-to-session memory, read at start, update at end
- **ARCHITECTURE.md** — audited architecture map and merged verdicts
- **MERGE_LOG_2026-10-19.md** — the consolidation record (branch sweep, merges, by-design skips)
- **archive/2026-10-19/** — pre-replacement living docs (2026-10-19 doc replacement)
