import assert from "node:assert/strict";
import { test } from "node:test";
import { assessScam } from "../src/gate/scam.ts";
import { extractVerificationCode, redactSecrets } from "../src/gate/secrets.ts";

test("verification code: nearest the keyword, not the year", () => {
  assert.equal(extractVerificationCode("Sept 5 2026: your verification code is 482913"), "482913");
  assert.equal(extractVerificationCode("Your order 2026 shipped. Promo code 5555 inside"), null);
  assert.equal(extractVerificationCode("Lunch at 1230?"), null);
});

test("redaction hides keys and codes", () => {
  const r = redactSecrets("key sk-ant-api03-abcdefghijklmnopqrstuvwxyz012345 and login code 773201");
  assert.ok(!r.text.includes("sk-ant-api03"));
  assert.ok(!r.text.includes("773201"));
  assert.deepEqual(r.found.map((f) => f.kind).sort(), ["anthropic_key", "verification_code"]);
});

test("scam: gift cards + pressure is flagged with targets", () => {
  const a = assessScam("URGENT from the IRS: your account is suspended. Buy Google Play cards and text the codes to +1 (555) 010-9999 now.");
  assert.equal(a.scam, true);
  assert.ok(a.targets.has("phone:5550109999"));
  assert.match(a.reasons, /gift cards/);
});

test("scam: an ordinary delivery note is not flagged", () => {
  assert.equal(assessScam("Your package from Amazon arrives today between 2 and 4pm. Track at amazon.com").scam, false);
  assert.equal(assessScam("Do not share this code with anyone. Your login code is 123987.").scam, false);
});

test("scam: lookalike link with a threat", () => {
  const a = assessScam("Your PayPal account is locked. Verify your account at paypa1-secure.top immediately");
  assert.equal(a.scam, true);
  assert.ok(a.signs.includes("LOOKALIKE"));
  assert.ok(a.targets.has("host:paypa1-secure.top"));
});
