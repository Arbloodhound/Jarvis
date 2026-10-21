import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { runInNewContext } from "node:vm";
import { test } from "node:test";

/**
 * The chat page's event rendering, run for real in a tiny stand-in DOM (the
 * page's own script, unmodified, in a vm context). Bug 5: the page kept ONE
 * current run box, so a subagent's events arriving mid-reply opened a new
 * box and the main reply's remaining text landed in yet another one — the
 * main answer was split up. A child's events belong in their own labelled
 * box; the main reply box stays whole; cards keep working.
 */

const HTML = readFileSync(path.join(path.dirname(fileURLToPath(import.meta.url)), "..", "src", "ui", "index.html"), "utf8");
const SCRIPT = HTML.slice(HTML.lastIndexOf("<script>") + "<script>".length, HTML.lastIndexOf("</script>"));

class FakeEl {
  children: FakeEl[] = [];
  parent: FakeEl | null = null;
  className = "";
  private _id = "";
  private _text = "";
  hidden = false;
  value = "";
  checked = false;
  disabled = false;
  type = "";
  title = "";
  dataset: Record<string, string> = {};
  style: Record<string, unknown> = {};
  scrollTop = 0; clientHeight = 0; scrollHeight = 0;
  onclick: unknown = null; onchange: unknown = null; onsubmit: unknown = null;
  constructor(readonly tag: string, private readonly doc: FakeDoc) {}
  get id() { return this._id; }
  set id(v: string) { this._id = v; this.doc.ids.set(v, this); }
  get textContent(): string { return this._text + this.children.map((c) => c.textContent).join(""); }
  set textContent(v: string) { this._text = String(v); this.children = []; }
  get classList() {
    const set = () => new Set(this.className.split(/\s+/).filter(Boolean));
    return {
      add: (c: string) => { const s = set(); s.add(c); this.className = [...s].join(" "); },
      remove: (c: string) => { const s = set(); s.delete(c); this.className = [...s].join(" "); },
      toggle: (c: string, on?: boolean) => { const s = set(); if (on ?? !s.has(c)) s.add(c); else s.delete(c); this.className = [...s].join(" "); },
      contains: (c: string) => set().has(c),
    };
  }
  get firstChild() { return this.children[0] ?? null; }
  appendChild(c: FakeEl) { c.parent?.children.splice(c.parent.children.indexOf(c), 1); c.parent = this; this.children.push(c); return c; }
  replaceChildren(...cs: FakeEl[]) { this.children = []; for (const c of cs) this.appendChild(c); }
  remove() { if (this.parent) this.parent.children.splice(this.parent.children.indexOf(this), 1); this.parent = null; if (this._id) this.doc.ids.delete(this._id); }
  all(): FakeEl[] { return this.children.flatMap((c) => [c, ...c.all()]); }
  querySelectorAll(sel: string): FakeEl[] {
    return this.all().filter((e) => (sel.startsWith(".") ? e.classList.contains(sel.slice(1)) : e.tag === sel));
  }
  querySelector(sel: string) { return this.querySelectorAll(sel)[0] ?? null; }
  addEventListener() {}
  focus() {} select() {} click() {} requestSubmit() {}
}

class FakeDoc {
  ids = new Map<string, FakeEl>();
  createElement(tag: string) { return new FakeEl(tag, this); }
  getElementById(id: string) { return this.ids.get(id) ?? null; }
}

function loadPage() {
  const doc = new FakeDoc();
  for (const id of ["log", "main", "input", "send", "status", "b-local", "b-cloud", "offline", "embed", "screenctl", "tor", "vision", "model",
    "branch", "delbranch", "new", "fork", "form", "chips", "attach", "fileinput", "footer", "picker", "picker-input", "picker-list", "picker-note", "picker-title"]) {
    doc.createElement("div").id = id;
  }
  const empty = doc.createElement("p"); empty.id = "empty"; doc.getElementById("log")!.appendChild(empty);
  const sent: unknown[] = [];
  let socket: { onmessage?: (m: { data: string }) => void; send: (s: string) => void; readyState: number } | null = null;
  class FakeWS {
    readyState = 1;
    onmessage?: (m: { data: string }) => void;
    onclose?: () => void;
    constructor() { socket = this; }
    send(s: string) { sent.push(JSON.parse(s)); }
  }
  runInNewContext(SCRIPT, {
    document: doc,
    location: { hash: "#token=t", host: "127.0.0.1:1", pathname: "/" },
    history: { replaceState() {} },
    sessionStorage: { setItem() {}, getItem() { return null; } },
    WebSocket: FakeWS,
    URLSearchParams,
    setTimeout,
    JSON,
  });
  const fire = (e: Record<string, unknown>) => socket!.onmessage!({ data: JSON.stringify(e) });
  const log = doc.getElementById("log")!;
  return { doc, log, fire, sent };
}

const route = { tier: "light", mode: "heuristic", reason: "x" };

test("bug 5: a subagent's events render in their own labelled box; the main reply stays in one box", () => {
  const { doc, log, fire, sent } = loadPage();
  fire({ type: "state", busy: false, branches: [] });
  fire({ type: "run_start", runId: "main1", brain: "Local", route });
  fire({ type: "text", runId: "main1", delta: "Hello " });
  const child = { id: "agent:1234abcd", name: "prices" };
  fire({ type: "run_start", runId: "kid1", brain: "Local", route, agent: child });
  fire({ type: "text", runId: "kid1", delta: "child working" });
  fire({ type: "tool_start", runId: "kid1", id: "t1", name: "web_search", args: { q: "x" }, agent: child });
  fire({ type: "confirm", runId: "kid1", cardId: "card1", tool: "write_file", args: {}, rule: "r", reason: "write it (via subagent \"prices\")", agent: child });
  fire({ type: "text", runId: "main1", delta: "world" });
  fire({ type: "done", runId: "kid1", text: "child working", agent: child });
  fire({ type: "text", runId: "main1", delta: "!" });
  fire({ type: "done", runId: "main1", text: "Hello world!" });

  const boxes = log.children.filter((c) => c.classList.contains("assistant"));
  const mainBoxes = boxes.filter((b) => b.textContent.includes("Hello"));
  assert.equal(mainBoxes.length, 1, "the main reply is one box");
  assert.ok(mainBoxes[0]!.textContent.includes("Hello world!"), `the main reply is intact: ${mainBoxes[0]!.textContent}`);
  assert.ok(!mainBoxes[0]!.textContent.includes("child working"), "no child text inside the main reply");
  const kidBox = boxes.find((b) => b.textContent.includes("child working"));
  assert.ok(kidBox && kidBox !== mainBoxes[0], "the child has its own box");
  assert.ok(kidBox!.textContent.includes("prices"), "the child's box is labelled with its name");
  assert.equal(boxes.length, 2, `exactly two boxes, got ${boxes.map((b) => JSON.stringify(b.textContent)).join(" | ")}`);

  // The child's card lives in the child's box and still answers.
  const card = doc.getElementById("card-card1");
  assert.ok(card, "the child's card rendered");
  assert.ok(kidBox!.all().includes(card!), "the card is in the child's box");
  const allow = card!.querySelectorAll("button").find((b) => b.textContent === "Allow once")!;
  (allow.onclick as () => void)();
  assert.deepEqual(sent.at(-1), { type: "confirm", cardId: "card1", allow: true });
});

test("bug 3: a hand-off notice is shown to the owner", () => {
  const { log, fire } = loadPage();
  fire({ type: "handoff", agent: { id: "agent:1", name: "prices" }, state: "done", task: "find prices", summary: "three shops" });
  assert.ok(log.textContent.includes("prices"), log.textContent);
  assert.ok(/finished|done/i.test(log.textContent), log.textContent);
});
