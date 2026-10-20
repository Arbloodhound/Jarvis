> **Post-merge replacement — 2026-10-19.** This is a point-in-time document. Its body is preserved verbatim as the historical record; the status section below states what the 2026-10-19 code audits and the full-branch consolidation (all work now on `main`) changed. The previous version is preserved at `archive/2026-10-19/SOLUTION_react_navigate_planning_step.md`. CI has not yet compiled the merged tree.

## Status as of 2026-10-19

This report predates the 2026-10-19 consolidation. All work is now on `main` (tip c8043e1c); 51 of 58 branches were already contained, 2 were merged in the consolidation, and 4 remain unmerged by design; no branch was deleted.
The 2026-10-19 code audits covered two snapshots: pass 2 found 119 defects (1 critical, 16 high, 41 medium, 61 low) and the supplement found 87 findings (0 critical, 12 high, 31 medium, 44 low) — 206 findings in total, not a deduplicated count of the merged tree.
Post-merge there are 0 known criticals: the pass-2 critical (harness DB wiring) is resolved — UltraDatabase is v9, with the harness entities, DAO accessors, and MIGRATION_7_8 + MIGRATION_8_9 registered.
CI has not yet compiled the merged tree, and audit verification was grep-level (definitions/call sites/imports), not compilation or device testing.
This document proposes a planning-step overhaul for `react_navigate`: a `planSteps()` method and plan-aware prompts in `src/core/ReActLoop.ts`, with no change to BrainExecutor.
In the merged tree the `src/` TS app's live path is AgentCore / BrainExecutor / TaskExecutor, and its verdict is 'works with defects'; its `server/` is orphaned.
The audits do not state whether `planSteps()` or the plan-tracking edits proposed here were implemented, landed, or work as designed — that specific proposal is not covered and is not re-verified.
Still open on the surrounding TS live path: it bypasses its own SafetyChecker / PermissionBroker — a high finding that bears directly on any navigation/planning work in that path.
Checked clean on the adjacent Kotlin side: screen reads/actions fail closed on protected apps, and screen actions are bound to the snapshot they were planned from, with stale screens refused.
No claim in the body below should be read as re-checked against the merged tree.

---

# SOLUTION: react_navigate Planning Step Overhaul

## Intent Header

**Problem solved:** The agent can open apps but cannot complete goals inside them. The ReActLoop improvises every turn — the LLM doesn't know what app/screen it's on and has no step plan. This is the #1 capability bottleneck for phone-use autonomy.

**Failure classes addressed:**
- Brain B (Planning failure): No decomposition — complex goals go straight to action selection with no plan
- Phone A (Perception failure): Observation shows TAPPABLE/TYPEABLE/SCROLLABLE elements but zero app/screen context — LLM is flying blind
- Brain F (Autonomy theater): Can "open app" but cannot complete the task inside it

**Files affected:** `src/core/ReActLoop.ts` only. No changes to BrainExecutor.ts — it already passes goal and appHint correctly.

**Expected behavior change:**
- BEFORE: execute() → parseGoal (regex) → loop improvises one action per turn from raw UI tree → fails
- AFTER: execute() → get app context → AI planning call → ordered step list → loop executes steps with plan context → completes goals

---

## Confirmed Facts (from source read)

1. ReActLoop.execute(goal, appHint?) is the entry point. appHint is passed from BrainExecutor via TaskExecutor.
2. The main loop runs up to maxIterations (default 8). Each iteration: try deterministic → if fail, LLM fallback.
3. The LLM fallback prompt has NO app context. It says "You control an Android phone" and shows GOAL + RECENT ACTIONS + SCREEN.
4. parseGoal() is regex-only — classifies as search/tap/type/scroll. Complex goals collapse.
5. executeDeterministic() only handles search well. Everything else falls through to LLM.
6. AppController.getActivePackage() already exists and is called for safety checks — we can reuse it for context.
7. observe() returns TAPPABLE/TYPEABLE/SCROLLABLE sections — clean structured format already.
8. The aiCall callback is already available as this.aiCall — same function used for LLM fallback.

---

## Design

### What changes

**Add 1 new method: `planSteps()`**
- Called once at the start of execute(), after initial observation, before the main loop
- Makes one AI call: "Given this app, this screen, and this goal, produce 3-8 ordered concrete steps"
- Returns string[] of step descriptions
- Only called when allowLLMFallback is true (no AI call in deterministic-only mode)

**Modify the LLM fallback prompt in the main loop**
- Add current app package to the prompt so LLM knows what app it's in
- Add the current plan step so LLM knows what it's trying to do right now
- Keep the action vocabulary and response format identical

**Add plan step tracking to the loop**
- Track currentPlanStep index
- Advance when LLM or deterministic succeeds and UI changes
- If stuck on same plan step for 2 iterations, advance anyway (avoid infinite retry on one step)

### What does NOT change

- executeDeterministic() — still gets first shot every iteration (no regression risk)
- observe() — format stays the same
- executeAction() — action vocabulary stays the same  
- extractAction() — parsing stays the same
- BrainExecutor.ts — no changes needed
- The existing stuck detector — still works as-is
- maxIterations default (8) — unchanged
- Safety checks — unchanged

---

## Exact Edits

### Edit 1: Add `planSteps()` method

Add this new private method to the ReActLoop class, after the `tryGetVisionContext()` method and before `execute()`:

```typescript
  /**
   * One-time planning call: given the goal, current screen, and app context,
   * produce an ordered list of concrete UI steps. Separates planning from
   * execution (AppAgent pattern).
   */
  private async planSteps(goal: string, observation: string, appPackage: string): Promise<string[]> {
    const prompt = `You are planning UI actions on an Android phone.

APP: ${appPackage || 'unknown'}
GOAL: ${goal}

CURRENT SCREEN:
${observation.slice(0, 1200)}

Produce 3-8 concrete UI steps to achieve the goal from this screen.
Each step must be a specific action like "tap the search bar", "type 'query text'", "scroll down to find X", "tap the first result".
Do NOT use vague steps like "find what you need" or "browse around".

Respond with ONLY a JSON array of strings. No explanation. Example:
["tap the search bar", "type 'tokyo flights'", "tap Search button", "scroll down to see results"]`;

    try {
      const response = await this.aiCall(prompt);
      const cleaned = response.replace(/```json|```/g, '').trim();
      const start = cleaned.indexOf('[');
      const end = cleaned.lastIndexOf(']');
      if (start >= 0 && end > start) {
        const parsed = JSON.parse(cleaned.slice(start, end + 1));
        if (Array.isArray(parsed) && parsed.length > 0 && parsed.every((s: any) => typeof s === 'string')) {
          DebugLog.systemEvent('ReActLoop', `PLAN (${parsed.length} steps): ${parsed.join(' → ')}`);
          return parsed;
        }
      }
    } catch (err: any) {
      DebugLog.error('ReActLoop', `Planning failed: ${err.message}`);
    }
    // If planning fails, return empty — loop will run without plan context (current behavior)
    return [];
  }
```

### Edit 2: Add planning call and app context to `execute()`

In the `execute()` method, after the initial observation retry block and `const parsedGoal = this.parseGoal(goal);` line, add:

```typescript
    // ── PLANNING STEP: get app context and produce ordered plan ──────
    let currentAppPackage = '';
    try {
      currentAppPackage = await AppController.getActivePackage() || appHint || '';
    } catch {
      currentAppPackage = appHint || '';
    }

    let plan: string[] = [];
    if (this.allowLLMFallback) {
      plan = await this.planSteps(goal, observation, currentAppPackage);
    }
    let currentPlanStep = 0;
    let stuckOnPlanStep = 0;
```

### Edit 3: Update app context inside the loop

Inside the main loop, after the safety check block (the try/catch that checks for self-interaction), add an app package refresh:

```typescript
      // Refresh app context each iteration
      try {
        currentAppPackage = await AppController.getActivePackage() || currentAppPackage;
      } catch { /* keep previous */ }
```

### Edit 4: Modify the LLM fallback prompt

Replace the existing `systemPrompt` and `userMessage` construction in the LLM fallback section with:

```typescript
      const planContext = plan.length > 0 && currentPlanStep < plan.length
        ? `\nCURRENT STEP (${currentPlanStep + 1}/${plan.length}): ${plan[currentPlanStep]}`
        : '';
      const remainingPlan = plan.length > 0 && currentPlanStep < plan.length
        ? `\nFULL PLAN:\n${plan.map((s, i) => `  ${i < currentPlanStep ? '✓' : i === currentPlanStep ? '→' : ' '} ${i + 1}. ${s}`).join('\n')}`
        : '';

      const systemPrompt = `You control an Android phone. You are inside the app: ${currentAppPackage || 'unknown'}.
Choose ONE action to make progress toward the current step.
ACTIONS YOU CAN USE:
- tap_index(N)   tap element by its index number
- tap(N)         same as tap_index(N)
- type("text")   type text into focused field
- scroll(down)   scroll the screen down
- scroll(up)     scroll the screen up
- back()         press the back button
- done           goal is complete

Respond with ONLY the action. No explanation. No prefix. Just the action.`;

      // Build step history for context (last 4 steps)
      const recentSteps = steps.slice(-4).map(s =>
        `  ${s.action} → ${s.uiChanged ? 'screen changed' : s.actionResult ? 'no visual change' : 'FAILED'}`
      ).join('\n');
      const historyLine = recentSteps ? `\nRECENT ACTIONS:\n${recentSteps}\n` : '';

      const userMessage = `GOAL: ${goal}${planContext}${remainingPlan}${historyLine}\n\nSCREEN:\n${enhancedObservation.slice(0, 1500)}\n\nACTION:`;
```

### Edit 5: Add plan step advancement after action execution

In both the deterministic path AND the LLM fallback path, after the action is executed and uiChanged is computed, add plan step advancement. 

After the deterministic action result block (where `uiChanged` is set and before `continue`):

```typescript
        // Advance plan step on successful UI change
        if (uiChanged && plan.length > 0 && currentPlanStep < plan.length) {
          currentPlanStep++;
          stuckOnPlanStep = 0;
          DebugLog.systemEvent('ReActLoop', `PLAN: advanced to step ${currentPlanStep + 1}/${plan.length}`);
        } else if (plan.length > 0) {
          stuckOnPlanStep++;
          if (stuckOnPlanStep >= 2) {
            currentPlanStep++;
            stuckOnPlanStep = 0;
            DebugLog.systemEvent('ReActLoop', `PLAN: forced advance past stuck step to ${currentPlanStep + 1}/${plan.length}`);
          }
        }
```

After the LLM fallback action result block (same logic, before the stuck detector):

```typescript
      // Advance plan step on successful UI change
      if (uiChanged && plan.length > 0 && currentPlanStep < plan.length) {
        currentPlanStep++;
        stuckOnPlanStep = 0;
        DebugLog.systemEvent('ReActLoop', `PLAN: advanced to step ${currentPlanStep + 1}/${plan.length}`);
      } else if (plan.length > 0) {
        stuckOnPlanStep++;
        if (stuckOnPlanStep >= 2) {
          currentPlanStep++;
          stuckOnPlanStep = 0;
          DebugLog.systemEvent('ReActLoop', `PLAN: forced advance past stuck step to ${currentPlanStep + 1}/${plan.length}`);
        }
      }
```

---

## What Claude Code Must Verify Before Applying

1. **Read the actual ReActLoop.ts** and confirm the line numbers and code structure match this solution. The source was read on 2026-04-01 — no other changes should have been made since.

2. **Confirm appHint propagation**: Trace from BrainExecutor → TaskExecutor.execWithParams('react_navigate', params) → ReActLoop constructor/execute to verify appHint is actually passed through. If it's not, that's a separate fix needed first.

3. **After applying**: Run `npx tsc --noEmit` — must remain at 0 errors.

4. **Grep verification after applying**:
```bash
grep -n "planSteps\|PLAN:" src/core/ReActLoop.ts | head -10
grep -n "currentAppPackage\|APP:" src/core/ReActLoop.ts | head -5
grep -n "CURRENT STEP\|FULL PLAN" src/core/ReActLoop.ts | head -5
```

All three must return matches confirming the planning step, app context, and plan-aware prompt are in place.

5. **Commit** with message: `feat: add planning step to ReActLoop (AppAgent pattern)`

6. **Update DEVLOG.md** with session entry.

---

## Status

**SOURCE-DESIGNED, NOT YET APPLIED**

This solution is designed against the real source (read this session). Claude Code must validate line positions and apply. Runtime proof requires issuing a real phone task and tracing the full perceive→plan→act→verify loop — which requires a device build.

---

## Residual Risks

1. **Planning call adds latency**: One extra AI call before the loop starts (~1-2s). Acceptable tradeoff for dramatically better goal completion.
2. **Plan quality depends on LLM**: If the planning call produces bad steps, the loop may follow a bad plan. Mitigated by: plan steps are guidance not hard constraints — the LLM can still adapt per-turn, and the deterministic path still gets first shot.
3. **Token cost**: Planning prompt + response adds ~500-800 tokens per react_navigate invocation. Acceptable given this is the agent's primary phone-use capability.
4. **Plan goes stale**: If the app state changes unexpectedly mid-plan, the plan may not match reality. Mitigated by: the per-turn prompt still shows the actual current screen, and forced advance after 2 stuck iterations prevents infinite retry.

End of solution.
