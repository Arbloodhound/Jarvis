# STACK — WORK (investigate + skills + scheduler) — desktop — 2026-10-20

**One stack branch** combining the three parity branches onto main, fully gated.
The coordinator/finisher merges this branch as one unit; the three source
branches were NOT merged individually by this builder, and main was NOT touched.

## Branch

- **Branch:** `claude/desktop-stack-work-2026-10-20`
- **Tip:** `8e6ad128ae5adc565e0eeeaefdb6f99783eff8c3` (ref-verified via API after push;
  desktop/ tree verified blob-for-blob against the locally gated tree, 66 files)
- **Commits (parent chain verified):**
  - `6952555d5d…` — stack: merge investigate — parent = main tip (below)
  - `b222111b88…` — stack: merge skills
  - `8e6ad128ae…` — stack: merge scheduler
- **Base tip:** main `fa5e16e951fc647de8c472e752c24db30c45a944` (177/177 at build start;
  read from PARITY_MERGE_CHAIN_2026-10-20.md and live-ref-verified)
- **Note:** main advanced to `9d71ceeb00f269641d6447f7f00fcdd3387aba02` (browse, 187)
  while this stack was being built. The stack remains parented on fa5e16e9 as
  specified; browse touches none of this stack's conflict surface except
  runtime/tools neighbours already composed here — a forward merge is expected
  to be light, but that merge is the finisher's step, not done here.
- **Inputs:** `claude/desktop-parity-investigate-2026-10-20` @ 59fe5456d7,
  `claude/desktop-parity-skills-2026-10-20` @ 7f0160c366,
  `claude/desktop-parity-scheduler-2026-10-20` @ e9021e5ff2 — all three-way
  merged against their fork base `fc22170776e7b50ab4002b1568ed14bc93c5d0ec`.
- **prep-a reuse:** prep-a was unfinished (scratch merges left unresolved, no
  SUMMARY/NOTES). Reused: its conflict maps — skills {loop, config, runtime,
  tools/index} and scheduler {cli, runtime} matched exactly once merges ran
  against the correct base. Its "ours" snapshot predated the voice merge, so
  its staged trees were not used.

## Gate (on the combined tree, local stack repo)

- `npm install` ✓ · `npx tsc --noEmit` — **clean**
- `npm test` — **228/228 pass, 0 fail** (= 177 main + 14 investigate + 18 skills
  + 19 scheduler — exactly the expected arithmetic, no test dropped anywhere)

## Conflicts + resolutions (compose, never drop)

### 1. investigate onto main — conflicts: loop.ts (1), tools/index.ts (2)
- `agent/loop.ts` ctx construction: main's `transcriber` (voice) + branch's
  `home` — composed line carries both.
- `tools/index.ts` imports: voice import + `INVESTIGATE_TOOLS` import kept.
- `tools/index.ts` ALL_TOOLS: `...shoppingTools()` + `...INVESTIGATE_TOOLS` kept.
- Auto-merged: `src/investigate.ts`, `src/tools/investigate.ts`,
  `test/investigate.test.ts` (new), ToolContext `home` field.

### 2. skills onto the stack — conflicts: loop.ts (3), config.ts (3), runtime.ts (2), tools/index.ts (2)
- `agent/loop.ts` AgentDeps: `transcriber?` + `skills?` both kept.
  `execute(...)` call: skills' extra `skills` argument AND main/NLI's
  `evidence.push` line both kept. ctx: `home` + `transcriber` + `skills` all present.
- `config.ts`: tor + nli + skills blocks composed in the Config type, the
  defaults, and the loadConfig merge (incl. skills' allow/roots sanitizers).
- `runtime.ts`: Agent deps keep `transcriber` + `skills`; `toolsFor` keeps
  main's tor/ttsEnabled options; `state()` keeps tor/nli fields + skills count.
- `tools/index.ts`: ToolContext gains `skills` beside `transcriber`;
  ALL_TOOLS keeps shopping + INVESTIGATE + the three skill tools.
- Auto-merged: `src/skills.ts`, `test/skills.test.ts` (new), prompt.ts wiring,
  SkillStore import.

### 3. scheduler onto the stack — conflicts: cli.ts (2), runtime.ts (4)
- `cli.ts`: imports keep `probeTorProxy` + `speak` + scheduler's
  JobStore/seedDefaultHarnessJobs; usage text keeps say/transcribe + jobs lines.
- `runtime.ts` — the structural one. The branch restructures construction:
  `this.gate`/`this.audit` fields, an `newAgent()` factory (also used per
  scheduled run), jobs/scheduler init + default-job seeding in the constructor.
  Composition kept that skeleton and re-expressed main's deps in `this.*` form
  inside `newAgent()`: transcriber + skills deps, `toolsFor` with tor/ttsEnabled,
  and the nli block (`this.cfg.*`). `gate: this.gate` is the same Gate the
  constructor builds with main's exact arguments, so gate semantics are unchanged.
- `runtime.ts` — one auto-merge misplacement, hand-fixed: git had placed main's
  conversation-resume block at the tail of `newAgent()`, after its `return`
  (unreachable; also wrong — per-job agents must not load the owner's
  conversation). Moved into the constructor immediately after
  `this.agent = this.newAgent();`, restoring main's exact startup semantics.
- `runtime.ts` fields: conversations + jobs + scheduler + audit + gate all kept.
  `state()`: tor/nli/skills fields + scheduler status + jobs list all kept.
- Auto-merged: `src/scheduler.ts`, `test/scheduler.test.ts` (new), config.ts
  `Paths.jobs` + jobs.json path, `app/main.ts` scheduler start/stop,
  cli jobsCommand + dispatch, `runScheduledJob`.

## Process note (honest record)

The first scratch repo was built with a branch-pointer bug (`git checkout
<name>` advanced the base branch, stacking the branch commits on each other),
which corrupted the merge bases for skills/scheduler and produced phantom
conflicts. It was caught before any gate ran (merge-base audit), the repo was
rebuilt with every branch commit a verified direct child of the base commit,
and all three merges were redone from scratch. The gate and the push above are
entirely from the rebuilt repo. Working files: `stack-work/` (repo + trees),
`stack-work-fetch.py`, `stack-work-push.py` in ~/workspace/jarvis-rebuild/.
