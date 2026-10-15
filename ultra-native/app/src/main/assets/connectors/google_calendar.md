# Google Calendar connector (`google_calendar`)

Wraps Google's REST API directly (no Google SDK, no Play services).

## Setup
1. You register a Google OAuth client (installed/Android app) in Google Cloud and enter its client ID in Settings. Until then this connector reports **needs setup** and nothing is connected.
2. Connect opens Google's sign-in in the browser; you allow only the scopes listed below.
3. The token is stored in the Android Keystore vault as an opaque handle bound to `www.googleapis.com`. The agent only ever sees the handle; the token is added at the network boundary and never logged or remembered.

## Status check
Before any use the connector calls `https://www.googleapis.com/calendar/v3/users/me/calendarList?maxResults=1`. Not connected or expired → the agent shows the connect flow and stops. It never says an action will happen.

## Errors
- 401 → sign-in expired: reconnect.
- 403 → the scope wasn't granted: reconnect and allow it.
- Anything else → the call fails with the HTTP code; nothing is retried automatically.

## Safety
- Results pass the secret scrubber: one-time codes, password-reset and magic sign-in links become `[credential:…]` and never reach the cloud model.

Scopes: calendar.events.

## Operations
| Operation | Effect | Approval |
|---|---|---|
| `list {from (RFC 3339), limit?}` | read | none |
| `create {title, start, end, location?}` | write | approve once |
| `invite {title, start, end, to}` | send | recipient grant, else the card |
