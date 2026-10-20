// Copies the static web UI next to the compiled server.
import { cpSync, mkdirSync } from "node:fs";
mkdirSync("dist/ui", { recursive: true });
cpSync("src/ui", "dist/ui", { recursive: true });
