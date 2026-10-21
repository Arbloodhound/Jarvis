import { app, BrowserWindow, clipboard, desktopCapturer, globalShortcut, Menu, nativeImage, Notification, screen, shell, Tray } from "electron";
import { existsSync } from "node:fs";
import { homedir } from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";
import { accessToken, loadConfig, paths } from "../config.js";
import { Runtime } from "../runtime.js";
import { startServer, type Gateway } from "../server.js";
import { useNative } from "../tools/platform.js";
import { isCrostini, onWindowClose } from "./behavior.js";

/**
 * Jarvis as a native desktop app (Windows, macOS, Linux).
 *
 * The agent, the gate and the gateway run inside this process, exactly as
 * `jarvis serve` runs them; the window is the same chat page. On top of that:
 * a tray icon and menu, a global hotkey to summon Jarvis from anywhere, close
 * to tray, launch at login, one instance only, and the operating system's own
 * clipboard, notifications, screen capture and default browser through
 * Electron (no xclip / notify-send / screencapture needed).
 *
 * When a confirm card arrives while the window is hidden, Jarvis comes to the
 * front and posts a notification: a card nobody sees is a run that never ends.
 *
 * `--self-test` starts everything, exercises the native pieces, prints a JSON
 * report and quits (exit code 0 when all passed). CI runs it on all three OSes.
 */

const HOTKEY = "CommandOrControl+Shift+J";
const here = path.dirname(fileURLToPath(import.meta.url));
const assets = path.join(here, "..", "..", "assets");
const selfTest = process.argv.includes("--self-test");

let win: BrowserWindow | null = null;
let tray: Tray | null = null;
let gw: Gateway | null = null;
let rt: Runtime | null = null;
let quitting = false;

if (!selfTest && !app.requestSingleInstanceLock()) {
  app.quit();
} else {
  app.on("second-instance", () => show());
  void app.whenReady().then(start).catch((err: unknown) => {
    console.error(err);
    app.exit(1);
  });
}

function installNative(): void {
  useNative({
    async openUrl(url) {
      await shell.openExternal(url);
    },
    async clipboardRead() {
      return clipboard.readText();
    },
    async clipboardWrite(text) {
      clipboard.writeText(text);
    },
    async notify(title, message) {
      if (!Notification.isSupported()) throw new Error("This system has no notification service.");
      new Notification({ title, body: message, icon: path.join(assets, "icon.png") }).show();
    },
    async screenshot() {
      const d = screen.getPrimaryDisplay();
      const size = { width: Math.round(d.size.width * d.scaleFactor), height: Math.round(d.size.height * d.scaleFactor) };
      const sources = await desktopCapturer.getSources({ types: ["screen"], thumbnailSize: size });
      const src = sources.find((s) => s.display_id === String(d.id)) ?? sources[0];
      if (!src || src.thumbnail.isEmpty()) {
        throw new Error(process.platform === "darwin"
          ? "macOS blocked the screen capture: allow Jarvis in System Settings → Privacy & Security → Screen Recording."
          : "The screen could not be captured.");
      }
      return src.thumbnail.toPNG();
    },
  });
}

async function start(): Promise<void> {
  app.setAppUserModelId("com.arbloodhound.jarvis");
  const p = paths();
  const cfg = loadConfig(p);
  installNative();
  rt = new Runtime(cfg, p, homedir());
  gw = await startServer(rt, accessToken(p), cfg.port, { fallbackPort: true, onEvent });
  rt.scheduler.start();

  win = new BrowserWindow({
    width: 980,
    height: 720,
    minWidth: 380,
    minHeight: 480,
    show: false,
    title: "Jarvis",
    icon: path.join(assets, "icon.png"),
    autoHideMenuBar: true,
    webPreferences: { contextIsolation: true, sandbox: true, nodeIntegration: false },
  });
  // The page may only ever be the gateway's chat page; every link goes to the real browser.
  const origin = new URL(gw.url).origin;
  win.webContents.setWindowOpenHandler(({ url }) => {
    if (/^https?:\/\//.test(url)) void shell.openExternal(url);
    return { action: "deny" };
  });
  win.webContents.on("will-navigate", (e, url) => {
    if (new URL(url).origin !== origin) {
      e.preventDefault();
      if (/^https?:\/\//.test(url)) void shell.openExternal(url);
    }
  });
  win.on("close", (e) => {
    // With a tray, closing hides to it; without one (e.g. a Chromebook's Crostini
    // container has no system tray), closing must quit, or Jarvis becomes an
    // unreachable background process with no window and no tray icon.
    if (onWindowClose(tray !== null, quitting) === "hide") { e.preventDefault(); win?.hide(); }
    else if (!quitting) { quitting = true; app.quit(); }
  });
  await win.loadURL(gw.url);

  makeTray();
  if (!globalShortcut.register(HOTKEY, toggle)) console.warn(`Hotkey ${HOTKEY} is taken by another app (or unavailable here, e.g. inside Crostini).`);
  if (isCrostini(process.env, existsSync)) {
    console.log("Running in Chrome OS's Linux container (Crostini): screenshots capture only this container, not the whole Chrome OS screen, and the tray/hotkey may be unavailable. The window works; closing it quits.");
  }

  if (selfTest) { await runSelfTest(); return; }
  if (!process.argv.includes("--hidden")) show();
}

function onEvent(e: { type: string } & Record<string, unknown>): void {
  if (e.type === "confirm" && !(win?.isVisible() && win.isFocused())) {
    show();
    if (Notification.isSupported()) {
      const n = new Notification({ title: `Jarvis needs your OK: ${String(e.tool)}`, body: String(e.reason ?? ""), icon: path.join(assets, "icon.png") });
      n.on("click", show);
      n.show();
    }
  }
  if (e.type === "state") refreshTray();
}

function show(): void {
  if (!win) return;
  if (win.isMinimized()) win.restore();
  win.show();
  win.focus();
}

function toggle(): void {
  if (win?.isVisible() && win.isFocused()) win.hide();
  else show();
}

function makeTray(): void {
  try {
    let icon = nativeImage.createFromPath(path.join(assets, "tray.png"));
    if (process.platform === "darwin") icon = icon.resize({ width: 18, height: 18 });
    tray = new Tray(icon);
    tray.setToolTip("Jarvis");
    tray.on("click", toggle);
    refreshTray();
  } catch (err) {
    // Some environments have no system tray (a Chromebook's Crostini container is one).
    // Jarvis runs without it: the window is the whole app, and closing it quits.
    tray = null;
    console.warn(`No system tray here (${err instanceof Error ? err.message : String(err)}); the window stays open and closing it quits.`);
  }
}

function refreshTray(): void {
  if (!tray || !rt) return;
  const s = rt.state();
  const set = (change: Parameters<Runtime["update"]>[0]) => { rt!.update(change); gw?.pushState(); };
  tray.setContextMenu(Menu.buildFromTemplate([
    // Linux tray menus can't show accelerators (GTK warns), so the hotkey goes in the label there.
    process.platform === "linux"
      ? { label: "Show Jarvis (Ctrl+Shift+J)", click: show }
      : { label: "Show Jarvis", accelerator: HOTKEY, click: show },
    { type: "separator" },
    { label: s.busy ? "Working… (Stop)" : "Idle", enabled: s.busy, click: () => rt?.stopAll() },
    { type: "separator" },
    { label: "On this computer", type: "radio", checked: s.brain === "local", click: () => set({ brain: "local" }) },
    { label: "Cloud", type: "radio", checked: s.brain === "cloud", click: () => set({ brain: "cloud" }) },
    { label: "Offline only", type: "checkbox", checked: s.offlineOnly, click: (i) => set({ offlineOnly: i.checked }) },
    { label: "Semantic memory", type: "checkbox", checked: s.embeddings, click: (i) => set({ embeddings: i.checked }) },
    { label: "Screen control (experimental)", type: "checkbox", checked: s.screenControl, click: (i) => set({ screenControl: i.checked }) },
    { label: "Tor for web tools", type: "checkbox", checked: s.tor, click: (i) => set({ tor: i.checked }) },
    { label: "Vision", type: "checkbox", checked: s.visionEnabled, click: (i) => set({ vision: i.checked }) },
    { type: "separator" },
    {
      label: "Start at login",
      type: "checkbox",
      checked: app.getLoginItemSettings().openAtLogin,
      click: (i) => app.setLoginItemSettings({ openAtLogin: i.checked, args: ["--hidden"] }),
      visible: process.platform !== "linux", // Linux needs an autostart .desktop file; not built yet.
    },
    { label: "Open Jarvis folder", click: () => void shell.openPath(paths().home) },
    { type: "separator" },
    { label: "Quit Jarvis", click: () => { quitting = true; app.quit(); } },
  ]));
}

async function runSelfTest(): Promise<void> {
  const report: Record<string, string> = {};
  const skip = new Set((process.env.JARVIS_SELF_TEST_SKIP ?? "").split(",").filter(Boolean));
  const check = async (name: string, fn: () => Promise<string>) => {
    if (skip.has(name)) {
      // Still run it, but report the result instead of failing on it.
      try { report[name] = `ok (not required): ${await fn()}`; } catch (err) { report[name] = `skipped: ${err instanceof Error ? err.message : String(err)}`; }
      return;
    }
    try { report[name] = `ok: ${await fn()}`; } catch (err) { report[name] = `FAIL: ${err instanceof Error ? err.message : String(err)}`; }
  };
  const { clipboardRead, clipboardWrite, notify, screenshot } = await import("../tools/platform.js");
  await check("window", async () => {
    // The page connected to the gateway with its token and received the state.
    for (let i = 0; i < 50; i++) {
      const status = (await win!.webContents.executeJavaScript("document.getElementById('status').textContent")) as string;
      if (status && !/Connecting|No token|Disconnected/.test(status)) return status;
      await new Promise((r) => setTimeout(r, 100));
    }
    throw new Error("the chat page did not connect");
  });
  await check("clipboard", async () => {
    const mark = `jarvis-self-test-${Date.now()}`;
    await clipboardWrite(mark);
    const back = await clipboardRead();
    if (back !== mark) throw new Error(`read back "${back.slice(0, 40)}"`);
    return "write + read";
  });
  await check("screenshot", async () => {
    const file = path.join(paths().workspace, "self-test.png");
    await screenshot(file);
    const img = nativeImage.createFromPath(file);
    if (img.isEmpty()) throw new Error("empty image");
    const { width, height } = img.getSize();
    return `${width}x${height}`;
  });
  await check("notification", async () => {
    if (!Notification.isSupported()) return "not supported on this system (skipped)";
    await notify("Jarvis", "Self-test");
    return "shown";
  });
  if (process.env.JARVIS_SELF_TEST_SHOT) {
    show();
    await new Promise((r) => setTimeout(r, 300));
    const { writeFile } = await import("node:fs/promises");
    await writeFile(process.env.JARVIS_SELF_TEST_SHOT, (await win!.webContents.capturePage()).toPNG());
  }
  await check("tray", async () => (tray ? "created" : Promise.reject(new Error("no tray"))));
  await check("hotkey", async () => (globalShortcut.isRegistered(HOTKEY) ? HOTKEY : "taken by another app (not an error)"));
  const failed = Object.values(report).some((v) => v.startsWith("FAIL"));
  console.log(JSON.stringify({ selfTest: failed ? "failed" : "passed", platform: process.platform, report }, null, 2));
  quitting = true;
  app.exit(failed ? 1 : 0);
}

app.on("before-quit", () => { quitting = true; });
app.on("will-quit", () => {
  globalShortcut.unregisterAll();
  // Quitting stops scheduled runs already working, not just future firings.
  rt?.stopScheduledRuns();
  rt?.scheduler.stop();
  gw?.server.close();
});
// Stay alive in the tray when every window is closed.
app.on("window-all-closed", () => { /* tray keeps running */ });
app.on("activate", show);
