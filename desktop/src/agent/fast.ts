/**
 * Obvious one-shot commands become a tool call with no model run at all, as on
 * the phone (FastCommand.kt): "open github.com", "remember that…", "take a
 * screenshot". They still go through the gate and confirm cards like any other
 * call. Questions, compounds ("…and then…") and anything unmatched go to the
 * model; a fast path that guesses is worse than a slow one that doesn't.
 */

export type Fast =
  | { kind: "tool"; tool: string; args: Record<string, unknown>; say?: (result: string) => string }
  | { kind: "answer"; text: string };

const COMPOUND = /\b(and then|then|after that|and also)\b|[;]/i;

export function fastCommand(input: string, now: Date = new Date()): Fast | null {
  const raw = input.trim().replace(/[.!]+$/, "");
  const s = raw.toLowerCase();
  if (!s || s.length > 200 || COMPOUND.test(s)) return null;

  if (/^(what('s| is) the )?time( is it)?\??$|^what time is it\??$/.test(s)) {
    return { kind: "answer", text: `It's ${now.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" })}.` };
  }
  if (/^(what('s| is) )?(the |today'?s )?date( today)?\??$|^what day is it( today)?\??$/.test(s)) {
    return { kind: "answer", text: `It's ${now.toLocaleDateString([], { weekday: "long", year: "numeric", month: "long", day: "numeric" })}.` };
  }

  let m = /^(?:open|go to|visit|browse to)\s+((?:https?:\/\/)?[a-z0-9-]+(?:\.[a-z0-9-]+)+(?:\/\S*)?)$/i.exec(raw);
  if (m) {
    const url = /^https?:\/\//i.test(m[1]!) ? m[1]! : `https://${m[1]!}`;
    return { kind: "tool", tool: "open_url", args: { url } };
  }
  m = /^(?:open|launch|start)\s+(?:the\s+)?([a-z0-9][\w .+-]{0,40}?)(?:\s+app)?$/i.exec(raw);
  if (m && !/\b(file|folder|it|that|this|my|a|an)\b/i.test(m[1]!)) {
    return { kind: "tool", tool: "open_app", args: { name: m[1]!.trim() } };
  }

  m = /^remember(?: that)?\s+(.{3,})$/i.exec(raw);
  if (m) return { kind: "tool", tool: "remember", args: { fact: m[1]!.trim() }, say: () => "Got it, I'll remember that." };

  if (/^what do you (remember|know)( about me)?\??$/.test(s)) {
    return { kind: "tool", tool: "recall", args: { query: "" } };
  }
  m = /^what do you (?:remember|know) about (.+?)\??$/i.exec(raw);
  if (m) return { kind: "tool", tool: "recall", args: { query: m[1]! } };

  m = /^forget(?: that| about)?\s+(.{3,})$/i.exec(raw);
  if (m) return { kind: "tool", tool: "forget", args: { query: m[1]!.trim() } };

  if (/^(take a |grab a )?screenshot$|^screen ?shot$/.test(s)) return { kind: "tool", tool: "screenshot", args: {} };
  if (/^(system|computer|pc|machine) info(rmation)?$/.test(s)) return { kind: "tool", tool: "system_info", args: {} };
  if (/^(what'?s|read|show)( on)? (my |the )?clipboard\??$/.test(s)) return { kind: "tool", tool: "clipboard_read", args: {} };

  return null;
}
