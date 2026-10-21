import assert from "node:assert/strict";
import { existsSync, mkdtempSync, readFileSync, readdirSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import {
  CaseStore, extractContacts, gatherLeg, isBrokerUrl, legQueries, renderReport, slugify,
  type SearchHit,
} from "../src/investigate.ts";
import { ALL_TOOLS, toolsFor } from "../src/tools/index.ts";
import { investigateDraftTool } from "../src/tools/investigate.ts";

const home = () => mkdtempSync(path.join(tmpdir(), "jarvis-cases-"));
const ctxFor = (h: string) => ({
  cwd: h, home: h, workspace: path.join(h, "workspace"),
  memory: null, embedder: null, vision: null, signal: new AbortController().signal,
}) as never;

test("slugify: target becomes a safe folder name", () => {
  assert.equal(slugify("Acme Corp, Inc."), "acme-corp-inc");
  assert.equal(slugify("../../etc/passwd"), "etc-passwd");
  assert.equal(slugify("!!!"), "case");
});

test("slugify: accented targets fold to ASCII, so the case can be created and reopened", async () => {
  assert.equal(slugify("Müller GmbH"), "muller-gmbh");
  assert.match(slugify("Ærøskøbing Café"), /^[a-z0-9][a-z0-9-]*$/);
  const h = home();
  const c = new CaseStore(h).create("Müller GmbH", "company");
  assert.equal(c.slug, "muller-gmbh");
  assert.equal(new CaseStore(h).load(c.slug).target, "Müller GmbH");
  const tool = ALL_TOOLS.find((t) => t.name === "investigate_new")!;
  const out = await tool.run({ target: "Zoë Ångström", kind: "person" }, ctxFor(h));
  assert.match(out, /Created investigation case zoe-angstrom /);
});

test("slugify: a target with no ASCII letters gets a stable short hash slug", () => {
  const s = slugify("東京電力");
  assert.match(s, /^case-[0-9a-f]{8}$/);
  assert.equal(slugify("東京電力"), s, "stable");
  assert.notEqual(slugify("大阪ガス"), s, "distinct targets get distinct slugs");
  const h = home();
  const c = new CaseStore(h).create("東京電力", "company");
  assert.equal(c.slug, s);
  assert.equal(new CaseStore(h).load(s).target, "東京電力");
  assert.throws(() => new CaseStore(h).load("東京電力"), /No case named/, "assertSlug still refuses non-ASCII names");
});

test("case create/open/list round-trips through cases/<slug>/case.json", () => {
  const h = home();
  const s = new CaseStore(h);
  const c = s.create("Acme Corp", "company");
  assert.equal(c.slug, "acme-corp");
  assert.equal(c.status, "open");
  assert.ok(existsSync(path.join(h, "cases", "acme-corp", "case.json")));
  assert.ok(existsSync(path.join(h, "cases", "acme-corp", "evidence")));
  // A second case with the same target gets its own slug.
  assert.equal(s.create("Acme Corp", "company").slug, "acme-corp-2");
  const listed = new CaseStore(h).list();
  assert.equal(listed.length, 2);
  assert.equal(new CaseStore(h).load("acme-corp").target, "Acme Corp");
  assert.throws(() => s.load("../secret"), /No case named/);
  assert.throws(() => s.load("nope"), /No case named/);
});

test("evidence: a claim without a source URL is refused; broker sources are refused", () => {
  const s = new CaseStore(home());
  const c = s.create("Acme Corp", "company");
  assert.throws(() => s.addEvidence(c.slug, { leg: "identity", claim: "x", sourceUrl: "" }), /no source URL/);
  assert.throws(() => s.addEvidence(c.slug, { leg: "identity", claim: "x", sourceUrl: "not a url" }), /not a URL/);
  assert.throws(
    () => s.addEvidence(c.slug, { leg: "identity", claim: "x", sourceUrl: "https://www.spokeo.com/acme" }),
    /out of scope/,
  );
  assert.equal(isBrokerUrl("https://sub.whitepages.com/x"), true);
  assert.equal(isBrokerUrl("https://example.com/"), false);
});

test("evidence: entries persist with grade + excerpt, one file each, and grades promote", () => {
  const h = home();
  const s = new CaseStore(h);
  const c = s.create("Acme Corp", "company");
  const e = s.addEvidence(c.slug, {
    leg: "records", claim: "Incorporated in Delaware", sourceUrl: "https://example.gov/acme",
    excerpt: "ACME CORP, file no. 123", grade: "likely",
  });
  assert.equal(e.grade, "likely");
  const file = path.join(h, "cases", c.slug, "evidence", `${e.id}.txt`);
  assert.ok(existsSync(file));
  assert.match(readFileSync(file, "utf8"), /Source: https:\/\/example\.gov\/acme/);
  const promoted = s.setGrade(c.slug, e.id, "confirmed");
  assert.equal(promoted.grade, "confirmed");
  assert.equal(new CaseStore(h).load(c.slug).evidence[0]!.grade, "confirmed");
  assert.throws(() => s.setGrade(c.slug, "missing", "confirmed"), /No evidence entry/);
});

test("contacts: provenance required, duplicates collapse, broker sources refused", () => {
  const s = new CaseStore(home());
  const c = s.create("Acme Corp", "company");
  assert.throws(() => s.addContact(c.slug, { kind: "email", value: "a@acme.test", provenanceUrl: "" }), /no provenance/);
  assert.throws(
    () => s.addContact(c.slug, { kind: "email", value: "a@acme.test", provenanceUrl: "https://beenverified.com/x" }),
    /out of scope/,
  );
  const first = s.addContact(c.slug, { kind: "email", value: "Press@Acme.test", provenanceUrl: "https://acme.test/contact" });
  assert.equal(first.added, true);
  const dup = s.addContact(c.slug, { kind: "email", value: "press@acme.test", provenanceUrl: "https://acme.test/other" });
  assert.equal(dup.added, false);
  assert.equal(s.load(c.slug).contacts.length, 1);
});

test("contact extraction: emails, handles, PGP and name-roles from page text, image 'emails' skipped", () => {
  const page = [
    "Jane Doe, Press Lead. Reach her at press@acme.test or call +1 415 555 0132.",
    "Follow @acmeteam for updates. Our key: -----BEGIN PGP PUBLIC KEY BLOCK-----",
    "Logo at logo@2x.png is not mail. Dated 2026-10-20.",
  ].join("\n");
  const found = extractContacts(page, "https://acme.test/contact");
  const by = (k: string) => found.filter((x) => x.kind === k).map((x) => x.value);
  assert.deepEqual(by("email"), ["press@acme.test"]);
  assert.deepEqual(by("handle"), ["@acmeteam"]);
  assert.deepEqual(by("pgp"), ["PGP block present"]);
  assert.ok(by("phone").some((v) => v.includes("415")));
  assert.ok(by("name_role").some((v) => v.startsWith("Jane Doe")));
});

test("drafts: draft-only by construction — unapproved, unsent, placeholders filled, no send anywhere", () => {
  const s = new CaseStore(home());
  const c = s.create("Acme Corp", "company");
  // A recipient Jarvis can't trace is refused.
  assert.throws(() => s.addDraft(c.slug, { to: "ghost@acme.test", subject: "hi", body: "x" }), /not a contact/);
  s.addContact(c.slug, { kind: "email", value: "press@acme.test", provenanceUrl: "https://acme.test/contact" });
  const d = s.addDraft(c.slug, {
    to: "press@acme.test", subject: "About {query}", body: "Hello {value} ({kind}), found via {source_url}.",
  });
  assert.equal(d.approved, false);
  assert.equal(d.sent, false);
  assert.equal(d.status, "draft");
  assert.equal(d.subject, "About Acme Corp");
  assert.equal(d.body, "Hello press@acme.test (email), found via https://acme.test/contact.");
  assert.throws(() => s.addDraft(c.slug, { to: "press@acme.test", subject: "{bogus}", body: "x" }), /unknown placeholder/);
  // Approval is a state change only; the draft stays unsent.
  const approved = s.setDraftApproval(c.slug, d.id, true);
  assert.equal(approved.approved, true);
  assert.equal(approved.sent, false);
  // Structural: neither the store nor the draft tool exposes any send path.
  assert.equal((s as unknown as Record<string, unknown>).send, undefined);
  assert.equal((s as unknown as Record<string, unknown>).sendDraft, undefined);
  assert.ok(!investigateDraftTool.policy.effects.includes("egress"), "draft tool must have no egress effect");
});

test("report: findings grouped by leg with grades and sources; drafts shown with approval state", () => {
  const s = new CaseStore(home());
  const c = s.create("Acme Corp", "company");
  s.addEvidence(c.slug, { leg: "identity", claim: "Runs acme.test", sourceUrl: "https://acme.test/", grade: "confirmed" });
  s.addEvidence(c.slug, { leg: "documents", claim: "Published a whitepaper", sourceUrl: "https://acme.test/paper.pdf" });
  s.addContact(c.slug, { kind: "handle", value: "@acmeteam", provenanceUrl: "https://acme.test/contact" });
  s.addDraft(c.slug, { to: "@acmeteam", subject: "Hello", body: "Draft body" });
  const md = renderReport(s.load(c.slug));
  assert.match(md, /# Investigation report — Acme Corp/);
  assert.match(md, /### Web identity & footprint/);
  assert.match(md, /\[confirmed\] Runs acme\.test/);
  assert.match(md, /Source: https:\/\/acme\.test\/paper\.pdf/);
  assert.match(md, /\[unverified\] Published a whitepaper/);
  assert.match(md, /handle: @acmeteam/);
  assert.match(md, /approval: unapproved; status: draft — never sent by Jarvis/);
  // compileReport writes report.md into the case folder.
  const written = s.compileReport(c.slug);
  assert.equal(written, md);
});

test("leg queries: two bounded queries per leg, target quoted where it matters", () => {
  for (const leg of ["identity", "records", "social", "documents"] as const) {
    assert.equal(legQueries(leg, "Acme Corp").length, 2);
  }
  assert.deepEqual(legQueries("documents", "Acme Corp")[0], '"Acme Corp" filetype:pdf');
});

test("gatherLeg: results become UNVERIFIED evidence, identity leg extracts contacts, fetches bounded", async () => {
  const s = new CaseStore(home());
  const c = s.create("Acme Corp", "company");
  let fetches = 0;
  const hits: SearchHit[] = [
    { title: "Acme Corp — official site", url: "https://acme.test/", snippet: "Acme makes widgets." },
    { title: "Acme on a broker", url: "https://www.spokeo.com/acme", snippet: "broker noise" },
    { title: "Acme profile", url: "https://profiles.test/acme", snippet: "Acme profile page." },
  ];
  const out = await gatherLeg(s, c.slug, "identity", {
    search: async () => { fetches++; return hits; },
    fetchText: async (url) => {
      fetches++;
      return url === "https://acme.test/" ? "Contact press@acme.test, follow @acmeteam" : "Nothing here.";
    },
  });
  assert.equal(out.status, "ok");
  assert.equal(out.evidenceAdded, 2); // broker hit skipped
  assert.ok(out.fetches <= 6, `fetches ${out.fetches} within bound`);
  assert.equal(fetches, out.fetches);
  const after = s.load(c.slug);
  assert.ok(after.evidence.every((e) => e.grade === "unverified"));
  assert.deepEqual(after.contacts.map((x) => x.value).sort(), ["@acmeteam", "press@acme.test"]);
  assert.ok(after.contacts.every((x) => x.provenanceUrl === "https://acme.test/"));
  assert.equal(after.legs.find((l) => l.leg === "identity")!.status, "ok");
});

test("gatherLeg: dedupe is per-leg — a source already used by another leg still counts here", async () => {
  const s = new CaseStore(home());
  const c = s.create("Acme Corp", "company");
  const deps = {
    search: async (): Promise<SearchHit[]> => [{ title: "Acme official", url: "https://acme.test/", snippet: "Acme." }],
    fetchText: async () => "Nothing here.",
  };
  const first = await gatherLeg(s, c.slug, "identity", deps);
  assert.equal(first.evidenceAdded, 1);
  const second = await gatherLeg(s, c.slug, "records", deps);
  assert.equal(second.evidenceAdded, 1); // same URL, different leg: not starved
  const again = await gatherLeg(s, c.slug, "records", deps);
  assert.equal(again.evidenceAdded, 0); // but a re-run of the same leg dedupes
  assert.equal(s.load(c.slug).evidence.length, 2);
});

test("gatherLeg: a failing search is recorded on the leg, not hidden, and adds nothing", async () => {
  const s = new CaseStore(home());
  const c = s.create("Acme Corp", "company");
  const out = await gatherLeg(s, c.slug, "records", {
    search: async () => { throw new Error("network down"); },
    fetchText: async () => "",
  });
  assert.equal(out.status, "error");
  assert.equal(out.evidenceAdded, 0);
  const leg = s.load(c.slug).legs.find((l) => l.leg === "records")!;
  assert.match(leg.note, /search failed/);
});

test("gatherLeg: non-identity legs never fetch pages", async () => {
  const s = new CaseStore(home());
  const c = s.create("Acme Corp", "company");
  let pageFetches = 0;
  const out = await gatherLeg(s, c.slug, "documents", {
    search: async () => [{ title: "Paper", url: "https://acme.test/p.pdf", snippet: "A paper." }],
    fetchText: async () => { pageFetches++; return ""; },
  });
  assert.equal(pageFetches, 0);
  assert.equal(out.fetches, 2); // the two searches only
  assert.equal(out.evidenceAdded, 1);
});

test("tools: registered, and offline-only withholds only the auto-gather", async () => {
  const names = ALL_TOOLS.map((t) => t.name);
  for (const n of ["investigate_new", "investigate_cases", "investigate_evidence", "investigate_contacts", "investigate_draft", "investigate_report", "investigate_run"]) {
    assert.ok(names.includes(n), `${n} registered`);
  }
  const offline = toolsFor({ offlineOnly: true, screenControl: false }).map((t) => t.name);
  assert.ok(!offline.includes("investigate_run"));
  assert.ok(offline.includes("investigate_new"));
  assert.ok(offline.includes("investigate_draft"));
  // The tools work end to end against a temp home.
  const h = home();
  const ctx = ctxFor(h);
  const byName = new Map(ALL_TOOLS.map((t) => [t.name, t]));
  const created = await byName.get("investigate_new")!.run({ target: "Acme Corp", kind: "company" }, ctx);
  assert.match(created, /acme-corp/);
  const listed = await byName.get("investigate_cases")!.run({}, ctx);
  assert.match(listed, /Acme Corp/);
  const report = await byName.get("investigate_report")!.run({ case: "acme-corp" }, ctx);
  assert.match(report, /# Investigation report — Acme Corp/);
  assert.ok(existsSync(path.join(h, "cases", "acme-corp", "report.md")));
  assert.equal(readdirSync(path.join(h, "cases")).length, 1);
});
