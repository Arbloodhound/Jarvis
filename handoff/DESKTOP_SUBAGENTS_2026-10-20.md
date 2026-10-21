# Desktop subagents — audit, build, and proof (2026-10-20)

Branch: `claude/desktop-subagents-2026-10-20` @ **`9585ce93e9a3d79a2cf63f1d353147045ceec280`**
Parent: main @ `c60f6e06602a0e345e0a2a53f4ac1851b7304471` (the commit this build branched from, per the brief — main has since moved with the parity chain; this branch merges after it).
Draft PR: attempted via API → **HTTP 403** (this token cannot open PRs, as expected). Open by hand from the branch; the PR body is in `~/workspace/jarvis-rebuild/desktop-subagents-push.py`.
Scope: **desktop/ only** — 8 files (7 edited, 2 new; list at the end). Nothing else touched; main untouched.

## 1. Audit verdict — what the desktop had (parity #21: UNVERIFIED → now answered)

**There was no subagent anything.** Verified by reading the tree at `c60f6e06`:

- Exactly **one** `Agent` is ever constructed (`src/runtime.ts`), the top-level conversation. Grep across `src/` found no orchestrator, no spawn/steer/stop tooling, nothing else agent-like (`platform.ts`'s `spawn` is `child_process` for OS commands).
- `src/agent/loop.ts` enforces one active run **per Agent instance** — `run()` throws *"Already working on something; stop it first."* while `running` is set. That guard is per-instance, so separate child instances never trip it; it stays exactly as-is for the top-level conversation.
- Stop existed in three places, all calling `rt.agent.stop()` only: the gateway `"stop"` message (`src/server.ts`), CLI SIGINT (`src/cli.ts`), and the tray menu (`src/app/main.ts`).
- Tools were a static list (`ALL_TOOLS`/`toolsFor`); the one stateful family (shopping) is built by a factory function — the pattern the new tools follow.

So #21's "UNVERIFIED" resolves to: **phone-only feature, absent on desktop** — the same gap Rafael identified ("why can't it run agents" was this missing layer, not a model-server limit).

**The spec** (read from source, `build-src/merged2/.../harness/Subagents.kt` + `TrashForgetSubagentsTest.kt` §4 + `ARCHITECTURE.md`): a coordinator spawns children (`agent:` + 8 hex chars); children run, report progress, and **hand off** `{task, status, result/error, verified}` — the hand-off is delivered *before* the status flips; failures hand off as failed, marked **unverified** (verification is a real step on the phone; its default is "not verified", never hidden); steering is queued messages; children **cannot spawn** ("One coordinator fans out; nesting stops there"); spawn refuses when the running cap is full.

## 2. As built

**New module `src/agent/orchestrator.ts`** — an `Orchestrator` holding child records and a hand-off queue, with the starter injected (the runtime supplies it). Plus small, backward-compatible loop support: `AgentDeps.inbox` (labeled messages drained into the conversation at run start and between steps) and `AgentDeps.systemSuffix` (a child's standing orders).

**The four tools** (registered like every other tool, same gate; only the top-level agent gets them):

| Tool | Gate policy | Behavior |
|---|---|---|
| `spawn_agent {task, name?}` | `mutate`, **alwaysConfirm** — the desktop mapping of the phone registry's approval-once for `agent.spawn`: the owner approves each distinct delegation on a card | Returns the child's id + name; the child starts immediately, in the background |
| `agent_status {}` | `read`, parallel-safe | Every child: id, name, task, state, last activity, current note |
| `steer_agent {agent, message}` | `mutate` | Queues a message the child reads at its next step; answers honestly when no running child matches |
| `stop_agent {agent}` | `mutate` | Stops one child; it hands off labeled **stopped** |

**Children** are separate `Agent` instances built by the runtime from the *same* deps factories — same provider endpoint (a second conversation, **not** a second model load), same gate instance, same memory (recall and saves shared), same embedder/vision route, same tool set from `toolsFor` with the same settings (offline-only, screen control, cloud vision, Tor). Each child gets a system-prompt suffix: it works for the main agent, does only its task, cannot spawn, and its final answer is its hand-off.

**Depth cap 1, two ways:** children are built *without* the four orchestration tools (a child calling `spawn_agent` gets the loop's plain `No tool named spawn_agent.` refusal), and `Orchestrator.spawn` itself throws for any depth ≥ 1 call.

**Concurrency cap:** `subagents.maxConcurrent` in config, **default 3**, read live. At the cap, spawn **refuses** (Kotlin semantics — it never queues): the tool result tells the parent to wait for a finish or stop one. (Refuse chosen over queue: a queued delegation would silently change what the owner approved on the spawn card.)

**Hand-off:** when a child settles, its hand-off is queued **before** its state flips (phone semantics), and the parent's loop drains the queue into its conversation — at the next run's start, or mid-run right after a tool step — as one labeled block per child, **in completion order**:

```
[Hand-off from subagent "beta" (agent:1a2b3c4d) — task: "beta task" — DONE (not verified)]
beta result
```

Failed children hand off the same way (`FAILED … It failed: <error>`), stopped children too (`STOPPED … It was stopped before finishing.`). Nothing fails silently. Hand-offs carry `verified: false / "not verified"` — the desktop has no verifier yet, and the label says so, exactly as the phone's default does.

**Cards:** a child's confirm cards travel the owner's live channel — a `RunHooks` the gateway or CLI registers on the runtime (`Runtime.setChannel`). The gateway extracts its card machinery into one `askOwner` used by the top run and by children alike; a child's card keeps its own text plus ` (via subagent "<name>")` in the reason, so the owner always knows whose action they are approving. With no channel registered, a child's card is declined (the same answer an unanswered card gets). One gateway fix rides along: when a top-level run ends, only *that run's* leftover cards are settled as declined — previously all pending cards were, which would have killed a child's open card; child cards are now scoped to their own run.

**Stop semantics (defined, as the brief asked): stopping the parent stops its children.** `Runtime.stopAll()` = parent run + every running child, wired to all three Stop surfaces (gateway `"stop"`, CLI SIGINT, tray). Each stopped child still produces its stopped hand-off; if the parent conversation already ended, the hand-off waits in the queue and rides the parent's next turn — it is never dropped. Children remain independently stoppable via `stop_agent` while the parent keeps working. The gateway state now also carries `agents` (the orchestrator's list), so clients can show subagent state.

**CLI note:** in `jarvis chat` the terminal is the channel, so children ask and report there and hand-offs arrive in the session. In one-shot `jarvis ask` the process exits when the parent run ends — children spawned there should be awaited via `agent_status` inside the same run; this is a front-end limitation, documented here rather than papered over.

**Deliberate deviations from the Kotlin original** (per the brief's tool surface): (1) stop delivers a *stopped hand-off* — Kotlin's `close` delivers none, but the brief's tests require the hand-off to say stopped; (2) no interrupt/resume tools — the brief's surface is spawn/status/steer/stop, so stop is terminal and there is no INTERRUPTED state; (3) cap default 3 (brief), not 8 (Kotlin).

## 3. Tests — 141/141, typecheck clean

Baseline gate first, on the untouched tree at `c60f6e06`: **typecheck clean, 125/125**. After the build: **typecheck clean, 141/141** (125 + 16 new, `test/subagents.test.ts`).

Orchestrator unit tests (fake starters, the Kotlin test's approach): ids/names/uniquified names; blank-task and depth refusals; cap refuses and frees on settle; steering order + drain-once + refused when not running; stop → stopped hand-off with the child's last words; failure hand-off (error, unverified); **completion order** (B settles before A → hand-offs [B, A]); stopAll skips settled children; a throwing starter fails the child with a hand-off.

Full-stack tests (real `Runtime`, scripted fake brain split by persona): **two children run concurrently and their hand-offs reach the parent in completion order with attribution**; **steering changes the child's answer** ("steered: focus on prices"); **a gate denial inside a child is honored** — the child's write outside the workspace is declined on a card whose reason reads `… (via subagent "writer")`, the file is never written, and the denial is in the child's hand-off to the parent; **stop_agent mid-run** → child state stopped, hand-off says STOPPED; **parent stop cascades** (run returns "Stopped.", child stopped, its hand-off queued for the next turn); **cap through the tools** (maxConcurrent 1 → the second spawn's refusal is an error result the parent reads); **depth cap through the tools** (child's tool list contains none of the four; its `spawn_agent` call is refused). One harness note: the fake brain yields 1 ms per turn — an all-microtask fake lets a polling parent starve a child's real filesystem calls, something a network provider never does.

## 4. Files

- `desktop/src/agent/orchestrator.ts` — **new**: Orchestrator, hand-off formatting, the four tools.
- `desktop/src/agent/loop.ts` — `AgentDeps.inbox` + `systemSuffix`; drains at run start and between steps.
- `desktop/src/runtime.ts` — orchestrator construction, child starter, `setChannel`, `stopAll`, shared deps builder, `agents` in state.
- `desktop/src/server.ts` — shared `askOwner`, channel registration, per-run card cleanup, `"stop"` → `stopAll`.
- `desktop/src/cli.ts` — terminal registered as the channel, SIGINT → `stopAll`, HELP documents the tools + knob.
- `desktop/src/app/main.ts` — tray Stop → `stopAll`.
- `desktop/src/config.ts` — `subagents.maxConcurrent` (default 3).
- `desktop/test/subagents.test.ts` — **new**: the 16 tests above.

Push: `~/workspace/jarvis-rebuild/desktop-subagents-push.py` (git-data API, surrogate auth); remote tree verified blob-for-blob against the local tree (55 files under `desktop/`). Local copy: `~/workspace/jarvis-rebuild/desktop-subagents/`.
