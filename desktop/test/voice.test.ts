import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { spawnSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { createServer, type Server } from "node:http";
import { tmpdir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { afterEach, test } from "node:test";
import { loadConfig, paths, type Config } from "../src/config.ts";
import { Runtime } from "../src/runtime.ts";
import { speakText, speakToFile, useOsForTest } from "../src/tools/platform.ts";
import { ALL_TOOLS, toolsFor, type ToolContext } from "../src/tools/index.ts";
import { Memory } from "../src/memory.ts";
import { assertFinished, isLoopbackUrl, Transcriber, UnfinishedUtterance } from "../src/voice.ts";

/**
 * The voice cluster: the finished-utterance rule (features/voice.py's
 * contract), the whisper-class transcription client, the honest OS TTS
 * backends, the gate-facing tools, and the offline loopback rule.
 */

afterEach(() => useOsForTest(null));

// ---------- the finished-utterance rule ----------

test("assertFinished accepts finished utterances", () => {
  for (const ok of ["Hello.", "Really?", "Yes!", "Done…", "  Padded.  ", "Two. Sentences."]) {
    assert.equal(assertFinished(ok), true, ok);
  }
});

test("assertFinished refuses empty and mid-thought text", () => {
  for (const bad of ["", "   ", "Hello", "and then I was", "Trailing comma,", "Half a thought… no wait"]) {
    assert.throws(() => assertFinished(bad), UnfinishedUtterance, JSON.stringify(bad));
  }
  assert.throws(() => assertFinished(""), /empty utterance/);
  assert.throws(() => assertFinished("not finished"), /ends mid-thought/);
});

// ---------- loopback rule ----------

test("isLoopbackUrl tells this computer from the network", () => {
  assert.equal(isLoopbackUrl("http://127.0.0.1:8000/v1"), true);
  assert.equal(isLoopbackUrl("http://localhost:11434/v1"), true);
  assert.equal(isLoopbackUrl("http://[::1]:9000/v1"), true);
  assert.equal(isLoopbackUrl("https://api.openai.com/v1"), false);
  assert.equal(isLoopbackUrl("http://192.168.1.10:8000/v1"), false);
  assert.equal(isLoopbackUrl("http://127.0.0.1.evil.example/v1"), false);
});

// ---------- transcription client ----------

function fakeStt(handler: (body: Buffer, contentType: string) => { status?: number; json: unknown }): Promise<{ srv: Server; url: string }> {
  return new Promise((resolve) => {
    const srv = createServer((req, res) => {
      const chunks: Buffer[] = [];
      req.on("data", (d) => chunks.push(d as Buffer));
      req.on("end", () => {
        const out = handler(Buffer.concat(chunks), String(req.headers["content-type"] ?? ""));
        res.writeHead(out.status ?? 200, { "content-type": "application/json" });
        res.end(JSON.stringify(out.json));
      });
    });
    srv.listen(0, "127.0.0.1", () => resolve({ srv, url: `http://127.0.0.1:${(srv.address() as any).port}/v1` }));
  });
}

test("Transcriber posts multipart (file + model) and returns the text", async () => {
  let seenBody = "";
  let seenType = "";
  const { srv, url } = await fakeStt((body, ct) => {
    seenBody = body.toString("latin1");
    seenType = ct;
    return { json: { text: "  The quick brown fox.  " } };
  });
  const t = new Transcriber({ baseUrl: url, model: "base.en" });
  const out = await t.transcribe(Buffer.from("RIFF-fake-audio"), "fox.wav", { language: "en" });
  srv.close();
  assert.equal(out, "The quick brown fox.");
  assert.match(seenType, /multipart\/form-data/);
  assert.ok(seenBody.includes('name="model"'), "model field sent");
  assert.ok(seenBody.includes("base.en"), "model value sent");
  assert.ok(seenBody.includes('name="file"; filename="fox.wav"'), "file field sent");
  assert.ok(seenBody.includes("RIFF-fake-audio"), "audio bytes sent");
  assert.ok(seenBody.includes('name="language"'), "language field sent");
});

test("Transcriber failures are plain, never a fake transcript", async () => {
  const { srv, url } = await fakeStt(() => ({ status: 500, json: { error: "boom" } }));
  const t = new Transcriber({ baseUrl: url, model: "base.en" });
  await assert.rejects(t.transcribe(Buffer.from("x"), "a.wav"), /answered 500/);
  srv.close();

  const empty = await fakeStt(() => ({ json: { text: "   " } }));
  const t2 = new Transcriber({ baseUrl: empty.url, model: "base.en" });
  await assert.rejects(t2.transcribe(Buffer.from("x"), "a.wav"), /no transcript/);
  empty.srv.close();

  const dead = new Transcriber({ baseUrl: "http://127.0.0.1:1/v1", model: "base.en" });
  await assert.rejects(dead.transcribe(Buffer.from("x"), "a.wav"), /Can't reach the speech-to-text server/);
  await assert.rejects(t.transcribe(Buffer.alloc(0), "a.wav"), /empty/);
});

// ---------- the tools ----------

function ctxWith(transcriber: Transcriber | null, dir: string): ToolContext {
  return {
    cwd: dir,
    workspace: dir,
    memory: new Memory(path.join(dir, "memory.json")),
    embedder: null,
    vision: null,
    transcriber,
    signal: new AbortController().signal,
  };
}

function toolByName(name: string) {
  const t = ALL_TOOLS.find((x) => x.name === name);
  assert.ok(t, `${name} is registered`);
  return t!;
}

test("transcribe_audio without a configured STT server says so plainly", async () => {
  const dir = mkdtempSync(path.join(tmpdir(), "jarvis-voice-"));
  const file = path.join(dir, "note.wav");
  writeFileSync(file, Buffer.from("RIFF"));
  await assert.rejects(
    toolByName("transcribe_audio").run({ path: file } as never, ctxWith(null, dir)),
    /Speech-to-text is not set up/,
  );
});

test("transcribe_audio returns the server's transcript", async () => {
  const dir = mkdtempSync(path.join(tmpdir(), "jarvis-voice-"));
  const file = path.join(dir, "note.wav");
  writeFileSync(file, Buffer.from("RIFF-audio"));
  const { srv, url } = await fakeStt(() => ({ json: { text: "Meeting at five." } }));
  const out = await toolByName("transcribe_audio").run(
    { path: file } as never,
    ctxWith(new Transcriber({ baseUrl: url, model: "base.en" }), dir),
  );
  srv.close();
  assert.match(out, /Transcript of /);
  assert.ok(out.includes("Meeting at five."));
});

test("speak refuses unfinished text BEFORE any backend is tried", async () => {
  const dir = mkdtempSync(path.join(tmpdir(), "jarvis-voice-"));
  // Pretend macOS: a finished text would fail with /say/ on this machine
  // (no such binary); the unfinished text must fail with the rule instead.
  useOsForTest("darwin");
  await assert.rejects(toolByName("speak").run({ text: "half a thought" } as never, ctxWith(null, dir)), /ends mid-thought/);
  await assert.rejects(toolByName("speak").run({ text: "   " } as never, ctxWith(null, dir)), /empty|min|too_small/i);
});

test("toolsFor: speak is present by default and gone when TTS is disabled", () => {
  assert.equal(toolsFor({ offlineOnly: false, screenControl: false }).some((t) => t.name === "speak"), true);
  assert.equal(toolsFor({ offlineOnly: false, screenControl: false, ttsEnabled: false }).some((t) => t.name === "speak"), false);
  // transcribe_audio stays listed (its backend decides at runtime, like look_at_image)
  assert.equal(toolsFor({ offlineOnly: true, screenControl: false }).some((t) => t.name === "transcribe_audio"), true);
});

// ---------- platform TTS: honest failure ----------

test("macOS/Windows speak attempts its real backend and fails loudly without it", async () => {
  useOsForTest("darwin");
  await assert.rejects(speakText("Hello."), /say/);
  useOsForTest("win32");
  await assert.rejects(speakText("Hello."), /powershell/);
});

test("Linux speak with no TTS backend installed reports the install hint", async () => {
  const has = (cmd: string) => spawnSync("which", [cmd]).status === 0;
  if (["spd-say", "espeak-ng", "espeak", "flite"].some(has)) return; // a backend exists here; nothing honest to assert
  await assert.rejects(speakText("Hello."), /No text-to-speech voice found/);
  await assert.rejects(speakToFile("Hello.", path.join(tmpdir(), "jarvis-tts-test.wav")), /espeak|flite/);
});

// ---------- runtime: config + offline rule + state ----------

function makeRuntime(mutate: (cfg: Config) => void): Runtime {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-voice-rt-"));
  const p = paths(home);
  const cfg = loadConfig(p);
  mutate(cfg);
  return new Runtime(cfg, p, home);
}

test("config defaults: voice block present, STT unconfigured, TTS on", () => {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-voice-cfg-"));
  const cfg = loadConfig(paths(home));
  assert.equal(cfg.voice.sttBaseUrl, "");
  assert.equal(cfg.voice.sttModel, "whisper-1");
  assert.equal(cfg.voice.ttsEnabled, true);
});

test("transcriber(): null until configured; loopback survives offline-only, remote does not", () => {
  assert.equal(makeRuntime(() => {}).transcriber(), null);
  const remote = (offlineOnly: boolean) => makeRuntime((cfg) => {
    cfg.voice.sttBaseUrl = "https://stt.example.com/v1";
    cfg.offlineOnly = offlineOnly;
  }).transcriber();
  assert.ok(remote(false), "remote STT allowed when online");
  assert.equal(remote(true), null, "remote STT withheld when offline-only");
  const local = makeRuntime((cfg) => {
    cfg.voice.sttBaseUrl = "http://127.0.0.1:8000/v1";
    cfg.offlineOnly = true;
  }).transcriber();
  assert.ok(local, "loopback STT keeps working offline");
  assert.match(local!.label, /127\.0\.0\.1:8000/);
});

test("state() surfaces the voice subsystem", () => {
  const rt = makeRuntime((cfg) => { cfg.voice.sttBaseUrl = "http://127.0.0.1:8000/v1"; });
  const s = rt.state() as unknown as Record<string, unknown>;
  assert.match(String(s.voiceStt), /Whisper-class STT/);
  assert.equal(s.voiceTts, true);
  const off = makeRuntime(() => {}).state() as unknown as Record<string, unknown>;
  assert.equal(off.voiceStt, null);
});

// ---------- CLI paths ----------

const PKG = path.join(path.dirname(fileURLToPath(import.meta.url)), "..");
const CLI = path.join(PKG, "src", "cli.ts");

function runCli(args: string[], env: Record<string, string>): Promise<{ code: number; out: string }> {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, ["--import", "tsx", CLI, ...args], {
      cwd: PKG,
      env: { ...process.env, ...env },
    });
    let out = "";
    child.stdout.on("data", (d) => (out += d));
    child.stderr.on("data", (d) => (out += d));
    child.on("error", reject);
    child.on("close", (code) => resolve({ code: code ?? -1, out }));
  });
}

test("CLI: jarvis say refuses an unfinished utterance (exit 1, rule named)", async () => {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-voice-cli-"));
  const r = await runCli(["say", "this is not finished"], { JARVIS_HOME: home });
  assert.equal(r.code, 1);
  assert.match(r.out, /ends mid-thought/);
});

test("CLI: jarvis transcribe prints the transcript from a configured server", async () => {
  const { srv, url } = await fakeStt(() => ({ json: { text: "The quick brown fox jumps over the lazy dog." } }));
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-voice-cli-"));
  const p = paths(home);
  const cfg = loadConfig(p);
  cfg.voice.sttBaseUrl = url;
  cfg.voice.sttModel = "base.en";
  writeFileSync(p.config, JSON.stringify(cfg, null, 2) + "\n");
  const wav = path.join(home, "fox.wav");
  writeFileSync(wav, Buffer.from("RIFF-audio"));
  const r = await runCli(["transcribe", wav], { JARVIS_HOME: home });
  srv.close();
  assert.equal(r.code, 0, r.out);
  assert.ok(r.out.includes("The quick brown fox jumps over the lazy dog."));
});

test("CLI: jarvis transcribe with no STT configured says so and exits 1", async () => {
  const home = mkdtempSync(path.join(tmpdir(), "jarvis-voice-cli-"));
  const wav = path.join(home, "fox.wav");
  writeFileSync(wav, Buffer.from("RIFF-audio"));
  const r = await runCli(["transcribe", wav], { JARVIS_HOME: home });
  assert.equal(r.code, 1);
  assert.match(r.out, /not set up/);
});
