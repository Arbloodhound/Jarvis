# Jarvis vs OpenClaw Android: why Jarvis feels slower

Read-only. Nothing built, run, installed, or networked. Git tree of /workspace/Jarvis is unchanged.

Path roots (absolute):
- OC = /tmp/claude-0/-workspace-Jarvis/2b882756-6656-5ff6-a5c0-c2015bc255cc/scratchpad/openclaw/apps/android/app/src/main/java/ai/openclaw/app
- JV = /workspace/Jarvis/ultra-native/app/src/main/java/com/agent/ultra

Labels: [V] read in code. [D] stated in DEVLOG or docs. UNVERIFIED = not checked (no device timings exist for OpenClaw).

## 1. Architecture (largest factor, PROVEN)
- **OpenClaw is a thin client.** The phone opens one OkHttp WebSocket to a user-chosen gateway: `OC/gateway/GatewaySession.kt` (`connect`, `request`, `handleEvent`). It sends `chat.send` (`OC/chat/ChatController.kt`, about line 5799) and renders the `chat`/`agent` events the gateway streams back. [V]
- The agent loop runs on the gateway: `src/agents/embedded-agent-runner/run-orchestrator.ts` and `packages/agent-core/src/agent-loop.ts` under the OpenClaw repo root. [V]
- No model-provider URL appears in OpenClaw's `main`, `play` or `thirdParty` source. [V, grep]
- **Jarvis is a fat client.** `JV/provider/OpenAiClient.kt` POSTs straight to `api.venice.ai/.../chat/completions` (default in `JV/provider/ProviderConfig.kt`). `JV/agent/Brain.kt` `runLoop` runs the agent on the phone. Jarvis has no websocket or gateway code. [V]
- Effect: an OpenClaw turn costs phone-to-gateway RTT plus the gateway's model call. A Jarvis turn adds all phone-side pre-processing (section 3) and drives the screen from the same process. UNVERIFIED: OpenClaw's gateway latency.

## 2. UI
- **OpenClaw:** StateFlows in `ChatController` (`messages`, `streamingAssistantText`, `toolActivities`, `progressCard`). Keyed timeline (`OC/ui/chat/ChatTimeline.kt`, key = entryId). Markdown is segmented per text change (`ChatMarkdown.kt`, `remember(text, isStreaming)`). Room-backed transcript cache (`OC/chat/ClientDatabases.kt`, `app/schemas`), so history paints before the network. Themed design system (`OC/ui/design/ClawTheme.kt`, 382 lines) and mascot animation. [V]
- **Jarvis:** `JV/ui/ChatScreen.kt` is a keyed `LazyColumn` of plain `Text` bubbles, with no markdown. Progress is one status line (`ChatStatus`: LOADING, THINKING, NAVIGATING). One hard-coded dark Material3 scheme in `JV/ui/theme/Theme.kt` (27 lines). No Canvas or animation in `JV/ui/*.kt`. [V]
- **Token streaming:** `JV/ui/ChatStore.kt` `appendTo` (line 164) rebuilds the whole message on Main per token. OpenClaw keeps a single streamed string, and `GatewayLiveTextProjection` rebuilds deltas off the UI. Per-token recomposition cost is UNVERIFIED (no profiling).
- **Tool progress:** OpenClaw shows per-call tool cards from `tool` and `item` events (`ChatController.handleAgentEvent`). Jarvis shows "thinking" and "step n/12", and removes the streamed bubble on tool-call turns (`Brain.kt`, about line 1270). [V]

## 3. Threading (PROVEN for the calls listed)
- **Jarvis runs the whole brain on the main thread.** `JV/ui/ChatStore.kt:51` sets `agentScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)`, and `ChatScreen.launchRun` launches `Brain.run` on it.
  - `Brain.runUnlocked` calls `recordTurn` (`Brain.kt:129`), which runs a synchronous SQLite `insert` (`JV/listen/AmbientMemory.kt:58`). Both `recordTurns` and `rememberChat` default to true, so every typed request does this.
  - `importVerdict` and `importFromLaptop` do file `exists`, `readLines` and `delete` inline on main on every run.
  - `lessonsFor`, `memoryHint` and the recipe lookup also run before the first cloud call. Their cost is UNVERIFIED.
- **Accessibility reads block main.** `JV/AgentAccessibilityService.java` `getScreenContentFlat` (line 884) posts the full tree walk to the main Looper and blocks the caller up to 4 s. `waitForUiChange` (line 1557) re-runs that walk every 150 ms. Nothing calls `waitForUiChange`; `AgentController.kt:135` is dead code.
- **Good:** model I/O, gestures and `LocalModelEngine.ensureLoaded` (line 340) are on IO. DEVLOG 2026-10-07 records an ANR from main-thread model load. The fix is in source, but the DEVLOG marks runtime proof as missing.
- **OpenClaw is IO-scoped.** `OC/NodeRuntime.kt:912` uses `SupervisorJob() + Dispatchers.IO`, and `GatewaySession` uses `lifecycleDispatcher = Dispatchers.IO`. Its accessibility walk is bounded (`thirdParty/.../AccessibilitySnapshotter.kt`: 400 nodes, 4000 visited, depth 40) and serialized by a Mutex (`thirdParty/.../node/MobileUiHandler.kt:40`).

## 4. Agent loop and tools
- **OpenClaw (gateway side):** `launchParallelToolCalls` (`agent-loop.ts`, line 858), steering checks between tool calls, per-session queue lanes. On the phone, `OC/node/InvokeDispatcher.kt` advertises capability-gated commands, which the gateway invokes. [V]
- **Jarvis:** tools are JSON parsed from free text (`Brain.parseToolCall` to `ModelOutput.toolCall`). The request body has no `tools` field (`OpenAiClient.kt`). Turns are strictly serial, up to 12. A truncated call is re-asked (`Brain.kt`, about line 1273). Per-tool verify waits are 500 to 2500 ms (`Brain.verifyAction`, line 1729). [V]
- Effect: text-parsed tools are more fragile, and serial turns add latency. Frequency UNVERIFIED.

## 5. Accessibility and screen control
- **OpenClaw** (thirdParty flavor only; the play flavor is a stub): `mobile.ui.observe` returns a snapshot with a `snapshotId` and ref-addressed nodes. `mobile.ui.act` must quote that `snapshotId`. Stale refs fail with `target_stale` (`AccessibilityActionExecutor.kt`, line 172), and the foreground package is preflighted (line 239). The model acts on refs, and the phone adds no sleeps. [V]
- **Jarvis:** `JV/agent/ReActNavigator.kt` runs observe, then a non-streaming model call (line 353, max 600 tokens, temp 0.1), then the action, then `delay(900)` and observe again (lines 426–427, 1314–1315). App launch waits 2500 ms (line 268). Per step: about 0.9 s of fixed sleep, two full tree walks, and one blocking model call. [V]
- This fixed-sleep loop is the most likely cause of the "unsmooth" feel. UNVERIFIED on device.

## 6. Model calls
- **OpenClaw:** streams `delta` and `assistant` events. `GatewayLiveTextProjection.kt` rebuilds deltas and recovers from sequence gaps (`GatewaySession.handleEvent`). Timeouts: connect 20 s, connect RPC 12 s. Reconnect backoff is `gatewayReconnectDelayMs` (line 312). [V]
- **Jarvis:** brain turns stream (`completeStreaming`, SSE). Navigator and librarian steps use the blocking `complete`. Timeouts: connect 20 s, read 120 s, call 180 s. Brain temp is 0.2. [V]
- One DEVLOG measurement: a simple tool-choice call took 1.2 s (DEVLOG around line 3549). [D, single sample]

## 7. Other
- **Send reliability:** OpenClaw has a Room-backed send outbox (`OC/chat/ChatCommandOutbox.kt`) and reconnect-restore tests. Jarvis has no outbox type; a grep for it found none. UNVERIFIED beyond grep.
- **Architecture caveat:** moving Jarvis's loop to a server is a large redesign. CLAUDE.md forbids that without root-cause proof and owner decision.

## Top 5 changes for Jarvis, ranked by user-visible impact vs effort

1. **Replace fixed sleeps in ReActNavigator with event-driven settle waits.** Use the existing `waitForUiChange`, or AccessibilityEvent-driven settling. Impact: high, since every step pays about 0.9 s and more. Effort: medium. Needs device timing to confirm.
2. **Move run-start work off main.** Change `ChatStore.agentScope` to Default or IO, keeping UI writes on the existing `onUi` hop. Also move `recordTurn`, `importVerdict` and `importFromLaptop` to IO. Impact: medium, fewer stalls at run start. Effort: low. Risk: UI state writes must stay on main.
3. **Stop tree walks from blocking main.** Run `getScreenContentFlat` on a worker thread, cap polling, and reuse snapshots within a step. Impact: medium to high. Effort: medium to high.
4. **Stream navigator steps and use native tool calls.** Apply to `ReActNavigator` (lines 353, 1662). Impact: medium, faster first feedback and fewer parse failures. Effort: medium.
5. **Adopt OpenClaw's snapshotId-checked ref actions and a transcript cache.** This covers `AccessibilitySnapshotter` and `SnapshotGenerationStore`, plus a Room transcript cache. Impact: reliability, plus faster chat startup. Effort: high.
