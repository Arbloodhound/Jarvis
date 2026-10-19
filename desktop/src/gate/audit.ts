import { appendFileSync, mkdirSync } from "node:fs";
import path from "node:path";

/**
 * Append-only JSON-lines record of every tool decision: what was asked, what
 * the gate said, what the owner chose, and how it ended. Nothing in it is
 * redacted except secrets, which are hidden by the caller before logging.
 */
export class AuditLog {
  constructor(private readonly file: string | null) {
    if (file) mkdirSync(path.dirname(file), { recursive: true });
  }

  write(entry: Record<string, unknown>): void {
    if (!this.file) return;
    try {
      appendFileSync(this.file, JSON.stringify({ at: new Date().toISOString(), ...entry }) + "\n");
    } catch {
      // An audit write failing must not stop the owner's request; the console still shows it.
    }
  }
}
