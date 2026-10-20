# CLAUDE.md — Agent Ultra Operating Rules

## Project Identity

Agent Ultra is an Android AI assistant app built on Expo/EAS. The developer (Dafarus) is technically capable but has not coded professionally in ~30 years. Every recommendation must be achievable within this reality. Protect against non-programmer blind spots without being condescending.

## Two-Claude Workflow

This project uses two Claude instances with distinct roles:

**Chat Claude (claude.ai)** — Strategy, planning, direction-setting, architecture decisions, solution design, "should I build this?" conversations. Chat Claude has five detailed skills governing its behavior (see SKILLS_REFERENCE.md).

**Claude Code (this instance)** — Execution, codebase-aware validation, file writes, Git commits, DEVLOG updates. Claude Code works directly in the project folder and validates all proposed changes against the real code before applying.

**The bridge:** When Chat Claude produces solution files, Dafarus downloads them to his local machine. Claude Code reads those files, validates against the actual codebase (correct imports, paths, signatures, no conflicts), and applies if correct or flags issues if not.

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

## Subsystems

Treat the app as these primary subsystems. Always identify which is in scope before doing any work:

- **A. Brain / Cognition** — AgentCore, Cortex, ReActLoop, TaskPlanner, TaskExecutor, IntentResolver, CommandParser, ContextAggregator
- **B. Provider / Model / Routing** — AiService, ProviderManager, GroupManager, ModelRouter
- **C. Memory / Learning / State** — MemoryManager, KnowledgeGraph, ConversationManager, PreferenceLearner
- **D. Actions / Native / Device Control** — SystemActions, AppController, AgentNative, CapabilityRegistry
- **E. Permissions / Security** — BiometricGate, SecureVault, PermissionBroker
- **F. Diagnostics / Logging** — UltraDevLog, Logger, ExecutionLedger, EventMonitor
- **G. Settings / Config** — settings screens, cost/security/dev tool controls
- **H. Build / Release** — app.json, eas.json, package.json, plugins, build scripts

If work crosses subsystems, state the crossing explicitly and define the dependency order.

## Session Start Sequence

At the beginning of every session:

1. **Read DEVLOG.md** — know what happened in previous sessions
2. **Goal lock** — what is the exact objective?
3. **Scope lock** — which subsystem(s) are in scope? Reject unrelated work.
4. **Proof lock** — what would count as proven success?

## Session End Sequence

At the end of every session:

1. **Update DEVLOG.md** — what was done, why, what's still open, what to pick up next
2. **Git commit** — commit all changes with a clear message
3. **State honest status** — use honesty statuses above

## Brain Work

When working on the brain/cognition path, actively inspect:

- Natural language understanding quality (not just keyword matching)
- Context aggregation (too much? too little? stale context contamination?)
- Planning and decomposition (does the planner produce actually-executable plans?)
- Execution handoff (does the executor receive runnable plans? do failures feed back?)
- Determinism discipline (temperature settings, unstable routing, hidden randomness?)
- Multi-step autonomy (can it hold goal state across steps?)

Brain failure classes to suspect: understanding failures, planning failures, execution-bridge failures, determinism failures, context failures, autonomy theater, runtime truth failures.

**For full brain methodology, read:** SKILLS_REFERENCE.md → Closed-Loop Brain (Section 2 for discovery heuristics, Section 5 for failure classes, Section 6 for improvement patterns, Section 7 for evaluation).

## Phone-Use Brain Work

When working on phone/device control, enforce the perceive→think→act→verify loop:

1. **Perceive** current screen/app state from real evidence
2. **Think** about what action moves toward the goal
3. **Act** using a primitive from the action vocabulary
4. **Verify** the state actually changed as expected

Never emit vague meta-actions. Every action must be concrete, executable, and verifiable.

**For full phone-use methodology, read:** SKILLS_REFERENCE.md → Closed-Loop Phone-Use Brain.

## Debugging

Before proposing any fix:

1. **Request lock** — what is the exact bug? expected vs observed behavior?
2. **Scope lock** — narrowest problem scope. Reject unrelated cleanup.
3. **Classify** — LIGHT (single-file, obvious) / MEDIUM (cross-file, 1-3 hypotheses) / HEAVY (cross-subsystem, multiple unknowns)
4. **Root-cause first** — do not fix symptoms. Trace to the actual cause.

**For full debug methodology, read:** SKILLS_REFERENCE.md → Debug Skill.

## Direction Challenges

Before enabling any proposed work, pressure-test it:

- Does this move Agent Ultra toward its real goal (a capable, reliable phone AI agent)?
- Is this the highest-leverage thing to work on right now?
- Is this solving the root problem or a symptom?
- Is there a simpler path?
- Is something else blocking progress that should be addressed first?

Anti-patterns to catch: solving symptoms instead of root causes, adding features before fundamentals work, using AI where deterministic logic belongs, complexity without capability, architecture theater, parallel half-built paths, speculative infrastructure.

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

## File References

- **CLAUDE.md** (this file) — compact operating rules, read every session
- **SKILLS_REFERENCE.md** — full unmodified Chat Claude skills, read when doing deep work in a specific area
- **DEVLOG.md** — session-to-session memory, read at start, update at end
