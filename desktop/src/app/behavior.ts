/**
 * Pure window/tray decisions, kept out of main.ts so they can be tested without
 * loading Electron.
 */

/**
 * What to do when the window's close button is pressed.
 * - With a tray, closing hides to it (Jarvis keeps running in the tray).
 * - Without a tray (a Chromebook's Crostini container has none) or when already
 *   quitting, the close proceeds — otherwise Jarvis would be an unreachable
 *   background process with no window and no tray icon.
 */
export function onWindowClose(hasTray: boolean, quitting: boolean): "hide" | "close" {
  return hasTray && !quitting ? "hide" : "close";
}

/**
 * Best-effort detection of Chrome OS's Linux container (Crostini / Chrome OS
 * Flex), so the app can tailor a hint. Not security-sensitive; only affects
 * wording and defaults.
 */
export function isCrostini(env: NodeJS.ProcessEnv = process.env, exists: (p: string) => boolean = () => false): boolean {
  if (process.platform !== "linux") return false;
  if (env.SOMMELIER_VERSION || env.CROS_USER_ID_HASH) return true;
  return exists("/opt/google/cros-containers") || exists("/dev/.cros_milestone");
}
