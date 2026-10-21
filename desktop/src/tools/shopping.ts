import { z } from "zod";
import type { FetchedBytes, Tool, ToolContext, WebFetchBytesFn } from "./index.js";
import { htmlToText, MAX_IMAGE_BYTES, runWebSearch, safeFetch, safeFetchBytes } from "./index.js";

/**
 * Shopping, marketplace and review tools — a port of the phone app's
 * products/ + shopping/ packages (jarvis-v4), rules first, code second:
 *
 * - The agent INFORMS, then ASKS. These tools verify, tabulate, warn and
 *   sort mechanically (sort key always labeled). They never render an
 *   allowed/forbidden verdict, never recommend, and never complete a
 *   purchase: `prepare_order` only builds a plan, sits behind an
 *   always-confirm card, and its output states that nothing was bought.
 * - Legitimate products and companies only. No product lists anywhere.
 * - Prices and facts come from FETCHED FULL PAGES, never from search
 *   snippets. Search results are labeled candidates until a page check.
 *   Every extracted claim carries its evidence and source URL, as plain
 *   structured data ({kind, value, evidence, source}) — the seam a later
 *   NLI/claim-check layer can verify without re-parsing prose.
 * - "Plugs" (the owner's word for known non-scammers) live in the app's
 *   memory: the check tools consult remembered facts about a seller and
 *   surface a match labeled as memory, never as page evidence.
 * - The shopping layer never branches on realm: extraction, navigation,
 *   search and the check tools are URL-agnostic, like the phone package's
 *   extraction. Owner reversal (2026-10-20, on the owner's explicit
 *   order): the former onion exclusion — excludeOnion / admitRows, and the
 *   pre-fetch refusal of onion targets in navigation and the check tools —
 *   was removed. Onion listings appear in results like any other; whether
 *   an onion page can be OPENED is the transport's decision alone (the
 *   fetch funnel refuses .onion unless Tor is on, and never falls back to
 *   clearnet).
 *
 * Transport is the app's own: every fetch goes through safeFetch, so the
 * search-fallback chain (DuckDuckGo → Bing → Brave) and any Tor routing
 * on that path strengthen these tools for free.
 *
 * - Vision is inherent when it is on (the owner's rule: vision ON means
 *   it is ALWAYS part of the model). product_check and seller_check read
 *   the page's images through the vision route as a fixed step of every
 *   check — never a per-call choice, and neither tool's schema carries a
 *   vision flag. The readings are reported in their own labeled block
 *   ("Vision-read evidence — not page text"), never merged into the
 *   page-text facts; a disagreement is printed as a named conflict. The
 *   only off switch is the vision setting itself: off (or no vision
 *   backend) means zero vision calls, and the check says so in one line.
 *   Image bytes come through safeFetchBytes — the funnel's bytes sibling,
 *   with the same SSRF checks and an image-only, size-capped payload.
 */

function tool<S extends z.ZodType = z.ZodType>(t: Tool<S>): Tool {
  return t as unknown as Tool;
}

type FetchFn = (url: string, signal: AbortSignal) => Promise<{ status: number; type: string; body: string; url: string }>;

// ---------- money & years (port of jarvis-v4 search/extract.py + brief.py) ----------

export interface Money {
  amount: number;
  currency: string;
  raw: string;
}

const SYMBOLS: Record<string, string> = { "$": "USD", "€": "EUR", "£": "GBP", "¥": "JPY", "₹": "INR" };
const CODES = new Set(["USD", "EUR", "GBP", "JPY", "INR", "CAD", "AUD"]);

// $1,299.99 or 1.299,99 € or USD 1299.00 — symbol/code before or after; a
// bare number is never a price. Thousands/decimal disambiguation matches
// the phone package (its regex was fixed against hostile review in v4).
const PRICE_RE =
  /([$€£¥₹])?\s*(USD|EUR|GBP|JPY|INR|CAD|AUD)?\s*(\d{1,3}(?:[.,]\d{3})+(?:[.,]\d{2})?|\d+(?:[.,]\d{2})?)(\.(?!\d))?(?!\d)(?!,[0-9])\s*([$€£¥₹])?(USD|EUR|GBP|JPY|INR|CAD|AUD)?(?!\s*\d)/gi;

function toFloat(num: string): number | null {
  const s = num.trim();
  try {
    if (s.includes(".") && s.includes(",")) {
      return s.lastIndexOf(".") > s.lastIndexOf(",")
        ? parseFloat(s.replace(/,/g, ""))
        : parseFloat(s.replace(/\./g, "").replace(",", "."));
    }
    if (s.includes(",")) {
      const parts = s.split(",");
      if (parts.length >= 2 && parts.slice(1).every((p) => p.length === 3) && parts[0]!.length <= 3) {
        return parseFloat(parts.join(""));
      }
      return parseFloat(s.replace(",", "."));
    }
    return parseFloat(s);
  } catch {
    return null;
  }
}

/** Every price in a text, deduped, in document order. Never invents. */
export function extractPricesInOrder(text: string): Money[] {
  const out: Money[] = [];
  const seen = new Set<string>();
  for (const m of (text || "").matchAll(PRICE_RE)) {
    const sym = m[1] || m[5];
    const code = (m[2] || m[6] || "").toUpperCase();
    const currency = CODES.has(code) ? code : SYMBOLS[sym || ""];
    if (!currency || !(sym || code)) continue;
    const amount = toFloat(m[3]!);
    if (amount === null || amount <= 0) continue;
    const key = `${amount.toFixed(2)} ${currency}`;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ amount: Math.round(amount * 100) / 100, currency, raw: m[0].trim() });
  }
  return out;
}

/** Every price in a text, deduped and sorted by amount. */
export function extractPrices(text: string): Money[] {
  return extractPricesInOrder(text).sort((a, b) => a.amount - b.amount);
}

/** Best-effort year tag for a page: the latest 4-digit year mentioned, or "n.d.". */
export function sourceYear(text: string): string {
  const years = [...(text || "").matchAll(/\b(?:19|20)\d{2}\b/g)]
    .map((m) => parseInt(m[0], 10))
    .filter((y) => y >= 1990 && y <= 2100);
  return years.length ? String(Math.max(...years)) : "n.d.";
}

export function fmtMoney(m: Money | null | undefined): string {
  if (!m) return "not stated";
  return m.raw || `${m.amount.toFixed(2)} ${m.currency}`;
}

// ---------- listing rows ----------

export interface ListingRow {
  title: string;
  price: Money | null;
  shipping: Money | null;
  /** price + shipping when both are known (and shipping is not "free"). */
  total: Money | null;
  freeShipping: boolean;
  seller: string;
  sellerPositivePct: number | null;
  sellerFeedbackCount: number | null;
  stock: string;
  condition: string;
  location: string;
  link: string;
  sourceUrl: string;
  year: string;
  /** Free-text note (e.g. a search snippet for a candidate row). */
  note?: string;
}

function blankRow(partial: Partial<ListingRow> & { sourceUrl: string }): ListingRow {
  return {
    title: "", price: null, shipping: null, total: null, freeShipping: false,
    seller: "", sellerPositivePct: null, sellerFeedbackCount: null,
    stock: "", condition: "", location: "", link: partial.sourceUrl, year: "n.d.",
    ...partial,
  };
}

function totalOf(price: Money | null, shipping: Money | null, freeShipping: boolean): Money | null {
  if (!price) return null;
  if (freeShipping) return price;
  if (shipping && shipping.currency === price.currency) {
    return { amount: Math.round((price.amount + shipping.amount) * 100) / 100, currency: price.currency, raw: "" };
  }
  return null; // shipping unknown or another currency: no invented total
}

// ---------- text helpers ----------

function cleanText(s: string): string {
  return htmlToText(s || "")
    .replace(/&#x27;|&#39;|&apos;/gi, "'")
    .replace(/&quot;/g, '"')
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&")
    .replace(/[ \t]+/g, " ")
    .trim();
}

function evidenceAround(text: string, m: RegExpMatchArray, span = 70): string {
  const i = m.index ?? 0;
  const start = Math.max(0, i - span);
  const end = Math.min(text.length, i + m[0].length + span);
  return text.slice(start, end).replace(/\s+/g, " ").trim();
}

/** htmlToText renders an anchor as "text (url)"; a title keeps only the text. */
function cleanTitle(s: string): string {
  return s.replace(/\s*\(https?:\/\/[^)]*\)/g, "").trim();
}

function resolveUrl(href: string, base: string): string {
  try {
    return new URL((href || "").replace(/&amp;/g, "&"), base).toString();
  } catch {
    return href || base;
  }
}

const SELLER_STOPWORDS = new Set(["in", "out", "only", "new", "used", "ships", "sold", "available"]);
// Words that start the prose after a seller mention on a flattened page
// ("Sold by TechNest Free shipping on this item") — a seller name ends there.
const SELLER_CUT = new Set(["free", "shipping", "delivery", "returns", "return", "price", "stock", "the", "on", "and", "with", "for", "items", "item", "ships", "fast"]);

function stripSeller(name: string): string {
  const words = name.trim().split(/\s+/);
  while (words.length && (SELLER_STOPWORDS.has(words[words.length - 1]!.toLowerCase()) || /^\d+$/.test(words[words.length - 1]!))) words.pop();
  return words.join(" ");
}

function cleanSellerName(raw: string): string {
  const words = raw.trim().split(/\s+/);
  const cut = words.findIndex((w, i) => i > 0 && SELLER_CUT.has(w.toLowerCase().replace(/[^a-z]/g, "")));
  return stripSeller((cut > 0 ? words.slice(0, cut) : words).join(" "));
}

// The \b after the keyword matters: without it "sellers" matches "seller"
// and the capture becomes junk like "s Compare all" (seen live on Walmart).
const SELLER_RE = /(?:sold by|seller|vendor|offered by)\b\s*[:\-–—]?\s*([A-Z0-9][\w&'-]*(?:\s+[A-Z0-9][\w&'-]*){0,3})/i;
const STOCK_RE = /\b(in stock|out of stock|only \d+ left(?: in stock)?|available|pre[\- ]?order|backorder)\b/i;
const CONDITION_RE = /\b(new|used\s*[–\-]?\s*like new|refurbished|open box|pre[\- ]?owned)\b/i;
const LOCATION_RE = /(?:ships from|located in|location|dispatched from)\s*[:\-–—]?\s*([A-Z][\w&'-]*(?:\s+[A-Z][\w&'-]*){0,3})/;

/**
 * Generic listing extraction — a port of jarvis-v4 shopping/listings.py.
 * URL-agnostic and realm-blind: one row per priced block (multi-seller
 * pages yield one row per seller-offer), every row with provenance.
 * Blocks with no parseable price are skipped; malformed prices never
 * corrupt the table.
 */
export function extractListings(html: string, pageUrl: string): ListingRow[] {
  const rows: ListingRow[] = [];
  if (!html || !pageUrl) return rows;
  const page = html.replace(/<head[\s\S]*?<\/head>/i, " ");
  for (const block of page.split(/<\/(?:div|li|tr|article|section)>/i)) {
    const chunk = cleanText(block);
    if (!chunk) continue;
    // The block's price is its FIRST price in document order (listing
    // blocks lead with the item price; a sort would hand the block to its
    // own shipping fee whenever the fee is smaller).
    const prices = extractPricesInOrder(chunk);
    if (!prices.length) continue;
    const price = prices[0]!;
    const sm = SELLER_RE.exec(chunk);
    const seller = sm ? cleanSellerName(sm[1]!) : "";
    const stock = STOCK_RE.exec(chunk)?.[1] ?? "";
    const condition = CONDITION_RE.exec(chunk)?.[1] ?? "";
    const location = LOCATION_RE.exec(chunk)?.[1]?.trim() ?? "";
    const href = /href=["']([^"']+)["']/i.exec(block)?.[1];
    const heading = /<h[1-3][^>]*>([\s\S]*?)<\/h[1-3]>/i.exec(block)?.[1];
    const freeShipping = /free shipping|free delivery/i.test(chunk);
    // Shipping counts only when the page labels it as shipping — a second
    // price in the block is usually the same price repeated (retail cards
    // repeat prices in several formats), not a shipping charge.
    const shipM = /(?:shipping|delivery|postage)[^$€£¥₹\d]{0,24}((?:[$€£¥₹]\s*)?[\d][\d.,]*)/i.exec(chunk)
      ?? /\+\s*((?:[$€£¥₹]\s*)?[\d][\d.,]*)\s*(?:shipping|delivery|postage)/i.exec(chunk);
    const shipping = freeShipping || !shipM ? null : (extractPrices(shipM[1]!)[0] ?? null);
    rows.push(blankRow({
      title: heading ? cleanTitle(cleanText(heading)).slice(0, 120) : (chunk.split(". ")[0]!.slice(0, 120) || "(untitled listing)"),
      price, shipping, total: totalOf(price, shipping, freeShipping), freeShipping,
      seller, stock, condition, location,
      link: href ? resolveUrl(href, pageUrl) : pageUrl,
      sourceUrl: pageUrl, year: sourceYear(chunk),
    }));
  }
  return rows;
}

/**
 * eBay search-results parser (the first-class marketplace). eBay result
 * blocks carry the seller line as "name 99.5% Positive (1,234)" — that is
 * page fact, extracted as such. Falls back cleanly: no s-item blocks, no
 * rows (the caller then tries the generic extractor).
 */
export function parseEbayResults(html: string, pageUrl: string): ListingRow[] {
  const rows: ListingRow[] = [];
  const blocks = (html || "").split(/<li[^>]+class="[^"]*\bs-item\b/).slice(1);
  for (const raw of blocks) {
    const block = raw.split(/<li[^>]+class="[^"]*\bs-item\b/)[0]!;
    const titleM = /class="[^"]*s-item__title[^"]*"[^>]*>([\s\S]*?)<\//.exec(block)
      ?? /<a[^>]+class="[^"]*s-item__link[^"]*"[^>]*>([\s\S]*?)<\/a>/.exec(block);
    const title = titleM ? cleanTitle(cleanText(titleM[1]!)) : "";
    if (!title || /^shop on ebay$/i.test(title)) continue; // placeholder card
    const href = /<a[^>]+class="[^"]*s-item__link[^"]*"[^>]*href="([^"]+)"/.exec(block)?.[1]
      ?? /href="(https?:\/\/[^"]*\/itm\/[^"]+)"/.exec(block)?.[1];
    const priceM = /class="[^"]*s-item__price[^"]*"[^>]*>([\s\S]*?)<\//.exec(block);
    const price = priceM ? (extractPrices(cleanText(priceM[1]!))[0] ?? null) : null;
    if (!price) continue;
    const shipM = /class="[^"]*(?:s-item__shipping|s-item__logisticsCost)[^"]*"[^>]*>([\s\S]*?)<\//.exec(block);
    const shipText = shipM ? cleanText(shipM[1]!) : "";
    const freeShipping = /free/i.test(shipText);
    const shipping = freeShipping ? null : (extractPrices(shipText)[0] ?? null);
    const sellerM = /class="[^"]*s-item__seller-info-text[^"]*"[^>]*>([\s\S]*?)<\//.exec(block);
    const sellerText = sellerM ? cleanText(sellerM[1]!) : "";
    const sellerParts = /^(.*?)\s+([\d.]+)%\s*positive\s*\(([\d,]+)\)/i.exec(sellerText);
    const condM = /class="[^"]*s-item__subtitle[^"]*"[^>]*>([\s\S]*?)<\//.exec(block);
    rows.push(blankRow({
      title: title.slice(0, 140),
      price, shipping, total: totalOf(price, shipping, freeShipping), freeShipping,
      seller: sellerParts ? sellerParts[1]!.trim() : sellerText.split(/\s+\d/)[0]!.trim(),
      sellerPositivePct: sellerParts ? parseFloat(sellerParts[2]!) : null,
      sellerFeedbackCount: sellerParts ? parseInt(sellerParts[3]!.replace(/,/g, ""), 10) : null,
      condition: condM ? cleanText(condM[1]!).slice(0, 60) : "",
      link: href ? resolveUrl(href, pageUrl) : pageUrl,
      sourceUrl: pageUrl, year: sourceYear(block),
    }));
  }
  return rows;
}

// ---------- structured claims (the NLI seam) ----------

/**
 * One claim extracted from a page, as plain data: what kind of claim,
 * the value as the page states it, the evidence snippet it came from,
 * and the source URL. A later claim-check/NLI layer verifies these
 * without re-parsing prose; the tools themselves stay mechanical.
 */
export interface Claim {
  kind: "title" | "price" | "shipping" | "seller" | "rating" | "feedback_count" | "returns" | "stock" | "condition" | "account_age";
  value: string;
  evidence: string;
  source: string;
}

export interface ListingDetail {
  url: string;
  title: string;
  price: Money | null;
  shipping: Money | null;
  freeShipping: boolean;
  total: Money | null;
  seller: string;
  returns: string;
  stock: string;
  condition: string;
  text: string;
  claims: Claim[];
  /** The page's image URLs, structured data first — the vision step reads these. */
  images: string[];
}

function jsonLdProduct(html: string): Record<string, unknown> | null {
  for (const m of html.matchAll(/<script[^>]+type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi)) {
    try {
      const data = JSON.parse(m[1]!) as unknown;
      const items = Array.isArray(data) ? data : [data];
      for (const it of items) {
        const o = it as Record<string, unknown>;
        const t = String(o["@type"] || "");
        if (/product/i.test(t) || o["offers"]) return o;
        const graph = o["@graph"];
        if (Array.isArray(graph)) {
          for (const g of graph) {
            const go = g as Record<string, unknown>;
            if (/product/i.test(String(go["@type"] || "")) || go["offers"]) return go;
          }
        }
      }
    } catch { /* not JSON — ignore the block */ }
  }
  return null;
}

function metaContent(html: string, prop: string): string {
  const m = new RegExp(`<meta[^>]+(?:property|name)=["']${prop}["'][^>]+content=["']([^"']+)["']`, "i").exec(html)
    ?? new RegExp(`<meta[^>]+content=["']([^"']+)["'][^>]+(?:property|name)=["']${prop}["']`, "i").exec(html);
  return m ? m[1]!.replace(/&amp;/g, "&") : "";
}

/**
 * The page's image URLs in reading order: the product's structured-data
 * image first (the listing's own photo, most likely), then og:image /
 * twitter:image, then <img> tags in document order. Resolved against the
 * page, deduped, capped. data:/blob: URLs are skipped — they cannot be
 * fetched through the funnel — and so are formats the vision route
 * cannot read (SVG/GIF/WebP/ICO/AVIF by extension). The vision step says
 * what it could not read rather than pretending.
 */
export function extractImageUrls(html: string, pageUrl: string): string[] {
  const out: string[] = [];
  const seen = new Set<string>();
  const push = (raw: unknown) => {
    if (typeof raw !== "string") return;
    const t = raw.trim();
    if (!t || /^(data|blob):/i.test(t)) return;
    const abs = resolveUrl(t, pageUrl);
    if (!/^https?:\/\//i.test(abs) || seen.has(abs)) return;
    // The vision route reads PNG and JPEG; formats it cannot read (vector
    // or animated chrome, mostly site logos) never enter the walk.
    if (/\.(svg|svgz|gif|webp|ico|avif)(\?|#|$)/i.test(abs)) return;
    seen.add(abs);
    out.push(abs);
  };
  const ld = jsonLdProduct(html);
  const ldImg = ld?.["image"] as unknown;
  if (typeof ldImg === "string") push(ldImg);
  else if (Array.isArray(ldImg)) for (const im of ldImg) push(typeof im === "string" ? im : (im as { url?: unknown } | null)?.url);
  else if (ldImg && typeof ldImg === "object") push((ldImg as { url?: unknown }).url);
  push(metaContent(html, "og:image"));
  push(metaContent(html, "twitter:image"));
  for (const m of html.matchAll(/<img[^>]+src=["']([^"']+)["']/gi)) { push(m[1]); if (out.length >= 8) break; }
  for (const m of html.matchAll(/<img[^>]+data-src=["']([^"']+)["']/gi)) { push(m[1]); if (out.length >= 8) break; }
  return out.slice(0, 8);
}

/** Fetch-shaped extraction of one listing/product page into claims + fields. */
export function parseListingPage(html: string, pageUrl: string): ListingDetail {
  const text = cleanText(html);
  const claims: Claim[] = [];
  const claim = (kind: Claim["kind"], value: string, evidence: string) => {
    if (value) claims.push({ kind, value, evidence: evidence.slice(0, 200), source: pageUrl });
  };

  const ld = jsonLdProduct(html);
  const offers = (ld?.["offers"] ?? null) as Record<string, unknown> | null;
  const agg = (ld?.["aggregateRating"] ?? null) as Record<string, unknown> | null;

  const h1 = /<h1[^>]*>([\s\S]*?)<\/h1>/i.exec(html)?.[1];
  const title = (ld?.["name"] ? String(ld["name"]) : "") || (h1 ? cleanText(h1) : "") || metaContent(html, "og:title") || cleanText(/<title>([\s\S]*?)<\/title>/i.exec(html)?.[1] ?? "").replace(/\s*[||·–—-]\s*(eBay|Amazon|Etsy|Walmart).*$/i, "");
  claim("title", title, title);

  let price: Money | null = null;
  let priceStructured = false;
  if (offers && offers["price"] != null) {
    const amt = parseFloat(String(offers["price"]));
    const cur = String(offers["priceCurrency"] || "USD");
    if (!Number.isNaN(amt)) { price = { amount: amt, currency: cur, raw: `${amt} ${cur}` }; priceStructured = true; }
  }
  if (!price) {
    const ip = /itemprop=["']price["'][^>]+content=["']([\d.,]+)["']/i.exec(html)?.[1];
    if (ip) { price = { amount: parseFloat(ip.replace(/,/g, "")), currency: "USD", raw: ip }; priceStructured = true; }
  }
  if (!price) {
    const labeled = /(?:price|total)[:\s]*((?:[$€£¥₹]\s*)?[\d][\d.,]*\s*(?:USD|EUR|GBP|JPY|INR|CAD|AUD)?)/i.exec(text);
    if (labeled) price = extractPrices(labeled[1]!)[0] ?? null;
  }
  if (!price) price = extractPrices(text.slice(0, 4000))[0] ?? null;
  if (price) {
    claim("price", fmtMoney(price), price.raw);
    // A price picked from raw page text (not the page's structured data)
    // can be a list/compare-at price while the buy price sits elsewhere —
    // seen live on Walmart. Surface the ambiguity instead of one
    // confident number: every other distinct price on the page is listed.
    if (!priceStructured) {
      const others = extractPrices(text).filter((p) => p.currency === price!.currency && p.amount !== price!.amount).slice(0, 5);
      if (others.length) {
        claim("price", `not from structured data — the page also shows: ${others.map((p) => fmtMoney(p)).join(", ")}`, "all prices appearing on the page");
      }
    }
  }

  const freeShipping = /free shipping|free delivery/i.test(text);
  let shipping: Money | null = null;
  const shipM = /(?:shipping|delivery)[:\s]*((?:[$€£¥₹]\s*)?[\d][\d.,]*)/i.exec(text);
  if (!freeShipping && shipM) shipping = extractPrices(shipM[1]!)[0] ?? null;
  claim("shipping", freeShipping ? "Free" : fmtMoney(shipping), /shipping[^.]{0,60}/i.exec(text)?.[0] ?? "");
  const total = totalOf(price, shipping, freeShipping);
  if (total && total !== price) claim("price", `total ${fmtMoney(total)}`, "price + shipping as stated on the page");

  const sellerM = SELLER_RE.exec(text) ?? /(?:^|\s)([A-Za-z0-9_.-]+)\s*\(([\d,]+)\)\s*(?:feedback|positive)/i.exec(text);
  const seller = sellerM ? cleanSellerName(sellerM[1]!) : "";
  claim("seller", seller, sellerM?.[0] ?? "");

  const returnsM = /(no returns(?: accepted)?|returns not accepted|all sales final|[\d]+[\s-]?day returns?|free returns|returns accepted|buyer pays for return shipping)/i.exec(text);
  const returns = returnsM ? returnsM[1]!.replace(/\s+/g, " ") : "";
  claim("returns", returns, returnsM ? evidenceAround(text, returnsM) : "");

  const stock = STOCK_RE.exec(text)?.[1] ?? "";
  claim("stock", stock, stock);
  const condition = CONDITION_RE.exec(text)?.[1] ?? "";
  claim("condition", condition, condition);

  if (agg) {
    if (agg["ratingValue"] != null) claim("rating", String(agg["ratingValue"]), "aggregateRating in the page's structured data");
    if (agg["reviewCount"] != null) claim("feedback_count", String(agg["reviewCount"]), "aggregateRating in the page's structured data");
  }

  return { url: pageUrl, title, price, shipping, freeShipping, total, seller, returns, stock, condition, text, claims, images: extractImageUrls(html, pageUrl) };
}

// ---------- scam signals (page-local, evidence-named) ----------

export interface ScamSignal {
  signal: string;
  evidence: string;
  source: string;
}

const SIGNAL_PATTERNS: Array<{ name: string; re: RegExp; why: string }> = [
  {
    name: "off_platform_payment_lure",
    why: "asks to pay or make contact outside the marketplace",
    re: /(pay|payment|contact|message|email|text)\b[^.]{0,70}\b(outside|off)\b[^.]{0,25}\b(ebay|amazon|etsy|walmart|the platform|this site)\b|\bzelle\b|\bwire transfer\b|\bgift cards?\b|\bvenmo\b|\bcash ?app\b/i,
  },
  {
    name: "no_returns",
    why: "the page states returns are refused",
    re: /\bno returns(?: accepted)?\b|\breturns not accepted\b|\ball sales final\b/i,
  },
  {
    name: "crypto_payment_demand",
    why: "payment demanded in cryptocurrency",
    re: /\b(bitcoin|btc|ethereum|crypto(?:currency)?)\s+(only|payment|accepted|payments)\b|\bpayment in (bitcoin|btc|crypto)\b/i,
  },
  {
    name: "urgency_pressure",
    why: "pressure phrasing from the scam phrase families",
    re: /\b(urgent|immediately|act now|last chance|must sell today|within \d+ (hours|minutes))\b/i,
  },
  {
    name: "extreme_discount_claim",
    why: "discount claim of 70% or more in the page text",
    re: /\b([7-9]\d|100)\s?%\s*off\b/i,
  },
];

/**
 * Mechanical scam-signal scan of one fetched page (plus, optionally, the
 * prices of comparable listings the caller already saw). Every signal is
 * named with the page evidence for it. No signal is a verdict.
 */
export function scanScamSignals(detail: ListingDetail, seenPrices: Money[] = []): ScamSignal[] {
  const out: ScamSignal[] = [];
  for (const p of SIGNAL_PATTERNS) {
    const m = p.re.exec(detail.text);
    if (m) out.push({ signal: p.name, evidence: `${p.why}: "${evidenceAround(detail.text, m)}"`, source: detail.url });
  }
  const fb = detail.claims.find((c) => c.kind === "feedback_count");
  if (fb && /^0+$/.test(fb.value.replace(/,/g, ""))) {
    out.push({ signal: "fresh_seller_no_feedback", evidence: "seller feedback count on the page is 0", source: detail.url });
  }
  if (detail.price && seenPrices.length >= 3) {
    const same = seenPrices.filter((p) => p.currency === detail.price!.currency).map((p) => p.amount).sort((a, b) => a - b);
    if (same.length >= 3) {
      const median = same[Math.floor(same.length / 2)]!;
      if (detail.price.amount < 0.5 * median) {
        out.push({
          signal: "price_far_below_comparables",
          evidence: `page price ${fmtMoney(detail.price)} is under half the median ${median.toFixed(2)} ${detail.price.currency} of ${same.length} comparable listings supplied by the caller`,
          source: detail.url,
        });
      }
    }
  }
  return out;
}

// ---------- reputation (port of jarvis-v4 shopping/reputation.py) ----------

export interface Reputation {
  seller: string;
  ratingOutOf5: number | null;
  positivePct: number | null;
  feedbackCount: number | null;
  disputes: number | null;
  accountAge: string;
  feedback: Array<{ text: string; source: string }>;
  sources: string[];
  claims: Claim[];
  /** The page's image URLs (storefront/logo photos) — the vision step reads these. */
  images: string[];
}

export function extractReputation(html: string, pageUrl: string, seller = ""): Reputation {
  const text = cleanText(html);
  const claims: Claim[] = [];
  const claim = (kind: Claim["kind"], value: string, evidence: string) => {
    if (value) claims.push({ kind, value, evidence: evidence.slice(0, 200), source: pageUrl });
  };
  const ratingM = /(\d(?:[.,]\d)?)\s*(?:\/|out of)\s*5(?:\.0)?/.exec(text);
  const pctM = /\b(\d{1,3}(?:\.\d)?)\s*%\s*(?:positive|approval)/i.exec(text);
  const countM = /([\d,]+)\s*(?:reviews|ratings|feedback|transactions)/i.exec(text);
  const disputeM = /([\d,]+)\s*(?:disputes|negative feedback|complaints)/i.exec(text);
  const ageM = /(?:member since|joined|on (?:the )?platform since|account (?:age|created))\s*[:\-–—]?\s*([\w ,./]{3,30})/i.exec(text);
  const ratingOutOf5 = ratingM ? parseFloat(ratingM[1]!.replace(",", ".")) : null;
  const positivePct = pctM ? parseFloat(pctM[1]!) : null;
  const feedbackCount = countM ? parseInt(countM[1]!.replace(/,/g, ""), 10) : null;
  const disputes = disputeM ? parseInt(disputeM[1]!.replace(/,/g, ""), 10) : null;
  const accountAge = ageM ? ageM[1]!.trim().slice(0, 40) : "";
  claim("rating", ratingOutOf5 !== null ? `${ratingOutOf5}/5` : positivePct !== null ? `${positivePct}% positive` : "", ratingM?.[0] ?? pctM?.[0] ?? "");
  claim("feedback_count", feedbackCount !== null ? String(feedbackCount) : "", countM?.[0] ?? "");
  claim("account_age", accountAge, ageM?.[0] ?? "");

  const feedback: Array<{ text: string; source: string }> = [];
  for (const m of html.matchAll(/<(?:p|div|li)[^>]*>([\s\S]{20,300}?)<\/(?:p|div|li)>/gi)) {
    const snip = cleanText(m[1]!);
    if (feedback.length < 5 && snip.length > 25 && /\b(ship|item|seller|quality|fast|slow|broken|great|poor|recommend|refund|arrived|exactly)\b/i.test(snip)) {
      feedback.push({ text: snip.slice(0, 200), source: pageUrl });
    }
  }
  return { seller, ratingOutOf5, positivePct, feedbackCount, disputes, accountAge, feedback, sources: [pageUrl], claims, images: extractImageUrls(html, pageUrl) };
}

// ---------- reviews ----------

export interface ReviewRecord {
  rating: number | null;
  title: string;
  text: string;
  source: string;
}

export interface ReviewSet {
  reviews: ReviewRecord[];
  statedAverage: number | null;
  statedCount: number | null;
  distribution: Record<string, number>;
  praiseThemes: Array<{ phrase: string; reviews: number; quote: string; source: string }>;
  complaintThemes: Array<{ phrase: string; reviews: number; quote: string; source: string }>;
}

function reviewRating(chunk: string): number | null {
  const aria = /aria-label=["']([\d.]+) out of 5/i.exec(chunk)?.[1];
  if (aria) return parseFloat(aria);
  const txt = /([\d.]+)\s*(?:out of|\/)\s*5/.exec(chunk)?.[1];
  if (txt) return parseFloat(txt);
  const stars = (chunk.match(/★/g) || []).length;
  if (stars >= 1 && stars <= 5) return stars;
  return null;
}

const STOPWORDS = new Set("the a an and or of to in is it its this that with for on was are be as at by from has have had not but you your they them then than so if when what which who how all any can could would should there their been were will would one two three four five get got just like more most other some such only over under again very really out about into after before".split(" "));

function themes(reviews: ReviewRecord[], pick: (r: ReviewRecord) => boolean): ReviewSet["praiseThemes"] {
  const counts = new Map<string, { n: number; quote: string; source: string }>();
  for (const r of reviews) {
    if (!pick(r)) continue;
    const words = r.text.toLowerCase().replace(/[^a-z0-9\s]/g, " ").split(/\s+/).filter((w) => w.length >= 3 && !STOPWORDS.has(w));
    const seenInReview = new Set<string>();
    for (let n = 2; n <= 3; n++) {
      for (let i = 0; i + n <= words.length; i++) {
        const phrase = words.slice(i, i + n).join(" ");
        if (seenInReview.has(phrase)) continue;
        seenInReview.add(phrase);
        const sentence = r.text.split(/(?<=[.!?])\s+/).find((s) => s.toLowerCase().includes(phrase)) ?? r.text;
        const cur = counts.get(phrase) ?? { n: 0, quote: sentence.slice(0, 160), source: r.source };
        cur.n += 1;
        counts.set(phrase, cur);
      }
    }
  }
  return [...counts.entries()]
    .filter(([, v]) => v.n >= 2)
    .sort((a, b) => b[1].n - a[1].n)
    .slice(0, 3)
    .map(([phrase, v]) => ({ phrase, reviews: v.n, quote: v.quote, source: v.source }));
}

/** Extract individual reviews + the page's own stated aggregates. No invented consensus. */
export function extractReviews(html: string, pageUrl: string): ReviewSet {
  const text = cleanText(html);
  const statedAvgM = /(?:average rating|overall rating)[:\s]*([\d.]+)/i.exec(text) ?? /([\d.]+)\s*out of 5 stars/i.exec(text);
  const statedCountM = /([\d,]+)\s*(?:global ratings|verified ratings|total reviews|reviews)/i.exec(text);
  const reviews: ReviewRecord[] = [];
  const chunks = (html || "").split(/(?=<(?:div|li|article)[^>]+class="[^"]*review)/i);
  // chunks[0] is the page preamble before the first review block — it can
  // carry the stated average ("4.5 out of 5 stars") and must not be
  // mistaken for a review itself.
  for (const chunk of chunks.slice(1)) {
    const rating = reviewRating(chunk);
    if (rating === null) continue;
    const body = cleanText(chunk);
    if (body.length < 25) continue;
    const titleM = /<(?:strong|b|h[34])[^>]*>([\s\S]*?)<\/(?:strong|b|h[34])>/i.exec(chunk);
    reviews.push({
      rating,
      title: titleM ? cleanText(titleM[1]!).slice(0, 100) : "",
      text: body.slice(0, 400),
      source: pageUrl,
    });
    if (reviews.length >= 40) break;
  }
  const distribution: Record<string, number> = {};
  for (const r of reviews) {
    const k = String(Math.round(r.rating ?? 0));
    distribution[k] = (distribution[k] ?? 0) + 1;
  }
  return {
    reviews,
    statedAverage: statedAvgM ? parseFloat(statedAvgM[1]!) : null,
    statedCount: statedCountM ? parseInt(statedCountM[1]!.replace(/,/g, ""), 10) : null,
    distribution,
    praiseThemes: themes(reviews, (r) => (r.rating ?? 0) >= 4),
    complaintThemes: themes(reviews, (r) => (r.rating ?? 5) <= 2),
  };
}

// ---------- mechanical sorting (labeled, never a recommendation) ----------

export function sortRowsMechanically(rows: ListingRow[], sort: "price" | "rating"): { rows: ListingRow[]; label: string } {
  const byPrice = (r: ListingRow) => r.total?.amount ?? r.price?.amount ?? Number.POSITIVE_INFINITY;
  if (sort === "rating") {
    const sorted = [...rows].sort((a, b) =>
      (b.sellerPositivePct ?? -1) - (a.sellerPositivePct ?? -1)
      || (b.sellerFeedbackCount ?? -1) - (a.sellerFeedbackCount ?? -1)
      || byPrice(a) - byPrice(b));
    return { rows: sorted, label: "seller positive rating (highest first), then feedback count (highest) — mechanical ordering, not a recommendation" };
  }
  const sorted = [...rows].sort((a, b) => byPrice(a) - byPrice(b));
  return { rows: sorted, label: "total price (lowest first) — mechanical ordering, not a recommendation" };
}

// ---------- marketplace navigation ----------

export interface NavLinks {
  next?: string;
  prev?: string;
  sorts: Array<{ label: string; url: string }>;
}

/** Pagination and sort/filter links, read from the page's own anchors — no site APIs. */
export function extractNavLinks(html: string, pageUrl: string): NavLinks {
  const out: NavLinks = { sorts: [] };
  for (const m of (html || "").matchAll(/<a[^>]+href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi)) {
    const href = resolveUrl(m[1]!, pageUrl);
    if (href === pageUrl) continue;
    const rel = /rel=["']([^"']+)["']/i.exec(m[0])?.[1] ?? "";
    const label = cleanText(m[2]!).replace(/\s+/g, " ").trim();
    if (!out.next && (/\bnext\b/i.test(rel) || /^(next|next page|›|»)$/i.test(label) || /\bnext\b/i.test(label) && label.length <= 30)) out.next = href;
    else if (!out.prev && (/\bprev\b/i.test(rel) || /^(prev|previous|prev page|‹|«)$/i.test(label))) out.prev = href;
    if (
      out.sorts.length < 8
      && /((price|postage|shipping)\s*\+\s*(shipping|price)|price|total)[^<]{0,40}(lowest|highest)|newly listed|newest first|best match|ending soonest/i.test(label)
      && !out.sorts.some((s) => s.url === href)
    ) {
      out.sorts.push({ label: label.slice(0, 80), url: href });
    }
  }
  return out;
}

export interface NavSession {
  currentUrl: string;
  history: string[];
  rows: ListingRow[];
  links: NavLinks;
  kind: "results" | "listing" | "";
}

export const navSessions = new Map<string, NavSession>();

function newSession(): NavSession {
  return { currentUrl: "", history: [], rows: [], links: { sorts: [] }, kind: "" };
}

function renderRows(rows: ListingRow[], heading: string): string {
  const lines = [heading];
  rows.forEach((r, i) => {
    const sellerBit = r.seller
      ? ` · seller ${r.seller}${r.sellerPositivePct !== null ? ` (${r.sellerPositivePct}% positive${r.sellerFeedbackCount !== null ? `, ${r.sellerFeedbackCount} feedback` : ""})` : ""}`
      : "";
    const shipBit = r.freeShipping ? " · free shipping" : r.shipping ? ` · shipping ${fmtMoney(r.shipping)}` : "";
    const totalBit = r.total && r.price && r.total.amount !== r.price.amount ? ` · total ${fmtMoney(r.total)}` : "";
    lines.push(`${i + 1}. ${r.title}\n   ${fmtMoney(r.price)}${shipBit}${totalBit}${sellerBit}\n   ${r.link}`);
  });
  return lines.join("\n");
}

function navFooter(sess: NavSession): string {
  const bits: string[] = [`Page: ${sess.currentUrl}`];
  if (sess.links.next) bits.push("a Next page link is present (marketplace_navigate action \"next\")");
  if (sess.links.prev) bits.push("a Previous page link is present (action \"prev\")");
  if (sess.links.sorts.length) bits.push(`sort/filter links on this page: ${sess.links.sorts.map((s) => s.label).join(" | ")} (action "sort", by price_asc | price_desc | newest)`);
  if (sess.history.length) bits.push("action \"back\" returns to the previous page");
  return bits.join("\n");
}

/**
 * Session-based marketplace navigation — general and URL-agnostic, built
 * from page structure the way the phone package's extraction is
 * content-driven. (Owner design change, desktop only: the phone
 * package's no-navigation stance does not apply here.) Read-only: no
 * carts, no bids, no purchases, no login handling.
 */
export async function runMarketplaceNav(
  sessionId: string,
  args: { action: string; url?: string; index?: number; by?: string },
  signal: AbortSignal,
  fetchFn: FetchFn = safeFetch,
  sessions: Map<string, NavSession> = navSessions,
): Promise<string> {
  const sess = sessions.get(sessionId) ?? newSession();
  sessions.set(sessionId, sess);

  const fetchInto = async (target: string, pushCurrent: boolean): Promise<string> => {
    let res: { status: number; type: string; body: string; url: string };
    try {
      res = await fetchFn(target, signal);
    } catch (err) {
      return `Could not fetch ${target}: ${err instanceof Error ? err.message : String(err)}`;
    }
    if (res.status >= 400) {
      return `The site answered HTTP ${res.status} for ${target}. It may block automated fetches or need a login this tool cannot do. Nothing was extracted.`;
    }
    if (pushCurrent && sess.currentUrl && sess.currentUrl !== target) sess.history.push(sess.currentUrl);
    // Work from the URL the fetch actually landed on (redirects followed).
    const finalUrl = res.url || target;
    sess.currentUrl = finalUrl;
    sess.links = extractNavLinks(res.body, finalUrl);
    const ebayRows = parseEbayResults(res.body, finalUrl);
    // A row that links back to this very page is the page's own product,
    // not a navigable result — what remains decides results vs listing.
    const rows = (ebayRows.length ? ebayRows : extractListings(res.body, finalUrl))
      .filter((r) => r.link !== finalUrl);
    if (rows.length) {
      sess.rows = rows;
      sess.kind = "results";
      return `${renderRows(rows, `Results — ${rows.length} listing(s)`)}\n${navFooter(sess)}`;
    }
    sess.kind = "listing";
    const detail = parseListingPage(res.body, finalUrl);
    if (detail.title || detail.price) {
      return `Listing page: ${detail.title || "(untitled)"}\nPrice: ${fmtMoney(detail.price)}${detail.freeShipping ? " · free shipping" : detail.shipping ? ` · shipping ${fmtMoney(detail.shipping)}` : ""}${detail.seller ? `\nSeller: ${detail.seller}` : ""}\n${finalUrl}\nRun product_check on this URL for the full verification table.\n${navFooter(sess)}`;
    }
    return `Fetched ${finalUrl} (HTTP ${res.status}) but no listings or product data could be extracted — the page may need JavaScript. Nothing is claimed from it.`;
  };

  switch (args.action) {
    case "open": {
      if (!args.url) return "marketplace_navigate open needs a url (a marketplace search or category page).";
      return fetchInto(args.url, true);
    }
    case "next": {
      if (!sess.links.next) return sess.currentUrl ? "This page has no Next link — there is no further results page to follow." : "No navigation session yet. Open a marketplace search or category URL first.";
      return fetchInto(sess.links.next, true);
    }
    case "prev": {
      if (!sess.links.prev) return sess.currentUrl ? "This page has no Previous link." : "No navigation session yet. Open a marketplace search or category URL first.";
      return fetchInto(sess.links.prev, true);
    }
    case "sort": {
      if (!sess.links.sorts.length) return "No sort/filter links were found on the current page.";
      const by = (args.by || "").toLowerCase();
      const pick = sess.links.sorts.find((s) =>
        (by === "price_asc" && /lowest/i.test(s.label))
        || (by === "price_desc" && /highest/i.test(s.label))
        || (by === "newest" && /newly|newest/i.test(s.label))
        || (by && s.label.toLowerCase().includes(by)));
      if (!pick) return `No sort link matches "${args.by}". Available on this page: ${sess.links.sorts.map((s) => s.label).join(" | ")}`;
      return fetchInto(pick.url, true);
    }
    case "open_listing": {
      const idx = args.index ?? 0;
      const row = sess.rows[idx - 1];
      if (!row) return sess.rows.length ? `There are ${sess.rows.length} listing(s) in the current results; pick an index from 1 to ${sess.rows.length}.` : "No results in this session yet. Open a marketplace search or category URL first.";
      return fetchInto(row.link, true);
    }
    case "back": {
      const target = sess.history.pop();
      if (!target) return "Nothing to go back to in this session.";
      return fetchInto(target, false);
    }
    case "results": {
      if (!sess.rows.length) return "No results in this session yet. Open a marketplace search or category URL first.";
      return `${renderRows(sess.rows, `Current results — ${sess.rows.length} listing(s)`)}\n${navFooter(sess)}`;
    }
    default:
      return `Unknown navigation action "${args.action}". Use open, next, prev, sort, open_listing, back or results.`;
  }
}

// ---------- owner memory ("plugs": known non-scammers) ----------

/**
 * Consult the owner's memory for a seller. A match is surfaced as
 * evidence labeled as memory — it is not page fact, and it means a
 * seller the owner saved is never reported as simply unknown.
 */
async function memoryNotes(ctx: ToolContext, seller: string): Promise<string[]> {
  if (!seller || !ctx.memory) return [];
  try {
    const facts = await ctx.memory.search(seller, ctx.embedder ?? null);
    const norm = (s: string) => s.toLowerCase().replace(/[^a-z0-9]/g, "");
    const n = norm(seller);
    if (!n) return [];
    return facts.filter((f) => norm(f.text).includes(n)).map((f) => f.text);
  } catch {
    return [];
  }
}

function memoryLines(notes: string[]): string {
  if (!notes.length) return "";
  return `\nOwner memory (the owner's own saved facts — labeled as memory, not page evidence):\n${notes.map((n) => `- ${n}`).join("\n")}`;
}

// ---------- vision-read evidence (a fixed step of every check when vision is on) ----------

const VISION_QUESTION_PRODUCT =
  "This is a photo from a product listing. Report only what is visible in the image: " +
  "(1) any price or price-tag text, exactly as shown; " +
  "(2) the item's apparent condition (new/sealed, like new, used, worn, damaged) and the cues you see; " +
  "(3) quantity, pack contents or bundle items visible; " +
  "(4) brand name, logo or markings; " +
  "(5) any other readable text. " +
  "If a thing is not visible, say it is not visible. Do not guess, and do not use outside knowledge.";

const VISION_QUESTION_SELLER =
  "This is a photo from a seller's page or storefront. Report only what is visible in the image: " +
  "any business name or signage text, exactly as shown; logos or brand markings; what is being sold or displayed; " +
  "and any other readable text. If a thing is not visible, say so. Do not guess.";

export interface VisionRead {
  image: string;
  text: string;
}

const errText = (err: unknown) => (err instanceof Error ? err.message : String(err));

/**
 * Read the page's images through the vision route: the primary image,
 * plus up to two more when an earlier read yields little (a substantial
 * report ends the walk). Every image that cannot be read earns a named
 * line with the reason — nothing is silently skipped.
 */
async function readListingImages(images: string[], question: string, ctx: ToolContext, bytesFn: WebFetchBytesFn): Promise<{ lines: string[]; reads: VisionRead[] }> {
  const lines: string[] = [];
  const reads: VisionRead[] = [];
  for (const img of images.slice(0, 6)) {
    if (reads.length >= 3) break;
    if (reads.length && reads[reads.length - 1]!.text.length >= 60) break;
    let f: FetchedBytes;
    try {
      f = await bytesFn(img, ctx.signal);
    } catch (err) {
      lines.push(`- ${img} — could not be fetched for reading: ${errText(err)}`);
      continue;
    }
    const mediaType = /^image\/png/i.test(f.type) ? "image/png" as const : /^image\/jpe?g/i.test(f.type) ? "image/jpeg" as const : null;
    if (!mediaType) {
      lines.push(`- ${img} — ${f.type || "no content-type"}: the vision route reads PNG and JPEG, so this image was not read.`);
      continue;
    }
    if (f.bytes.length > MAX_IMAGE_BYTES) {
      lines.push(`- ${img} — too large to read (over ${Math.round(MAX_IMAGE_BYTES / (1024 * 1024))} MB).`);
      continue;
    }
    try {
      const text = (await ctx.vision!.describe([{ data: f.bytes.toString("base64"), mediaType }], question, ctx.signal)).replace(/\s+/g, " ").trim();
      reads.push({ image: img, text });
      lines.push(`- ${img}\n  Vision read: "${text.slice(0, 800)}"`);
    } catch (err) {
      lines.push(`- ${img} — the vision model could not read it: ${errText(err)}`);
    }
  }
  return { lines, reads };
}

/**
 * The vision step of a check. It ALWAYS runs — when vision is on it is
 * part of the model, not a step anyone chooses — and it always leaves
 * its block in the output: the readings, or one plain line saying why
 * there are none (off in settings / no vision backend / no usable
 * image). Vision facts never touch the page-text table; a price visible
 * in an image that disagrees with the page's stated price becomes a
 * named CONFLICT line, reported and never silently resolved.
 */
async function visionEvidenceBlock(opts: { images: string[]; question: string; pagePrice: Money | null; ctx: ToolContext; bytesFn: WebFetchBytesFn }): Promise<string> {
  const { ctx } = opts;
  if (ctx.visionEnabled === false) {
    return "\nVision-read evidence: off — vision is turned off in settings (the only switch), so no images were read for this check.";
  }
  if (!ctx.vision) {
    return "\nVision-read evidence: unavailable — no vision model is configured, so the page's images were not read.";
  }
  if (!opts.images.length) {
    return "\nVision-read evidence: no usable image was found on the page, so nothing was read from images.";
  }
  const { lines, reads } = await readListingImages(opts.images, opts.question, ctx, opts.bytesFn);
  const out = ["\nVision-read evidence (from listing images — not page text):", ...(lines.length ? lines : ["- no image on the page could be read."])];
  if (opts.pagePrice) {
    const seen = new Set<number>();
    for (const r of reads) {
      for (const p of extractPrices(r.text)) {
        if (p.currency !== opts.pagePrice.currency || Math.abs(p.amount - opts.pagePrice.amount) < 0.005 || seen.has(p.amount)) continue;
        seen.add(p.amount);
        out.push(`- CONFLICT (price): the page text states ${fmtMoney(opts.pagePrice)}, but the listing image at ${r.image} shows ${fmtMoney(p)} (vision-read). Both are reported as found; neither was changed — verify on the listing itself.`);
      }
    }
  }
  return out.join("\n");
}

// ---------- shared fetch cores (injectable fetch, like runWebSearch) ----------

async function fetchPage(url: string, signal: AbortSignal, fetchFn: FetchFn): Promise<{ res?: { status: number; type: string; body: string; url: string }; refusal?: string }> {
  let res: { status: number; type: string; body: string; url: string };
  try {
    res = await fetchFn(url, signal);
  } catch (err) {
    return { refusal: `Could not fetch ${url}: ${err instanceof Error ? err.message : String(err)}` };
  }
  if (res.status >= 400) {
    return { refusal: `The site answered HTTP ${res.status} for ${url}. It may block automated fetches or need a login this tool cannot do. Nothing was extracted, and nothing is claimed.` };
  }
  return { res };
}

export async function fetchProductCheck(url: string, seenPrices: Money[], signal: AbortSignal, fetchFn: FetchFn = safeFetch): Promise<{ detail?: ListingDetail; signals?: ScamSignal[]; refusal?: string }> {
  const { res, refusal } = await fetchPage(url, signal, fetchFn);
  if (refusal) return { refusal };
  const detail = parseListingPage(res!.body, res!.url || url);
  if (!detail.title && !detail.price) {
    return { refusal: `Fetched ${url} (HTTP ${res!.status}) but no product data could be extracted — the page may need JavaScript or a login. Nothing is claimed from it.` };
  }
  return { detail, signals: scanScamSignals(detail, seenPrices) };
}

export async function fetchSellerCheck(urls: string[], signal: AbortSignal, fetchFn: FetchFn = safeFetch): Promise<{ rep?: Reputation; signals?: ScamSignal[]; refusal?: string }> {
  let rep: Reputation | null = null;
  const texts: string[] = [];
  for (const url of urls) {
    const { res, refusal } = await fetchPage(url, signal, fetchFn);
    if (refusal) {
      if (!rep) return { refusal };
      continue;
    }
    const r = extractReputation(res!.body, res!.url || url);
    texts.push(cleanText(res!.body));
    if (!rep) rep = r;
    else {
      rep.ratingOutOf5 ??= r.ratingOutOf5;
      rep.positivePct ??= r.positivePct;
      rep.feedbackCount ??= r.feedbackCount;
      rep.disputes ??= r.disputes;
      rep.accountAge ||= r.accountAge;
      rep.feedback = [...rep.feedback, ...r.feedback].slice(0, 5);
      rep.sources.push(url);
      rep.claims.push(...r.claims);
      if (!rep.images.length) rep.images = r.images;
    }
  }
  if (!rep) return { refusal: "No seller page could be fetched." };
  const pseudo = parseListingPage("", urls[0]!);
  pseudo.text = texts.join("\n");
  pseudo.claims = rep.claims;
  return { rep, signals: scanScamSignals(pseudo) };
}

export async function fetchProductReviews(urls: string[], signal: AbortSignal, fetchFn: FetchFn = safeFetch): Promise<{ set?: ReviewSet; refusal?: string }> {
  let merged: ReviewSet | null = null;
  for (const url of urls) {
    const { res, refusal } = await fetchPage(url, signal, fetchFn);
    if (refusal) {
      if (!merged) return { refusal };
      continue;
    }
    const s = extractReviews(res!.body, res!.url || url);
    if (!merged) merged = s;
    else {
      merged.reviews.push(...s.reviews);
      merged.statedAverage ??= s.statedAverage;
      merged.statedCount ??= s.statedCount;
      for (const [k, v] of Object.entries(s.distribution)) merged.distribution[k] = (merged.distribution[k] ?? 0) + v;
    }
  }
  if (!merged || !merged.reviews.length) {
    return { refusal: `No individual reviews could be extracted from ${urls.join(", ")}. The pages may state only an aggregate score, need JavaScript, or block automated fetches. No consensus is invented.` };
  }
  merged.praiseThemes = themes(merged.reviews, (r) => (r.rating ?? 0) >= 4);
  merged.complaintThemes = themes(merged.reviews, (r) => (r.rating ?? 5) <= 2);
  return { set: merged };
}

// ---------- renderers ----------

function detailTable(detail: ListingDetail, signals: ScamSignal[]): string {
  const lines = [
    `Product check — ${detail.title || "(untitled)"}`,
    `Source: ${detail.url} (fetched page — every value below is from this page)`,
    `Price: ${fmtMoney(detail.price)}`,
    `Shipping: ${detail.freeShipping ? "Free" : fmtMoney(detail.shipping)}`,
    `Total: ${detail.total ? fmtMoney(detail.total) : "cannot be computed from what the page states"}`,
    `Seller: ${detail.seller || "not stated on the page"}`,
    `Returns: ${detail.returns || "not stated on the page"}`,
    `Stock: ${detail.stock || "not stated on the page"} · Condition: ${detail.condition || "not stated on the page"}`,
  ];
  if (detail.claims.length) {
    lines.push("Claims (structured, for cross-checking):");
    for (const c of detail.claims) lines.push(`- ${c.kind}: ${c.value} [evidence: "${c.evidence}"]`);
  }
  if (signals.length) {
    lines.push("Scam signals (named, with the page evidence — a signal is not a verdict):");
    for (const s of signals) lines.push(`- ${s.signal}: ${s.evidence}`);
  } else {
    lines.push("Scam signals: none found in the page text. That is an absence of evidence, not proof of safety.");
  }
  lines.push("This table informs; it does not recommend for or against buying. What happens next is the owner's call.");
  return lines.join("\n");
}

function reputationTable(rep: Reputation, signals: ScamSignal[]): string {
  const lines = [
    `Seller check — ${rep.seller || "(unnamed seller)"}`,
    `Sources: ${rep.sources.join(", ")} (fetched pages — every value below is from them)`,
    `Rating: ${rep.ratingOutOf5 !== null ? `${rep.ratingOutOf5}/5` : rep.positivePct !== null ? `${rep.positivePct}% positive` : "not stated on the fetched page(s)"}`,
    `Feedback/reviews: ${rep.feedbackCount !== null ? rep.feedbackCount : "not stated"}`,
    `Disputes/negative feedback: ${rep.disputes !== null ? rep.disputes : "not stated"}`,
    `Account age: ${rep.accountAge || "not stated"}`,
  ];
  if (rep.feedback.length) {
    lines.push("Feedback snippets (quoted from the page):");
    for (const f of rep.feedback) lines.push(`- "${f.text}" (${f.source})`);
  }
  if (signals.length) {
    lines.push("Scam signals (named, with evidence — a signal is not a verdict):");
    for (const s of signals) lines.push(`- ${s.signal}: ${s.evidence}`);
  } else {
    lines.push("Scam signals: none found in the page text. That is an absence of evidence, not proof of trustworthiness.");
  }
  return lines.join("\n");
}

function reviewsTable(set: ReviewSet, urls: string[]): string {
  const lines = [
    `Product reviews — from ${urls.join(", ")}`,
    `Stated on the page: ${set.statedAverage !== null ? `average ${set.statedAverage}/5` : "no stated average"}${set.statedCount !== null ? ` · ${set.statedCount} ratings/reviews` : ""}`,
    `Extracted ${set.reviews.length} individual review(s). Rating distribution as extracted: ${Object.entries(set.distribution).sort((a, b) => Number(b[0]) - Number(a[0])).map(([k, v]) => `${k}★: ${v}`).join(" · ") || "none"}`,
  ];
  const themeLines = (label: string, ts: ReviewSet["praiseThemes"]) => {
    if (!ts.length) return;
    lines.push(`${label} (phrases recurring in ≥2 reviews, with a quote each — patterns in the text, not a consensus):`);
    for (const t of ts) lines.push(`- "${t.phrase}" — in ${t.reviews} review(s). Quote: "${t.quote}" (${t.source})`);
  };
  themeLines("Recurring praise", set.praiseThemes);
  themeLines("Recurring complaints", set.complaintThemes);
  lines.push("Sample reviews:");
  for (const r of set.reviews.slice(0, 5)) lines.push(`- ${r.rating}★ ${r.title ? `${r.title} — ` : ""}${r.text.slice(0, 200)} (${r.source})`);
  return lines.join("\n");
}

// ---------- the tools ----------

function shopSearchTool(): Tool {
  return tool({
  name: "shop_search",
  description: "Search the web for products to buy. Returns CANDIDATE listings with source URLs — snippets are not verified facts. Verify any candidate with product_check before trusting a price. Legitimate products and companies only.",
  schema: z.object({ query: z.string().min(1) }),
  policy: { effects: ["egress"], egressArgs: ["query"] },
  outside: true,
  network: true,
  parallelSafe: true,
  async run({ query }, ctx) {
    const outcome = await runWebSearch(`${query} buy price`, ctx.signal, ctx.webFetch ?? safeFetch);
    if (!outcome.results.length) return outcome.text;
    const candidates = outcome.results.map((r) => blankRow({ title: r.title, link: r.url, sourceUrl: r.url, note: r.snippet }));
    const lines = [
      `Candidate listings for "${query}" (via ${outcome.provider ?? "web search"}) — candidates only: the snippets below are NOT verified facts. Run product_check on a listing before trusting any price.`,
    ];
    candidates.forEach((r, i) => lines.push(`${i + 1}. ${r.title}\n   ${r.link}${r.note ? `\n   ${r.note}` : ""}`));
    return lines.join("\n");
  },
  });
}

const MARKETPLACES: Record<string, (q: string) => string> = {
  ebay: (q) => `https://www.ebay.com/sch/i.html?_nkw=${encodeURIComponent(q)}`,
  etsy: (q) => `https://www.etsy.com/search?q=${encodeURIComponent(q)}`,
  amazon: (q) => `https://www.amazon.com/s?k=${encodeURIComponent(q)}`,
  walmart: (q) => `https://www.walmart.com/search?q=${encodeURIComponent(q)}`,
};

export async function fetchMarketplace(query: string, marketplace: string, sort: "price" | "rating", signal: AbortSignal, fetchFn: FetchFn = safeFetch): Promise<string> {
  const build = MARKETPLACES[marketplace] ?? MARKETPLACES["ebay"]!;
  const url = build(query);
  const { res, refusal } = await fetchPage(url, signal, fetchFn);
  if (refusal) return refusal;
  const ebayRows = parseEbayResults(res!.body, url);
  const rows = ebayRows.length ? ebayRows : extractListings(res!.body, url);
  if (!rows.length) {
    return `Fetched ${url} (HTTP ${res!.status}) but no listings could be extracted — the site may require JavaScript or a login from this network. Nothing is claimed. shop_search can still find candidate pages to check one by one.`;
  }
  const { rows: sorted, label } = sortRowsMechanically(rows, sort);
  return `${renderRows(sorted, `Marketplace results for "${query}" on ${marketplace}`)}\nSorted by ${label}.\nSource page: ${url}. Prices are as the page states them — verify a listing with product_check before trusting it.`;
}

function marketplaceSearchTool(): Tool {
  return tool({
  name: "marketplace_search",
  description: "Search a marketplace (eBay first-class; Etsy, Amazon, Walmart as fetchable) for an item. Returns listings with seller, rating and total price, sorted mechanically (sort key labeled). Prices are as the fetched page states them.",
  schema: z.object({
    query: z.string().min(1),
    marketplace: z.enum(["ebay", "etsy", "amazon", "walmart"]).optional(),
    sort: z.enum(["price", "rating"]).optional().describe("price = total price lowest first; rating = seller positive rating highest first"),
  }),
  policy: { effects: ["egress"], egressArgs: ["query"] },
  outside: true,
  network: true,
  parallelSafe: true,
  async run({ query, marketplace = "ebay", sort = "price" }, ctx) {
    return fetchMarketplace(query, marketplace, sort, ctx.signal, ctx.webFetch ?? safeFetch);
  },
  });
}

function marketplaceNavigateTool(): Tool {
  return tool({
  name: "marketplace_navigate",
  description: "Navigate a marketplace in a session: open a search/category URL, follow Next/Previous, follow the page's own sort links, open a listing by its number from the current results, and go back. Read-only — no carts, no bids, no purchases, no logins. Say what page you are on and what links exist; never invent pages.",
  schema: z.object({
    action: z.enum(["open", "next", "prev", "sort", "open_listing", "back", "results"]),
    url: z.string().url().optional().describe("for open: a marketplace search or category URL"),
    index: z.number().int().min(1).optional().describe("for open_listing: the listing's number in the current results"),
    by: z.string().optional().describe("for sort: price_asc, price_desc, newest, or part of the sort link's label"),
    session: z.string().max(40).optional().describe("session name; conversations can keep separate sessions"),
  }),
  policy: { effects: ["egress"], targetArgs: ["url"] },
  outside: true,
  network: true,
  async run({ action, url, index, by, session = "default" }, ctx) {
    return runMarketplaceNav(session, { action, url, index, by }, ctx.signal, ctx.webFetch ?? safeFetch);
  },
  });
}

export async function runProductCheck(url: string, seenPrices: Money[], ctx: ToolContext, fetchFn: FetchFn = safeFetch, bytesFn: WebFetchBytesFn = safeFetchBytes): Promise<string> {
  const { detail, signals, refusal } = await fetchProductCheck(url, seenPrices, ctx.signal, fetchFn);
  if (refusal) return refusal;
  const notes = await memoryNotes(ctx, detail!.seller);
  const vision = await visionEvidenceBlock({ images: detail!.images, question: VISION_QUESTION_PRODUCT, pagePrice: detail!.price, ctx, bytesFn });
  return detailTable(detail!, signals!) + vision + memoryLines(notes);
}

function productCheckTool(): Tool {
  return tool({
  name: "product_check",
  description: "Fetch a product/listing page and build a verification table: title, price, shipping, total, seller, returns, stock/condition — every value from the fetched page — plus named scam signals with their page evidence. When vision is on in settings, the listing's photos are also read automatically and reported as a separate vision-read evidence block (never merged into the page-text facts; a disagreement is printed as a conflict). Informs; never recommends for or against buying.",
  schema: z.object({
    url: z.string().url(),
    seen_prices: z.array(z.object({ amount: z.number(), currency: z.string() })).optional()
      .describe("prices of comparable listings already seen, so a far-below-market price can be flagged mechanically"),
  }),
  policy: { effects: ["egress"], targetArgs: ["url"] },
  outside: true,
  network: true,
  parallelSafe: true,
  async run({ url, seen_prices = [] }, ctx) {
    const seen: Money[] = seen_prices.map((p) => ({ amount: p.amount, currency: p.currency, raw: "" }));
    return runProductCheck(url, seen, ctx, ctx.webFetch ?? safeFetch, ctx.webFetchBytes ?? safeFetchBytes);
  },
  });
}

export async function runSellerCheck(urls: string[], seller: string, ctx: ToolContext, fetchFn: FetchFn = safeFetch, bytesFn: WebFetchBytesFn = safeFetchBytes): Promise<string> {
  const { rep, signals, refusal } = await fetchSellerCheck(urls, ctx.signal, fetchFn);
  if (refusal) return refusal;
  rep!.seller = seller || rep!.seller || "";
  const notes = await memoryNotes(ctx, rep!.seller);
  const vision = await visionEvidenceBlock({ images: rep!.images, question: VISION_QUESTION_SELLER, pagePrice: null, ctx, bytesFn });
  let out = reputationTable(rep!, signals!) + vision;
  if (!rep!.claims.length && !rep!.feedback.length) {
    out += "\nNo reputation data could be extracted from the fetched page(s) — the seller is UNVERIFIED by this check, which is not the same as untrustworthy.";
  }
  return out + memoryLines(notes);
}

function sellerCheckTool(): Tool {
  return tool({
  name: "seller_check",
  description: "Check a seller or company from fetched pages: rating, feedback count, disputes, account age, quoted feedback snippets and named scam signals — a trust table of evidence, never a verdict. When vision is on in settings, storefront/page photos are also read automatically and reported as a separate vision-read evidence block. Consults the owner's memory for saved facts about the seller.",
  schema: z.object({
    url: z.string().url().describe("the seller's profile/store page"),
    extra_urls: z.array(z.string().url()).max(4).optional().describe("more fetched pages about the same seller"),
    seller: z.string().optional().describe("the seller's name, if known (used for the memory lookup)"),
  }),
  policy: { effects: ["egress"], targetArgs: ["url"] },
  outside: true,
  network: true,
  parallelSafe: true,
  async run({ url, extra_urls = [], seller = "" }, ctx) {
    return runSellerCheck([url, ...extra_urls], seller, ctx, ctx.webFetch ?? safeFetch, ctx.webFetchBytes ?? safeFetchBytes);
  },
  });
}

function productReviewsTool(): Tool {
  return tool({
  name: "product_reviews",
  description: "Fetch review pages for a product and aggregate what they actually say: the page's stated average, the rating distribution of extracted reviews, recurring praise/complaint phrases with quotes, and sample reviews — each with its source. Never invents a consensus.",
  schema: z.object({
    url: z.string().url().describe("a product page with reviews, or a reviews page"),
    extra_urls: z.array(z.string().url()).max(4).optional(),
  }),
  policy: { effects: ["egress"], targetArgs: ["url"] },
  outside: true,
  network: true,
  parallelSafe: true,
  async run({ url, extra_urls = [] }, ctx) {
    const urls = [url, ...extra_urls];
    const { set, refusal } = await fetchProductReviews(urls, ctx.signal, ctx.webFetch ?? safeFetch);
    if (refusal) return refusal;
    return reviewsTable(set!, urls);
  },
  });
}

function prepareOrderTool(): Tool {
  return tool({
  name: "prepare_order",
  description: "Prepare an order plan from a verified listing (title, unit price, quantity, seller, shipping) and STOP at the owner's confirmation. This tool never buys, reserves, pays, or contacts a seller — approval of the plan is not a purchase; executing one is a separate step that does not exist in these tools.",
  schema: z.object({
    title: z.string().min(1),
    unit_price: z.number().positive(),
    currency: z.string().min(1).max(8),
    quantity: z.number().int().min(1).max(99).optional(),
    seller: z.string().optional(),
    link: z.string().optional(),
    shipping: z.number().min(0).optional(),
  }),
  // Purchase-adjacent: the owner confirms every prepared plan, always.
  policy: { effects: ["read"], alwaysConfirm: true },
  async run({ title, unit_price, currency, quantity = 1, seller = "", link = "", shipping = 0 }) {
    const itemsTotal = Math.round(unit_price * quantity * 100) / 100;
    const total = Math.round((itemsTotal + shipping) * 100) / 100;
    return [
      "Order plan — PREPARED ONLY, awaiting the owner's confirmation.",
      `Item: ${title} × ${quantity}`,
      `Unit price: ${unit_price.toFixed(2)} ${currency}${shipping ? ` · Shipping: ${shipping.toFixed(2)} ${currency}` : ""} · Total: ${total.toFixed(2)} ${currency}`,
      seller ? `Seller: ${seller}` : "",
      link ? `Listing: ${link}` : "",
      "Status: prepared — nothing was bought, reserved, or paid, and no seller was contacted. This plan is the end of what these tools do; any purchase is a separate step outside them.",
    ].filter(Boolean).join("\n");
  },
  });
}

/**
 * The shopping tools, built by a function rather than a module-level
 * const: this module imports index.ts (safeFetch & co) and index.ts
 * imports this one back. Function declarations are initialized at module
 * instantiation, so index.ts can build ALL_TOOLS during its own
 * evaluation no matter which module a caller imports first — a const
 * array here sat in the temporal dead zone in one of the two orders.
 */
export function shoppingTools(): Tool[] {
  return [
    shopSearchTool(), marketplaceSearchTool(), marketplaceNavigateTool(),
    productCheckTool(), sellerCheckTool(), productReviewsTool(), prepareOrderTool(),
  ];
}
