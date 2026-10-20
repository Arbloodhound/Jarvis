import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { mkdtempSync, mkdirSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { Episode, Gate } from "../src/gate/gate.ts";
import { ALL_TOOLS, toolsFor, type ToolContext } from "../src/tools/index.ts";
import {
  shoppingTools, excludeOnion, extractListings, extractNavLinks, extractPrices, extractReputation,
  extractReviews, fetchMarketplace, fetchProductCheck, parseEbayResults, parseListingPage,
  runMarketplaceNav, runProductCheck, runSellerCheck, scanScamSignals, sortRowsMechanically,
  sourceYear, type ListingRow, type NavSession,
} from "../src/tools/shopping.ts";

// ---------- helpers ----------

function row(partial: Partial<ListingRow> & { sourceUrl: string }): ListingRow {
  return {
    title: "", price: null, shipping: null, total: null, freeShipping: false, seller: "",
    sellerPositivePct: null, sellerFeedbackCount: null, stock: "", condition: "", location: "",
    link: partial.sourceUrl, year: "n.d.", ...partial,
  };
}

type Page = { status: number; body: string };
function stubFetch(pages: Record<string, Page>, calls: string[] = []) {
  return async (url: string) => {
    calls.push(url);
    const p = pages[url];
    if (!p) return { status: 404, type: "text/html", body: "<html><body>not found</body></html>", url };
    return { status: p.status, type: "text/html", body: p.body, url };
  };
}

function stubCtx(facts: Array<{ id: string; text: string }> = []): ToolContext {
  return {
    cwd: "/tmp", workspace: "/tmp", embedder: null, vision: null,
    signal: new AbortController().signal,
    memory: {
      search: async () => facts,
      remember: (t: string) => ({ id: "fx", text: t }),
    },
  } as unknown as ToolContext;
}

// ---------- fixtures (real-shaped, trimmed) ----------

const EBAY_URL = "https://www.ebay.com/sch/i.html?_nkw=widget+pro";
const EBAY_RESULTS_HTML = `<ul class="srp-results srp-list">
<li class="s-item s-item__pl-on-bottom"><div class="s-item__wrapper clearfix">
<a class="s-item__link" href="https://www.ebay.com/itm/111111?mkevt=1&amp;mkcid=1"><div class="s-item__title"><span>Widget Pro 3000 — Brand New</span></div></a>
<span class="s-item__price">$24.99</span>
<span class="s-item__shipping s-item__logisticsCost">+$5.00 shipping</span>
<div class="s-item__subtitle">New</div>
<span class="s-item__seller-info-text">gadgetdepot 99.5% Positive (1,234)</span>
</div></li>
<li class="s-item s-item__pl-on-bottom"><div class="s-item__wrapper clearfix">
<a class="s-item__link" href="https://www.ebay.com/itm/222222"><div class="s-item__title"><span>Widget Pro 3000 Refurbished</span></div></a>
<span class="s-item__price">$18.50</span>
<span class="s-item__shipping s-item__logisticsCost">Free shipping</span>
<div class="s-item__subtitle">Refurbished</div>
<span class="s-item__seller-info-text">valueparts 98.1% Positive (87)</span>
</div></li>
<li class="s-item"><div class="s-item__wrapper">
<div class="s-item__title"><span>Shop on eBay</span></div>
</div></li>
</ul>`;

const CLEAN_URL = "https://shop.example.com/products/widget-pro";
const CLEAN_LISTING_HTML = `<html><head><title>Widget Pro 3000 | TechNest Store</title>
<script type="application/ld+json">{"@type":"Product","name":"Widget Pro 3000","offers":{"@type":"Offer","price":"129.99","priceCurrency":"USD","availability":"https://schema.org/InStock"},"aggregateRating":{"@type":"AggregateRating","ratingValue":"4.6","reviewCount":"128"}}</script></head>
<body><h1>Widget Pro 3000</h1>
<p>Sold by TechNest</p>
<p>Free shipping on this item.</p>
<p>30-day returns, buyer pays for return shipping.</p>
<p>In stock and ready to ship. Condition: New.</p></body></html>`;

const SCAM_URL = "https://sketchy.example.com/phones/flagship-x";
const SCAM_LISTING_HTML = `<html><head><title>Flagship X Phone — 90% OFF</title>
<script type="application/ld+json">{"@type":"Product","name":"Flagship X Phone","offers":{"@type":"Offer","price":"19.99","priceCurrency":"USD"},"aggregateRating":{"@type":"AggregateRating","ratingValue":"1.2","reviewCount":"0"}}</script></head>
<body><h1>Flagship X Phone</h1>
<p>90% off retail — this week only!</p>
<p>No returns accepted. All sales final.</p>
<p>Contact me before buying and pay outside eBay via Zelle to reserve yours.</p>
<p>Act now — must sell today, within 24 hours they're gone.</p>
<p>Sold by QuickDeals99</p></body></html>`;

const SELLER_URL = "https://www.ebay.com/usr/technest";
const SELLER_HTML = `<html><body><h1>technest</h1>
<p>Member since March 2019</p>
<p>99.8% positive feedback</p>
<p>12,483 feedback received</p>
<p>3 complaints in the last year</p>
<div class="feedback-comment"><p>Fast shipping and the item was exactly as described — great seller, would buy again.</p></div>
<div class="feedback-comment"><p>Item arrived late but the seller communicated well and offered a refund quickly.</p></div>
</body></html>`;

const REVIEWS_URL = "https://shop.example.com/products/widget-pro/reviews";
const REVIEWS_HTML = `<html><body><h2>Customer reviews</h2><p>4.5 out of 5 stars</p><p>2,314 global ratings</p>
<div class="review" data-hook="review"><span aria-label="5 out of 5 stars"></span><strong>Amazing battery</strong><p>The battery life is amazing and the battery lasts two days on one charge.</p></div>
<div class="review"><span aria-label="5 out of 5 stars"></span><strong>Great battery life</strong><p>Battery life is amazing, easily two days of battery for me.</p></div>
<div class="review"><span aria-label="1 out of 5 stars"></span><strong>Cracked fast</strong><p>The screen cracked within a week and the screen feels fragile.</p></div>
<div class="review"><span aria-label="2 out of 5 stars"></span><strong>Fragile</strong><p>Screen cracked already; the screen is too fragile for daily use.</p></div>
</body></html>`;

const PAGE1 = "https://market.example/search?q=widget";
const PAGE2 = "https://market.example/search?q=widget&page=2";
const SORTED = "https://market.example/search?q=widget&sort=price_asc";
const ITEM3 = "https://market.example/item/3";
const NAV_PAGE1_HTML = `<html><body>
<div class="result"><h3><a href="https://market.example/item/1">Basic Widget</a></h3><p>$10.00</p><p>Sold by AlphaStore</p></div>
<div class="result"><h3><a href="https://market.example/item/2">Widget Deluxe</a></h3><p>$25.50</p><p>Sold by BetaGoods</p></div>
<div class="result"><h3><a href="https://evil.onion/item/9">Onion Widget</a></h3><p>$1.00</p><p>Sold by DarkMart</p></div>
<nav><a href="${PAGE2}">Next</a>
<a href="${SORTED}">Price + Shipping: lowest first</a>
<a href="https://market.example/search?q=widget&sort=new">Time: newly listed</a></nav>
</body></html>`;
const NAV_PAGE2_HTML = `<html><body>
<div class="result"><h3><a href="${ITEM3}">Widget Ultra</a></h3><p>$42.00</p><p>Sold by GammaParts</p></div>
<div class="result"><h3><a href="https://market.example/item/4">Widget Mini</a></h3><p>$8.75</p><p>Sold by AlphaStore</p></div>
<nav><a href="${PAGE1}">Previous</a></nav>
</body></html>`;
const NAV_SORTED_HTML = `<html><body>
<div class="result"><h3><a href="https://market.example/item/4">Widget Mini</a></h3><p>$8.75</p><p>Sold by AlphaStore</p></div>
<div class="result"><h3><a href="https://market.example/item/1">Basic Widget</a></h3><p>$10.00</p><p>Sold by AlphaStore</p></div>
<nav><a href="https://market.example/search?q=widget&sort=price_desc">Price + Shipping: highest first</a></nav>
</body></html>`;
const NAV_ITEM3_HTML = `<html><body><h1>Widget Ultra</h1><p>Price: $42.00</p><p>Sold by GammaParts</p><p>In stock. Condition: New.</p></body></html>`;

const NAV_PAGES: Record<string, Page> = {
  [PAGE1]: { status: 200, body: NAV_PAGE1_HTML },
  [PAGE2]: { status: 200, body: NAV_PAGE2_HTML },
  [SORTED]: { status: 200, body: NAV_SORTED_HTML },
  [ITEM3]: { status: 200, body: NAV_ITEM3_HTML },
};

// ---------- money & years (phone-package port) ----------

test("extractPrices: disambiguation, symbols required, no invented prices", () => {
  assert.deepEqual(extractPrices("Was $1,299.99 now $999.00").map((p) => p.amount), [999, 1299.99]);
  assert.equal(extractPrices("1.299,99 €")[0]!.amount, 1299.99);
  assert.equal(extractPrices("1.299,99 €")[0]!.currency, "EUR");
  assert.equal(extractPrices("Price $39.99, today only")[0]!.amount, 39.99);
  assert.deepEqual(extractPrices("model 42 has 42 parts"), []);
  assert.equal(sourceYear("© 2023, updated 2026"), "2026");
  assert.equal(sourceYear("no dates here"), "n.d.");
});

// ---------- the onion exclusion: one function, one call site ----------

test("excludeOnion drops rows by source OR link, keeps everything else", () => {
  const rows = [
    row({ sourceUrl: "https://shop.example.com/a", link: "https://shop.example.com/a" }),
    row({ sourceUrl: "https://hidden.onion/list", link: "https://hidden.onion/item" }),
    row({ sourceUrl: "https://shop.example.com/b", link: "https://sneaky.onion/item" }),
    row({ sourceUrl: "https://shop.example.com/search?q=x.onion", link: "https://shop.example.com/c" }),
  ];
  const { rows: kept, excluded } = excludeOnion(rows);
  assert.equal(kept.length, 2);
  assert.equal(excluded.length, 2);
  assert.equal(kept[1]!.sourceUrl, "https://shop.example.com/search?q=x.onion"); // query text is not a hostname
});

test("extraction is realm-blind: onion rows are extracted, only the choke point removes them", () => {
  const extracted = extractListings(NAV_PAGE1_HTML, PAGE1);
  assert.equal(extracted.length, 3);
  assert.ok(extracted.some((r) => r.link.includes("onion")));
  const { rows: kept } = excludeOnion(extracted);
  assert.equal(kept.length, 2);
});

test("source pin: excludeOnion has exactly one definition and one call site in the shopping layer", () => {
  const src = readFileSync(new URL("../src/tools/shopping.ts", import.meta.url), "utf8");
  assert.equal((src.match(/excludeOnion\(/g) || []).length, 2, "definition + the admitRows call, nothing else");
  assert.equal((src.match(/isOnionUrl\(/g) || []).length, 2, "helper definition + its use inside excludeOnion");
  const idx = readFileSync(new URL("../src/tools/index.ts", import.meta.url), "utf8");
  assert.equal(/onion/i.test(idx.replace(/excludeOnion/g, "")), false, "index.ts never branches on onion");
});

// ---------- marketplace extraction ----------

test("parseEbayResults: seller line, totals, free shipping; placeholder skipped", () => {
  const rows = parseEbayResults(EBAY_RESULTS_HTML, EBAY_URL);
  assert.equal(rows.length, 2);
  assert.equal(rows[0]!.seller, "gadgetdepot");
  assert.equal(rows[0]!.sellerPositivePct, 99.5);
  assert.equal(rows[0]!.sellerFeedbackCount, 1234);
  assert.equal(rows[0]!.total?.amount, 29.99);
  assert.equal(rows[1]!.freeShipping, true);
  assert.equal(rows[1]!.total?.amount, 18.5);
});

test("sortRowsMechanically: labeled mechanical sorts", () => {
  const rows = parseEbayResults(EBAY_RESULTS_HTML, EBAY_URL);
  const byPrice = sortRowsMechanically(rows, "price");
  assert.equal(byPrice.rows[0]!.title, "Widget Pro 3000 Refurbished");
  assert.match(byPrice.label, /not a recommendation/);
  const byRating = sortRowsMechanically(rows, "rating");
  assert.equal(byRating.rows[0]!.seller, "gadgetdepot");
  assert.match(byRating.label, /rating \(highest first\)/);
});

test("fetchMarketplace renders a labeled, sorted table from a stubbed page", async () => {
  const calls: string[] = [];
  const out = await fetchMarketplace("widget pro", "ebay", "price", new AbortController().signal, async (url: string) => {
    calls.push(url);
    return { status: 200, type: "text/html", body: EBAY_RESULTS_HTML, url };
  });
  assert.match(out, /Marketplace results for "widget pro" on ebay/);
  assert.match(out, /Sorted by total price \(lowest first\)/);
  assert.ok(out.indexOf("Refurbished") < out.indexOf("Brand New"), "cheapest total first");
  assert.equal(calls.length, 1);
});

test("extractListings: a repeated price is not shipping; entities are decoded", () => {
  // Real shape from a live Walmart card (2026-10-20): the price repeats in
  // several formats inside one card; none of the repeats is a shipping fee.
  const html = `<div class="card"><h3><a href="https://www.walmart.com/ip/123">Skullcandy Smokin&#x27; Buds XT</a></h3>
  <p>Price Now $14.88 Was $19.88 Options from $14.88 – $19.88 Now $ 14 88</p></div>
  <div class="card"><h3><a href="https://shop.example.com/item/7">Labelled Ship Widget</a></h3>
  <p>$10.00 + $3.50 shipping</p><p>Sold by AlphaStore</p></div>`;
  const rows = extractListings(html, "https://www.walmart.com/search?q=earbuds");
  const buds = rows.find((r) => r.title.includes("Skullcandy"));
  assert.ok(buds, "walmart-shaped row extracted");
  assert.equal(buds.title, "Skullcandy Smokin' Buds XT");
  assert.equal(buds.shipping, null);
  assert.equal(buds.total, null); // shipping unknown — no invented total
  const labelled = rows.find((r) => r.title.includes("Labelled"));
  assert.equal(labelled?.shipping?.amount, 3.5);
  assert.equal(labelled?.total?.amount, 13.5);
});

// ---------- product check ----------

test("parseListingPage on a clean page: fields and structured claims", () => {
  const d = parseListingPage(CLEAN_LISTING_HTML, CLEAN_URL);
  assert.equal(d.title, "Widget Pro 3000");
  assert.equal(d.price?.amount, 129.99);
  assert.equal(d.freeShipping, true);
  assert.equal(d.total?.amount, 129.99);
  assert.equal(d.seller, "TechNest");
  assert.match(d.returns, /30-day returns/);
  const kinds = d.claims.map((c) => c.kind);
  for (const k of ["price", "rating", "feedback_count", "returns", "seller"]) assert.ok(kinds.includes(k as never), k);
  assert.ok(d.claims.every((c) => c.source === CLEAN_URL));
  assert.deepEqual(scanScamSignals(d), []);
});

test("parseListingPage: 'sellers' prose is not a seller; unstructured prices are flagged ambiguous", () => {
  // Both shapes seen live on Walmart (2026-10-20): "Compare all 4 sellers"
  // prose near the buy box, and a compare-at price ahead of the buy price.
  const html = `<html><body><h1>Some Earbuds</h1>
  <p>Compare all 4 sellers for this item.</p>
  <p>List price $129.00 — now $14.88 at checkout.</p></body></html>`;
  const d = parseListingPage(html, "https://www.walmart.com/ip/999");
  assert.equal(d.seller, "");
  const priceClaims = d.claims.filter((c) => c.kind === "price").map((c) => c.value).join(" | ");
  assert.match(priceClaims, /also shows/);
  assert.match(priceClaims, /129/);
});

test("scam signals: every named signal fires with evidence on the scam fixture", () => {
  const d = parseListingPage(SCAM_LISTING_HTML, SCAM_URL);
  const signals = scanScamSignals(d, [
    { amount: 499, currency: "USD", raw: "$499" },
    { amount: 529, currency: "USD", raw: "$529" },
    { amount: 549, currency: "USD", raw: "$549" },
  ]);
  const names = signals.map((s) => s.signal);
  for (const n of ["off_platform_payment_lure", "no_returns", "urgency_pressure", "extreme_discount_claim", "fresh_seller_no_feedback", "price_far_below_comparables"]) {
    assert.ok(names.includes(n), n);
  }
  assert.ok(signals.every((s) => s.evidence.length > 10 && s.source === SCAM_URL));
});

test("fetchProductCheck refuses honestly when blocked or unparseable", async () => {
  const sig = new AbortController().signal;
  const blocked = await fetchProductCheck(CLEAN_URL, [], sig, stubFetch({ [CLEAN_URL]: { status: 403, body: "blocked" } }));
  assert.match(blocked.refusal ?? "", /HTTP 403/);
  const empty = await fetchProductCheck(CLEAN_URL, [], sig, stubFetch({ [CLEAN_URL]: { status: 200, body: "<html><body>hello world, no products</body></html>" } }));
  assert.match(empty.refusal ?? "", /no product data/i);
});

// ---------- reputation & reviews ----------

test("extractReputation: rating, counts, disputes, age, quoted feedback", () => {
  const rep = extractReputation(SELLER_HTML, SELLER_URL, "technest");
  assert.equal(rep.positivePct, 99.8);
  assert.equal(rep.feedbackCount, 12483);
  assert.equal(rep.disputes, 3);
  assert.equal(rep.accountAge, "March 2019");
  assert.equal(rep.feedback.length, 2);
  assert.ok(rep.feedback.every((f) => f.source === SELLER_URL));
});

test("extractReviews: stated aggregates, distribution, recurring themes with quotes", () => {
  const set = extractReviews(REVIEWS_HTML, REVIEWS_URL);
  assert.equal(set.statedAverage, 4.5);
  assert.equal(set.statedCount, 2314);
  assert.equal(set.reviews.length, 4);
  assert.deepEqual(set.distribution, { "5": 2, "1": 1, "2": 1 });
  const praise = set.praiseThemes.find((t) => t.phrase === "battery life");
  assert.ok(praise && praise.reviews === 2 && praise.quote.length > 10);
  const complaint = set.complaintThemes.find((t) => t.phrase === "screen cracked");
  assert.ok(complaint && complaint.reviews === 2);
});

// ---------- navigation ----------

test("extractNavLinks reads pagination and the page's own sort links", () => {
  const links = extractNavLinks(NAV_PAGE1_HTML, PAGE1);
  assert.equal(links.next, PAGE2);
  assert.equal(links.sorts.length, 2);
  assert.ok(links.sorts.some((s) => /lowest first/.test(s.label)));
});

test("navigation flow: open → next → open listing → back to page 2 → sort link", async () => {
  const sig = new AbortController().signal;
  const sessions = new Map<string, NavSession>();
  const fetch = stubFetch(NAV_PAGES);

  const p1 = await runMarketplaceNav("s", { action: "open", url: PAGE1 }, sig, fetch, sessions);
  assert.match(p1, /Basic Widget/);
  assert.match(p1, /Next page link is present/);
  assert.ok(!p1.includes("Onion Widget"), "onion listing removed at the choke point");
  assert.equal(sessions.get("s")!.rows.length, 2);

  const p2 = await runMarketplaceNav("s", { action: "next" }, sig, fetch, sessions);
  assert.match(p2, /Widget Ultra/);
  assert.equal(sessions.get("s")!.currentUrl, PAGE2);

  const listing = await runMarketplaceNav("s", { action: "open_listing", index: 1 }, sig, fetch, sessions);
  assert.match(listing, /Listing page: Widget Ultra/);
  assert.match(listing, /42\.00/);

  const back = await runMarketplaceNav("s", { action: "back" }, sig, fetch, sessions);
  assert.match(back, /Widget Ultra/);
  assert.match(back, /Widget Mini/);
  assert.equal(sessions.get("s")!.currentUrl, PAGE2);

  const sorted = await runMarketplaceNav("s", { action: "open", url: PAGE1 }, sig, fetch, sessions)
    .then(() => runMarketplaceNav("s", { action: "sort", by: "price_asc" }, sig, fetch, sessions));
  assert.equal(sessions.get("s")!.currentUrl, SORTED);
  assert.ok(sorted.indexOf("Widget Mini") < sorted.indexOf("Basic Widget"), "followed the page's own sort link");

  const noNext = await runMarketplaceNav("s", { action: "next" }, sig, fetch, sessions);
  assert.match(noNext, /no Next link/i); // the sorted page has none — honest, not invented
});

test("navigation refuses onion targets without fetching; sessions stay isolated", async () => {
  const sig = new AbortController().signal;
  const sessions = new Map<string, NavSession>();
  const calls: string[] = [];
  const fetch = stubFetch(NAV_PAGES, calls);

  const onion = await runMarketplaceNav("a", { action: "open", url: "https://evil.onion/search" }, sig, fetch, sessions);
  assert.match(onion, /onion address/i);
  assert.equal(calls.length, 0, "the onion target was never fetched");

  await runMarketplaceNav("a", { action: "open", url: PAGE1 }, sig, fetch, sessions);
  const bResults = await runMarketplaceNav("b", { action: "results" }, sig, fetch, sessions);
  assert.match(bResults, /No results in this session/);
  const aResults = await runMarketplaceNav("a", { action: "results" }, sig, fetch, sessions);
  assert.match(aResults, /Basic Widget/);
});

// ---------- memory plugs ----------

test("seller_check surfaces the owner's memory; a saved seller is never just 'unknown'", async () => {
  const plainUrl = "https://shop.example.com/store/technest";
  const plain = `<html><body><h1>TechNest store</h1><p>Welcome to our store front page. We stock widgets and gadgets of many kinds for everyone at home.</p></body></html>`;
  const fetch = stubFetch({ [plainUrl]: { status: 200, body: plain } });
  const ctx = stubCtx([{ id: "f1", text: "TechNest is a plug — known non-scammer, bought from them twice" }]);
  const out = await runSellerCheck([plainUrl], "TechNest", ctx, fetch);
  assert.match(out, /UNVERIFIED by this check/);
  assert.match(out, /Owner memory/);
  assert.match(out, /TechNest is a plug/);
});

test("product_check labels memory as memory, not page evidence", async () => {
  const fetch = stubFetch({ [CLEAN_URL]: { status: 200, body: CLEAN_LISTING_HTML } });
  const ctx = stubCtx([{ id: "f2", text: "TechNest is a plug — known non-scammer" }]);
  const out = await runProductCheck(CLEAN_URL, [], ctx, fetch);
  assert.match(out, /Product check — Widget Pro 3000/);
  assert.match(out, /Owner memory \(the owner's own saved facts/);
  const noMemory = await runProductCheck(CLEAN_URL, [], stubCtx(), fetch);
  assert.ok(!noMemory.includes("Owner memory"));
});

test("check tools refuse onion targets outright", async () => {
  const fetch = stubFetch({});
  const out = await runProductCheck("https://evil.onion/item/1", [], stubCtx(), fetch);
  assert.match(out, /onion address/i);
  const out2 = await runSellerCheck(["https://evil.onion/usr/x"], "x", stubCtx(), fetch);
  assert.match(out2, /onion address/i);
});

// ---------- gate posture & registration ----------

test("prepare_order always requires confirmation and never executes a purchase", async () => {
  const root = mkdtempSync(path.join(tmpdir(), "jarvis-shop-gate-"));
  mkdirSync(path.join(root, "private"), { recursive: true });
  const gate = new Gate({ writableRoots: [root], extraAllowedCommands: [] });
  const tool = shoppingTools().find((t) => t.name === "prepare_order")!;
  const ep = new Episode("prepare the order for the widget");
  const args = { title: "Widget", unit_price: 10, currency: "USD" };
  const verdict = gate.check(ep, tool.name, tool.policy, args);
  assert.equal(verdict.allowed, false);
  assert.equal(verdict.confirm?.rule, "always_confirm");
  ep.confirm(tool.name, args);
  assert.equal(gate.check(ep, tool.name, tool.policy, args).allowed, true, "owner confirmation is the only way through");

  const plan = await tool.run({ title: "Widget Pro", unit_price: 10, currency: "USD", quantity: 3, shipping: 5 }, stubCtx());
  assert.match(plan, /Total: 35\.00 USD/);
  assert.match(plan, /nothing was bought/i);

  const names = ALL_TOOLS.map((t) => t.name);
  assert.ok(!names.some((n) => /^(buy|purchase|checkout|pay)_/.test(n)), "no purchase-execution tool exists");
});

test("offline mode withholds every network shopping tool; prepare_order stays", () => {
  const offline = toolsFor({ offlineOnly: true, screenControl: false }).map((t) => t.name);
  for (const n of ["shop_search", "marketplace_search", "marketplace_navigate", "product_check", "seller_check", "product_reviews"]) {
    assert.ok(!offline.includes(n), n);
  }
  assert.ok(offline.includes("prepare_order"));
  const online = toolsFor({ offlineOnly: false, screenControl: false }).map((t) => t.name);
  for (const n of ["shop_search", "marketplace_search", "marketplace_navigate", "product_check", "seller_check", "product_reviews", "prepare_order"]) {
    assert.ok(online.includes(n), n);
  }
});
