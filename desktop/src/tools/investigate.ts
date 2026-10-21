import { z } from "zod";
import type { ToolPolicy } from "../gate/gate.js";
import {
  CaseStore, LEGS, LEG_LABELS, gatherLeg, summarizeCase, type Leg,
} from "../investigate.js";
import { htmlToText, runWebSearch, safeFetch, type Tool } from "./index.js";

/**
 * The investigate tools. Case CRUD is local-file only (storage under the app
 * home, like memory.ts); the only network tool is investigate_run, which is
 * marked `network` so offline-only withholds the auto-gather while the case
 * tools keep working, and which reaches the web solely through the existing
 * safeFetch / runWebSearch machinery (SSRF guard, provider fallback chain).
 *
 * investigate_draft is structurally incapable of sending: its policy has no
 * egress effect, it calls only CaseStore.addDraft, and neither the store nor
 * this file contains a send path. Approval state on a draft is bookkeeping
 * for the owner, who sends (or doesn't) themselves, outside Jarvis.
 */

function tool<S extends z.ZodType>(t: {
  name: string;
  description: string;
  schema: S;
  policy: ToolPolicy;
  outside?: boolean;
  parallelSafe?: boolean;
  network?: boolean;
  run(args: z.infer<S>, ctx: { home: string; signal: AbortSignal }): Promise<string>;
}): Tool {
  return t as unknown as Tool;
}

const store = (home: string) => new CaseStore(home);

const caseArg = z.string().min(1).describe("Case slug from investigate_new (e.g. \"acme-corp\")");
const legArg = z.enum(LEGS).describe("identity = web identity/footprint, records = official records/registries, social = social/public mentions, documents = documents/publications");

export const investigateNewTool = tool({
  name: "investigate_new",
  description: "Start an investigation case file on a person, company, domain or other target. Creates cases/<slug>/ under Jarvis's home and returns the slug. Research only: findings need sources, outreach is drafts only.",
  schema: z.object({
    target: z.string().min(1).max(300),
    kind: z.enum(["person", "company", "domain", "other"]).optional(),
  }),
  policy: { effects: ["mutate"], lastsBeyondRequest: true },
  async run({ target, kind }, ctx) {
    const c = store(ctx.home).create(target, kind ?? "other");
    return `Created investigation case ${c.slug} for "${c.target}" (${c.targetKind}).\nLegs: ${LEGS.map((l) => `${l} (${LEG_LABELS[l]})`).join("; ")} — none run yet.\nAdd evidence with investigate_evidence, gather with investigate_run, compile with investigate_report.`;
  },
});

export const investigateCasesTool = tool({
  name: "investigate_cases",
  description: "List investigation cases, or open one case (pass its slug) for its summary: legs, graded evidence counts, contacts and drafts.",
  schema: z.object({ case: caseArg.optional(), close: z.boolean().optional().describe("With a case slug: mark the case closed") }),
  policy: { effects: ["read"] },
  parallelSafe: true,
  async run({ case: slug, close }, ctx) {
    const s = store(ctx.home);
    if (!slug) {
      const all = s.list();
      return all.length ? all.map(summarizeCase).join("\n\n") : "No investigation cases yet. Start one with investigate_new.";
    }
    if (close) return summarizeCase(s.setStatus(slug, "closed"));
    return summarizeCase(s.load(slug));
  },
});

export const investigateEvidenceTool = tool({
  name: "investigate_evidence",
  description: "Add an evidence entry to a case (claim + source URL + fetched excerpt + grade: confirmed/likely/unverified), list a case's evidence, or promote an entry's grade. A claim without a source URL is refused; people-search/data-broker sources are out of scope.",
  schema: z.object({
    case: caseArg,
    action: z.enum(["add", "list", "promote"]).optional(),
    leg: legArg.optional(),
    claim: z.string().max(2000).optional(),
    source_url: z.string().max(2000).optional(),
    excerpt: z.string().max(1200).optional(),
    grade: z.enum(["confirmed", "likely", "unverified"]).optional(),
    evidence_id: z.string().optional().describe("For promote: the entry id"),
  }),
  policy: { effects: ["mutate"], lastsBeyondRequest: true },
  async run({ case: slug, action = "add", leg, claim, source_url, excerpt, grade, evidence_id }, ctx) {
    const s = store(ctx.home);
    if (action === "list") {
      const c = s.load(slug);
      const rows = leg ? c.evidence.filter((e) => e.leg === leg) : c.evidence;
      return rows.length
        ? rows.map((e) => `[${e.grade}] (${e.leg}, ${e.id}) ${e.claim}\n  Source: ${e.sourceUrl}${e.excerpt ? `\n  Excerpt: ${e.excerpt}` : ""}`).join("\n")
        : `No evidence${leg ? ` in leg ${leg}` : ""} in case ${c.slug} yet.`;
    }
    if (action === "promote") {
      if (!evidence_id || !grade) throw new Error("promote needs evidence_id and grade.");
      const e = s.setGrade(slug, evidence_id, grade);
      return `Evidence ${e.id} in case ${slug} is now graded ${e.grade}: ${e.claim} (source: ${e.sourceUrl}).`;
    }
    if (!leg || !claim || !source_url) throw new Error("add needs leg, claim and source_url.");
    const e = s.addEvidence(slug, { leg, claim, sourceUrl: source_url, excerpt, grade });
    return `Added evidence ${e.id} to case ${slug} (${e.leg}, ${e.grade}): ${e.claim}\nSource: ${e.sourceUrl}`;
  },
});

export const investigateContactsTool = tool({
  name: "investigate_contacts",
  description: "Add or list a case's contacts (email, handle, pgp, phone, name_role, other). Every contact needs the URL it was found on; duplicates and data-broker sources are refused.",
  schema: z.object({
    case: caseArg,
    action: z.enum(["add", "list"]).optional(),
    kind: z.enum(["email", "handle", "pgp", "phone", "name_role", "other"]).optional(),
    value: z.string().max(500).optional(),
    provenance_url: z.string().max(2000).optional().describe("The page the contact was found on"),
    provenance_snippet: z.string().max(300).optional(),
  }),
  policy: { effects: ["mutate"], lastsBeyondRequest: true },
  async run({ case: slug, action = "add", kind, value, provenance_url, provenance_snippet }, ctx) {
    const s = store(ctx.home);
    if (action === "list") {
      const c = s.load(slug);
      return c.contacts.length
        ? c.contacts.map((ct) => `${ct.kind}: ${ct.value} — found on ${ct.provenanceUrl}${ct.provenanceSnippet ? ` ("${ct.provenanceSnippet}")` : ""}`).join("\n")
        : `No contacts in case ${c.slug} yet.`;
    }
    if (!kind || !value || !provenance_url) throw new Error("add needs kind, value and provenance_url.");
    const { contact, added } = s.addContact(slug, { kind, value, provenanceUrl: provenance_url, provenanceSnippet: provenance_snippet });
    return added
      ? `Added contact ${contact.kind}: ${contact.value} to case ${slug} (found on ${contact.provenanceUrl}).`
      : `Already in case ${slug}: ${contact.kind}: ${contact.value} (not duplicated).`;
  },
});

export const investigateDraftTool = tool({
  name: "investigate_draft",
  description: "Write an outreach DRAFT into a case, list drafts, or record the owner's per-recipient approval decision. DRAFT ONLY: this tool cannot send anything — there is no send path; the draft stays in the case file, unapproved by default, and the owner sends it themselves if they choose. The recipient must already be a contact in the case. Placeholders {value}, {kind}, {query}, {source_url} are filled from the contact.",
  schema: z.object({
    case: caseArg,
    action: z.enum(["create", "list", "approve", "unapprove"]).optional(),
    to: z.string().max(500).optional().describe("The contact value to address (must be a contact in the case)"),
    channel: z.string().max(80).optional(),
    subject: z.string().max(500).optional(),
    body: z.string().max(5000).optional(),
    draft_id: z.string().optional().describe("For approve/unapprove"),
  }),
  // No egress effect, deliberately: drafting must never be a way to send.
  policy: { effects: ["mutate"], lastsBeyondRequest: true },
  async run({ case: slug, action = "create", to, channel, subject, body, draft_id }, ctx) {
    const s = store(ctx.home);
    if (action === "list") {
      const c = s.load(slug);
      return c.drafts.length
        ? c.drafts.map((d) => `Draft ${d.id} to ${d.to} via ${d.channel} — approval: ${d.approved ? "approved by owner" : "unapproved"}; status: draft — never sent by Jarvis\n  Subject: ${d.subject}\n  ${d.body.slice(0, 300)}`).join("\n")
        : `No drafts in case ${c.slug}.`;
    }
    if (action === "approve" || action === "unapprove") {
      if (!draft_id) throw new Error(`${action} needs draft_id.`);
      const d = s.setDraftApproval(slug, draft_id, action === "approve");
      return `Draft ${d.id} to ${d.to} is now ${d.approved ? "marked approved by the owner" : "unapproved"}. Nothing was sent — drafts are never sent by Jarvis; the owner sends it themselves if they choose.`;
    }
    if (!to) throw new Error("create needs `to` (a contact value already in the case).");
    const d = s.addDraft(slug, { to, channel, subject: subject ?? "", body: body ?? "" });
    return `Draft ${d.id} written into case ${slug} for ${d.to} via ${d.channel}. Approval: unapproved. Status: draft — never sent by Jarvis.`;
  },
});

export const investigateReportTool = tool({
  name: "investigate_report",
  description: "Compile a case into its graded report: findings grouped by leg with evidence grades and source URLs, contacts with provenance, and drafts with their approval state. Writes report.md into the case folder and returns it.",
  schema: z.object({ case: caseArg }),
  policy: { effects: ["mutate"], lastsBeyondRequest: true },
  async run({ case: slug }, ctx) {
    return store(ctx.home).compileReport(slug);
  },
});

export const investigateRunTool = tool({
  name: "investigate_run",
  description: "Auto-gather one leg of a case from the live web (the app's search + safe fetch; at most ~6 fetches). Candidate results enter the case as UNVERIFIED evidence — grades are promoted later, never by the gatherer. The identity leg also extracts contacts (emails, handles, PGP) from the pages it reads, each with provenance. Legs: identity, records, social, documents. Failures are recorded on the leg, not hidden.",
  schema: z.object({
    case: caseArg,
    leg: legArg,
  }),
  policy: { effects: ["egress", "mutate"], lastsBeyondRequest: true },
  outside: true,
  network: true,
  async run({ case: slug, leg }, ctx) {
    const s = store(ctx.home);
    const out = await gatherLeg(s, slug, leg as Leg, {
      search: async (q) => (await runWebSearch(q, ctx.signal)).results,
      fetchText: async (url) => {
        const res = await safeFetch(url, ctx.signal);
        if (res.status >= 400) throw new Error(`HTTP ${res.status}`);
        return res.type.includes("html") ? htmlToText(res.body) : res.body;
      },
    });
    const c = s.load(slug);
    return `Leg ${out.leg} (${LEG_LABELS[out.leg]}) for case ${c.slug}: ${out.status} — ${out.note}. Fetches used: ${out.fetches} (bounded).\nCase now holds ${c.evidence.length} evidence entries and ${c.contacts.length} contacts. All gathered evidence is UNVERIFIED until promoted with its source attached.`;
  },
});

export const INVESTIGATE_TOOLS: Tool[] = [
  investigateNewTool, investigateCasesTool, investigateEvidenceTool,
  investigateContactsTool, investigateDraftTool, investigateReportTool, investigateRunTool,
];
