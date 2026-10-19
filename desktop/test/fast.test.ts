import assert from "node:assert/strict";
import { test } from "node:test";
import { fastCommand } from "../src/agent/fast.ts";
import { ThinkSplitter } from "../src/providers/local.ts";

test("fast commands", () => {
  assert.deepEqual(fastCommand("open github.com"), { kind: "tool", tool: "open_url", args: { url: "https://github.com" } });
  assert.deepEqual(fastCommand("Open Spotify"), { kind: "tool", tool: "open_app", args: { name: "Spotify" } });
  assert.equal(fastCommand("remember that my sister is Ana")?.kind, "tool");
  assert.equal(fastCommand("what time is it?")?.kind, "answer");
  assert.equal(fastCommand("take a screenshot")?.kind, "tool");
  assert.equal(fastCommand("open spotify and then play jazz"), null);
  assert.equal(fastCommand("open my tax file"), null);
  assert.equal(fastCommand("why is the sky blue?"), null);
});

test("think tags are split across chunk borders", () => {
  let thought = "";
  let said = "";
  const s = new ThinkSplitter((t) => (said += t), (t) => (thought += t));
  for (const c of ["<thi", "nk>plan it", "</th", "ink>Hello", " there"]) s.push(c);
  s.flush();
  assert.equal(thought, "plan it");
  assert.equal(said, "Hello there");
});
