import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import {
  isDuckDuckGoChallenge,
  parseBing,
  parseBrave,
  runWebSearch,
} from "../src/tools/index.ts";

// Real-shaped fixtures, trimmed from live captures of each provider's results page.

const BING_HTML = `<ol id="b_results"><li class="b_algo" data-id iid=SERP.1><h2><a target="_blank" href="https://www.bing.com/ck/a?!&amp;&amp;p=abc&amp;u=a1aHR0cHM6Ly93ZWF0aGVyLmNvbS91cy9mbG9yaWRhL2NpdHkvbWlhbWkvdG9kYXk&amp;ntb=1"><strong>Weather</strong> Forecast for <strong>Miami</strong></a></h2><div class="b_caption"><p class="b_lineclamp2">Today's Miami weather forecast and conditions.</p></div></li>
<li class="b_algo"><h2><a href="https://example.org/direct">Direct <strong>Link</strong> Result</a></h2><div class="b_caption"><p>A direct href result.</p></div></li></ol>`;

const BRAVE_HTML = `<section id="mixed-main"><div class="snippet svelte-x" data-pos="0" data-type="web" data-keynav="true"><div class="result-body"><a href="https://www.weather.gov/mfl/" target="_self" class="svelte-y l1"><div class="title search-snippet-title line-clamp-1 svelte-y" title="Miami - South Florida">Miami - South Florida</div></a><div class="generic-snippet svelte-z"><div class="content desktop-default-regular t-primary svelte-z"><span class="t-secondary">1 week ago -</span> FORECASTS Forecast Discussion Tropical Weather</div></div></div></div>
<div class="snippet svelte-x" data-pos="1" data-type="web"><a href="https://example.com/two" class="l1"><div class="title line-clamp-1">Second Result</div></a><div class="generic-snippet"><div class="content">Second snippet text.</div></div></div></section>`;

// Excerpt of the real HTTP 202 challenge body DuckDuckGo serves to walled networks
// (full capture: the anomaly-modal form + "bots use DuckDuckGo too").
const DDG_CHALLENGE_HTML = `<!DOCTYPE html><html><head><title>DuckDuckGo</title></head><body>
<div class="anomaly-modal__mask"><div class="anomaly-modal__modal">
<p class="anomaly-modal__title">Unfortunately, bots use DuckDuckGo too.</p>
<p class="anomaly-modal__description">Please complete the following challenge to confirm this request was made by a human.</p>
</div></div></body></html>`;

const EMPTY_PAGE = `<html><head><title>Search</title></head><body><p>Nothing matched.</p></body></html>`;

test("Bing parser: ck/a redirect is decoded, direct hrefs kept, snippets extracted", () => {
  const r = parseBing(BING_HTML);
  assert.equal(r.length, 2);
  assert.deepEqual(r[0], {
    title: "Weather Forecast for Miami",
    url: "https://weather.com/us/florida/city/miami/today",
    snippet: "Today's Miami weather forecast and conditions.",
  });
  assert.equal(r[1]!.url, "https://example.org/direct");
  assert.equal(r[1]!.snippet, "A direct href result.");
});

test("Brave parser: title, url and snippet from data-type=web blocks", () => {
  const r = parseBrave(BRAVE_HTML);
  assert.equal(r.length, 2);
  assert.equal(r[0]!.title, "Miami - South Florida");
  assert.equal(r[0]!.url, "https://www.weather.gov/mfl/");
  assert.match(r[0]!.snippet, /FORECASTS Forecast Discussion/);
  assert.equal(r[1]!.title, "Second Result");
  assert.equal(r[1]!.snippet, "Second snippet text.");
});

test("DuckDuckGo challenge page is classified as a challenge, not as results", () => {
  assert.equal(isDuckDuckGoChallenge(DDG_CHALLENGE_HTML, 202), true);
  assert.equal(isDuckDuckGoChallenge(DDG_CHALLENGE_HTML, 200), true); // markers alone suffice
  assert.equal(isDuckDuckGoChallenge(EMPTY_PAGE, 200), false);
});

type FakeFetch = (url: string, signal: AbortSignal) => Promise<{ status: number; type: string; body: string; url: string }>;

function stubFetch(handler: (url: string) => { status: number; body: string } | "throw"): FakeFetch {
  return async (url: string) => {
    const r = handler(url);
    if (r === "throw") throw new Error("connection reset");
    return { status: r.status, type: "text/html", body: r.body, url };
  };
}

const sig = () => new AbortController().signal;

test("chain: DDG challenge falls through to Bing results", async () => {
  const fetchFn = stubFetch((url) => {
    if (url.includes("duckduckgo")) return { status: 202, body: DDG_CHALLENGE_HTML };
    if (url.includes("bing.com")) return { status: 200, body: BING_HTML };
    throw new Error(`unexpected fetch ${url}`);
  });
  const out = await runWebSearch("weather in Miami", sig(), fetchFn);
  assert.equal(out.provider, "Bing");
  assert.deepEqual(out.blocked, ["DuckDuckGo"]);
  assert.equal(out.results.length, 2);
  assert.match(out.text, /1\. Weather Forecast for Miami/);
  assert.match(out.text, /https:\/\/weather\.com\/us\/florida\/city\/miami\/today/);
});

test("chain: DDG transport failure also falls through", async () => {
  const fetchFn = stubFetch((url) => {
    if (url.includes("duckduckgo")) return "throw";
    if (url.includes("bing.com")) return { status: 200, body: BING_HTML };
    throw new Error(`unexpected fetch ${url}`);
  });
  const out = await runWebSearch("weather in Miami", sig(), fetchFn);
  assert.equal(out.provider, "Bing");
  assert.deepEqual(out.blocked, ["DuckDuckGo"]);
});

test("chain: DDG results win and no fallback is fetched", async () => {
  const fetched: string[] = [];
  const fetchFn: FakeFetch = async (url) => {
    fetched.push(url);
    return {
      status: 200, type: "text/html", url,
      body: `<div class="result web-result"><h2><a class="result__a" href="https://example.com/a">A</a></h2><div class="result__snippet">S.</div></div>`,
    };
  };
  const out = await runWebSearch("anything", sig(), fetchFn);
  assert.equal(out.provider, "DuckDuckGo");
  assert.equal(fetched.length, 1);
  assert.deepEqual(out.blocked, []);
});

test("chain: every provider down gives the honest empty message that names the block", async () => {
  const fetchFn = stubFetch((url) => {
    if (url.includes("duckduckgo")) return { status: 202, body: DDG_CHALLENGE_HTML };
    if (url.includes("bing.com")) return "throw";
    return { status: 503, body: "down" };
  });
  const out = await runWebSearch("weather in Miami", sig(), fetchFn);
  assert.equal(out.provider, null);
  assert.deepEqual(out.blocked, ["DuckDuckGo", "Bing", "Brave"]);
  assert.match(out.text, /No results for "weather in Miami"\. Do not make any up\./);
  assert.match(out.text, /DuckDuckGo, Bing and Brave were all tried/);
});

test("chain: a genuinely empty page is not reported as a block", async () => {
  const fetchFn = stubFetch(() => ({ status: 200, body: EMPTY_PAGE }));
  const out = await runWebSearch("zzzznothingmatchesthis", sig(), fetchFn);
  assert.equal(out.provider, null);
  assert.equal(out.text, `No results for "zzzznothingmatchesthis". Do not make any up.`);
});

test("the saved live DDG challenge body stays a challenge (regression fixture)", () => {
  // The real 202 body captured from this network during the root-cause hunt.
  let body: string;
  try {
    body = readFileSync(new URL("../../../ddg-node-body.html", import.meta.url), "utf8");
  } catch {
    return; // capture not present in this checkout; the excerpt test above covers it
  }
  assert.equal(isDuckDuckGoChallenge(body, 202), true);
});
