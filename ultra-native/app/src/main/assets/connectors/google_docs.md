<!-- Post-merge replacement — 2026-10-19. Refreshed from the audited state of the code after the 2026-10-19 full-branch consolidation (all work now on `main`). The previous version is preserved at `archive/2026-10-19/ultra-native/app/src/main/assets/connectors/google_docs.md`. Status claims reflect the 2026-10-19 code audits; CI has not yet compiled the merged tree. -->
# Google Docs connector (`google_docs`)

Wraps Google's REST API directly (no Google SDK, no Play services).

## Setup
1. This connector is implemented in the harness package (`com.agent.jarvis.harness.connectors`), alongside the CredentialVault. It reports **needs setup** until you connect an account; nothing is connected before that.
2. Connect opens Google's sign-in in the browser (using the Google OAuth client configured for the app); you sign in with your own account and allow only the scopes listed below.
3. The token is stored in the Android Keystore vault as an opaque handle bound to `docs.googleapis.com`. The agent only ever sees the handle; the token is added at the network boundary and never logged or remembered.

## Status check
Before any use the connector calls `https://docs.googleapis.com/v1/documents/<probe> (404 = token works)`. Not connected or expired → the agent shows the connect flow and stops. It never says an action will happen.

## Errors
- 401 → sign-in expired: reconnect.
- 403 → the scope wasn't granted: reconnect and allow it.
- Anything else → the call fails with the HTTP code; nothing is retried automatically.

## Safety
- Results pass the secret scrubber: one-time codes, password-reset and magic sign-in links become `[credential:…]` and never reach the cloud model.
- Approvals go through the harness grant system (ApprovalGrants): writes and sends stop at a confirmation card unless a grant covers that exact action. The agent cannot see the approval card and cannot revoke grants; the user manages grants in Settings.

Scopes: documents.

## Operations
| Operation | Effect | Approval |
|---|---|---|
| `read {id}` | read | none |
| `create {title, text}` | write | approve once |
