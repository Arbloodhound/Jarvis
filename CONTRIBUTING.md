# Contributing to Agent Ultra

Bug reports and design pushback are the most useful things you can send. Several parts of 2.3 — risk scoring, low-risk auto-approve, confidence-scored observations — came out of people arguing with the first release. See the Credit section in the README.

## Reporting

- Use the issue templates. Phone model, Android version and app version matter more than anything else.
- **Never paste an API key, a password, or a screenshot that shows messages, contacts or notifications.** Issues are public.
- If you're on 2.3.0, uninstall it and install 2.3.1 before reporting. 2.3.0 was withdrawn for being signed with Android's debug key.

## Building

Follow **Build it** in the README. The native library needs llama.cpp built for arm64 first; it isn't vendored here. Debug builds work from a fresh clone. Release builds need a `keystore.properties` that isn't in the repo, and fail without it on purpose.

Run the unit tests before sending a change:

```
cd ultra-native
./gradlew :app:testDebugUnitTest
```

## Pull requests

- Keep a change to one thing, and say what it proves — which tests, and whether it ran on a real phone.
- Anything that touches the policy gate needs a test showing what it now refuses and what it still allows.
- `DEVLOG.md` is append-only. Add an entry; don't rewrite old ones.

## License

Agent Ultra is AGPL-3.0-only, with a commercial license available (see `NOTICE`). By submitting a contribution you agree it may be licensed under both the AGPL-3.0 and that commercial license — that keeps the dual licensing intact.
