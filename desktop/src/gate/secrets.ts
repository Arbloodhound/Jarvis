/**
 * Secret-shaped material: verification codes, API keys, private keys, tokens.
 *
 * Deterministic, no model. Ported from the Android app's SmsCodeDetector and
 * ScreenSecrets: anything found here during a run may never leave the computer
 * through an egress tool, and is masked before a tool result reaches a cloud
 * model.
 */

const KEYWORD =
  /\b(verif\w*|codes?|otp|pin|passcode|one[- ]time|2fa|mfa|confirm|confirmation|authenticat\w*|sign[- ]?in|log[- ]?in|authori[sz]ation)\b/gi;
/** "zip code", "promo code": the word is there, but not as a verification code. */
const NOT_A_CODE_PHRASE =
  /\b(zip|postal|post|postcode|area|promo|promotional|discount|coupon|voucher|referral|tracking|dress|qr|bar|source|country|sort)\s+codes?\b/gi;
const CODE_PATTERN = /(?<![\d.,])\b(\d{4,8})\b(?![.,]\d)/g;
const FALSE_POSITIVES = new Set(["1234", "12345", "123456", "1234567", "12345678", "0000", "00000", "000000"]);

function looksLikeYear(code: string): boolean {
  if (code.length !== 4) return false;
  const n = Number(code);
  return n >= 1900 && n <= 2100;
}

/** The verification code nearest a verification keyword, or null. */
export function extractVerificationCode(text: string): string | null {
  if (!text.trim()) return null;
  const keywordText = text.replace(NOT_A_CODE_PHRASE, (m) => " ".repeat(m.length));
  const keywords = [...keywordText.matchAll(KEYWORD)].map((m) => [m.index!, m.index! + m[0].length - 1] as const);
  if (keywords.length === 0) return null;
  let best: { code: string; dist: number } | null = null;
  for (const m of text.matchAll(CODE_PATTERN)) {
    const code = m[1]!;
    if (FALSE_POSITIVES.has(code) || looksLikeYear(code)) continue;
    const start = m.index!;
    const end = start + code.length - 1;
    // Part of a price, a phone number or a longer id: "$1500", "555-1234".
    const before = text.slice(Math.max(0, start - 2), start);
    if (/[$£€#]|\d[-\s]$|-$/.test(before)) continue;
    const dist = Math.min(
      ...keywords.map(([ks, ke]) => (start > ke ? start - ke : ks > end ? ks - end : 0)),
    );
    if (dist > 60) continue;
    if (!best || dist < best.dist) best = { code, dist };
  }
  return best?.code ?? null;
}

const SECRET_PATTERNS: Array<[string, RegExp]> = [
  ["anthropic_key", /\bsk-ant-[A-Za-z0-9_-]{20,}\b/g],
  ["openai_key", /\bsk-(?!ant-)(?:proj-)?[A-Za-z0-9_-]{32,}\b/g],
  ["github_token", /\b(?:ghp|gho|ghu|ghs|ghr|github_pat)_[A-Za-z0-9_]{20,}\b/g],
  ["aws_access_key", /\b(?:AKIA|ASIA)[A-Z0-9]{16}\b/g],
  ["slack_token", /\bxox[abprs]-[A-Za-z0-9-]{10,}\b/g],
  ["google_api_key", /\bAIza[0-9A-Za-z_-]{35}\b/g],
  ["jwt", /\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\b/g],
  ["private_key", /-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----/g],
];

export interface FoundSecret {
  kind: string;
  value: string;
}

export function findSecrets(text: string): FoundSecret[] {
  const out: FoundSecret[] = [];
  const seen = new Set<string>();
  for (const [kind, rx] of SECRET_PATTERNS) {
    for (const m of text.matchAll(rx)) {
      if (!seen.has(m[0])) { seen.add(m[0]); out.push({ kind, value: m[0] }); }
    }
  }
  const code = extractVerificationCode(text);
  if (code) out.push({ kind: "verification_code", value: code });
  return out;
}

/** Replace every secret found in [text] with a placeholder naming its kind. */
export function redactSecrets(text: string): { text: string; found: FoundSecret[] } {
  const found = findSecrets(text);
  let out = text;
  // Longest first so a key is not half-replaced by a shorter overlapping match.
  for (const s of [...found].sort((a, b) => b.value.length - a.value.length)) {
    out = out.split(s.value).join(`[${s.kind} hidden]`);
  }
  return { text: out, found };
}
