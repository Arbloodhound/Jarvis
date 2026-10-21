import assert from "node:assert/strict";
import { createServer } from "node:http";
import { test } from "node:test";
import { WebSocketServer } from "ws";
import { dropOverlap, formatPage, pickTab, readChromePage, READ_PAGE_EXPRESSION, runScrollRead, type ChromeTarget } from "../src/tools/pageread.ts";
import { ALL_TOOLS, toolsFor } from "../src/tools/index.ts";
import type { Vision } from "../src/vision.ts";

/**
 * Reading more than one screen at a time: read_browser_page (Chrome's
 * debug port, whole page text) and scroll_read (scroll + vision read of
 * every screen, overlap removed, in one call).
 */

const tab = (id: string, title: string, url: string): ChromeTarget =>
  ({ id, type: "page", title, url, webSocketDebuggerUrl: `ws://x/devtools/page/${id}` });

test("pickTab: by URL or title, else the most recent page; never devtools or non-pages", () => {
  const targets = [
    { id: "w", type: "service_worker", title: "sw", url: "https://a.example/sw.js", webSocketDebuggerUrl: "ws://x/w" },
    tab("d", "DevTools", "devtools://devtools/bundled/inspector.html"),
    tab("1", "Shop — Cart", "https://shop.example/cart"),
    tab("2", "News", "https://news.example/today"),
  ];
  assert.equal(pickTab(targets)?.id, "1");
  assert.equal(pickTab(targets, "NEWS")?.id, "2");
  assert.equal(pickTab(targets, "cart")?.id, "1");
  assert.equal(pickTab(targets, "nothing"), null);
});

test("formatPage: text capped, links de-duplicated", () => {
  const out = formatPage({ title: "T", url: "https://t.example", text: "a".repeat(5000), links: [{ text: "x", href: "https://x" }, { text: "x2", href: "https://x" }] }, 1000);
  assert.ok(out.includes("first 1000 shown") && out.includes("[… cut]"));
  assert.equal(out.split("→ https://x").length - 1, 1);
});

test("read_browser_page reads the whole page from a (fake) Chrome debug port, with one fixed read-only expression", async () => {
  const evaluated: string[] = [];
  const http = createServer((req, res) => {
    const port = (http.address() as { port: number }).port;
    if (req.url === "/json/list") {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify([
        { id: "p1", type: "page", title: "Long article", url: "https://blog.example/post", webSocketDebuggerUrl: `ws://127.0.0.1:${port}/devtools/page/p1` },
      ]));
      return;
    }
    res.writeHead(404).end();
  });
  const wss = new WebSocketServer({ server: http });
  wss.on("connection", (ws) => ws.on("message", (raw) => {
    const msg = JSON.parse(raw.toString()) as { id: number; params: { expression: string } };
    evaluated.push(msg.params.expression);
    ws.send(JSON.stringify({ id: msg.id, result: { result: { value: {
      title: "Long article", url: "https://blog.example/post",
      text: "Heading\n\nParagraph one.\nParagraph two at the very bottom.",
      links: [{ text: "Next post", href: "https://blog.example/next" }],
    } } } }));
  }));
  await new Promise<void>((r) => http.listen(0, "127.0.0.1", () => r()));
  const port = (http.address() as { port: number }).port;
  try {
    const out = await readChromePage(undefined, 10_000, undefined, port);
    assert.ok(out.includes("Page: Long article") && out.includes("Paragraph two at the very bottom."), out);
    assert.ok(out.includes("Next post → https://blog.example/next"));
    assert.deepEqual(evaluated, [READ_PAGE_EXPRESSION]);
    await assert.rejects(readChromePage("nomatch", 10_000, undefined, port), /No open Chrome tab matches "nomatch"/);
  } finally {
    wss.close();
    http.close();
  }
});

test("read_browser_page without Chrome's debug port says how to start it", async () => {
  await assert.rejects(readChromePage(undefined, 1000, undefined, 1), /remote-debugging-port=9222/);
});

test("dropOverlap removes the lines the previous screen already had", () => {
  const prev = "Title\nline one is here\nline two is here\nline three is here";
  const next = "line two is here\nline three is here\nline four is new\nline five is new";
  assert.equal(dropOverlap(prev, next), "line four is new\nline five is new");
  assert.equal(dropOverlap("unrelated text", "all new text\nmore"), "all new text\nmore");
});

test("scroll_read: reads each screen, joins without overlap, stops when the screen stops changing", async () => {
  const pages = [
    "Article title\nfirst paragraph text\nsecond paragraph text",
    "second paragraph text\nthird paragraph text\nfourth paragraph text",
    "fourth paragraph text\nthe end of the article",
  ];
  let pos = 0;
  const scrolls: number[] = [];
  const asked: string[] = [];
  const vision = {
    async describe(images: Array<{ data: string }>, q: string) {
      asked.push(q);
      return pages[Number(Buffer.from(images[0]!.data, "base64").toString())]!;
    },
  } as unknown as Vision;
  const r = await runScrollRead({ maxScreens: 10, clicks: 5 }, {
    vision,
    screenshot: async () => Buffer.from(String(pos)),
    scroll: async (c) => { scrolls.push(c); pos = Math.min(pos + 1, pages.length - 1); },
    settleMs: 0,
  });
  assert.equal(r.reachedEnd, true);
  assert.equal(r.screens, 3);
  assert.equal(asked.length, 3, "one vision read per distinct screen");
  assert.ok(asked[1]!.includes("second paragraph text"), "the previous screen's tail is given to the reader");
  const lines = r.text.split("\n").filter(Boolean);
  assert.deepEqual(lines, ["Article title", "first paragraph text", "second paragraph text", "third paragraph text", "fourth paragraph text", "the end of the article"]);
  assert.deepEqual(scrolls, [5, 5, 5]);
});

test("tool wiring: scroll_read follows screen control and vision; read_browser_page is read-only web content", () => {
  const names = (o: Parameters<typeof toolsFor>[0]) => toolsFor(o).map((t) => t.name);
  assert.ok(!names({ offlineOnly: false, screenControl: false }).includes("scroll_read"));
  assert.ok(names({ offlineOnly: false, screenControl: true }).includes("scroll_read"));
  assert.ok(!names({ offlineOnly: false, screenControl: true, vision: false }).includes("scroll_read"));
  const rb = ALL_TOOLS.find((t) => t.name === "read_browser_page")!;
  assert.deepEqual(rb.policy.effects, ["read"]);
  assert.equal(rb.outside, true);
  const sr = ALL_TOOLS.find((t) => t.name === "scroll_read")!;
  assert.equal(sr.policy.alwaysConfirm, true);
});
