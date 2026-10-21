import assert from "node:assert/strict";
import { mkdtempSync, writeFileSync } from "node:fs";
import { createServer, type Socket } from "node:net";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { loadConfig, paths, type Config } from "../src/config.ts";
import { Memory } from "../src/memory.ts";
import { Runtime } from "../src/runtime.ts";
import {
  isOnionUrl,
  makeWebFetch,
  probeTorProxy,
  safeFetch,
  toolsFor,
  type ToolContext,
} from "../src/tools/index.ts";

/**
 * Tor transport: routing decisions + a stub SOCKS5 proxy that proves a
 * fetch really transits the proxy with the hostname UNRESOLVED (SOCKS5
 * address type 3 — remote DNS), which is the only way .onion can work.
 *
 * The stub answers the greeting, records every CONNECT (host + atyp), then
 * acts as the origin server with a canned page. HTTP only — TLS through
 * the stub is out of scope; the live test covers real Tor + HTTPS.
 */

const STUB_BODY = "tor stub origin content";

interface StubProxy {
  port: number;
  /** Connections that reached a CONNECT request (the probe never does). */
  connects: Array<{ atyp: number; host: string }>;
  /** Greeting handshakes seen (probes + agent connects). */
  greetings: number;
  close(): Promise<void>;
}

function startStubProxy(opts: { grantThenClose?: boolean; respond?: (host: string) => { type: string; body: string | Buffer } } = {}): Promise<StubProxy> {
  const connects: StubProxy["connects"] = [];
  let greetings = 0;
  const server = createServer((sock: Socket) => {
    let buf = Buffer.alloc(0);
    let stage: "greeting" | "connect" | "tunnel" = "greeting";
    let connHost = "";
    sock.on("error", () => {});
    sock.on("data", (chunk) => {
      buf = Buffer.concat([buf, chunk as Buffer]);
      for (;;) {
        if (stage === "greeting") {
          if (buf.length < 2) return;
          const n = buf[1]!;
          if (buf.length < 2 + n) return;
          buf = buf.subarray(2 + n);
          greetings++;
          sock.write(Buffer.from([0x05, 0x00])); // no-auth selected
          stage = "connect";
          continue;
        }
        if (stage === "connect") {
          if (buf.length < 4) return;
          const atyp = buf[3]!;
          let host = "";
          let addrLen: number;
          if (atyp === 1) {
            addrLen = 4;
            if (buf.length < 4 + addrLen + 2) return;
            host = [...buf.subarray(4, 8)].join(".");
          } else if (atyp === 3) {
            const l = buf[4]!;
            addrLen = 1 + l;
            if (buf.length < 4 + addrLen + 2) return;
            host = buf.subarray(5, 5 + l).toString("utf8");
          } else if (atyp === 4) {
            addrLen = 16;
            if (buf.length < 4 + addrLen + 2) return;
            host = "ipv6-literal";
          } else {
            sock.destroy();
            return;
          }
          buf = buf.subarray(4 + addrLen + 2);
          connects.push({ atyp, host });
          connHost = host;
          sock.write(Buffer.from([0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0])); // granted
          // Search test variant: the stub cannot terminate TLS, so it
          // grants the tunnel and closes — the fetch then fails fast at
          // the TLS step, and the CONNECT log is the routing evidence.
          if (opts.grantThenClose) { sock.end(); return; }
          stage = "tunnel";
          continue;
        }
        // Tunnel open: be the origin server for whatever HTTP arrives.
        if (!buf.includes("\r\n\r\n")) return;
        const page = opts.respond?.(connHost) ?? { type: "text/html", body: STUB_BODY };
        const body = typeof page.body === "string" ? Buffer.from(page.body) : page.body;
        sock.write(`HTTP/1.1 200 OK\r\ncontent-type: ${page.type}\r\ncontent-length: ${body.length}\r\nconnection: close\r\n\r\n`);
        sock.end(body);
        return;
      }
    });
  });
  return new Promise((resolve) => {
    server.listen(0, "127.0.0.1", () => {
      const a = server.address();
      resolve({
        port: typeof a === "object" && a ? a.port : 0,
        connects,
        get greetings() { return greetings; },
        close: () => new Promise<void>((r) => server.close(() => r())),
      });
    });
  });
}

/** A port nothing listens on: bind, take the number, let go. */
async function deadPort(): Promise<number> {
  const s = createServer();
  await new Promise<void>((r) => s.listen(0, "127.0.0.1", r));
  const a = s.address();
  const port = typeof a === "object" && a ? a.port : 0;
  await new Promise<void>((r) => s.close(() => r()));
  return port;
}

const sig = () => new AbortController().signal;
const ONION = "http://duckduckgogg42xjoc72x3sjasowoarfbgcmvfimaftt6twagswzczad.onion/";

test("isOnionUrl: only .onion hostnames", () => {
  assert.equal(isOnionUrl(new URL(ONION)), true);
  assert.equal(isOnionUrl(new URL("https://example.com/")), false);
  assert.equal(isOnionUrl(new URL("https://onion.example.com/")), false);
});

test("probe: true for a live SOCKS stub, false for a dead port", async () => {
  const stub = await startStubProxy();
  try {
    assert.equal(await probeTorProxy(stub.port), true);
  } finally {
    await stub.close();
  }
  assert.equal(await probeTorProxy(await deadPort()), false);
});

test("tor off: .onion is refused before any fetch (direct path and disabled binding)", async () => {
  await assert.rejects(safeFetch(ONION, sig()), /only with Tor on/);
  await assert.rejects(makeWebFetch({ enabled: false, socksPort: 9050 })(ONION, sig()), /never over the regular internet/);
});

test("tor off: clearnet never consults the proxy", async () => {
  const stub = await startStubProxy();
  try {
    // The fetch itself may succeed or fail with the sandbox network; the
    // routing claim is what is tested — the proxy must see nothing at all.
    await makeWebFetch({ enabled: false, socksPort: stub.port })("http://example.com/", sig()).catch(() => {});
    assert.equal(stub.greetings, 0);
    assert.equal(stub.connects.length, 0);
  } finally {
    await stub.close();
  }
});

test("tor on: clearnet transits the proxy, hostname sent unresolved (atyp 3)", async () => {
  const stub = await startStubProxy();
  try {
    const res = await makeWebFetch({ enabled: true, socksPort: stub.port })("http://example.com/hello", sig());
    assert.equal(res.status, 200);
    assert.equal(res.body, STUB_BODY);
    assert.deepEqual(stub.connects, [{ atyp: 3, host: "example.com" }]);
  } finally {
    await stub.close();
  }
});

test("tor on: .onion transits the proxy the same way", async () => {
  const stub = await startStubProxy();
  try {
    const res = await makeWebFetch({ enabled: true, socksPort: stub.port })(ONION, sig());
    assert.equal(res.status, 200);
    assert.equal(stub.connects.length, 1);
    assert.equal(stub.connects[0]!.atyp, 3);
    assert.ok(stub.connects[0]!.host.endsWith(".onion"), stub.connects[0]!.host);
  } finally {
    await stub.close();
  }
});

test("tor on + proxy down: fails closed with the clear message, never falls back", async () => {
  const port = await deadPort();
  await assert.rejects(
    makeWebFetch({ enabled: true, socksPort: port })("http://example.com/", sig()),
    /Tor is on but no Tor proxy is reachable/,
  );
  await assert.rejects(makeWebFetch({ enabled: true, socksPort: port })(ONION, sig()), /will not fall back/);
});

// ---------- auto: Tor only where needed (.onion) or asked (viaTor) ----------

test("tor auto: clearnet stays direct — the proxy sees nothing", async () => {
  const stub = await startStubProxy();
  try {
    await makeWebFetch({ enabled: false, auto: true, socksPort: stub.port })("http://example.com/", sig()).catch(() => {});
    assert.equal(stub.greetings, 0);
    assert.equal(stub.connects.length, 0);
  } finally {
    await stub.close();
  }
});

test("tor auto: .onion goes through the proxy on its own, hostname unresolved", async () => {
  const stub = await startStubProxy();
  try {
    const res = await makeWebFetch({ enabled: false, auto: true, socksPort: stub.port })(ONION, sig());
    assert.equal(res.status, 200);
    assert.equal(stub.connects.length, 1);
    assert.equal(stub.connects[0]!.atyp, 3);
    assert.ok(stub.connects[0]!.host.endsWith(".onion"), stub.connects[0]!.host);
  } finally {
    await stub.close();
  }
});

test("tor auto: a clearnet fetch the owner asked to send over Tor (viaTor) transits the proxy", async () => {
  const stub = await startStubProxy();
  try {
    const res = await makeWebFetch({ enabled: false, auto: true, socksPort: stub.port })("http://example.com/asked", sig(), { viaTor: true });
    assert.equal(res.body, STUB_BODY);
    assert.deepEqual(stub.connects, [{ atyp: 3, host: "example.com" }]);
  } finally {
    await stub.close();
  }
});

test("tor auto + proxy down: .onion and viaTor fail closed, never direct", async () => {
  const port = await deadPort();
  const f = makeWebFetch({ enabled: false, auto: true, socksPort: port });
  await assert.rejects(f(ONION, sig()), /will not fall back/);
  await assert.rejects(f("http://example.com/", sig(), { viaTor: true }), /will not fall back/);
});

test("tor off: a viaTor request is refused before anything is fetched", async () => {
  const stub = await startStubProxy();
  try {
    await assert.rejects(
      makeWebFetch({ enabled: false, socksPort: stub.port })("http://example.com/", sig(), { viaTor: true }),
      /Tor was asked for, but Tor is off/,
    );
    assert.equal(stub.greetings, 0);
  } finally {
    await stub.close();
  }
});

test("the web_fetch tool, bound with Tor on auto: via_tor routes through the proxy, .onion too", async () => {
  const stub = await startStubProxy();
  try {
    const tools = toolsFor({ offlineOnly: false, screenControl: false, tor: { enabled: false, auto: true, socksPort: stub.port } });
    const wf = tools.find((t) => t.name === "web_fetch")!;
    assert.match(wf.description, /Tor is on auto/);
    const ctx = { signal: sig() } as unknown as ToolContext;
    const asked = await wf.run({ url: "http://example.com/a", via_tor: true }, ctx);
    assert.match(asked, /tor stub origin content/);
    const onion = await wf.run({ url: ONION }, ctx);
    assert.match(onion, /tor stub origin content/);
    assert.equal(stub.connects.length, 2);
  } finally {
    await stub.close();
  }
});

test("the web_fetch tool with Tor off refuses via_tor instead of fetching direct", async () => {
  const tools = toolsFor({ offlineOnly: false, screenControl: false });
  const wf = tools.find((t) => t.name === "web_fetch")!;
  await assert.rejects(wf.run({ url: "http://example.com/", via_tor: true }, { signal: sig() } as unknown as ToolContext), /Tor was asked for, but Tor is off/);
});

test("tor on: local names and private literals are still refused pre-flight", async () => {
  const stub = await startStubProxy();
  try {
    const fetchTor = makeWebFetch({ enabled: true, socksPort: stub.port });
    await assert.rejects(fetchTor("http://192.168.0.5/", sig()), /do not reach this computer or the local network/);
    await assert.rejects(fetchTor("http://localhost:8080/", sig()), /do not reach this computer or the local network/);
    await assert.rejects(fetchTor("ftp://example.com/", sig()), /Only http and https/);
    // The refusals happened before any CONNECT was issued to the proxy.
    assert.equal(stub.connects.length, 0);
  } finally {
    await stub.close();
  }
});

test("toolsFor: offline-only withholds the web tools even with Tor on", () => {
  const tor = { enabled: true, socksPort: 9050 };
  const off = toolsFor({ offlineOnly: true, screenControl: false, tor });
  assert.equal(off.some((t) => t.name === "web_fetch"), false);
  assert.equal(off.some((t) => t.name === "web_search"), false);
  const on = toolsFor({ offlineOnly: false, screenControl: false, tor });
  assert.equal(on.some((t) => t.name === "web_fetch"), true);
  assert.equal(on.some((t) => t.name === "web_search"), true);
});

function toolCtx(home: string): ToolContext {
  return {
    cwd: home,
    workspace: home,
    memory: new Memory(path.join(home, "memory.json")),
    embedder: null,
    vision: null,
    signal: sig(),
  };
}

test("the web_fetch tool, bound by toolsFor with Tor on, transits the proxy", async () => {
  const stub = await startStubProxy();
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-tor-tool-"));
  try {
    const tools = toolsFor({ offlineOnly: false, screenControl: false, tor: { enabled: true, socksPort: stub.port } });
    const wf = tools.find((t) => t.name === "web_fetch")!;
    const out = await wf.run({ url: "http://example.com/abc" } as never, toolCtx(home));
    assert.match(out, /^200 http:\/\/example\.com\/abc/);
    assert.ok(out.includes(STUB_BODY), out);
    assert.deepEqual(stub.connects, [{ atyp: 3, host: "example.com" }]);
    assert.match(wf.description, /Tor is on/);
  } finally {
    await stub.close();
  }
});

test("the web_search tool bound with Tor on asks every provider through the proxy", async () => {
  // Provider endpoints are HTTPS and the stub cannot terminate TLS, so it
  // grants each tunnel and closes: every provider fetch fails at the TLS
  // step, and the chain returns its all-tried honest-empty text — what
  // this test claims is the routing: one CONNECT per provider, all
  // through the proxy, hostnames unresolved.
  const stub = await startStubProxy({ grantThenClose: true });
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-tor-search-"));
  try {
    const tools = toolsFor({ offlineOnly: false, screenControl: false, tor: { enabled: true, socksPort: stub.port } });
    const ws = tools.find((t) => t.name === "web_search")!;
    const out = await ws.run({ query: "weather in Miami" } as never, toolCtx(home));
    assert.match(out, /No results for "weather in Miami"\. Do not make any up\. \(DuckDuckGo, Bing and Brave were all tried/);
    assert.deepEqual(
      stub.connects.map((c) => c.host),
      ["html.duckduckgo.com", "www.bing.com", "search.brave.com"],
    );
    assert.ok(stub.connects.every((c) => c.atyp === 3));
  } finally {
    await stub.close();
  }
});

// Every web tool shares one transport: with Tor on, toolsFor hands each
// tool's run a Tor-bound webFetch / webFetchBytes, so shopping, browse and
// investigate fetch through the proxy exactly like web_fetch does.

const PNG_1PX = Buffer.from("89504e470d0a1a0a0000000d4948445200000001000000010806000000" + "1f15c4890000000d4944415478da63f8cfc0f01f0005000201" + "e221bc330000000049454e44ae426082", "hex");

test("product_check with Tor on: the listing page AND its photo (vision read) transit the proxy", async () => {
  const page = `<html><head><title>Kettle</title><meta property="og:image" content="http://img.example.com/kettle.png"></head><body><h1>Kettle</h1><p>Price: $25.00</p></body></html>`;
  const stub = await startStubProxy({ respond: (host) => (host === "img.example.com" ? { type: "image/png", body: PNG_1PX } : { type: "text/html", body: page }) });
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-tor-shop-"));
  try {
    const tools = toolsFor({ offlineOnly: false, screenControl: false, tor: { enabled: true, socksPort: stub.port } });
    const pc = tools.find((t) => t.name === "product_check")!;
    const asked: string[] = [];
    const vision = { kind: "local", label: "stub-vlm", describe: async (_imgs: unknown, q: string) => { asked.push(q); return "A white kettle; price tag reads $25.00."; } };
    const out = await pc.run({ url: "http://shop.example.com/kettle" } as never, { ...toolCtx(home), vision: vision as never, visionEnabled: true });
    const hosts = stub.connects.map((c) => c.host);
    assert.ok(hosts.includes("shop.example.com"), `page via proxy: ${hosts}`);
    assert.ok(hosts.includes("img.example.com"), `photo via proxy: ${hosts}`);
    assert.ok(stub.connects.every((c) => c.atyp === 3), "hostnames unresolved");
    assert.equal(asked.length, 1, "the photo was read once");
    assert.match(out, /Vision read/);
  } finally {
    await stub.close();
  }
});

test("browse_task with Tor on fetches an .onion start page through the proxy", async () => {
  const stub = await startStubProxy();
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-tor-browse-"));
  try {
    const tools = toolsFor({ offlineOnly: false, screenControl: false, tor: { enabled: true, socksPort: stub.port } });
    const bt = tools.find((t) => t.name === "browse_task")!;
    const out = await bt.run({ goal: "read the page", start_url: ONION, max_steps: 1 } as never, toolCtx(home));
    assert.deepEqual(stub.connects, [{ atyp: 3, host: new URL(ONION).hostname }]);
    assert.ok(!/REFUSED/.test(out), out);
    assert.ok(out.includes(STUB_BODY), out);
  } finally {
    await stub.close();
  }
});

test("product_check on an .onion listing: Tor on fetches it through the proxy; Tor off gets the transport refusal", async () => {
  // No shopping-layer onion exclusion (owner reversal, 2026-10-20): the transport decides.
  const listing = `<html><head><title>Onion Kettle</title></head><body><h1>Onion Kettle</h1><p>Price: $19.00</p></body></html>`;
  const stub = await startStubProxy({ respond: () => ({ type: "text/html", body: listing }) });
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-tor-onion-shop-"));
  try {
    const on = toolsFor({ offlineOnly: false, screenControl: false, tor: { enabled: true, socksPort: stub.port } }).find((t) => t.name === "product_check")!;
    const out = await on.run({ url: ONION } as never, toolCtx(home));
    assert.deepEqual(stub.connects, [{ atyp: 3, host: new URL(ONION).hostname }]);
    assert.match(out, /Onion Kettle/);
    const off = toolsFor({ offlineOnly: false, screenControl: false }).find((t) => t.name === "product_check")!;
    const refused = await off.run({ url: ONION } as never, toolCtx(home));
    assert.match(refused, /opens \.onion addresses only with Tor on/);
    assert.equal(stub.connects.length, 1, "Tor off never touched the proxy");
  } finally {
    await stub.close();
  }
});

test("browse_task with Tor off refuses an .onion before any fetch", async () => {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-notor-browse-"));
  const tools = toolsFor({ offlineOnly: false, screenControl: false });
  const bt = tools.find((t) => t.name === "browse_task")!;
  const out = await bt.run({ goal: "read the page", start_url: ONION, max_steps: 1 } as never, toolCtx(home));
  assert.match(out, /fetched only through Tor, and Tor is off/);
});

// ---------- settings / update path ----------

function makeRuntime(mutate?: (cfg: Config) => void): { rt: Runtime; home: string } {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-tor-rt-"));
  const p = paths(home);
  const cfg = loadConfig(p);
  mutate?.(cfg);
  return { rt: new Runtime(cfg, p, home), home };
}

test("tor defaults: off, port 9050, state carries all three fields", () => {
  const { rt } = makeRuntime();
  const s = rt.state();
  assert.equal(s.tor, false);
  assert.equal(s.torPort, 9050);
  assert.equal(s.torReachable, null);
});

test("update({tor}) flips the setting, persists it, and state follows", () => {
  const { rt, home } = makeRuntime();
  rt.update({ tor: true });
  assert.equal(rt.cfg.tor.enabled, true);
  assert.equal(rt.state().tor, true);
  assert.equal(loadConfig(paths(home)).tor.enabled, true); // on disk, not just in memory
  rt.update({ tor: false });
  assert.equal(rt.state().tor, false);
  assert.equal(loadConfig(paths(home)).tor.enabled, false);
});

test("config merge: a partial tor block keeps the other field's default", () => {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-tor-cfg-"));
  const p = paths(home);
  writeFileSync(p.config, JSON.stringify({ tor: { socksPort: 9150 } }));
  let cfg = loadConfig(p);
  assert.equal(cfg.tor.enabled, false);
  assert.equal(cfg.tor.socksPort, 9150);
  writeFileSync(p.config, JSON.stringify({ tor: { enabled: true } }));
  cfg = loadConfig(p);
  assert.equal(cfg.tor.enabled, true);
  assert.equal(cfg.tor.socksPort, 9050);
});

test("refreshTorStatus reports the probe honestly", async () => {
  const stub = await startStubProxy();
  try {
    const { rt } = makeRuntime((cfg) => { cfg.tor.enabled = true; cfg.tor.socksPort = stub.port; });
    assert.equal(await rt.refreshTorStatus(), true);
    assert.equal(rt.state().torReachable, true);
    const { rt: rt2 } = makeRuntime((cfg) => { cfg.tor.enabled = true; cfg.tor.socksPort = 1; });
    assert.equal(await rt2.refreshTorStatus(), false);
    assert.equal(rt2.state().torReachable, false);
  } finally {
    await stub.close();
  }
});
