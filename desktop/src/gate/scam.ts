/**
 * Does this text look like a scam, and why, in words a person can act on.
 *
 * Deterministic, no model. A scam pairs
 * an ask nobody legitimate makes (gift cards as payment, share a code, double
 * your crypto) or any ask together with heavy pressure (threats, authority,
 * "new number", prizes, secrecy). Either half alone is ordinary, so this flags
 * only the combinations.
 *
 * On the desktop it runs on what the agent reads (emails, pages, files). The
 * hosts, emails and numbers in a flagged text become scam targets: the gate
 * will not let a tool send anything to them without the owner confirming.
 */

export type Sign =
  | "GIFT_CARDS" | "CODE_REQUEST" | "ODD_PAYMENT" | "PERSONAL_INFO" | "LINK" | "LOOKALIKE"
  | "MONEY_REQUEST" | "CALL_NUMBER" | "DOUBLING" | "URGENCY" | "THREAT" | "AUTHORITY"
  | "FAMILY" | "PRIZE" | "SECRECY" | "JOB" | "HELD";

const SIGNS: Record<Sign, { words: string; ask?: boolean; heavy?: boolean }> = {
  GIFT_CARDS: { words: "asks for gift cards", ask: true },
  CODE_REQUEST: { words: "asks you to share a code", ask: true },
  ODD_PAYMENT: { words: "asks for payment by wire, crypto or a payment app", ask: true },
  PERSONAL_INFO: { words: "asks you to confirm your account or card details", ask: true },
  LINK: { words: "has a link to open", ask: true },
  LOOKALIKE: { words: "has a link pretending to be a company it isn't", ask: true, heavy: true },
  MONEY_REQUEST: { words: "asks you to send or pay money", ask: true },
  CALL_NUMBER: { words: "asks you to call a number", ask: true },
  DOUBLING: { words: "promises to double your crypto or investment", ask: true },
  URGENCY: { words: "says it's urgent" },
  THREAT: { words: "threatens your account, or legal action", heavy: true },
  AUTHORITY: { words: "claims to be a bank, tax office, delivery firm or government", heavy: true },
  FAMILY: { words: "says it's family in trouble or on a new number", heavy: true },
  PRIZE: { words: "says you've won or are owed money", heavy: true },
  SECRECY: { words: "asks you to keep it secret", heavy: true },
  JOB: { words: "offers a work-from-home wage", heavy: true },
  HELD: { words: "says a parcel is held or undeliverable", heavy: true },
};

export interface ScamAssessment {
  scam: boolean;
  signs: Sign[];
  /** "host:x", "email:x", "phone:x", "cashtag:x" the text points at (only when scam). */
  targets: Set<string>;
  reasons: string;
}

const rx = (p: string) => new RegExp(p, "i");
const rxg = (p: string) => new RegExp(p, "gi");

const GIFT_CARD = rx(String.raw`\b(gift ?cards?|google ?play (cards?|codes?)|itunes (cards?|codes?)|steam (cards?|codes?)|apple (gift )?cards?|amazon (gift )?cards?|ebay (gift )?cards?|vanilla (visa|cards?)|razer gold)\b`);
const GC_SEND = rx(String.raw`\b(send|text|read|give|email|share)\b(\s+\w+){0,3}?\s+(codes?|numbers|pictures?|photos?|pics?)\b|\bscratch\b|numbers on the back`);
const GC_BUY = rx(String.raw`\b(buy|purchase|pick up|get me)\b|\bpay\b(\s+\w+){0,3}?\s+(with|in|using)\b`);
const CODE_ASK = rxg(String.raw`\b(send|give|tell|share|forward|text|read|reply with|what'?s|what is)\b(\s+\w+){0,3}?\s+(verification code|security code|confirmation code|login code|sign-?in code|otp|one-time (code|password|passcode|pin)|\d-digit code|code (we|i|they) (just )?(sent|texted)|code you (just )?(got|received))\b`);
const NEGATION = rx(String.raw`\b(not|never|don'?t|do not|no one|nobody|won'?t)\b`);
const ODD_PAY = rx(String.raw`\b(wire (transfer|the money|money|funds)|western union|moneygram|bitcoin|btc|crypto(currency)?|usdt|tether|bitcoin atm|zelle|cash ?app|venmo|paypal friends|(safe|secure|protected) (account|wallet))\b`);
const MONEY_ASK = rx(String.raw`\b(pay|transfer|send|wire|lend|move)\b(\s+\w+){0,3}?\s+((money|cash|funds|bills?|rent|fees?|fines?|payments?|deposit)\b|[£$€]\s?\d[\d,.]*|\d[\d,.]*\s?(dollars|pounds|euros|bucks|quid)\b)|\bpay\b(\s+\w+){0,2}?\s+(something|it)\b`);
const FEE_ASK = rx(String.raw`[£$€]\s?\d[\d,.]*\s+(\w+\s+){0,2}?(fee|charge)s?\b|\b(fee|charge)\s+of\s+[£$€]\s?\d`);
const OWE_ASK = rx(String.raw`\byou\s+owe\s+[£$€]\s?\d`);
const DOUBLE = rx(String.raw`\b(double|triple|2x|3x|10x)\s+(your\s+)?(crypto|bitcoin|btc|eth|investment|deposit)\b`);
const PERSONAL = rx(String.raw`\b(confirm(ing)?|verify(ing)?|updat(e|ing)|validat(e|ing)|re-?enter(ing)?|unlock(ing)?|restor(e|ing))\b(\s+\w+){0,3}?\s+(account|card|bank|banking|password|login|log-in|details|information|identity|ssn|social security|medicare number)\b`);
const CALL = rx(String.raw`\bcall\b(\s+\w+){0,3}?\s+(\+?\d[\d\s().-]{6,}\d)`);
const URGENT = rx(String.raw`\b(urgent(ly)?|immediately|right away|right now|asap|as soon as possible|within \d+ ?(hours?|hrs?|minutes?|mins?)|final (notice|warning|reminder)|act now|last chance|expires? today)\b`);
const THREAT = rx(String.raw`\b((account|card|profile|number|service)\b(\s+\w+){0,3}?\s+(locked|suspended|closed|frozen|restricted|limited|disabled|deactivated|terminated|disconnected|cut off|shut off)|arrest(ed)?|warrant|legal action|lawsuit|court (date|order|summons|case)|summons|penalt(y|ies)|deport(ed|ation)?|police (will|are coming|have been|case|report)|(report(ed)?|refer(red)?) (you )?to (the )?police|sheriff)\b`);
const AUTHORITY = rx(String.raw`\b(social security|medicare|tax (refund|office|rebate)|customs (fee|charge|duty)|(fraud|security) (department|dept|team)|royal mail|fedex|evri|toll (service|road|charges?)|ez-?pass)\b`);
/** Acronyms only in capitals: "ups" is also the end of "check-ups". */
const AUTHORITY_CAPS = /\b(IRS|HMRC|SSA|USPS|UPS|DHL|DVLA)\b/;
const FAMILY = rx(String.raw`(\bnew number\b|\b(old |my )?phone (broke|is broken|died|got stolen)\b|\b(lost|broke|dropped|cracked) my phone\b|\bin jail\b|\bbail (money|out)\b|\bpost(ing)? bail\b)`);
const PRIZE = rx(String.raw`\b(you('ve| have)? won|you are a winner|(you('re| are)|lucky|selected|declared|prize|lottery|jackpot|cash) winner|(been |are )?awarded (a|with|£|\$)|selected (2|to) receive|(cash|bonus|caller) prize|prize (reward|jackpot|draw)|guaranteed (a )?(£|\$|cash|prize)|claim (your )?(prize|reward|refund|gift|payment|money|funds|cash)|lottery|sweepstakes?|unclaimed|you('re| are) owed|owed a refund)\b`);
const WORK_FROM_HOME = rx(String.raw`\bwork(ing)? from home\b`);
const WAGE = rx(String.raw`[£$€]\s?\d[\d,]*\s*(/|per |a )(week|day|hour|hr)\b`);
const HELD = rx(String.raw`(parcel|package|shipment)\b(\s+\w+){0,3}?\s+(held|on hold|undeliverable|could not be delivered)\b`);
const SECRET = rx(String.raw`\b((don'?t|do not) (tell|say anything to|mention (this|it) to) (anyone|anybody|mum|mom|dad|your family|the bank)|keep (this|it) (between us|secret|quiet|to yourself)|(don'?t|do not) hang up)\b`);

const URL_RX = rxg(String.raw`\b((https?://)?((\d{1,3}\.){3}\d{1,3}|[a-z0-9-]+(\.[a-z0-9-]+)*\.[a-z]{2,24})(/\S*)?)`);
const PHONE_RX = /(\+?\d[\d\s().-]{5,}\d)/g;
const EMAIL_RX = rxg(String.raw`\b[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,24}\b`);
const CASHTAG_RX = /(?<![\w$])\$[A-Za-z][A-Za-z0-9_-]{1,19}\b/g;
const DATE_RX = /^(\d{4}[-/.]\d{1,2}[-/.]\d{1,2}|\d{1,2}[-/.]\d{1,2}[-/.]\d{2,4})$/;
const NOT_HOSTS = new Set(["e.g", "i.e", "a.m", "p.m", "u.s", "u.k", "etc"]);

const BRANDS = new Set([
  "usps", "ups", "fedex", "dhl", "royalmail", "evri", "hermes", "amazon", "apple", "appleid", "icloud",
  "paypal", "venmo", "zelle", "cashapp", "chase", "wellsfargo", "wf", "bofa", "bankofamerica", "citi",
  "netflix", "microsoft", "google", "coinbase", "irs", "hmrc", "ssa", "medicare", "ezpass", "walmart",
]);
const OFFICIAL = [
  "usps.com", "ups.com", "fedex.com", "dhl.com", "royalmail.com", "evri.com", "amazon.com", "amazon.co.uk",
  "amzn.to", "apple.com", "icloud.com", "paypal.com", "venmo.com", "zellepay.com", "cash.app", "chase.com",
  "wellsfargo.com", "bankofamerica.com", "citi.com", "netflix.com", "microsoft.com", "google.com",
  "coinbase.com", "irs.gov", "ssa.gov", "medicare.gov", "gov.uk", "e-zpassny.com", "walmart.com",
  "paypal.me", "apple.co", "a.co", "amzn.eu", "g.co",
];
const GLOBAL_BRANDS = new Set(["amazon", "apple", "dhl", "fedex", "ups", "paypal", "google", "microsoft", "netflix", "walmart"]);
const OPEN_CCTLDS = new Set(["co", "io", "me", "ly", "cc", "tv", "ws", "to", "tk", "ml", "ga", "cf", "gq", "ai", "pw", "cx", "su"]);
const COMMON_TLDS = new Set([
  "com", "net", "org", "info", "biz", "io", "co", "uk", "us", "ca", "au", "de", "fr", "nl", "in",
  "gov", "edu", "top", "xyz", "site", "online", "club", "shop", "app", "link", "live", "me", "ly",
  "cc", "icu", "vip", "buzz", "cfd", "sbs", "cyou", "support", "help", "services", "delivery",
]);

/**
 * The real host a URL points at, exactly as a browser or Node would connect:
 * the WHATWG URL parser, which strips user names ("a@evil"), treats backslashes
 * as slashes in http(s), and lowercases and punycodes the host. A bare host like
 * "amazon.com/x" is parsed by prepending http://. Returns null for non-hosts.
 */
export function normHost(raw: string): string | null {
  const t = raw.trim().replace(/[.,)!?;:]+$/, "");
  const hasScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(t);
  try {
    const u = new URL(hasScheme ? t : `http://${t}`);
    if (hasScheme && u.protocol !== "http:" && u.protocol !== "https:") return null;
    let h = u.hostname.toLowerCase();
    if (h.startsWith("www.")) h = h.slice(4);
    // A bracketed IPv6 host keeps its brackets for matching; a name must have a dot.
    if (h.startsWith("[")) return h;
    return h.includes(".") && h.length >= 4 ? h : null;
  } catch {
    return null;
  }
}

/** Digits only, at least 7; North American numbers compared on their last 10 digits. */
export function normPhone(raw: string): string | null {
  const d = raw.replace(/\D/g, "");
  if (d.length < 7 || d.length > 15) return null;
  if (d.length === 11 && d.startsWith("1")) return d.slice(1);
  return d;
}

function brandCountrySite(host: string): boolean {
  const l = host.split(".");
  if (l.length < 2) return false;
  const cc = l[l.length - 1]!;
  if (!/^[a-z]{2}$/.test(cc) || OPEN_CCTLDS.has(cc)) return false;
  const name = l.length >= 3 && (l[l.length - 2] === "co" || l[l.length - 2] === "com") ? l[l.length - 3]! : l[l.length - 2]!;
  return GLOBAL_BRANDS.has(name);
}

export function official(host: string): boolean {
  return OFFICIAL.some((o) => host === o || host.endsWith(`.${o}`)) || brandCountrySite(host);
}

/** "paypa1" -> "paypal": digits standing in for the letters they resemble. */
function unDigit(label: string): string {
  if (!/\d/.test(label) || /^\d+$/.test(label)) return label;
  const map: Record<string, string> = { "1": "l", "0": "o", "3": "e", "5": "s", "4": "a" };
  return label.replace(/[10354]/g, (c) => map[c]!);
}

export function lookalike(host: string): boolean {
  return !official(host) && host.split(/[.-]/).some((p) => BRANDS.has(p) || BRANDS.has(unDigit(p)));
}

function hosts(body: string): Set<string> {
  const out = new Set<string>();
  for (const m of body.matchAll(URL_RX)) {
    const raw = m[0];
    if (m.index! > 0 && body[m.index! - 1] === "@") continue;
    const host = normHost(raw);
    if (!host || NOT_HOSTS.has(host)) continue;
    const tld = host.slice(host.lastIndexOf(".") + 1);
    if (/^\d+$/.test(tld) && !/^(\d{1,3}\.){3}\d{1,3}$/.test(host)) continue;
    const bare = !raw.includes("://") && !raw.toLowerCase().startsWith("www.") && !raw.includes("/");
    if (bare && !COMMON_TLDS.has(tld)) continue;
    out.add(host);
  }
  return out;
}

function targets(body: string, hs: Set<string>): Set<string> {
  const out = new Set<string>([...hs].map((h) => `host:${h}`));
  for (const m of body.matchAll(EMAIL_RX)) out.add(`email:${m[0].toLowerCase()}`);
  for (const m of body.matchAll(PHONE_RX)) {
    if (DATE_RX.test(m[0].trim())) continue;
    const p = normPhone(m[0]);
    if (p) out.add(`phone:${p}`);
  }
  for (const m of body.matchAll(CASHTAG_RX)) out.add(`cashtag:${m[0].toLowerCase()}`);
  return out;
}

/** Targets inside any text (a tool argument) in the same normal form. */
export function targetsIn(text: string): Set<string> {
  return targets(text, hosts(text));
}

export function assessScam(text: string, title = ""): ScamAssessment {
  const body = `${title}\n${text}`;
  const signs = new Set<Sign>();
  const giftSend = GIFT_CARD.test(body) && GC_SEND.test(body);
  if (giftSend || (GIFT_CARD.test(body) && GC_BUY.test(body))) signs.add("GIFT_CARDS");
  for (const m of body.matchAll(CODE_ASK)) {
    const window = body.slice(Math.max(0, m.index! - 24), m.index! + m[0].length);
    if (!NEGATION.test(window)) { signs.add("CODE_REQUEST"); break; }
  }
  if (ODD_PAY.test(body)) signs.add("ODD_PAYMENT");
  if (PERSONAL.test(body)) signs.add("PERSONAL_INFO");
  const hs = hosts(body);
  const foreign = [...hs].filter((h) => !official(h));
  if (foreign.length) signs.add("LINK");
  if (foreign.some(lookalike)) signs.add("LOOKALIKE");
  if (MONEY_ASK.test(body) || FEE_ASK.test(body) || OWE_ASK.test(body)) signs.add("MONEY_REQUEST");
  if (CALL.test(body)) signs.add("CALL_NUMBER");
  if (DOUBLE.test(body)) signs.add("DOUBLING");
  if (URGENT.test(body)) signs.add("URGENCY");
  if (THREAT.test(body)) signs.add("THREAT");
  if (AUTHORITY.test(body) || AUTHORITY_CAPS.test(body)) signs.add("AUTHORITY");
  if (FAMILY.test(body)) signs.add("FAMILY");
  if (PRIZE.test(body)) signs.add("PRIZE");
  if (SECRET.test(body)) signs.add("SECRECY");
  if (WORK_FROM_HOME.test(body) && WAGE.test(body)) signs.add("JOB");
  if (HELD.test(body)) signs.add("HELD");

  const list = [...signs];
  const asks = list.filter((s) => SIGNS[s].ask);
  const heavy = list.filter((s) => SIGNS[s].heavy);
  const scam =
    giftSend || signs.has("CODE_REQUEST") || signs.has("DOUBLING") ||
    (signs.has("GIFT_CARDS") && (signs.has("URGENCY") || heavy.length > 0)) ||
    (asks.length > 0 && heavy.length > 0) ||
    (signs.has("ODD_PAYMENT") && signs.has("URGENCY"));

  if (signs.has("LOOKALIKE")) signs.delete("LINK");
  const ordered = [...signs].sort((a, b) => (SIGNS[a].ask ? 0 : 1) - (SIGNS[b].ask ? 0 : 1));
  return {
    scam,
    signs: ordered,
    targets: scam ? targets(body, hs) : new Set(),
    reasons: ordered.map((s) => SIGNS[s].words).join(", "),
  };
}
