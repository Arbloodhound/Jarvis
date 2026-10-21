import { existsSync, readFileSync, renameSync, writeFileSync } from "node:fs";

/**
 * The prediction layer: numbers Jarvis tracks over time, and forecasts of
 * them from a Timestral forecasting server (forecast/server.py, CPU).
 *
 * Series come from anywhere a number recurs: facts in memory (spending,
 * bills, sleep, weight, how long a task took), runtime numbers, prices.
 * Each is a named list of timestamped points in ~/.jarvis/series.json.
 * A forecast is a range (quantiles), never a single certain value, and it
 * only informs: nothing here acts on a forecast.
 */

export const MAX_POINTS = 32_768;
export const MAX_SERIES = 500;
export const MAX_HORIZON = 1024;

export interface Point { t: string; v: number }
export interface Series { unit?: string; points: Point[] }

export class SeriesStore {
  private data: Record<string, Series> = {};

  constructor(private readonly file: string | null) {
    if (file && existsSync(file)) {
      try {
        this.data = JSON.parse(readFileSync(file, "utf8")) as Record<string, Series>;
      } catch {
        // A broken file must not stop Jarvis: keep it aside, start empty.
        renameSync(file, `${file}.broken-${Date.now()}`);
        this.data = {};
      }
    }
  }

  static cleanName(name: string): string {
    return name.trim().toLowerCase().replace(/[^a-z0-9._-]+/g, "_").replace(/^_+|_+$/g, "").slice(0, 80);
  }

  /** Add one point; points stay in time order. Returns the series. */
  add(name: string, value: number, at?: string, unit?: string): Series {
    const key = SeriesStore.cleanName(name);
    if (!key) throw new Error("Give the series a name, e.g. \"spending.groceries\".");
    if (!Number.isFinite(value)) throw new Error("The value must be a finite number.");
    const t = at ? new Date(at) : new Date();
    if (Number.isNaN(t.getTime())) throw new Error(`Not a date: ${at}`);
    if (!this.data[key] && Object.keys(this.data).length >= MAX_SERIES) throw new Error(`Too many series (${MAX_SERIES}). Remove one first.`);
    const s = (this.data[key] ??= { points: [] });
    if (unit) s.unit = unit.slice(0, 20);
    s.points.push({ t: t.toISOString(), v: value });
    s.points.sort((a, b) => a.t.localeCompare(b.t));
    if (s.points.length > MAX_POINTS) s.points.splice(0, s.points.length - MAX_POINTS);
    this.save();
    return s;
  }

  get(name: string): Series | undefined {
    return this.data[SeriesStore.cleanName(name)];
  }

  remove(name: string): boolean {
    const key = SeriesStore.cleanName(name);
    if (!this.data[key]) return false;
    delete this.data[key];
    this.save();
    return true;
  }

  list(): Array<{ name: string; unit?: string; count: number; last?: Point }> {
    return Object.entries(this.data).map(([name, s]) => ({ name, unit: s.unit, count: s.points.length, last: s.points[s.points.length - 1] }));
  }

  private save(): void {
    if (!this.file) return;
    const tmp = `${this.file}.tmp`;
    writeFileSync(tmp, JSON.stringify(this.data), { mode: 0o600 });
    renameSync(tmp, this.file);
  }
}

export interface ForecastResult {
  model: string;
  median: number[];
  /** Quantile level ("0.1", "0.9", …) → values per step. */
  quantiles: Record<string, number[]>;
}

export class Forecaster {
  constructor(private readonly baseUrl: string) {}

  async forecast(values: number[], horizon: number, signal?: AbortSignal): Promise<ForecastResult> {
    let res: Response;
    try {
      res = await fetch(`${this.baseUrl.replace(/\/$/, "")}/forecast`, {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ values, horizon, quantiles: [0.1, 0.5, 0.9] }),
        signal,
      });
    } catch (err) {
      if (signal?.aborted) throw err;
      throw new Error(`Can't reach the forecasting server at ${this.baseUrl}. Start it (forecast/server.py) or set forecast.baseUrl in config.`);
    }
    const body = (await res.json().catch(() => ({}))) as Partial<ForecastResult> & { error?: string };
    if (!res.ok) throw new Error(`Forecasting server answered ${res.status}: ${body.error ?? "error"}`);
    if (!Array.isArray(body.median)) throw new Error("The forecasting server returned no forecast.");
    return { model: body.model ?? "?", median: body.median, quantiles: body.quantiles ?? {} };
  }
}

/** The typical gap between points in ms (median), or 0 with under 2 points. */
export function typicalStep(points: Point[]): number {
  const gaps: number[] = [];
  for (let i = 1; i < points.length; i++) gaps.push(Date.parse(points[i]!.t) - Date.parse(points[i - 1]!.t));
  if (!gaps.length) return 0;
  gaps.sort((a, b) => a - b);
  return gaps[Math.floor(gaps.length / 2)]!;
}

function fmtNum(n: number): string {
  const a = Math.abs(n);
  return a >= 1000 ? n.toFixed(0) : a >= 10 ? n.toFixed(1) : n.toFixed(2);
}

function fmtStep(ms: number): string {
  const units: Array<[number, string]> = [[86_400_000 * 28, "month"], [86_400_000 * 7, "week"], [86_400_000, "day"], [3_600_000, "hour"], [60_000, "minute"], [1000, "second"]];
  for (const [u, label] of units) if (ms >= u) { const n = Math.round(ms / u); return `${n} ${label}${n === 1 ? "" : "s"}`; }
  return `${ms} ms`;
}

/**
 * The owner-facing forecast: each step with its likely value and a range
 * (10–90%), the dates when the series has times, and what it's based on.
 */
export function formatForecast(f: ForecastResult, opts: { name?: string; unit?: string; history: number[]; points?: Point[] }): string {
  const lo = f.quantiles["0.1"] ?? [];
  const hi = f.quantiles["0.9"] ?? [];
  const step = opts.points ? typicalStep(opts.points) : 0;
  const lastT = opts.points?.length ? Date.parse(opts.points[opts.points.length - 1]!.t) : 0;
  const u = opts.unit ? ` ${opts.unit}` : "";
  const rows = f.median.map((m, i) => {
    const when = step && lastT ? new Date(lastT + step * (i + 1)).toISOString().slice(0, step >= 86_400_000 ? 10 : 16).replace("T", " ") : `step ${i + 1}`;
    const range = lo[i] !== undefined && hi[i] !== undefined ? ` (likely ${fmtNum(lo[i]!)}–${fmtNum(hi[i]!)}${u})` : "";
    return `- ${when}: ~${fmtNum(m)}${u}${range}`;
  });
  const recent = opts.history.slice(-5).map(fmtNum).join(", ");
  return [
    `Forecast${opts.name ? ` for "${opts.name}"` : ""} (${f.model}), ${f.median.length} step(s) ahead${step ? `, one step ≈ ${fmtStep(step)}` : ""}:`,
    ...rows,
    `Based on ${opts.history.length} past value(s); latest: ${recent}.`,
    "This extends the pattern in the numbers; it can't know about events that aren't in them. Ranges are 10–90%.",
    opts.history.length < 20 ? "Few points so far: treat this as rough." : "",
  ].filter(Boolean).join("\n");
}
