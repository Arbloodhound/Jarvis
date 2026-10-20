import { appendFileSync, mkdirSync, renameSync, statSync } from "node:fs";
import path from "node:path";

/**
 * Append-only JSON-lines record of every tool decision: what was asked, what
 * the gate said, what the owner chose, and how it ended. Nothing in it is
 * redacted except secrets, which are hidden by the caller before logging.
 */
export class AuditLog {
  private writes = 0;

  constructor(private readonly file: string | null) {
    if (file) mkdirSync(path.dirname(file), { recursive: true });
  }

  write(entry: Record<string, unknown>): void {
    if (!this.file) return;
    try {
      // Past 10 MB the log moves to audit.1.jsonl (replacing the previous one) and starts fresh.
      if (++this.writes % 200 === 1) {
        try { if (statSync(this.file).size > 10 * 1024 * 1024) renameSync(this.file, this.file.replace(/\.jsonl$/, ".1.jsonl")); } catch { /* no file yet */ }
      }
      appendFileSync(this.file, JSON.stringify({ at: new Date().toISOString(), ...entry }) + "\n");
    } catch {
      // An audit write failing must not stop the owner's request; the console still shows it.
    }
  }
}
