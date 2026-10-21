import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { createServer, type Socket } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { loadConfig, paths, type Config } from "../src/config.ts";
import { Runtime } from "../src/runtime.ts";
import {
  ALL_TOOLS, MAX_IMAGE_BYTES, makeWebFetchBytes, safeFetchBytes, toolsFor,
  type ToolContext, type WebFetchBytesFn,
} from "../src/tools/index.ts";
import { extractImageUrls, runProductCheck, runSellerCheck } from "../src/tools/shopping.ts";
import type { Vision } from "../src/vision.ts";

/**
 * Vision-inherent shopping (owner rule: vision ON means it is ALWAYS part
 * of the model). Two halves pinned here:
 *  - the setting: one switch (config / update / state / toolsFor), off =
 *    no vision backend exists and the look tools are withheld;
 *  - the checks: product_check / seller_check read listing images as a
 *    fixed step — no per-call flag exists in either schema — and report
 *    the readings in their own labeled block, with disagreements named
 *    as conflicts and never resolved silently.
 */

// ---------- stubs ----------

class StubVision {
  calls = 0;
  questions: string[] = [];
  constructor(private replies: string[]) {}
  get kind() { return "local" as const; }
  get label() { return "Stub VLM"; }
  async describe(_images: unknown, question: string): Promise<string> {
    this.calls++;
    this.questions.push(question);
    return this.replies[Math.min(this.calls - 1, this.replies.length - 1)]!;
  }
}
const asVision = (v: StubVision) => v as unknown as Vision;

function stubCtx(opts: { vision?: Vision | null; visionEnabled?: boolean } = {}): ToolContext {
  return {
    cwd: "/tmp", workspace: "/tmp", embedder: null,
    vision: opts.vision ?? null,
    visionEnabled: opts.visionEnabled,
    signal: new AbortController().signal,
    memory: {
      search: async () => [],
      remember: (t: string) => ({ id: "fx", text: t }),
    },
  } as unknown as ToolContext;
}

function stubFetch(pages: Record<string, { status: number; body: string }>) {
  return async (url: string) => {
    const p = pages[url];
    if (!p) return { status: 404, type: "text/html", body: "<html><body>not found</body></html>", url };
    return { status: p.status, type: "text/html", body: p.body, url };
  };
}

const PNG_HEAD = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3, 4]);

function stubBytes(map: Record<string, { type: string; bytes: Buffer } | Error>) {
  const calls: string[] = [];
  const fn = (async (url: string) => {
    calls.push(url);
    const r = map[url];
    if (!r) throw new Error("The image link answered HTTP 404.");
    if (r instanceof Error) throw r;
    return { status: 200, type: r.type, bytes: r.bytes, url };
  }) as WebFetchBytesFn;
  return { fn, calls };
}

// ---------- fixtures ----------

const SHOP = "https://shop.example.com";
const LISTING_URL = `${SHOP}/products/widget-pro`;
const IMG_FRONT = `${SHOP}/img/widget-front.png`;
const IMG_TAG = `${SHOP}/img/widget-tag.jpg`;

const LISTING_HTML = `<!DOCTYPE html><html><head>
<meta property="og:image" content="${IMG_TAG}">
<script type="application/ld+json">{"@type":"Product","name":"Widget Pro 3000","image":"${IMG_FRONT}","offers":{"@type":"Offer","price":129.99,"priceCurrency":"USD"},"aggregateRating":{"@type":"AggregateRating","ratingValue":4.5,"reviewCount":212}}</script>
</head><body>
<h1>Widget Pro 3000</h1>
<p>Price: $129.99 · Free shipping</p>
<p>Sold by TechNest</p>
<p>30-day returns</p>
<p>In stock · New</p>
<img src="/img/widget-front.png">
<img src="data:image/gif;base64,R0lGODlhAQABAAAAACw=">
</body></html>`;

const NO_IMAGE_HTML = `<html><body>
<h1>Widget Pro 3000</h1>
<p>Price: $129.99 · Free shipping</p>
<p>Sold by TechNest</p>
<p>In stock · New</p>
</body></html>`;

const SELLER_URL = `${SHOP}/sellers/technest`;
const SELLER_IMG = `${SHOP}/img/storefront.jpg`;
const SELLER_HTML = `<html><head><meta property="og:image" content="${SELLER_IMG}"></head><body>
<h1>TechNest</h1>
<p>99.2% positive (1,234 feedback)</p>
<p>Member since 2019</p>
<p>Great seller — fast shipping, and the item arrived exactly as described.</p>
<img src="${SELLER_IMG}">
</body></html>`;

const LONG_READ = "The photo shows a Widget Pro 3000 retail box, factory sealed with the brand logo visible. No price is visible.";
const PRICE_READ = "A paper price tag in the photo reads $99.99. The item looks new and sealed in its box.";

// ---------- image extraction ----------

test("extractImageUrls: structured image first, og:image next, <img> resolved + deduped, data: skipped", () => {
  assert.deepEqual(extractImageUrls(LISTING_HTML, LISTING_URL), [IMG_FRONT, IMG_TAG]);
  assert.deepEqual(extractImageUrls(NO_IMAGE_HTML, LISTING_URL), []);
  const chrome = `<html><body><img src="${SHOP}/logo.svg"><img src="${SHOP}/anim.gif"><img src="${SHOP}/photo.webp"><img src="${SHOP}/real.png"></body></html>`;
  assert.deepEqual(extractImageUrls(chrome, LISTING_URL), [`${SHOP}/real.png`], "formats the vision route cannot read never enter the walk");
});

// ---------- the deterministic step (the pin) ----------

test("PIN: vision ON — product_check reads the listing image with no per-call opt-in", async () => {
  const vision = new StubVision([LONG_READ]);
  const bytes = stubBytes({ [IMG_FRONT]: { type: "image/png", bytes: PNG_HEAD }, [IMG_TAG]: { type: "image/jpeg", bytes: PNG_HEAD } });
  const ctx = stubCtx({ vision: asVision(vision), visionEnabled: true });
  const out = await runProductCheck(LISTING_URL, [], ctx, stubFetch({ [LISTING_URL]: { status: 200, body: LISTING_HTML } }), bytes.fn);
  assert.ok(vision.calls > 0, "the enrichment ran on its own — nothing in the call asked for it");
  assert.equal(vision.calls, 1, "a substantial first read ends the walk");
  assert.deepEqual(bytes.calls, [IMG_FRONT], "the primary (structured-data) image is read first");
  assert.match(out, /Vision-read evidence \(from listing images — not page text\):/);
  assert.ok(out.includes(IMG_FRONT), "the read carries its image provenance");
  assert.match(out, /factory sealed/);
});

test("PIN: neither check's schema carries a vision option — the setting is the only switch", () => {
  for (const name of ["product_check", "seller_check"]) {
    const t = ALL_TOOLS.find((x) => x.name === name)!;
    assert.ok(t, `${name} exists`);
    const keys = Object.keys((t.schema as unknown as { shape: Record<string, unknown> }).shape);
    assert.ok(keys.every((k) => !/vision/i.test(k)), `${name} schema keys must not include a vision flag: ${keys.join(", ")}`);
  }
});

test("a thin first read earns the next image a try; three reads is the cap", async () => {
  const vision = new StubVision(["Not visible.", "Second photo: a hang tag is visible and the retail box is sealed."]);
  const bytes = stubBytes({ [IMG_FRONT]: { type: "image/png", bytes: PNG_HEAD }, [IMG_TAG]: { type: "image/jpeg", bytes: PNG_HEAD } });
  const out = await runProductCheck(LISTING_URL, [], stubCtx({ vision: asVision(vision), visionEnabled: true }), stubFetch({ [LISTING_URL]: { status: 200, body: LISTING_HTML } }), bytes.fn);
  assert.equal(vision.calls, 2);
  assert.deepEqual(bytes.calls, [IMG_FRONT, IMG_TAG]);
  assert.match(out, /hang tag/);

  const fourImgHtml = `<html><body><h1>Widget</h1><p>Price: $10.00</p>${[1, 2, 3, 4].map((i) => `<img src="${SHOP}/img/p${i}.png">`).join("")}</body></html>`;
  const FOUR_URL = `${SHOP}/products/four`;
  const vision2 = new StubVision(["Not visible."]);
  const bytes2 = stubBytes(Object.fromEntries([1, 2, 3, 4].map((i) => [`${SHOP}/img/p${i}.png`, { type: "image/png", bytes: PNG_HEAD }])));
  await runProductCheck(FOUR_URL, [], stubCtx({ vision: asVision(vision2), visionEnabled: true }), stubFetch({ [FOUR_URL]: { status: 200, body: fourImgHtml } }), bytes2.fn);
  assert.equal(vision2.calls, 3, "at most three images are read");
  assert.equal(bytes2.calls.length, 3);
});

// ---------- the labeled block: conflicts, skips, absences ----------

test("a vision-read price that disagrees with the page price is a named CONFLICT — nothing is overwritten", async () => {
  const vision = new StubVision([PRICE_READ]);
  const bytes = stubBytes({ [IMG_FRONT]: { type: "image/png", bytes: PNG_HEAD } });
  const out = await runProductCheck(LISTING_URL, [], stubCtx({ vision: asVision(vision), visionEnabled: true }), stubFetch({ [LISTING_URL]: { status: 200, body: LISTING_HTML } }), bytes.fn);
  assert.match(out, /CONFLICT \(price\)/);
  assert.ok(out.includes("129.99") && out.includes("99.99"), "both prices are reported");
  assert.match(out, /Price: 129\.99 USD/, "the page-text table keeps the page's price");
  assert.ok(out.indexOf("Vision-read evidence") > out.indexOf("Claims (structured"), "vision evidence is its own block, after the page facts");
});

test("a vision-read price that agrees with the page raises no conflict", async () => {
  const vision = new StubVision(["The hang tag in the photo reads $129.99 and the box is sealed."]);
  const bytes = stubBytes({ [IMG_FRONT]: { type: "image/png", bytes: PNG_HEAD } });
  const out = await runProductCheck(LISTING_URL, [], stubCtx({ vision: asVision(vision), visionEnabled: true }), stubFetch({ [LISTING_URL]: { status: 200, body: LISTING_HTML } }), bytes.fn);
  assert.ok(!/CONFLICT/.test(out));
  assert.match(out, /Vision-read evidence/);
});

test("vision OFF: zero vision calls, zero image fetches, and the output states the evidence is off", async () => {
  const vision = new StubVision([LONG_READ]);
  const bytes = stubBytes({ [IMG_FRONT]: { type: "image/png", bytes: PNG_HEAD } });
  const fetch = stubFetch({ [LISTING_URL]: { status: 200, body: LISTING_HTML } });
  const out = await runProductCheck(LISTING_URL, [], stubCtx({ vision: asVision(vision), visionEnabled: false }), fetch, bytes.fn);
  assert.equal(vision.calls, 0);
  assert.equal(bytes.calls.length, 0);
  assert.match(out, /Vision-read evidence: off/);
  assert.ok(!out.includes("Vision read:"), "no readings are reported");
  // The runtime shape of "off" (no backend at all) produces the identical check.
  const out2 = await runProductCheck(LISTING_URL, [], stubCtx({ vision: null, visionEnabled: false }), fetch, bytes.fn);
  assert.equal(out, out2);
});

test("vision ON but no backend configured, and no usable image: each absence is stated plainly", async () => {
  const fetch = stubFetch({ [LISTING_URL]: { status: 200, body: LISTING_HTML }, [`${SHOP}/products/plain`]: { status: 200, body: NO_IMAGE_HTML } });
  const noBackend = await runProductCheck(LISTING_URL, [], stubCtx({ vision: null, visionEnabled: true }), fetch, stubBytes({}).fn);
  assert.match(noBackend, /Vision-read evidence: unavailable — no vision model is configured/);
  const vision = new StubVision([LONG_READ]);
  const noImage = await runProductCheck(`${SHOP}/products/plain`, [], stubCtx({ vision: asVision(vision), visionEnabled: true }), fetch, stubBytes({}).fn);
  assert.match(noImage, /Vision-read evidence: no usable image was found on the page/);
  assert.equal(vision.calls, 0);
});

test("an unreadable image is named with its reason; a later readable one is still read", async () => {
  const vision = new StubVision([LONG_READ]);
  const bytes = stubBytes({
    [IMG_FRONT]: { type: "text/html", bytes: Buffer.from("<html>not an image</html>") },
    [IMG_TAG]: { type: "image/jpeg", bytes: PNG_HEAD },
  });
  const out = await runProductCheck(LISTING_URL, [], stubCtx({ vision: asVision(vision), visionEnabled: true }), stubFetch({ [LISTING_URL]: { status: 200, body: LISTING_HTML } }), bytes.fn);
  assert.match(out, /text\/html: the vision route reads PNG and JPEG, so this image was not read/);
  assert.equal(vision.calls, 1, "the JPEG was still read");
  assert.match(out, /factory sealed/);
});

test("an oversized image is refused with its reason, not read", async () => {
  const vision = new StubVision([LONG_READ]);
  const bytes = stubBytes({
    [IMG_FRONT]: { type: "image/png", bytes: Buffer.alloc(MAX_IMAGE_BYTES + 1) },
    [IMG_TAG]: { type: "image/jpeg", bytes: PNG_HEAD },
  });
  const out = await runProductCheck(LISTING_URL, [], stubCtx({ vision: asVision(vision), visionEnabled: true }), stubFetch({ [LISTING_URL]: { status: 200, body: LISTING_HTML } }), bytes.fn);
  assert.match(out, /too large to read \(over 5 MB\)/);
  assert.equal(vision.calls, 1, "only the readable image reached the vision model");
});

// ---------- seller check ----------

test("seller_check reads the storefront image when vision is on; the setting off means no calls", async () => {
  const fetch = stubFetch({ [SELLER_URL]: { status: 200, body: SELLER_HTML } });
  const vision = new StubVision(["Storefront signage reads 'TechNest Outlet'. Shelves of boxed widgets are visible."]);
  const bytes = stubBytes({ [SELLER_IMG]: { type: "image/jpeg", bytes: PNG_HEAD } });
  const out = await runSellerCheck([SELLER_URL], "TechNest", stubCtx({ vision: asVision(vision), visionEnabled: true }), fetch, bytes.fn);
  assert.equal(vision.calls, 1);
  assert.match(out, /Vision-read evidence \(from listing images — not page text\):/);
  assert.ok(out.includes(SELLER_IMG));
  assert.match(out, /TechNest Outlet/);

  const vision2 = new StubVision(["unused"]);
  const out2 = await runSellerCheck([SELLER_URL], "TechNest", stubCtx({ vision: asVision(vision2), visionEnabled: false }), fetch, stubBytes({}).fn);
  assert.equal(vision2.calls, 0);
  assert.match(out2, /Vision-read evidence: off/);
});

// ---------- the setting: toolsFor, update, state, persistence ----------

test("toolsFor: vision off withholds both look tools; on or unset keeps them", () => {
  const off = toolsFor({ offlineOnly: false, screenControl: false, vision: false }).map((t) => t.name);
  assert.ok(!off.includes("look_at_screen") && !off.includes("look_at_image"), "both look tools withheld");
  assert.ok(off.includes("product_check"), "unrelated tools are untouched");
  const on = toolsFor({ offlineOnly: false, screenControl: false, vision: true }).map((t) => t.name);
  assert.ok(on.includes("look_at_screen") && on.includes("look_at_image"));
  const unset = toolsFor({ offlineOnly: false, screenControl: false }).map((t) => t.name);
  assert.ok(unset.includes("look_at_image"), "an unset flag keeps today's behavior");
});

function makeRuntime(mutate?: (cfg: Config) => void): { rt: Runtime; home: string } {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-vision-rt-"));
  const p = paths(home);
  const cfg = loadConfig(p);
  mutate?.(cfg);
  return { rt: new Runtime(cfg, p, home), home };
}

test("vision setting: default on; update({vision}) flips it, persists it, and the backend follows", () => {
  const { rt, home } = makeRuntime();
  assert.equal(rt.cfg.vision.enabled, true);
  assert.equal(rt.state().visionEnabled, true);
  assert.ok(rt.vision(), "default config yields a vision backend");
  rt.update({ vision: false });
  assert.equal(rt.cfg.vision.enabled, false);
  assert.equal(rt.state().visionEnabled, false);
  assert.equal(rt.vision(), null, "off means no backend exists for any caller");
  assert.equal(loadConfig(paths(home)).vision.enabled, false, "persisted to disk");
  rt.update({ vision: true });
  assert.equal(rt.state().visionEnabled, true);
  assert.ok(rt.vision());
});

test("config merge: a vision block written before the switch existed defaults to on", () => {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-vision-cfg-"));
  const p = paths(home);
  writeFileSync(p.config, JSON.stringify({ vision: { baseUrl: "", model: "qwen3-vl" } }) + "\n");
  const cfg = loadConfig(p);
  assert.equal(cfg.vision.enabled, true);
  assert.equal(cfg.vision.model, "qwen3-vl");
});

// ---------- the bytes sibling of the funnel ----------

interface BytesProxy {
  port: number;
  connects: string[];
  close(): Promise<void>;
}

/** A SOCKS5 stub that grants CONNECT and then serves one canned HTTP response in the tunnel. */
function startBytesProxy(respond: { status: number; type: string; body: Buffer }): Promise<BytesProxy> {
  const connects: string[] = [];
  const server = createServer((sock: Socket) => {
    let buf = Buffer.alloc(0);
    let stage: "greeting" | "connect" | "tunnel" = "greeting";
    sock.on("error", () => {});
    sock.on("data", (chunk) => {
      buf = Buffer.concat([buf, chunk as Buffer]);
      for (;;) {
        if (stage === "greeting") {
          if (buf.length < 2) return;
          const n = buf[1]!;
          if (buf.length < 2 + n) return;
          buf = buf.subarray(2 + n);
          sock.write(Buffer.from([0x05, 0x00]));
          stage = "connect";
          continue;
        }
        if (stage === "connect") {
          if (buf.length < 4) return;
          const atyp = buf[3]!;
          let host = "";
          let addrLen: number;
          if (atyp === 1) { addrLen = 4; if (buf.length < 10) return; host = [...buf.subarray(4, 8)].join("."); }
          else if (atyp === 3) { const l = buf[4]!; addrLen = 1 + l; if (buf.length < 4 + addrLen + 2) return; host = buf.subarray(5, 5 + l).toString("utf8"); }
          else { sock.destroy(); return; }
          buf = buf.subarray(4 + addrLen + 2);
          connects.push(host);
          sock.write(Buffer.from([0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0]));
          stage = "tunnel";
          continue;
        }
        if (!buf.includes("\r\n\r\n")) return;
        sock.end(Buffer.concat([
          Buffer.from(`HTTP/1.1 ${respond.status} OK\r\ncontent-type: ${respond.type}\r\ncontent-length: ${respond.body.length}\r\nconnection: close\r\n\r\n`),
          respond.body,
        ]));
        return;
      }
    });
  });
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      const a = server.address();
      resolve({ port: typeof a === "object" && a ? a.port : 0, connects, close: () => new Promise<void>((r) => server.close(() => r())) });
    });
  });
}

test("safeFetchBytes through Tor: image bytes return, hostname sent unresolved", async () => {
  const proxy = await startBytesProxy({ status: 200, type: "image/png", body: PNG_HEAD });
  try {
    const fetchBytes = makeWebFetchBytes({ enabled: true, socksPort: proxy.port });
    const out = await fetchBytes("http://images.example.com/widget.png", new AbortController().signal);
    assert.equal(out.type, "image/png");
    assert.deepEqual(out.bytes, PNG_HEAD);
    assert.deepEqual(proxy.connects, ["images.example.com"], "the hostname traveled to the proxy (ATYP form), never resolved locally");
  } finally {
    await proxy.close();
  }
});

test("safeFetchBytes refuses a non-image payload and an oversized one", async () => {
  const sig = new AbortController().signal;
  const htmlProxy = await startBytesProxy({ status: 200, type: "text/html", body: Buffer.from("<html>nope</html>") });
  try {
    const fetchBytes = makeWebFetchBytes({ enabled: true, socksPort: htmlProxy.port });
    await assert.rejects(fetchBytes("http://images.example.com/widget.png", sig), /not an image/);
  } finally {
    await htmlProxy.close();
  }
  const bigProxy = await startBytesProxy({ status: 200, type: "image/png", body: Buffer.alloc(256, 7) });
  try {
    const fetchBytes = makeWebFetchBytes({ enabled: true, socksPort: bigProxy.port }, 64);
    await assert.rejects(fetchBytes("http://images.example.com/big.png", sig), /too large/);
  } finally {
    await bigProxy.close();
  }
});

test("safeFetchBytes (direct): private addresses and onion targets are refused before any fetch", async () => {
  const sig = new AbortController().signal;
  await assert.rejects(safeFetchBytes("https://127.0.0.1:9/x.png", sig), /do not reach this computer/);
  await assert.rejects(safeFetchBytes("http://192.168.1.10/x.png", sig), /do not reach this computer/);
  await assert.rejects(safeFetchBytes("https://shop.onion/x.png", sig), /only with Tor on/);
});
