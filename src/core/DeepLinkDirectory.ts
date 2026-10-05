import { ActivityAction } from 'expo-intent-launcher';
import { UltraDevLog } from '../utils/UltraDevLog';

export interface DeepLinkEntry {
  triggers: string[];
  uri: string;
  packageHint?: string;
  label: string;
}

export const DEEP_LINKS: DeepLinkEntry[] = [
  { triggers: ['spotify liked songs', 'my liked songs', 'liked songs'],
    uri: 'spotify://collection/tracks', packageHint: 'com.spotify.music', label: 'Spotify Liked Songs' },
  { triggers: ['spotify home'],
    uri: 'spotify://', packageHint: 'com.spotify.music', label: 'Spotify Home' },
  { triggers: ['youtube home'],
    uri: 'vnd.youtube://', packageHint: 'com.google.android.youtube', label: 'YouTube Home' },
  { triggers: ['gmail compose', 'compose email', 'new email', 'write email', 'compose gmail'],
    uri: 'googlegmail://co', packageHint: 'com.google.android.gm', label: 'Gmail Compose' },
  { triggers: ['gmail inbox', 'open gmail'],
    uri: 'googlegmail://', packageHint: 'com.google.android.gm', label: 'Gmail' },
  { triggers: ['google maps', 'open maps', 'maps home'],
    uri: 'geo:0,0', packageHint: 'com.google.android.apps.maps', label: 'Google Maps' },
  { triggers: ['dialer', 'open dialer', 'phone dialer', 'open phone', 'keypad'],
    uri: 'tel:', packageHint: 'com.android.dialer', label: 'Phone Dialer' },
  { triggers: ['messages', 'open messages', 'sms app', 'text messages'],
    uri: 'sms:', packageHint: 'com.google.android.apps.messaging', label: 'Messages' },
  { triggers: ['whatsapp', 'open whatsapp'],
    uri: 'whatsapp://', packageHint: 'com.whatsapp', label: 'WhatsApp' },
  { triggers: ['instagram', 'open instagram'],
    uri: 'instagram://', packageHint: 'com.instagram.android', label: 'Instagram' },
  { triggers: ['tiktok', 'open tiktok'],
    uri: 'snssdk1233://', packageHint: 'com.zhiliaoapp.musically', label: 'TikTok' },
  { triggers: ['twitter', 'x app', 'open twitter', 'open x'],
    uri: 'twitter://', packageHint: 'com.twitter.android', label: 'Twitter/X' },
  { triggers: ['facebook', 'open facebook'],
    uri: 'fb://', packageHint: 'com.facebook.katana', label: 'Facebook' },
  { triggers: ['snapchat', 'open snapchat'],
    uri: 'snapchat://', packageHint: 'com.snapchat.android', label: 'Snapchat' },
  { triggers: ['calendar', 'open calendar', 'my calendar'],
    uri: 'content://com.android.calendar/time/', packageHint: 'com.google.android.calendar', label: 'Calendar' },
  { triggers: ['new event', 'create event', 'add event', 'new calendar event'],
    uri: 'content://com.android.calendar/events', label: 'New Calendar Event' },
  { triggers: ['alarm', 'open alarm', 'alarms', 'my alarms'],
    uri: 'alarm:', packageHint: 'com.google.android.deskclock', label: 'Alarm' },
  { triggers: ['clock', 'open clock'],
    uri: 'clock:', packageHint: 'com.google.android.deskclock', label: 'Clock' },
  { triggers: ['play store', 'google play', 'app store'],
    uri: 'market://', packageHint: 'com.android.vending', label: 'Play Store' },
];

export function resolveDeepLink(query: string): DeepLinkEntry | null {
  const q = query.toLowerCase().trim()
    .replace(/^(open|go to|show me|launch|take me to)\s+/i, '')
    .replace(/\s+(please|now)$/i, '');
  let best: DeepLinkEntry | null = null;
  let bestLen = 0;
  for (const link of DEEP_LINKS) {
    for (const trigger of link.triggers) {
      if ((q === trigger || q.includes(trigger)) && trigger.length > bestLen) {
        best = link;
        bestLen = trigger.length;
      }
    }
  }
  UltraDevLog.push('SYSTEM', { event: 'deep_link_resolve', query: q, found: !!best, label: best?.label ?? null, uri: best?.uri ?? null, packageHint: best?.packageHint ?? null });
  return best;
}
