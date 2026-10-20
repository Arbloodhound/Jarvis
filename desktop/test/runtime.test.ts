import assert from "node:assert/strict";
import { mkdtempSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import { afterEach, test } from "node:test";
import { loadConfig, paths, type Config } from "../src/config.ts";
import { Runtime } from "../src/runtime.ts";

/**
 * vision() used to decide local-vs-cloud on cfg.cloud.apiKey alone. A key
 * supplied only via ANTHROPIC_API_KEY (which the SDK reads on its own, and
 * config documents as the fallback) left the config field empty, so with a
 * vision.model set the local branch won and looks silently went to a local
 * server, which 404'd. The decision must treat the env key as present.
 */

const ENV_KEY = "ANTHROPIC_API_KEY";
const savedEnv = process.env[ENV_KEY];

afterEach(() => {
  if (savedEnv === undefined) delete process.env[ENV_KEY];
  else process.env[ENV_KEY] = savedEnv;
});

function makeRuntime(mutate: (cfg: Config) => void): Runtime {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-runtime-"));
  const p = paths(home);
  const cfg = loadConfig(p);
  cfg.brain = "cloud";
  cfg.vision.model = "qwen3-vl";
  mutate(cfg);
  return new Runtime(cfg, p, home);
}

test("env-only key + cloud brain: vision() returns the cloud backend", () => {
  process.env[ENV_KEY] = "sk-ant-env-only-key";
  const rt = makeRuntime((cfg) => { cfg.cloud.apiKey = ""; });
  assert.equal(rt.vision()?.kind, "cloud");
});

test("config key + cloud brain: vision() returns the cloud backend (unchanged)", () => {
  delete process.env[ENV_KEY];
  const rt = makeRuntime((cfg) => { cfg.cloud.apiKey = "sk-ant-config-key"; });
  assert.equal(rt.vision()?.kind, "cloud");
});

test("no key anywhere + cloud brain: vision() keeps the local fallback (unchanged)", () => {
  delete process.env[ENV_KEY];
  const rt = makeRuntime((cfg) => { cfg.cloud.apiKey = ""; });
  assert.equal(rt.vision()?.kind, "local");
});

test("env key does not override the local brain's local VLM (unchanged)", () => {
  process.env[ENV_KEY] = "sk-ant-env-only-key";
  const rt = makeRuntime((cfg) => { cfg.brain = "local"; cfg.cloud.apiKey = ""; });
  assert.equal(rt.vision()?.kind, "local");
});

test("state() reports the key source without ever carrying the key", () => {
  process.env[ENV_KEY] = "sk-ant-env-only-key";
  const rt = makeRuntime((cfg) => { cfg.cloud.apiKey = ""; });
  const s = rt.state() as unknown as Record<string, unknown>;
  assert.equal(s.cloudKeySource, "env");
  assert.ok(!JSON.stringify(s).includes("sk-ant-env-only-key"));
});
