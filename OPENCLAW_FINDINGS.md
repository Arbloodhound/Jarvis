> **Post-merge replacement — 2026-10-19.** Rewritten from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/OPENCLAW_FINDINGS.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree.

## Post-merge status correction (2026-10-19) — read first

This file documents research into OpenClaw's code (commit `129049bb`, 2026-10-15, MIT license). That research content is unchanged below and remains valid as a description of OpenClaw. **Every Jarvis implementation-status claim or "Jarvis lifts" framing in the body is superseded by this section**, which states the audited post-merge state. CI has not yet compiled the merged tree; audit verification was grep-level, not compilation.

- **Now exists (was "proposed / owner decides / not yet built" in the body):** the patterns in §3 were substantially adopted into the harness package `com.agent.ultra.harness` in the merged tree: MemoryClaims (SQLite-backed claims with provenance and supersede chains, hybrid search + query-time temporal decay), ApprovalGrants (grants bound to the exact operation — approval tokens are bound to the exact sorted-args key), Scheduler (owner-tagged jobs), SkillStore (lazy skills), NotificationForwardingPolicy (3-gate: allowlist → quiet hours → rate limit), ScreenGuard (snapshot-bound screen actions; stale screens refused; protected apps fail closed), Trash, Forget, Subagents (depth-1 by construction — stricter than OpenClaw's configurable depth), and `connectors/` with CredentialVault (Keystore AES-GCM) and SecretScrubber. OpenClaw-derived code keeps MIT attribution.
- **DB wiring resolved:** UltraDatabase v9 wires the harness entities (ClaimEntity/GrantEntity/MemoryEmbeddingEntity/BanditArmEntity) with migrations registered — the former pass-2 critical; post-merge 0 known criticals.
- **Still open:** 2 manifest registrations (`SchedulerReceiver`, `SpotifyRedirectActivity`), 2 broken unit tests, TS `server/` orphaned, ~70 dead-code candidates, CI compile pending. Audit highs include harness grant keying as the weak core and claim promotion storing a model paraphrase as a verbatim quote — both qualify the "adopted" status above.
- Comparative claims about OpenClaw vs Muse in §2 describe those external codebases, not Jarvis, and are unaffected. Anything about Jarvis not covered here is **not re-verified in the 2026-10-19 audits**.

---
# OpenClaw: architecture findings for Muse comparison and Jarvis (Android/Kotlin)

- **Repo:** https://github.com/openclaw/openclaw (shallow clone, `--depth 1`)
- **Commit analysed:** `129049bbc3a5dbcc6b83bbabf5703e1d847d714a` (committed 2026-10-15 03:22 -0700)
- **Clone location:** `/tmp/claude-0/-workspace-Jarvis/2b882756-6656-5ff6-a5c0-c2015bc255cc/scratchpad/openclaw`
- **Method:** I read docs and source only. I ran no install scripts, builds or code. I confirmed the repo is official from its own `SECURITY.md`, which names `openclaw/openclaw` as the core repo (with the iOS/Android/macOS apps under `apps/`). I did not run a separate web search.
- **Scale:** about 56k files and 952 MB. TypeScript monorepo (`src/`, `packages/`, about 150 `extensions/`), plus native apps in `apps/android` (Kotlin, about 620 `.kt` files), `apps/ios` and `apps/macos`.

**Evidence labels used below**
- **[V]** I read it in source code (file path given).
- **[D]** It is stated in the repo's own docs (`docs/...`), and I did not trace it in code.
- **[I]** My inference, not stated anywhere.

---

## 0. Headline finding: OpenClaw ships a `muse` plugin

`extensions/muse/` (README, `harness.ts`, `src/approvals.ts`, `src/runtime.ts`) and `docs/plugins/muse.md` register an agent harness that runs `meta/*` models through **Meta's "Muse Code" CLI** (`muse serve`, the Muse Session Protocol, `@muse-code/sdk`) [V/D]. There is also a `meta` provider plugin, `extensions/meta/` [V]. The mapping in that plugin:
- OpenClaw exec policy maps to Muse approval modes: `denyUnmatched`, `allowAll` and `promptUnmatched`. `allowlist` and `ask:"always"` are refused because Muse cannot enforce them (`docs/plugins/muse.md` "Approvals and sandbox") [D].
- Muse sessions receive OpenClaw's `SOUL.md`, `USER.md` and `MEMORY.md` bootstrap context. It is prefixed to the first user input because MSP has no system field [D].
- Muse's approval requests appear as OpenClaw "Allow once / Deny" cards. "The harness never grants persistent approvals" [D].

So, at this commit, the codebases are coupled in the opposite direction from "Muse is built on OpenClaw": OpenClaw consumes Meta's coding CLI as one runtime. This does **not** prove anything about the consumer Muse agent's internals. **[I]** Meta's team clearly knows the OpenClaw conventions well. The overlap in workspace filenames, `memory_search`/`memory_get`, and the `exec`+`process` action vocabulary (section 3) is too exact to be coincidence.

---

## 1. Architecture map (from real code)

### 1.1 Agent loop / harness
- **Core loop:** `packages/agent-core/src/agent-loop.ts` (1,403 lines) [V].
  - `runAgentLoop` / `runLoop` uses an outer `while(true)` and an inner `while (hasMoreToolCalls || pendingMessages.length > 0)` (lines 221–224).
  - It checks for steering messages between tool calls (`getSteeringAtCheckpoint`, lines 165, 438, 587, 616, 752).
  - It calls `beforeToolCall` / `afterToolCall` hooks (lines 983, 1226).
  - It supports parallel tool-call groups (`launchParallelToolCalls`) and has a repeated-tool-error code (`REPEATED_TOOL_ERROR_CODE`).
- **Turn-taint helper:** `packages/agent-core/src/turn-taint.ts` [V, file exists].
- **Run orchestration:**
  - `src/agents/agent-command.ts:605` `agentCommand` resolves the model and thinking level, then loads the skills snapshot [V].
  - `src/agents/embedded-agent-runner/run-orchestrator.ts:110` `runEmbeddedAgent` handles per-session and global queue lanes, model/auth resolution and streaming [V].
  - `src/agents/embedded-agent-runner/run-loop.ts` drives attempts and retries and creates the turn-taint state (line 167) [V].
- **Lifecycle:** the Gateway RPC `agent` returns `{runId}` immediately and `agent.wait` waits for the terminal event. Streams are `lifecycle`, `assistant` and `tool`. Runs are serialized per session key, and a durable `activeWriterRunId` claim stops a superseded run from committing transcript data (`docs/concepts/agent-loop.md`) [D].
- **Runtimes are pluggable:** the built-in "openclaw" runtime, a Codex app-server harness (`extensions/codex`), Copilot, CLI backends (claude-cli) and Muse (`docs/concepts/agent-runtimes.md`) [D].
- **Reply contract:**
  - The exact token `NO_REPLY` is filtered out, so a turn can deliberately stay silent.
  - If a required-reply turn ends after tools with no answer, a "tool-free finalization pass" runs.
  - (`docs/concepts/agent-loop.md`) [D]

### 1.2 Workspace files: loading and injection
- **Constants:** `src/agents/workspace-bootstrap-policy.ts:10-36` [V].
  - Canonical prompt order: `AGENTS.md`, `SOUL.md`, `IDENTITY.md`, `USER.md`, `BOOTSTRAP.md`, `MEMORY.md`.
  - `TOOLS.md` is still defined as a constant but is retired. Its content moves into an `## Tools` section of AGENTS.md (`docs/reference/templates/TOOLS.md`) [D].
  - `HEARTBEAT.md` is retired and replaced by "monitor scratch" stored in SQLite (`docs/reference/templates/HEARTBEAT.md`). Doctor migrates it [D].
- **Loader:** `src/agents/workspace.ts:905` `loadWorkspaceBootstrapFiles` [V].
  - Reads go through `readWorkspaceFileWithGuards`.
  - A missing required file becomes `{missing:true}`, which injects a "missing file" marker.
  - A missing `USER.md` or `MEMORY.md` is silently skipped.
  - An unreadable file becomes `[UNREADABLE: reason]`.
- **Per-session filter:** `src/agents/workspace.ts:998` `filterBootstrapFilesForSession` [V].
  - Subagent sessions get **only** `AGENTS.md` (`SUBAGENT_BOOTSTRAP_ALLOWLIST`).
  - Cron sessions get AGENTS, SOUL, IDENTITY and USER.
  - Root `MEMORY.md` is removed for subagent, cron, group and channel sessions, so it is private-session only.
- **Prompt assembly:**
  - `src/agents/system-prompt.ts:283` `buildAgentSystemPrompt` is a pure renderer [V].
  - `src/agents/system-prompt-config.ts:33` applies config knobs [V].
  - Context assembly: `src/agents/bootstrap-files.ts:397` `resolveBootstrapContextForRun` and `src/agents/embedded-agent-helpers/bootstrap.ts:381` `buildBootstrapContextFiles` [V].
- **Caps:**
  - `bootstrapMaxChars` 20000 per file, `bootstrapTotalMaxChars` 60000 total. `USER.md` has a separate 4,000-character budget.
  - On truncation, a fixed notice tells the model to read the file directly.
  - Stable files sit above an internal prompt-cache boundary. Tools, skills, time and the like sit below it. Date and time travel in a separate per-turn "runtime context" message so the cached prefix stays byte-identical.
  - (`docs/concepts/system-prompt.md`) [D]
- **Other files:**
  - `BOOT.md` is a startup checklist run by the opt-in `boot-md` hook.
  - `BOOTSTRAP.md` is a one-time first-run ritual, created only for brand-new workspaces.
  - `DREAMS.md` is the human-review diary of memory consolidation.
  - (`docs/concepts/agent-workspace.md`, `docs/concepts/memory.md`) [D]
- **Hook point:** the internal hook `agent:bootstrap` can add, remove or replace bootstrap files, for example to swap SOUL.md [D].

### 1.3 Memory
- **Layers** (`docs/concepts/memory.md`) [D]:
  - `USER.md` holds directive-style user-model entries, dated and marked active or superseded.
  - `MEMORY.md` is curated long-term memory, injected at session start.
  - `memory/YYYY-MM-DD.md` holds daily notes. These are **not** injected. They are retrieved on demand, except that a bare `/new` or `/reset` prepends today's and yesterday's notes.
- **Tools:** `memory_search` (`query`, `maxResults`, `minScore`, `corpus` ∈ memory|wiki|all|sessions) and `memory_get` (`path`, `from`, `lines`) are defined in `extensions/memory-core/src/memory-tool-contract.ts:27-48` [V]. `memory-core` also registers an `intent` tool for standing intents (event-triggered prospective memory) (`extensions/memory-core/index.ts:106`) [V].
- **Storage:** SQLite in a per-agent DB (`~/.openclaw/agents/<id>/agent/openclaw-agent.sqlite`) [D].
  - Full-text search uses **FTS5**: `packages/memory-host-sdk/src/host/memory-schema-fts.ts:231,310`, with a separate FTS table for filenames [V].
  - Vector search uses optional **sqlite-vec `vec0`** (`extensions/memory-core/src/memory/manager-publication.worker.ts:545`) [V]. If it is missing, an in-process cosine fallback is used [D].
- **Hybrid ranking defaults** [V]:
  - Weights: `src/agents/memory-search.ts:120-128` sets `vectorWeight 0.7`, `textWeight 0.3`, `candidateMultiplier 4`, `mmr {enabled, lambda 0.7}`, `temporalDecay {enabled, halfLifeDays 30}`.
  - Merge: `extensions/memory-core/src/memory/hybrid.ts:135-139` computes `vectorWeight*vectorScore + textWeight*keywordScore`.
  - Then temporal decay and importance are applied, then MMR.
  - Decay: `extensions/memory-core/src/memory/temporal-decay.ts` computes `score*exp(-ln2/halfLife*age)`. It applies **only** to paths matching `memory/…/YYYY-MM-DD(-slug).md`. Curated files never decay.
- **Chunking:** about 400 tokens with 80 overlap; heading-only chunks are merged into the next chunk (`docs/concepts/memory-builtin.md`; code in `packages/memory-host-sdk/src/host/markdown-chunks.ts`) [D/V].
- **Embedding providers:** OpenAI by default; also Gemini, Voyage, Mistral, Bedrock, local llama.cpp GGUF, Ollama and LM Studio. `provider:"none"` gives FTS-only search. An explicitly named provider that fails reports memory as unavailable instead of silently degrading (`docs/concepts/memory-search.md`) [D].
- **Provenance** [V]:
  - Each chunk carries `origin_class TEXT NOT NULL CHECK (origin_class IN ('owner','agent','untrusted','system'))`, plus `session_kind` and `observed_at` (`packages/memory-host-sdk/src/host/memory-schema-provenance.ts:15`).
  - Legacy rows are backfilled as `'untrusted'` (line 86).
  - Write provenance: `src/agents/agent-tools.ts:205-222`. A file write gets origin `untrusted` if the sender is not the owner **or** the turn is tainted; otherwise it gets `agent`.
- **Turn taint** [V]: `src/agents/embedded-agent-runner/run/turn-taint-state.ts` sets a sticky flag once any non-presentation tool result reports `resultContentSource === "network"`. Where the flag is used:
  - memory write provenance (`agent-tools.ts:218`);
  - decision-prefilter context exclusion (`attempt-prompt-build.ts:251`);
  - propagation into Codex, Copilot and agentsapi tool bridges.
  - I found **no** use for gating egress (I grepped every `isTurnTainted` call site).
- **Promotion / "dreaming":** a scheduled sweep scores short-term recall and promotes items into `MEMORY.md` [D].
  - Gates: score, recall frequency and query diversity.
  - Untrusted and system candidates never reach consolidation.
  - Cron, heartbeat and subagent sessions produce no candidates.
  - Content that was injected from memory is never re-extracted ("recall-loop prevention").
  - Sources: `docs/concepts/memory-architecture.md`, `docs/concepts/dreaming.md`; code in `extensions/memory-core/src/dreaming-*.ts`, with a 14-day recency half-life in `short-term-promotion.ts:26`.
- **Pre-compaction memory flush:** `extensions/memory-core/src/flush-plan.ts` [V].
  - It is a silent extra turn that runs when the session is `softThresholdTokens` (default 4000) short of the compaction threshold. A forced flush also happens on transcript bytes (2 MiB).
  - Prompt: "Store durable memories only in memory/YYYY-MM-DD.md … treat MEMORY.md, DREAMS.md, SOUL.md, AGENTS.md as read-only … APPEND only … If nothing to store, reply NO_REPLY."
  - Only `read` plus an append-only `write` are exposed (`src/agents/agent-tools.memory-flush.ts`; wrapper `wrapToolMemoryFlushAppendOnlyWrite`) [V].
  - The flush runs on a private copy of the conversation [D].
- **Forget:** `openclaw memory forget --agent --session|--participant|--hook-source [--dry-run] [--json]` [D: `docs/concepts/memory-provenance.md`].
  - It removes entries by **session lineage** and records the sessions as forgotten so they are never re-ingested.
  - "There is no additional confirmation prompt or --apply flag. A preview is computed from current state, not saved as a deletion plan."
  - It does not delete transcripts, untracked prose or paraphrases.

### 1.4 `exec` and `process` tools
- **`exec`** (`src/agents/bash-tools.exec-run.ts`, schema in `bash-tools.schemas.ts`) [V].
  - Parameters: `command`, `workdir`, `env`, `yieldMs` (documented default 10000), `background`, `timeoutSeconds` (default 1800), `pty`, `host` (auto|sandbox|gateway|node), `ask`, `node`, `elevated` (`docs/tools/exec.md`) [D].
  - After `yieldMs` the call returns a process `sessionId` and the command keeps running. By default (`tools.exec.notifyOnExit`), its exit wakes an ordinary agent turn in the originating session [D].
  - Host exec rejects `env.PATH` and `LD_*`/`DYLD_*` overrides [D].
  - PTY uses `@lydell/node-pty` (`src/process/terminal-pty.ts:156`, `src/process/supervisor/adapters/pty.ts`) [V].
- **`process`** (`src/agents/bash-tools.process.ts`) [V].
  - Actions: `list`, `poll`, `log`, `write`, `send-keys`, `submit`, `paste`, `kill`, `clear`, `remove` (lines 315–600).
  - Registry limits (`src/agents/bash-process-registry.ts:20-25`): job TTL 30 min (1 min to 3 h), 30,000 pending output characters, 50 finished sessions kept, 2,000,000 output characters per finished session.
  - Sessions are scoped per agent [D].
- **Prompt guidance:** use cron for "later"; never use sleep loops or repeated polling; rely on push wake (`docs/concepts/system-prompt.md`) [D].

### 1.5 Approvals, allowlists, sandboxing
- **Exec modes** (`docs/tools/exec.md`) [D]:
  - `deny`
  - `allowlist`
  - `ask` (allowlist + on-miss human)
  - `auto` (allowlist + an **LLM reviewer** returning allow/deny/ask)
  - `full` — the **default** on gateway and node hosts. Sandboxing is **off by default**.
- **Approval store:** a SQLite row `exec_approvals_config` holding JSON with `defaults` and per-agent `security`, `ask`, `askFallback`, `allowlist[]` (`pattern` glob, `argPattern` regex or argv-hash, `source:"allow-always"`) and `mcpTools[]` grants (`docs/tools/exec-approvals.md`) [D].
  - Code: `src/infra/exec-approvals-*.ts` (allowlist, allow-always, authorization kernel/worker) [V, files exist].
- **Decisions:** `allow-once`, `allow-always` and `deny`. These are the only `allow-*` decision literals I found in exec code: about 862 and 418 occurrences respectively [V grep].
  - "Always allow here" is bound to **exact argv + cwd** [D].
  - The approval binds the resolved executable identity, plus a content hash for writable executables, and re-checks it before launch. A script operand that changes after approval is denied [D].
- **Standing grants for automations:**
  - "Always allow" on a cron run mints a grant bound to agent + job definition + exact command/cwd/env.
  - It lasts until revoked or until optional `grantExpiryDays` / `--expires-in-days`.
  - The ledger can be listed and revoked.
  - With no approval surface connected, the request is denied immediately.
  - Docs: `docs/tools/exec-approvals.md` "Standing grants" [D]. Code: `src/gateway/standing-grant-expiry-config.ts`, `src/gateway/operator-approval-standing-grants.types.ts` [V, files exist].
- **Session permission modes:**
  - `read-only`: managed mutation tools are omitted and exec is denied.
  - `guarded`: a human reviews after the allowlist fast path.
  - `workspace`: an LLM reviewer decides; three consecutive denials escalate to a human.
  - `full`.
  - Changing the mode cancels pending approvals (`docs/gateway/permission-modes.md`) [D].
- **Plugin approval API:** `allowedDecisions` defaults to `["allow-once","allow-always","deny"]`. Plugins can restrict this to once/deny. An optional **typed scope summary** (`message-send`, `payment`, `external-post`) is rendered on approval cards but grants nothing. `allow-always` is durable only if the plugin implements it (`docs/plugins/plugin-permission-requests.md`, `docs/tools/exec-approvals.md` "Approval scope summaries") [D]. Code: `src/infra/approval-scope.ts` [V exists].
- **Chat approvals:** `/approve <id> allow-once|deny`. Free text such as "yes" never authorizes (`docs/gateway/permission-modes.md`) [D].
- **Sandbox:**
  - Mode `off|non-main|all`, scope `agent|session|shared`.
  - Backends: docker (default), podman, ssh, openshell, crabbox.
  - Docker sandboxes default to **no network**, a read-only root filesystem and a non-root user. `network:"host"` is blocked.
  - (`docs/gateway/sandboxing/*.md`; code `src/agents/sandbox/`) [D/V-exists]

### 1.6 Skills
- **Format:** `SKILL.md` follows the AgentSkills spec (agentskills.io). YAML frontmatter has `name` and `description`, plus optional `user-invocable`, `disable-model-invocation`, `command-dispatch: tool`, and `metadata.openclaw` with `requires.{bins,anyBins,env,config}`, `os`, `always`, `primaryEnv` and `install` specs (`docs/tools/skills.md`) [D].
- **Precedence:** workspace `skills/` > `.agents/skills` > `~/.agents/skills` > managed > workshop > bundled > extra dirs [D].
- **Injection:** only an `<available_skills>` XML list (name, description, location) is injected. The model `read`s the SKILL.md on demand. `formatSkillsForPrompt` is in `src/skills/loading/session.ts:180` and filters out `disableModelInvocation` skills [V].
- **Prompt cost:** about 97 characters per skill plus the field lengths. The list is budgeted by `skills.limits.maxSkillsPromptChars` [D].
- **Snapshots:** skills are snapshotted per session [D].
- **Install hardening:** ClawHub registry; `security.installPolicy` (an operator command that can block installs); realpath containment for skill roots [D].

### 1.7 Cron / heartbeat / hooks
- **Automations (cron):** `src/cron/` [V].
  - Payload kinds: agent turn, system event, **command** (argv on the gateway host, admin-authored) and **script** (headless JS in "code-mode").
  - Script result contract `{notify?, wake?: "now"|"next-heartbeat", state? (≤16 KB), nextCheck?}` (`src/cron/trigger-script-result.ts:54-60`).
  - **Condition triggers** return `{fire: boolean, message?, state?}` (lines 31-52), so quiet runs never call the model.
  - Jobs carry immutable **creator provenance** (`src/cron/types.ts:386-417`) [V]. Management needs the channel owner (`commands.ownerAllowFrom`) or `operator.admin`, through one-use 60-second grants (`docs/automation/cron-jobs/managing-jobs.md`) [D].
- **Heartbeat** (`docs/gateway/heartbeat.md`) [D].
  - A system-owned cron job per agent, default every 30 minutes.
  - The default prompt says to follow monitor scratch, not to infer old tasks, and to reply `NO_REPLY` if nothing needs attention.
  - It respects `activeHours`.
  - Event wakes are rate-limited: at least 30 seconds apart, with a flood guard at 5 per 60 seconds.
  - It defers while other work is active.
  - Code: `src/cron/heartbeat-monitor.ts` [V exists].
- **Hooks** — two systems [D: `docs/automation/hooks.md`, `docs/concepts/agent-loop.md`]:
  - Internal `HOOK.md` + handler hooks for events such as `command:new`, `agent:bootstrap` and `gateway:startup`. **Workspace hooks require explicit opt-in.** They are trusted in-process code with no timeout or retry.
  - Typed plugin hooks via `api.on(...)`: `before_tool_call` (`{block:true}` is terminal), `before_prompt_build`, `message_sending` (`{cancel:true}`), `tool_result_persist`, `before/after_compaction`, and others.
  - There are also HTTP webhooks (`/hooks/wake`, `/hooks/agent`) and Gmail PubSub.

### 1.8 Subagents / sessions
- **Tools:** `sessions_spawn`, `sessions_yield` and `subagents`. Each child runs in its own session `agent:<id>:subagent:<uuid>` and by default *announces* its result back to the requester. Context is `isolated` by default, or `fork` (`docs/tools/subagents*.md`) [D].
- **Hard deny list** (`src/agents/agent-tools.policy.ts:57-90`) [V]:
  - Always denied: `gateway`, `agents_list`, `openclaw`, `session_status`, `progress_card`, automations, `message`, `sessions_send`, `conversations_*`.
  - Leaf children also lose `subagents`, `sessions_list`, `sessions_history`, `sessions_search` and `sessions_spawn`.
- **Limits:** `maxSpawnDepth` 5 (range 1–5), `maxChildrenPerAgent` 5, `maxConcurrent` 8 [D].
- **Child prompt:** children use `promptMode=minimal` and get only AGENTS.md [V/D].

### 1.9 Browser tool
- **Plugin:** `extensions/browser`, depending on `playwright-core` 1.63.0 and `chrome-devtools-mcp` 1.10.1 [V package.json].
- **One `browser` tool:** actions are `status/start/stop/tabs/open/focus/close/snapshot/screenshot/navigate/act/requests/errors/text/emulate/doctor` (`docs/tools/browser/agent-tools.md`) [D].
- **What the agent sees:** `snapshot` returns an AI or ARIA UI tree with element **refs**. `act` clicks or types by ref; a stale ref fails and the agent must re-snapshot. `navigate` returns a compact snapshot inline. `text` is capped at 40k characters [D].
- **Untrusted wrapping:** page text is wrapped as untrusted (`extensions/browser/src/browser-tool.actions.ts:1,309`, `externalContent:{untrusted:true, source:"browser"}`) [V].
- **Isolation is by browser profile:** a dedicated `openclaw` Chrome profile, versus a `user` profile attached through Chrome DevTools MCP. The tool runs **in the same agent loop and conversation**. There is no dedicated browser sub-agent [D]. The docs recommend a separate "reader agent" for untrusted content (`docs/gateway/security/prompt-injection.md`) [D].

### 1.10 Channels
- **Channel list:** about 30 in `docs/channels/`, including WhatsApp (`baileys` 7.0.0-rc14), Telegram (`grammy` 1.46.0), Slack (`@slack/bolt`), Discord, Signal, iMessage/BlueBubbles, Matrix, MS Teams, SMS, Zalo and WeChat [V package.json / D].
- **DM access:** `dmPolicy` `pairing|open|…`. Unknown senders get an 8-character code that expires in 1 hour, with at most 3 pending per account (`docs/channels/pairing.md`) [D].
- **Node devices:** iOS, Android, macOS and Linux pair as "nodes" and expose commands (section 1.13).

### 1.11 Context compaction
- **Behavior** (`docs/concepts/compaction.md`) [D]:
  - Auto-compaction near the limit or on overflow errors (dozens of provider error strings are recognized).
  - Older turns are summarized in one model request, with input capped at about 160k characters.
  - Tool-call/result pairs are never split.
  - "Safeguard" mode (the default for new configs) audits summary quality: structure, pending asks and exact identifiers.
  - On timeout it commits *without* a summary rather than stalling.
- **Code:** `src/agents/embedded-agent-runner/compact*.ts` [V exists]. The memory flush (section 1.3) runs before compaction.
- **Context engine:** pluggable (`docs/concepts/context-engine.md`) [D, not read].

### 1.12 Security model and known weaknesses
- **`SECURITY.md`:**
  - Single trusted operator per gateway; **not** a multi-tenant boundary.
  - "Anyone who can operate an agent can make it do anything that agent can do."
  - Prompt injection on its own is not considered a vulnerability.
- **`docs/security/THREAT-MODEL-ATLAS.md` (MITRE ATLAS):**
  - Critical risks: T-EXEC-001 (prompt injection), T-PERSIST-001 (malicious skill), T-EXFIL-003 (credential harvest).
  - Open P0 recommendations: "skill execution sandboxing" and "output validation for sensitive actions".
  - Open P1: per-sender rate limiting, token encryption at rest, URL allowlisting for `web_fetch`.
  - Key files named there: `src/infra/exec-approvals.ts`, `src/infra/net/ssrf.ts`, `src/security/external-content.ts`.
- **Untrusted-content boundary:**
  - Content is wrapped in `<<<EXTERNAL_UNTRUSTED_CONTENT … >>>` markers (`src/security/external-content.ts:37-38`) [V].
  - Chat-template tokens are stripped from wrapped content [D].
  - Bypass flags `allowUnsafeExternalContent` exist for hooks and cron [D].
- **Secrets:**
  - SecretRefs, an `exec`/`file`/`env`/`store` provider, and a Vault plugin (`extensions/vault`).
  - Model-provider keys become sealed **sentinels** (section 3, item 8).
  - The docs admit that a plaintext file the agent can read stays readable and that sentinels "are not process isolation" (`docs/gateway/secrets/runtime-model.md`) [D].

### 1.13 Android app (directly relevant to Jarvis)
- **What it is:** `apps/android` (Kotlin) is a **companion node** to the gateway. The brain runs on the gateway, not on the phone (`apps/android/README.md`) [D].
- **Command table:** `apps/android/app/src/main/java/ai/openclaw/app/node/InvokeDispatcher.kt` [V].
  - Each command has a `CommandGate(isAvailable, unavailable error, isAdvertised)` and an optional `requiresForeground`.
  - Commands: `system.notify`, `talk.ptt.*`, `camera.list/snap/clip`, `location.get`, `device.status/info/permissions/health/apps`, `notifications.list/actions`, `photos.latest`, `contacts.search/add`, `calendar.events/add`, `motion.activity/pedometer`, `sms.send/search`, `callLog.search`, `mobile.ui.observe/act`, and debug commands.
  - The same list drives both advertising (`buildInvokeCommands`) and dispatch.
- **Gateway-side policy** (`docs/nodes/command-policy.md`) [D]:
  - A command must be declared by the node **and** approved on its pairing record **and** in the platform allowlist.
  - Dangerous commands (`camera.snap/clip`, `sms.send/search`, `contacts.add`, `calendar.add`, `screen.record`, …) need explicit `gateway.nodes.commands.allow`.
- **Accessibility UI control:** `apps/android/app/src/thirdParty/java/ai/openclaw/app/node/MobileUiHandler.kt` and `.../accessibility/*` [V].
  - The snapshot is `MobileUiSnapshot{id, packageName, windowTitle, nodes[]}`, where each node has `ref, parentRef, role, text, contentDescription, viewId, bounds, clickable, editable, scrollable, enabled, focused, actions`.
  - Act types: `activate(ref)`, `set_text(ref,text)`, `scroll(ref,dir)`, `tap(x,y)`, `swipe`, `global_action(back|home|recents|notifications)` and `wait(ms)`. Each act is bound to a `snapshotId`.
  - `AccessibilityActionExecutor.kt:172-295` returns specific failures: `TargetStale` (ref not in the current snapshot), `TargetNotFound` (`node.refresh()` failed), `SecureContent` ("Text entry into password fields is refused") and `ActionNotSupported`.
  - A "UI epoch" gates blind coordinate actions.
  - This only ships in the third-party build; the Play flavor has a separate `MobileUiHandler`.
- **Notification forwarding:** `DeviceNotificationListenerService.kt:230-265` [V] applies, in order, enabled, package allowlist, quiet hours, then a burst limiter (`maxEventsPerMinute`). Blocked events don't consume quota. The resulting `notifications.changed` events can wake the agent.

### 1.14 Config format
- **File:** `~/.openclaw/openclaw.json`, written in JSON5 (`src/config/paths.ts:35`) and validated by zod schemas (`src/config/zod-schema.*.ts`) [V].
- **Layout:** top-level keys include `agents.defaults` / `agents.entries.<id>`, `tools.exec`, `memory.search`, `plugins.entries.<id>.config`, `gateway.nodes`, `secrets`, `cron`, `hooks` and `commands.ownerAllowFrom` [D/V].
- **Runtime state lives in SQLite, not JSON:** `~/.openclaw/state/openclaw.sqlite` (shared) and `agents/<id>/agent/openclaw-agent.sqlite` (per agent). `openclaw doctor --fix` migrates legacy JSON files [D].

---

## 2. Muse features vs OpenClaw

| Muse feature | In OpenClaw? | How it differs / evidence |
|---|---|---|
| Workspace files AGENTS/SOUL/IDENTITY/USER/MEMORY.md | **Yes, the same names** [V] | Same files and roles (`workspace-bootstrap-policy.ts`). OpenClaw has retired TOOLS.md and HEARTBEAT.md. |
| `memory_search` / `memory_get` | **Yes, the same names** [V] | `memory-tool-contract.ts`. Hybrid FTS5 + vector search with decay and MMR. |
| `exec` + `process` (list/log/poll/write/send_keys/kill/clear) | **Yes, nearly identical** [V] | OpenClaw's actions: list, poll, log, write, `send-keys` (hyphen), submit, paste, kill, clear, remove. Muse's `send_keys` is a snake_case variant of the same set. |
| Skills (SKILL.md) | **Yes** [D/V] | AgentSkills spec; on-demand `read`; `metadata.openclaw.requires` gating. |
| Cron, hooks, subagents, browser, approvals | **Yes** | See sections 1.5–1.9. |
| **memory_explain** (provenance) | **Partial: data yes, tool no** | Provenance is stored per chunk (`origin_class`, `session_kind`, `observed_at`) and per entry (session-lineage origins) [V]. It is used internally for trust gating, promotion and forget. I found no model- or user-facing explain tool: memory-core registers only `memory_search`, `memory_get`, `intent` and dreaming [V grep]. |
| **forget plan/confirm** | **Partial** | CLI `memory forget --dry-run` then re-run without the flag [D]. The docs say outright: "no additional confirmation prompt … not saved as a deletion plan". It is operator-only, not an agent tool, and selects by session lineage, not by individual fact. Muse's plan/confirm two-step is stricter. |
| **trash** | **No (for memory)** | Forget deletes immediately (it keeps only dreaming rewrite preimages and backups) [D]. OpenClaw uses the OS Trash only when an *agent* is deleted (`src/gateway/server-methods/agents-delete-filesystem.ts:12`, `movePathToTrash`) [V]. |
| **Hooks start disabled + dry_run + silent/wake script contract** | **Pieces exist separately** | Workspace hooks need explicit opt-in [D]. `--dry-run` exists only for hook-pack *updates* (`src/cli/hooks-cli.ts:390`) [V], not for execution. The silent/wake contract exists in **cron script payloads** (`{notify?, wake:"now"|"next-heartbeat", state}`; no `notify` means silent) and condition triggers (`{fire}`) [V `src/cron/trigger-script-result.ts`]. **[I]** Muse appears to merge these into a single hook model. |
| **Cron with owner** | **Yes** [V] | Immutable creator provenance on jobs (`src/cron/types.ts:386-417`). Owner/admin authority to manage them; standing grants bound to agent + job. |
| **tool_search, deferred namespaces** | **Yes (similar)** [D] | `tool_search`, `tool_describe`, `dispatch_action` plus a bounded directory, on by default for embedded runs. MCP tools are named `server__tool`, and the untrusted MCP descriptions stay out of the system prompt (`docs/tools/tool-search.md`). "Namespaces" as a first-class concept: not seen. |
| **Browser sub-agent isolated from the conversation** | **No** | The browser is a tool in the main loop. Isolation is by *Chrome profile*, not by context [D]. Output is wrapped as untrusted and taints the turn for memory only [V]. The docs recommend a manual "reader agent" pattern instead [D]. Muse is stronger here. |
| **Credential surrogates / vault** | **Yes, closely matching** [V/D] | `src/secrets/sentinel.ts`: `oc-sent-v2.<AES-256-GCM ciphertext>.end`, sealed with a per-process random key. Model-provider keys are sentinels throughout the call chain and swapped at fetch time. An opt-in loopback **egress proxy** gives Gateway-hosted exec only sentinels and substitutes plaintext only for **hosts bound to that secret** (`--allow-host`). Per-process proxy grants are revoked when the process exits. Not available in sandbox or node exec. `extensions/vault` is a HashiCorp Vault SecretRef provider. |
| **Sentinel-style egress approval** | **Static allowlist only** | `secrets.egressProxy.allowedHosts` (empty list = lockdown) plus per-secret host binding. A refusal prints the exact command to allow the host [D]. I found no interactive per-request egress approval (grep of docs). Only clients that honour the proxy are constrained [D]. |
| **Tainted egress** | **Taint exists, egress gating does not** | The turn-taint flag is set by network-sourced tool results [V `turn-taint-state.ts`]. It is used for memory provenance and context exclusion, **not** to block or approve outbound sends [V call sites]. |
| **Approvals with scopes (once/session/task/time/permanent)** | **Partial** | Interactive decisions are `allow-once` / `allow-always` (argv + cwd bound) / `deny` [V]. Time scope exists only for *automation standing grants* (`grantExpiryDays`) [D]. Session scope is expressed as a *permission mode* (read-only/guarded/workspace/full), not as an approval decision [D]. Per-turn `/exec security=…` overrides. I found no "task" or "session" approval decision. |

---

## 3. Most valuable patterns for a phone assistant (Jarvis, Kotlin)

1. **Snapshot-bound UI refs with stale/secure refusals.** `apps/android/app/src/thirdParty/java/ai/openclaw/app/node/MobileUiHandler.kt`, `.../accessibility/AccessibilityActionExecutor.kt:172-295`, `MobileUiSnapshot.kt`.
   - Each accessibility snapshot gets an id and per-node refs, and every act must quote the `snapshotId`. A ref from an older generation returns `target_stale`, and `node.refresh()` must succeed before acting.
   - Password fields refuse `set_text`. Coordinate taps are gated by a UI epoch.
   - This gives perceive→act→verify a hard freshness contract instead of trusting the model's memory of the screen.
2. **Single command table drives advertising and dispatch.** `apps/android/.../node/InvokeDispatcher.kt`.
   - Each device command is one entry: `(name, handler, CommandGate(isAvailable, typedError, isAdvertised), requiresForeground)`.
   - The capability list sent to the brain is derived from the same table, so the model is never offered a tool that will fail for permission or foreground reasons. Errors are typed codes such as `CAMERA_DISABLED` or `NODE_BACKGROUND_UNAVAILABLE`.
3. **Default-deny for dangerous device actions.** `docs/nodes/command-policy.md`.
   - A command must be declared × approved × allowlisted.
   - Privacy-heavy or side-effecting commands (`sms.send`, `camera.snap`, `contacts.add`, `calendar.add`, `screen.record`) need a separate one-time persistent opt-in, and a deny list always wins.
   - For Jarvis, this maps to a per-action risk tier in the CapabilityRegistry.
4. **Bootstrap injection with per-session filtering and hard caps.** `src/agents/workspace.ts:905-1017`, `src/agents/workspace-bootstrap-policy.ts`.
   - Persona and instructions are injected every turn, with per-file and total character caps and a truncation notice.
   - Private memory (`MEMORY.md`) is dropped for background, cron and subagent contexts; subagents get only the operating rules.
   - A missing file becomes an explicit marker, never silent garbage.
5. **Curated vs daily memory with on-demand retrieval.** `docs/concepts/memory.md`, `extensions/memory-core/src/memory-tool-contract.ts`.
   - Only a small curated file is always in context. Daily logs (`memory/YYYY-MM-DD.md`) are fetched by `memory_search` / `memory_get` with line ranges.
   - This keeps prompt cost flat as memory grows, which matters on mobile tokens and latency.
6. **Hybrid FTS + vector ranking that runs on SQLite.** `src/agents/memory-search.ts:120-128`, `extensions/memory-core/src/memory/hybrid.ts:135-139`, `temporal-decay.ts`, `packages/memory-host-sdk/src/host/memory-schema-fts.ts`.
   - Score = 0.7·vector + 0.3·BM25 (FTS5), with candidates ×4.
   - Then `exp(-ln2·age/30d)` decay **only on dated notes**, then MMR with λ=0.7.
   - Falls back to keyword-only if embeddings are unavailable. All of this works on Android's SQLite with FTS5, plus an on-device embedder or a remote one.
7. **Provenance columns + turn taint for memory trust.** `packages/memory-host-sdk/src/host/memory-schema-provenance.ts:15`, `src/agents/embedded-agent-runner/run/turn-taint-state.ts`, `src/agents/agent-tools.ts:205-222`.
   - Each memory row stores `origin_class ∈ {owner, agent, untrusted, system}`. Unknown rows default to untrusted, never owner.
   - Once a network-sourced tool result appears, everything the agent writes for the rest of that turn is classified untrusted. Untrusted and system rows never get auto-promoted or auto-injected.
   - **[I]** Jarvis could extend the same flag to gate outbound actions (SMS, email), which OpenClaw does not do.
8. **Pre-compaction silent memory flush.** `extensions/memory-core/src/flush-plan.ts`, `src/agents/agent-tools.memory-flush.ts`.
   - When context is within about 4,000 tokens of compaction, the agent gets a hidden turn with only `read` and an **append-only** write to today's note, and replies `NO_REPLY` if nothing needs saving.
   - Persona and curated files are read-only during the flush. This prevents losing facts at summarization time without letting the model rewrite its own rules.
9. **Exact-operation approvals and expiring standing grants.** `docs/tools/exec-approvals.md` (allowlist schema, "Standing grants"), `src/infra/exec-approvals-*.ts`, `src/gateway/standing-grant-expiry-config.ts`.
   - "Allow always" binds the exact argv + cwd (+ executable identity), so any drift re-prompts.
   - Background or scheduled jobs mint grants bound to job + operation, with optional expiry and a revocable ledger. With no human surface available, the default is **deny**.
   - Free-text "yes" never counts as approval.
10. **Deterministic gate before waking the model.** `src/cron/trigger-script-result.ts`, `docs/automation/cron-jobs/payloads.md`.
    - Scheduled checks run cheap code first. It returns `{fire:boolean}`, or `{notify?, wake?: "now"|"next-heartbeat", state (≤16 KB)}`, so quiet runs cost no LLM call.
    - The heartbeat default prompt adds "reply NO_REPLY if nothing needs attention", plus active hours and wake rate limits (30-second minimum, 5 per minute flood guard; `docs/gateway/heartbeat.md`).
11. **Throttled, filtered event ingestion.** `apps/android/.../node/DeviceNotificationListenerService.kt:230-265`.
    - Device events (notifications) pass through filters in order: enabled → package allowlist → quiet hours → burst limiter. Filtered events do not consume quota.
    - This stops a noisy app from flooding the agent with wakes and spending tokens.
12. **Credential surrogates (sentinels).** `src/secrets/sentinel.ts`, `docs/gateway/secrets/secret-store-and-egress.md`.
    - The model, logs and tool environment only ever see an AES-GCM-sealed `oc-sent-v2…end` token. The real secret is substituted at the last network hop, and only for hosts bound to that secret.
    - An unknown or mis-bound sentinel fails closed.
    - **[I]** On Android: keep secrets in Keystore and substitute placeholders in an OkHttp interceptor with a per-secret host allowlist.

Also worth copying (smaller):
- The subagent hard-deny list plus depth roles (`src/agents/agent-tools.policy.ts:57-90`).
- Stop a turn after 3 identical tool errors (`docs/tools/loop-detection.md`).
- Untrusted-content wrapping markers (`src/security/external-content.ts:37-38`).
- Tool Search deferral when the tool catalog is large (`docs/tools/tool-search.md`).

---

## 4. Honesty notes
- Everything marked [D] comes from OpenClaw's own docs at this commit, and I did not trace it in code. Given the size of the repo (more than 2,000 files in `src/agents` alone), I verified only the mechanisms cited [V].
- I did not read `docs/concepts/context-engine.md`, the dreaming phase internals, or the Codex harness.
- The Muse comparison is limited to the feature list given to me. I had no access to Muse source. Statements about Muse "being stronger" or "merging" features are [I].
- I did not run, build or install anything from the clone.
