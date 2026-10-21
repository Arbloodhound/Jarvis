import assert from "node:assert/strict";
import { mkdtempSync, readFileSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { test } from "node:test";
import { Forecaster, formatForecast, SeriesStore, typicalStep } from "../src/forecast.ts";
import { forecastTool, listSeriesTool, recordNumberTool } from "../src/tools/forecast.ts";
import { ALL_TOOLS, type ToolContext } from "../src/tools/index.ts";

/**
 * The prediction layer: numbers tracked over time (series.json) and
 * forecast with a range by a Timestral server (faked here).
 */

const dir = () => mkdtempSync(path.join(tmpdir(), "jarvis-forecast-"));

test("SeriesStore: named series, time-ordered, saved and reloaded; bad input refused", () => {
  const file = path.join(dir(), "series.json");
  const s = new SeriesStore(file);
  s.add("Spending Groceries", 120, "2026-10-08", "$");
  s.add("spending groceries", 95, "2026-10-01");
  s.add("spending_groceries", 130, "2026-10-15");
  const again = new SeriesStore(file);
  const g = again.get("spending.groceries") ?? again.get("spending_groceries")!;
  assert.deepEqual(g.points.map((p) => p.v), [95, 120, 130], "time order, one series for name variants");
  assert.equal(g.unit, "$");
  assert.throws(() => s.add("x", Number.NaN), /finite/);
  assert.throws(() => s.add("x", 1, "not a date"), /Not a date/);
  assert.throws(() => s.add("  ", 1), /name/);
  assert.ok(JSON.parse(readFileSync(file, "utf8")));
});

test("typicalStep and formatForecast: dates per step, a range, and what it's based on", () => {
  const points = ["2026-10-01", "2026-10-02", "2026-10-03", "2026-10-04"].map((t, i) => ({ t: new Date(t).toISOString(), v: 10 + i }));
  assert.equal(typicalStep(points), 86_400_000);
  const out = formatForecast({ model: "fake", median: [14, 15], quantiles: { "0.1": [13, 13.5], "0.9": [15, 16.5] } }, { name: "x", unit: "$", history: points.map((p) => p.v), points });
  assert.ok(out.includes("- 2026-10-05: ~14.0 $ (likely 13.0–15.0 $)"), out);
  assert.ok(out.includes("- 2026-10-06:"), out);
  assert.ok(out.includes("one step ≈ 1 day"));
  assert.ok(out.includes("Based on 4 past value(s)") && out.includes("Few points so far"));
});

test("forecast tool: a tracked series goes to the server and comes back as a range", async () => {
  const got: Array<{ values: number[]; horizon: number }> = [];
  const server = createServer((req, res) => {
    let b = "";
    req.on("data", (d) => (b += d));
    req.on("end", () => {
      const body = JSON.parse(b) as { values: number[]; horizon: number };
      got.push(body);
      const last = body.values[body.values.length - 1]!;
      const med = Array.from({ length: body.horizon }, (_, i) => last + i + 1);
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ model: "fake-timestral", median: med, quantiles: { "0.1": med.map((m) => m - 1), "0.5": med, "0.9": med.map((m) => m + 1) } }));
    });
  });
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", () => r()));
  const url = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  const series = new SeriesStore(null);
  const ctx = { series, forecaster: new Forecaster(url), signal: new AbortController().signal } as unknown as ToolContext;
  try {
    for (const [i, v] of [100, 104, 103, 108, 110].entries()) await recordNumberTool.run({ series: "bill.power", value: v, at: `2026-0${i + 1}-01`, unit: "$" }, ctx);
    assert.ok((await listSeriesTool.run({}, ctx)).includes("bill.power: 5 point(s), latest 110 $"));
    const out = await forecastTool.run({ series: "bill.power", horizon: 3 }, ctx);
    assert.deepEqual(got[0], { values: [100, 104, 103, 108, 110], horizon: 3, quantiles: [0.1, 0.5, 0.9] });
    assert.ok(out.includes("fake-timestral") && out.includes("~111") && out.includes("likely 110.0–112.0 $") && out.includes("one step ≈ 1 month"), out);
    assert.ok(out.includes("can't know about events"), "the limits are said");
    const inline = await forecastTool.run({ values: [1, 2, 3, 4], horizon: 2 }, ctx);
    assert.ok(inline.includes("step 1: ~5.00"), inline);
    await assert.rejects(forecastTool.run({ values: [1, 2] }, ctx), /at least 3/);
    await assert.rejects(forecastTool.run({ series: "nope" }, ctx), /No series named/);
  } finally {
    server.close();
  }
});

test("forecast without a server says how to set it up; the tools are registered with honest policies", async () => {
  const ctx = { series: new SeriesStore(null), forecaster: null, signal: new AbortController().signal } as unknown as ToolContext;
  await assert.rejects(forecastTool.run({ values: [1, 2, 3] }, ctx), /forecast\.baseUrl/);
  const unreachable = { ...ctx, forecaster: new Forecaster("http://127.0.0.1:1") } as ToolContext;
  await assert.rejects(forecastTool.run({ values: [1, 2, 3] }, unreachable), /Can't reach the forecasting server/);
  const names = ALL_TOOLS.map((t) => t.name);
  for (const n of ["record_number", "list_series", "forecast"]) assert.ok(names.includes(n), n);
  assert.equal(recordNumberTool.policy.lastsBeyondRequest, true, "a lasting write, carded after web content like remember");
  assert.deepEqual(forecastTool.policy.effects, ["read"]);
});
