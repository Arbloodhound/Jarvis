import assert from "node:assert/strict";
import { createInterface } from "node:readline/promises";
import { PassThrough } from "node:stream";
import { test } from "node:test";
import { TerminalPrompt } from "../src/terminal.ts";

/**
 * The serialized terminal prompt behind `jarvis chat` / `jarvis ask`
 * (bug 1): one line, one taker. A card raised while the chat prompt waits
 * takes the next line; overlapping cards queue and each gets its own line;
 * a withdrawn card hands its turn on; closing the input declines the rest.
 */

function harness() {
  const input = new PassThrough();
  const output = new PassThrough();
  let printed = "";
  output.on("data", (d) => (printed += d));
  const rl = createInterface({ input, output, terminal: false });
  const term = new TerminalPrompt(rl, output);
  const type = (line: string) => input.write(`${line}\n`);
  const tick = () => new Promise((r) => setTimeout(r, 10));
  return { term, type, tick, input, out: () => printed };
}

test("a card raised while the chat prompt waits takes the next line; the prompt then takes the one after", async () => {
  const h = harness();
  const chat = h.term.ask("you › ");
  const card = h.term.card("c1", "? run_command (via subagent \"x\")\n", "Allow? [y/N] ");
  h.type("y");
  assert.equal(await card, "y", "the card got the owner's answer");
  h.type("next request");
  assert.equal(await chat, "next request", "the chat prompt got the next line, not the card's answer");
  const out = h.out();
  assert.ok(out.indexOf("Allow? [y/N]") > out.indexOf("(via subagent"), "the card's text then its question were shown");
  assert.ok(out.lastIndexOf("you › ") > out.indexOf("Allow? [y/N]"), "the chat prompt was shown again after the card");
});

test("overlapping cards (main + child) queue: each is shown in turn and gets its own line", async () => {
  const h = harness();
  const a = h.term.card("main", "? main card\n", "Allow A? ");
  const b = h.term.card("kid", "? child card\n", "Allow B? ");
  await h.tick();
  assert.ok(h.out().includes("Allow A?") && !h.out().includes("Allow B?"), "only the head card is up");
  h.type("y");
  assert.equal(await a, "y");
  await h.tick();
  assert.ok(h.out().includes("Allow B?"), "the second card is shown after the first is answered");
  h.type("n");
  assert.equal(await b, "n");
});

test("a withdrawn card frees the prompt; lines typed with nothing asking are dropped, never held for a later card", async () => {
  const h = harness();
  const a = h.term.card("gone", "? stale card\n", "Allow? ");
  const b = h.term.card("live", "? live card\n", "Allow live? ");
  assert.equal(h.term.withdraw("gone", "(withdrawn)"), true);
  assert.equal(await a, null);
  h.type("y");
  assert.equal(await b, "y", "the live card got the line");
  h.type("y"); // nobody is asking: dropped
  await h.tick();
  const c = h.term.card("later", "? later card\n", "Allow later? ");
  h.type("n");
  assert.equal(await c, "n", "the earlier stray 'y' did not answer the later card");
});

test("closing the input settles open cards and the prompt with null (declined / end of input)", async () => {
  const h = harness();
  const chat = h.term.ask("you › ");
  const card = h.term.card("c", "? card\n", "Allow? ");
  h.input.end();
  assert.equal(await card, null);
  assert.equal(await chat, null);
  assert.equal(h.term.closed, true);
  assert.equal(await h.term.card("after", "x", "y"), null, "a card after close is declined at once");
});
