import assert from "node:assert/strict";
import { createServer, type Server } from "node:http";
import { test } from "node:test";
import { Vision } from "../src/vision.ts";

function fake(handler: (body: any) => any): Promise<{ srv: Server; url: string }> {
  return new Promise((resolve) => {
    const srv = createServer((req, res) => {
      let b = ""; req.on("data", (d) => (b += d));
      req.on("end", () => { res.writeHead(200, { "content-type": "application/json" }); res.end(JSON.stringify(handler(JSON.parse(b)))); });
    });
    srv.listen(0, "127.0.0.1", () => resolve({ srv, url: `http://127.0.0.1:${(srv.address() as any).port}/v1` }));
  });
}

test("local vision sends an image_url and returns the description", async () => {
  let seen: any;
  const { srv, url } = await fake((body) => { seen = body; return { choices: [{ message: { content: "A cat on a desk." } }] }; });
  const v = new Vision({ kind: "local", baseUrl: url, model: "qwen3-vl" });
  const out = await v.describe([{ data: "aGVsbG8=", mediaType: "image/png" }], "what's this?");
  srv.close();
  assert.equal(out, "A cat on a desk.");
  const content = seen.messages[0].content;
  assert.equal(content[0].type, "text");
  assert.ok(content[1].image_url.url.startsWith("data:image/png;base64,aGVsbG8="));
});

test("a non-vision local model gives a clear error", async () => {
  const { srv, url } = await fake(() => ({ choices: [{ message: {} }] }));
  const v = new Vision({ kind: "local", baseUrl: url, model: "text-only" });
  await assert.rejects(v.describe([{ data: "x", mediaType: "image/png" }], "?"), /no description|vision model/i);
  srv.close();
});

test("describe passes a caller maxTokens cap to the local server (default stays 2048)", async () => {
  const seen: number[] = [];
  const { srv, url } = await fake((body) => { seen.push(body.max_tokens); return { choices: [{ message: { content: "ok" } }] }; });
  const v = new Vision({ kind: "local", baseUrl: url, model: "qwen3-vl" });
  await v.describe([{ data: "aGVsbG8=", mediaType: "image/png" }], "?", undefined, 123);
  await v.describe([{ data: "aGVsbG8=", mediaType: "image/png" }], "?");
  srv.close();
  assert.deepEqual(seen, [123, 2048]);
});
