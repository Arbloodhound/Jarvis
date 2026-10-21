import { z } from "zod";
import { formatForecast, MAX_HORIZON, SeriesStore } from "../forecast.js";
import type { Tool, ToolContext } from "./index.js";

/**
 * Prediction-layer tools: record numbers over time, see what's tracked,
 * and forecast a series (or a list of numbers) with a likely range.
 */

function tool<S extends z.ZodType>(t: Tool<S>): Tool {
  return t as unknown as Tool;
}

function needSeries(ctx: ToolContext): SeriesStore {
  if (!ctx.series) throw new Error("Number tracking isn't available in this session.");
  return ctx.series;
}

export const recordNumberTool = tool({
  name: "record_number",
  description:
    "Track a number over time for forecasting: add one value to a named series (e.g. spending.groceries, sleep.hours, weight.kg, bill.electricity, task.build_minutes). Use it whenever a recurring number appears — from the owner, from memory facts (recall them, then record each with its date), or from results. Give `at` (ISO date) when the value belongs to a past date.",
  schema: z.object({
    series: z.string().min(1).max(80).describe("series name, dotted words, e.g. spending.groceries"),
    value: z.number().describe("the number"),
    at: z.string().max(40).optional().describe("when it happened (ISO date/time); default now"),
    unit: z.string().max(20).optional().describe("unit, e.g. $, h, kg"),
  }),
  // A lasting write like remember: after web content in the run it gets a card.
  policy: { effects: ["mutate"], lastsBeyondRequest: true },
  async run({ series, value, at, unit }, ctx) {
    const s = needSeries(ctx).add(series, value, at, unit);
    return `Recorded ${value}${s.unit ? ` ${s.unit}` : ""} in "${SeriesStore.cleanName(series)}" (${s.points.length} point${s.points.length === 1 ? "" : "s"}).`;
  },
});

export const listSeriesTool = tool({
  name: "list_series",
  description: "List the number series Jarvis is tracking for forecasts: name, how many points, latest value and date.",
  schema: z.object({}),
  policy: { effects: ["read"] },
  parallelSafe: true,
  async run(_a, ctx) {
    const all = needSeries(ctx).list();
    if (!all.length) return "No series tracked yet. Use record_number to start one.";
    return all.map((s) => `- ${s.name}: ${s.count} point(s)${s.last ? `, latest ${s.last.v}${s.unit ? ` ${s.unit}` : ""} at ${s.last.t.slice(0, 16).replace("T", " ")}` : ""}`).join("\n");
  },
});

export const forecastTool = tool({
  name: "forecast",
  description:
    "Forecast what comes next for a tracked series (by name) or for a list of numbers, with a likely range for each step (Timestral, a time-series model). Use it to answer 'where is this heading?', budgets, bills, ETAs, usage trends, or whether a new value is unusual. It extends the pattern in the numbers only; say so when you report it. Needs at least 3 values.",
  schema: z.object({
    series: z.string().max(80).optional().describe("a tracked series name"),
    values: z.array(z.number()).max(32_768).optional().describe("or the numbers themselves, oldest first, evenly spaced"),
    horizon: z.number().int().min(1).max(MAX_HORIZON).optional().describe("steps ahead (default 7)"),
  }),
  policy: { effects: ["read"] },
  parallelSafe: true,
  async run({ series, values, horizon }, ctx) {
    if (!ctx.forecaster) throw new Error("No forecasting server is set up. Set forecast.baseUrl in config (the pod runs one on http://127.0.0.1:8095).");
    let history: number[];
    let s: ReturnType<SeriesStore["get"]>;
    if (series) {
      s = needSeries(ctx).get(series);
      if (!s) throw new Error(`No series named "${series}". Use list_series to see what's tracked.`);
      history = s.points.map((p) => p.v);
    } else if (values?.length) {
      history = values;
    } else {
      throw new Error("Give a series name or a list of values.");
    }
    if (history.length < 3) throw new Error(`Only ${history.length} value(s) so far; a forecast needs at least 3.`);
    const f = await ctx.forecaster.forecast(history, horizon ?? 7, ctx.signal);
    return formatForecast(f, { name: series ? SeriesStore.cleanName(series) : undefined, unit: s?.unit, history, points: s?.points });
  },
});

export const FORECAST_TOOLS: Tool[] = [recordNumberTool, listSeriesTool, forecastTool];
