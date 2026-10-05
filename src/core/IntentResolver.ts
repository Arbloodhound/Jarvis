/**
 * IntentResolver — Maps natural language commands to Android intent specifications.
 *
 * This is how "play Bad to the Bone" becomes an actual Android intent with
 * INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH + extras, instead of just opening Spotify's
 * main activity.
 *
 * Two resolution paths:
 * 1. Deterministic patterns — regex matching for known patterns
 * 2. Returns null for ambiguous commands → caller falls back to AI or simple launch
 */

import { lookupPackage } from './AppDirectory';
import { ActivityAction } from 'expo-intent-launcher';
import { resolveSettingsIntent } from './SettingsDirectory';
import { resolveDeepLink } from './DeepLinkDirectory';
import { UltraDevLog } from '../utils/UltraDevLog';

// ── Types ──────────────────────────────────────────────
export interface ResolvedIntent {
  action: string;
  data?: string;           // URI: tel:, geo:, https:, spotify:, etc.
  extras?: Record<string, string | number | boolean>;
  packageName?: string;    // target specific app
  mimeType?: string;
  category?: string;
  description: string;     // human-readable description of what this intent does
}

// ── Android Intent Constants ───────────────────────────
// Using string literals instead of importing from android SDK

const ACTION_VIEW = 'android.intent.action.VIEW';
const ACTION_DIAL = 'android.intent.action.DIAL';
const ACTION_CALL = 'android.intent.action.CALL';
const ACTION_SEND = 'android.intent.action.SEND';
const ACTION_SENDTO = 'android.intent.action.SENDTO';
const ACTION_WEB_SEARCH = 'android.intent.action.WEB_SEARCH';
const ACTION_SET_ALARM = 'android.intent.action.SET_ALARM';
const ACTION_SET_TIMER = 'android.intent.action.SET_TIMER';
const ACTION_IMAGE_CAPTURE = 'android.media.action.IMAGE_CAPTURE';
const ACTION_MEDIA_PLAY_FROM_SEARCH = 'android.media.action.MEDIA_PLAY_FROM_SEARCH';

// MediaStore extras
const EXTRA_MEDIA_FOCUS = 'android.intent.extra.focus';
const EXTRA_MEDIA_TITLE = 'android.intent.extra.title';
const EXTRA_MEDIA_ARTIST = 'android.intent.extra.artist';
const EXTRA_MEDIA_ALBUM = 'android.intent.extra.album';
const EXTRA_MEDIA_GENRE = 'android.intent.extra.genre';
const EXTRA_SEARCH_QUERY = 'query';

// Media focus types
const FOCUS_ANY = 'vnd.android.cursor.item/*';
const FOCUS_AUDIO = 'vnd.android.cursor.item/audio';
const FOCUS_ARTIST = 'vnd.android.cursor.item/artist';
const FOCUS_ALBUM = 'vnd.android.cursor.item/album';
const FOCUS_GENRE = 'vnd.android.cursor.item/genre';
const FOCUS_PLAYLIST = 'vnd.android.cursor.item/playlist';

// Alarm extras
const EXTRA_ALARM_HOUR = 'android.intent.extra.alarm.HOUR';
const EXTRA_ALARM_MINUTES = 'android.intent.extra.alarm.MINUTES';
const EXTRA_ALARM_MESSAGE = 'android.intent.extra.alarm.MESSAGE';
const EXTRA_TIMER_LENGTH = 'android.intent.extra.alarm.LENGTH';
const EXTRA_TIMER_MESSAGE = 'android.intent.extra.alarm.MESSAGE';

// ── Pattern definitions ────────────────────────────────

interface IntentPattern {
  pattern: RegExp;
  resolve: (match: RegExpMatchArray, fullInput: string) => ResolvedIntent | null;
}

const INTENT_PATTERNS: IntentPattern[] = [
  // ── MUSIC PLAYBACK ─────────────────────────────────
  // "play Bad to the Bone on Spotify"
  {
    pattern: /^play\s+(.+?)\s+(?:on|in|with|using)\s+(.+)$/i,
    resolve: (m) => {
      const query = m[1].trim();
      const appName = m[2].trim();
      const pkg = lookupPackage(appName);
      return {
        action: ACTION_MEDIA_PLAY_FROM_SEARCH,
        extras: {
          [EXTRA_MEDIA_FOCUS]: FOCUS_AUDIO,
          [EXTRA_SEARCH_QUERY]: query,
        },
        packageName: pkg,
        description: `Play "${query}"${pkg ? ` on ${appName}` : ''}`,
      };
    },
  },
  // "play music by George Thorogood"
  {
    pattern: /^play\s+(?:some\s+)?(?:music\s+)?by\s+(.+)$/i,
    resolve: (m) => ({
      action: ACTION_MEDIA_PLAY_FROM_SEARCH,
      extras: {
        [EXTRA_MEDIA_FOCUS]: FOCUS_ARTIST,
        [EXTRA_MEDIA_ARTIST]: m[1].trim(),
        [EXTRA_SEARCH_QUERY]: m[1].trim(),
      },
      description: `Play music by ${m[1].trim()}`,
    }),
  },
  // "play the album Appetite for Destruction"
  {
    pattern: /^play\s+(?:the\s+)?album\s+(.+)$/i,
    resolve: (m) => ({
      action: ACTION_MEDIA_PLAY_FROM_SEARCH,
      extras: {
        [EXTRA_MEDIA_FOCUS]: FOCUS_ALBUM,
        [EXTRA_MEDIA_ALBUM]: m[1].trim(),
        [EXTRA_SEARCH_QUERY]: m[1].trim(),
      },
      description: `Play album "${m[1].trim()}"`,
    }),
  },
  // "play <anything>" — generic music search
  {
    pattern: /^play\s+(.+)$/i,
    resolve: (m) => {
      const query = m[1].trim();
      // Don't match if it looks like an app launch ("play store", "play games")
      if (/^(store|games?|services|protect|console|books|movies|newsstand)$/i.test(query)) {
        return null;
      }
      return {
        action: ACTION_MEDIA_PLAY_FROM_SEARCH,
        extras: {
          [EXTRA_MEDIA_FOCUS]: FOCUS_ANY,
          [EXTRA_SEARCH_QUERY]: query,
        },
        description: `Play "${query}"`,
      };
    },
  },

  // ── PHONE CALLS ────────────────────────────────────
  // "call 555-1234" (direct number)
  {
    pattern: /^call\s+([\d\s\-\+\(\)]{7,})$/i,
    resolve: (m) => {
      const number = m[1].replace(/\s/g, '');
      return {
        action: ACTION_DIAL,
        data: `tel:${number}`,
        description: `Dial ${number}`,
      };
    },
  },
  // "call Mom" / "call John Smith" — contact name (needs resolution by caller)
  // We return ACTION_DIAL with a placeholder; the executor resolves the contact
  {
    pattern: /^call\s+(.+)$/i,
    resolve: (m) => {
      const contact = m[1].trim();
      // Don't match if it looks like an app name
      if (lookupPackage(contact)) return null;
      return {
        action: ACTION_DIAL,
        extras: { _contactName: contact }, // special key: executor resolves to tel: URI
        description: `Call ${contact}`,
      };
    },
  },
  // "dial 555-1234"
  {
    pattern: /^dial\s+([\d\s\-\+\(\)]+)$/i,
    resolve: (m) => ({
      action: ACTION_DIAL,
      data: `tel:${m[1].replace(/\s/g, '')}`,
      description: `Dial ${m[1].trim()}`,
    }),
  },

  // ── NAVIGATION ─────────────────────────────────────
  // "navigate to Times Square" / "directions to 123 Main St"
  {
    pattern: /^(?:navigate|directions?|take me|drive)\s+to\s+(.+)$/i,
    resolve: (m) => {
      const destination = m[1].trim();
      return {
        action: ACTION_VIEW,
        data: `google.navigation:q=${encodeURIComponent(destination)}`,
        description: `Navigate to ${destination}`,
      };
    },
  },
  // "show me 123 Main St on the map" / "find Times Square on maps"
  {
    pattern: /^(?:show|find|locate)\s+(.+?)\s+(?:on\s+)?(?:the\s+)?map(?:s)?$/i,
    resolve: (m) => ({
      action: ACTION_VIEW,
      data: `geo:0,0?q=${encodeURIComponent(m[1].trim())}`,
      description: `Show ${m[1].trim()} on map`,
    }),
  },
  // "map of downtown Chicago"
  {
    pattern: /^map\s+(?:of\s+)?(.+)$/i,
    resolve: (m) => ({
      action: ACTION_VIEW,
      data: `geo:0,0?q=${encodeURIComponent(m[1].trim())}`,
      description: `Map of ${m[1].trim()}`,
    }),
  },

  // ── WEB URLs ───────────────────────────────────────
  // "open https://google.com" or "open google.com in chrome"
  {
    pattern: /^(?:open|go\s+to|visit|browse)\s+(https?:\/\/\S+)(?:\s+(?:in|with|using)\s+(.+))?$/i,
    resolve: (m) => {
      const url = m[1].trim();
      const browser = m[2]?.trim();
      const pkg = browser ? lookupPackage(browser) : undefined;
      return {
        action: ACTION_VIEW,
        data: url,
        packageName: pkg,
        description: `Open ${url}${pkg ? ` in ${browser}` : ''}`,
      };
    },
  },
  // "open google.com" (no scheme — add https)
  {
    pattern: /^(?:open|go\s+to|visit|browse)\s+(\w+\.\w+(?:\.\w+)*(?:\/\S*)?)$/i,
    resolve: (m) => {
      const domain = m[1].trim();
      // Only if it looks like a domain (has a dot and no spaces)
      if (!domain.includes('.') || domain.includes(' ')) return null;
      return {
        action: ACTION_VIEW,
        data: `https://${domain}`,
        description: `Open https://${domain}`,
      };
    },
  },

  // ── SEARCH ─────────────────────────────────────────
  // "search for best restaurants near me"
  {
    pattern: /^(?:search|google|look\s+up)\s+(?:for\s+)?(.+)$/i,
    resolve: (m) => ({
      action: ACTION_WEB_SEARCH,
      extras: { [EXTRA_SEARCH_QUERY]: m[1].trim() },
      description: `Search for "${m[1].trim()}"`,
    }),
  },

  // ── ALARMS & TIMERS ────────────────────────────────
  // "set alarm for 7:30 am"
  {
    pattern: /^set\s+(?:an?\s+)?alarm\s+(?:for\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm)?$/i,
    resolve: (m) => {
      let hour = parseInt(m[1], 10);
      const minutes = m[2] ? parseInt(m[2], 10) : 0;
      const ampm = m[3]?.toLowerCase();
      if (ampm === 'pm' && hour < 12) hour += 12;
      if (ampm === 'am' && hour === 12) hour = 0;
      return {
        action: ACTION_SET_ALARM,
        extras: {
          [EXTRA_ALARM_HOUR]: hour,
          [EXTRA_ALARM_MINUTES]: minutes,
        },
        description: `Set alarm for ${m[0].replace(/^set\s+(?:an?\s+)?alarm\s+(?:for\s+)?/i, '')}`,
      };
    },
  },
  // "set timer for 5 minutes" / "set timer 30 seconds"
  {
    pattern: /^set\s+(?:a\s+)?timer\s+(?:for\s+)?(\d+)\s*(seconds?|minutes?|hours?|mins?|hrs?|secs?)$/i,
    resolve: (m) => {
      const value = parseInt(m[1], 10);
      const unit = m[2].toLowerCase();
      let seconds = value;
      if (unit.startsWith('min')) seconds = value * 60;
      else if (unit.startsWith('hr') || unit.startsWith('hour')) seconds = value * 3600;
      return {
        action: ACTION_SET_TIMER,
        extras: { [EXTRA_TIMER_LENGTH]: seconds },
        description: `Set timer for ${value} ${m[2]}`,
      };
    },
  },

  // ── EMAIL ──────────────────────────────────────────
  // "email john@example.com about the meeting"
  {
    pattern: /^(?:email|mail)\s+(\S+@\S+)\s+(?:about|regarding|re)\s+(.+)$/i,
    resolve: (m) => ({
      action: ACTION_SENDTO,
      data: `mailto:${m[1].trim()}`,
      extras: { 'android.intent.extra.SUBJECT': m[2].trim() },
      description: `Email ${m[1].trim()} about "${m[2].trim()}"`,
    }),
  },
  // "email john@example.com"
  {
    pattern: /^(?:email|mail)\s+(\S+@\S+)$/i,
    resolve: (m) => ({
      action: ACTION_SENDTO,
      data: `mailto:${m[1].trim()}`,
      description: `Compose email to ${m[1].trim()}`,
    }),
  },
];

// ── Public API ─────────────────────────────────────────

/**
 * Attempt to resolve a user's natural language input into a rich Android intent.
 * Returns null if no pattern matches — caller should fall back to simple app launch
 * or AI classification.
 */
export function resolveIntent(input: string): ResolvedIntent | null {
  const trimmed = input.trim()
    .replace(/^ultra[\s,]+/i, '')
    .replace(/^(?:hey\s+)?(?:ultra|agent)\s*,?\s*/i, '');

  // ── Layer 2: Settings intents (checked before all other patterns) ──
  const settingsMatch = resolveSettingsIntent(trimmed);
  UltraDevLog.settingsIntent(trimmed, !!settingsMatch, settingsMatch?.action, settingsMatch?.label);
  if (settingsMatch) {
    return {
      action: settingsMatch.action,
      data: undefined,
      extras: {},
      description: `Open ${settingsMatch.label}`,
    };
  }

  // ── Layer 3: App deep links ──
  const deepLinkMatch = resolveDeepLink(trimmed);
  if (!settingsMatch) {
    UltraDevLog.deepLink(trimmed, !!deepLinkMatch, deepLinkMatch?.uri, deepLinkMatch?.label);
  }
  if (deepLinkMatch) {
    return {
      action: ActivityAction.VIEW,
      data: deepLinkMatch.uri,
      packageName: deepLinkMatch.packageHint,
      description: `Open ${deepLinkMatch.label}`,
    };
  }

  for (const { pattern, resolve } of INTENT_PATTERNS) {
    const match = trimmed.match(pattern);
    if (match) {
      const result = resolve(match, trimmed);
      if (result) return result;
    }
  }

  return null;
}

/**
 * Check if an input looks like it should be a rich intent rather than a simple app launch.
 * Used by the CommandParser to decide whether to route to intent resolution.
 */
export function looksLikeRichIntent(input: string): boolean {
  const t = input.toLowerCase().trim();
  return /^(play|call|dial|navigate|directions?|search|google|look|set\s+(?:alarm|timer)|email|mail|map)\b/i.test(t);
}

export default { resolveIntent, looksLikeRichIntent };
