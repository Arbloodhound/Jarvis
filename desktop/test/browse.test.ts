import assert from "node:assert/strict";
import { test } from "node:test";
import {
  contentHash,
  renderBrowseReport,
  runBrowseTask,
  type BrowseDeps,
  type BrowseFetchFn,
} from "../src/tools/browse.ts";
import { ALL_TOOLS, htmlToText, safeFetch, toolsFor } from "../src/tools/index.ts";

// A tiny fake web. Page A (a search result) links to page B, which holds
// the fact. The search snippet for A deliberately states a WRONG version
// of the fact: if the report ever presents the snippet's claim as page
// content, these tests fail.
const PAGE_A = `<html><head><title>Widget Project — Home</title></head><body>
<h1>Widget Project</h1><p>Welcome to the widget project.</p>
<p><a href="https://example.org/widget/releases">Release history</a></p>
<p><a href="https://example.org/widget/about">About the team</a></p>
</body></html>`;

const PAGE_B = `<html><head><title>Widget Releases</title></head><body>
<h1>Releases</h1><p>The current stable widget release is 4.2, published 2026-03-04.</p>
</body></html>`;

const PAGES: Record<string, string> = {
  "https://example.org/widget": PAGE_A,
  "https://example.org/widget/releases": PAGE_B,
};

function fakeFetch(map: Record<string, string>): BrowseFetchFn {
  return async (url) => {
    const body = map[url];
    if (body === undefined) throw new Error("connection reset by peer");
    return { status: 200, type: "text/html", body, url };
  };
}

function deps(over: Partial<BrowseDeps> = {}): BrowseDeps {
  return {
    fetchFn: fakeFetch(PAGES),
    searchFn: async () => ({
      provider: "DuckDuckGo",
      blocked: [],
      results: [
        {
          title: "Widget Project — Home",
          url: "https://example.org/widget",
          snippet: "The current stable widget release is 9.9, published 1999-09-09.",
        },
      ],
    }),
    htmlToText,
    signal: new AbortController().signal,
    ...over,
  };
}

const sig = () => new AbortController().signal;
void sig;

test("multi-step: search seeds, links are followed, the fact comes from the fetched page — not the snippet", async () => {
  const r = await runBrowseTask({ goal: "current stable widget release and its date" }, deps());
  assert.equal(r.pages.length, 2);
  assert.equal(r.pages[0]!.url, "https://example.org/widget");
  assert.equal(r.pages[1]!.url, "https://example.org/widget/releases");
  // Provenance: hash is the SHA-256 of the fetched body.
  assert.equal(r.pages[1]!.hash, contentHash(PAGE_B));
  assert.match(r.pages[1]!.text, /current stable widget release is 4\.2, published 2026-03-04/);
  // The link was followed for a stated reason.
  const follow = r.followed.find((f) => f.toUrl === "https://example.org/widget/releases");
  assert.ok(follow, "release link recorded as followed");
  assert.match(follow!.reason, /Release history/);
  assert.match(follow!.reason, /release/);
  const text = renderBrowseReport(r);
  // The snippet is present ONLY in the labelled snippet section…
  const snippetAt = text.indexOf("9.9, published 1999-09-09");
  assert.ok(snippetAt > text.indexOf("SEARCH SNIPPETS (NOT page content"), "snippet appears under the snippet label");
  assert.ok(snippetAt < text.indexOf("PAGES READ IN FULL"), "snippet never appears as a page");
  assert.match(text, /sha256: [0-9a-f]{64}/);
  // The About link is followed too; the fake web has no such page, so it
  // lands as an explicit FAILED alongside the two successful reads.
  assert.equal(r.failed.length, 1);
  assert.equal(r.failed[0]!.url, "https://example.org/widget/about");
  assert.match(text, /Pages read in full: 2\. Pages FAILED: 1\./);
});

test("a page that fails to fetch is FAILED explicitly, with the error, and no content is invented for it", async () => {
  const d = deps({
    searchFn: async () => ({
      provider: "Bing",
      blocked: ["DuckDuckGo"],
      results: [{ title: "Gone", url: "https://example.org/gone", snippet: "Trust me, the answer is here." }],
    }),
  });
  const r = await runBrowseTask({ goal: "anything" }, d);
  assert.equal(r.pages.length, 0);
  assert.equal(r.failed.length, 1);
  assert.match(r.failed[0]!.error, /FAILED to fetch: connection reset by peer/);
  const text = renderBrowseReport(r);
  assert.match(text, /FAILED PAGES — these were NOT read/);
  assert.match(text, /\(none — no page was successfully read\)/);
});

test("an HTTP error status is a FAILED page, not a read", async () => {
  const d = deps({
    fetchFn: async (url) => ({ status: 404, type: "text/html", body: "<html>nope</html>", url }),
  });
  const r = await runBrowseTask({ goal: "x", startUrl: "https://example.org/missing" }, d);
  assert.equal(r.pages.length, 0);
  assert.equal(r.failed.length, 1);
  assert.match(r.failed[0]!.error, /FAILED: HTTP 404/);
});

test(".onion URLs are refused and the fetcher is never called for them", async () => {
  let calls = 0;
  const d = deps({
    fetchFn: async (url) => {
      calls++;
      return { status: 200, type: "text/html", body: "<html>x</html>", url };
    },
  });
  const r = await runBrowseTask({ goal: "x", startUrl: "http://abcdefghijklmnop.onion/page" }, d);
  assert.equal(calls, 0);
  assert.equal(r.pages.length, 0);
  assert.match(r.failed[0]!.error, /REFUSED: \.onion/);
});

test("a local-network start URL fails through the real safeFetch SSRF guard (explicit failure, no read)", async () => {
  const r = await runBrowseTask(
    { goal: "x", startUrl: "http://127.0.0.1:9/secret" },
    deps({ fetchFn: safeFetch }),
  );
  assert.equal(r.pages.length, 0);
  assert.equal(r.failed.length, 1);
  assert.match(r.failed[0]!.error, /FAILED to fetch/);
});

test("step budget bounds the run: a link chain is cut at the budget and the report says so", async () => {
  const chain: Record<string, string> = {};
  for (let i = 0; i < 10; i++) {
    chain[`https://example.org/chain/${i}`] =
      `<html><head><title>Chain ${i}</title></head><body><p>chain page ${i}</p><a href="https://example.org/chain/${i + 1}">next chain page</a></body></html>`;
  }
  const r = await runBrowseTask(
    { goal: "chain page", startUrl: "https://example.org/chain/0", maxSteps: 3 },
    deps({ fetchFn: fakeFetch(chain) }),
  );
  assert.equal(r.stepsUsed, 3);
  assert.equal(r.pages.length, 3);
  assert.equal(r.budgetExhausted, true);
  assert.match(renderBrowseReport(r), /Step budget \(3\) exhausted/);
});

test("the hard cap clamps an over-large budget to 15", async () => {
  const r = await runBrowseTask(
    { goal: "chain page", startUrl: "https://example.org/widget", maxSteps: 99 },
    deps(),
  );
  assert.equal(r.stepBudget, 15);
});

test("a page that changes mid-task is flagged and the earlier extract is marked stale", async () => {
  // Two seeds; the second redirects to the first's URL with different content.
  const d = deps({
    searchFn: async () => ({
      provider: "Bing",
      blocked: [],
      results: [
        { title: "One", url: "https://example.org/one", snippet: "" },
        { title: "Two", url: "https://example.org/two", snippet: "" },
      ],
    }),
    fetchFn: async (url) => {
      if (url.endsWith("/one")) return { status: 200, type: "text/html", body: "<html><title>v1</title>first version</html>", url: "https://example.org/same" };
      return { status: 200, type: "text/html", body: "<html><title>v2</title>second version</html>", url: "https://example.org/same" };
    },
  });
  const r = await runBrowseTask({ goal: "version" }, d);
  assert.equal(r.pages.length, 2);
  assert.deepEqual(r.changed, ["https://example.org/same"]);
  assert.equal(r.pages[0]!.stale, true);
  const text = renderBrowseReport(r);
  assert.match(text, /CHANGED PAGES/);
  assert.match(text, /STALE/);
});

test("no search results and no start URL: an honest empty report, not an error", async () => {
  const d = deps({
    searchFn: async () => ({ provider: null, blocked: ["DuckDuckGo", "Bing", "Brave"], results: [] }),
  });
  const r = await runBrowseTask({ goal: "nothing anywhere" }, d);
  assert.equal(r.pages.length, 0);
  assert.match(r.notes.join(" "), /providers blocked\/unreachable/);
});

test("browse_task is registered as a network egress tool and withheld offline", () => {
  const t = ALL_TOOLS.find((x) => x.name === "browse_task");
  assert.ok(t, "browse_task is in ALL_TOOLS");
  assert.equal(t!.network, true);
  assert.equal(t!.outside, true);
  assert.deepEqual(t!.policy.effects, ["egress"]);
  const offline = toolsFor({ offlineOnly: true, screenControl: false });
  assert.ok(!offline.some((x) => x.name === "browse_task"), "withheld under offlineOnly");
  const online = toolsFor({ offlineOnly: false, screenControl: false });
  assert.ok(online.some((x) => x.name === "browse_task"));
});
