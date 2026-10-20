/**
 * AppDirectory — Static map of common app names → Android package names,
 * plus a fuzzy name-matching scorer for installed apps.
 *
 * Purpose: Avoid burning AI credits asking "what is Spotify's package name?"
 * for the 200 most common apps. Also provides a proper scoring-based match
 * against the device's installed app list instead of first-partial-match.
 */

import { UltraDevLog } from '../utils/UltraDevLog';

// ── Common Apps Directory ──────────────────────────────
// This covers the top ~150 apps by installs. Names are lowercase.
// Multiple aliases map to the same package.

const KNOWN_APPS: Record<string, string> = {
  // Google
  'chrome': 'com.android.chrome',
  'google chrome': 'com.android.chrome',
  'email': 'com.google.android.gm',
  'gmail': 'com.google.android.gm',
  'samsung email': 'com.samsung.android.email.provider',
  'google maps': 'com.google.android.apps.maps',
  'maps': 'com.google.android.apps.maps',
  'youtube': 'com.google.android.youtube',
  'google photos': 'com.google.android.apps.photos',
  'photos': 'com.google.android.apps.photos',
  'google drive': 'com.google.android.apps.docs',
  'drive': 'com.google.android.apps.docs',
  'google calendar': 'com.google.android.calendar',
  'calendar': 'com.google.android.calendar',
  'google keep': 'com.google.android.keep',
  'keep': 'com.google.android.keep',
  'google translate': 'com.google.android.apps.translate',
  'translate': 'com.google.android.apps.translate',
  'google play store': 'com.android.vending',
  'play store': 'com.android.vending',
  'google meet': 'com.google.android.apps.tachyon',
  'meet': 'com.google.android.apps.tachyon',
  'google docs': 'com.google.android.apps.docs.editors.docs',
  'docs': 'com.google.android.apps.docs.editors.docs',
  'google sheets': 'com.google.android.apps.docs.editors.sheets',
  'sheets': 'com.google.android.apps.docs.editors.sheets',
  'google slides': 'com.google.android.apps.docs.editors.slides',
  'google lens': 'com.google.ar.lens',
  'lens': 'com.google.ar.lens',
  'google home': 'com.google.android.apps.chromecast.app',
  'youtube music': 'com.google.android.apps.youtube.music',
  'google messages': 'com.google.android.apps.messaging',
  'samsung messages': 'com.samsung.android.messaging',
  'google phone': 'com.google.android.dialer',
  'google contacts': 'com.google.android.contacts',
  'google clock': 'com.google.android.deskclock',
  'clock': 'com.google.android.deskclock',
  'google calculator': 'com.google.android.calculator',
  'calculator': 'com.google.android.calculator',
  'google files': 'com.google.android.apps.nbu.files',
  'google assistant': 'com.google.android.apps.googleassistant',
  'assistant': 'com.google.android.apps.googleassistant',
  'google news': 'com.google.android.apps.magazines',
  'google earth': 'com.google.earth',
  'waze': 'com.waze',

  // Social
  'facebook': 'com.facebook.katana',
  'facebook messenger': 'com.facebook.orca',
  'messenger': 'com.facebook.orca',
  'instagram': 'com.instagram.android',
  'whatsapp': 'com.whatsapp',
  'twitter': 'com.twitter.android',
  'x': 'com.twitter.android',
  'tiktok': 'com.zhiliaoapp.musically',
  'snapchat': 'com.snapchat.android',
  'reddit': 'com.reddit.frontpage',
  'discord': 'com.discord',
  'telegram': 'org.telegram.messenger',
  'signal': 'org.thoughtcrime.securesms',
  'linkedin': 'com.linkedin.android',
  'pinterest': 'com.pinterest',
  'threads': 'com.instagram.barcelona',

  // Music & Media
  'spotify': 'com.spotify.music',
  'pandora': 'com.pandora.android',
  'apple music': 'com.apple.android.music',
  'amazon music': 'com.amazon.mp3',
  'soundcloud': 'com.soundcloud.android',
  'tidal': 'com.aspiro.tidal',
  'deezer': 'deezer.android.app',
  'iheartradio': 'com.clearchannel.iheartradio.controller',
  'audible': 'com.audible.application',

  // Video
  'netflix': 'com.netflix.mediaclient',
  'hulu': 'com.hulu.plus',
  'disney+': 'com.disney.disneyplus',
  'disney plus': 'com.disney.disneyplus',
  'hbo max': 'com.wbd.stream',
  'max': 'com.wbd.stream',
  'amazon prime video': 'com.amazon.avod.thirdpartyclient',
  'prime video': 'com.amazon.avod.thirdpartyclient',
  'peacock': 'com.peacocktv.peacockandroid',
  'paramount+': 'com.cbs.ott',
  'paramount plus': 'com.cbs.ott',
  'twitch': 'tv.twitch.android.app',
  'pluto tv': 'tv.pluto.android',
  'vlc': 'org.videolan.vlc',
  'mx player': 'com.mxtech.videoplayer.ad',

  // Productivity
  'slack': 'com.Slack',
  'zoom': 'us.zoom.videomeetings',
  'microsoft teams': 'com.microsoft.teams',
  'teams': 'com.microsoft.teams',
  'microsoft outlook': 'com.microsoft.office.outlook',
  'outlook': 'com.microsoft.office.outlook',
  'microsoft word': 'com.microsoft.office.word',
  'word': 'com.microsoft.office.word',
  'microsoft excel': 'com.microsoft.office.excel',
  'excel': 'com.microsoft.office.excel',
  'notion': 'notion.id',
  'evernote': 'com.evernote',
  'todoist': 'com.todoist',
  'trello': 'com.trello',
  'dropbox': 'com.dropbox.android',
  'onedrive': 'com.microsoft.skydrive',

  // Shopping
  'amazon': 'com.amazon.mShop.android.shopping',
  'ebay': 'com.ebay.mobile',
  'walmart': 'com.walmart.android',
  'target': 'com.target.ui',
  'etsy': 'com.etsy.android',
  'wish': 'com.contextlogic.wish',
  'aliexpress': 'com.alibaba.aliexpresshd',

  // Finance
  'venmo': 'com.venmo',
  'paypal': 'com.paypal.android.p2pmobile',
  'cash app': 'com.squareup.cash',
  'zelle': 'com.zellepay.zelle',
  'robinhood': 'com.robinhood.android',
  'coinbase': 'com.coinbase.android',

  // Food
  'doordash': 'com.dd.doordash',
  'uber eats': 'com.ubercab.eats',
  'grubhub': 'com.grubhub.android',
  'instacart': 'com.instacart.client',
  'starbucks': 'com.starbucks.mobilecard',

  // Travel
  'uber': 'com.ubercab',
  'lyft': 'me.lyft.android',
  'airbnb': 'com.airbnb.android',
  'booking': 'com.booking',
  'google flights': 'com.google.android.apps.flights',

  // Utilities
  'settings': 'com.android.settings',
  'camera': 'com.sec.android.app.camera',
  'camera2': 'com.sec.android.app.camera',
  'gallery': 'com.sec.android.gallery3d',
  'samsung gallery': 'com.sec.android.gallery3d',
  'samsung camera': 'com.sec.android.app.camera',
  'samsung notes': 'com.samsung.android.app.notes',
  'samsung internet': 'com.sec.android.app.sbrowser',
  'samsung health': 'com.sec.android.app.shealth',
  'kraken pro': 'com.krakenpro.app',
  'browser': 'com.sec.android.app.sbrowser',
  'sms': 'com.samsung.android.messaging',
  'samsung phone': 'com.samsung.android.dialer',
  'samsung contacts': 'com.samsung.android.contacts',
  'downloads': 'com.sec.android.app.myfiles',
  'my files': 'com.sec.android.app.myfiles',
  'files': 'com.sec.android.app.myfiles',

  // Games
  'candy crush': 'com.king.candycrushsaga',
  'minecraft': 'com.mojang.minecraftpe',
  'roblox': 'com.roblox.client',
  'pokemon go': 'com.nianticlabs.pokemongo',
  'among us': 'com.innersloth.spacemafia',

  // Bare "weather" intentionally omitted. Generic weather requests should
  // resolve to an installed weather app via fuzzy/device lookup, not Google Search.
  // Only map explicit branded names so we never silently launch Google Search
  // when the user has AccuWeather, Samsung Weather, etc. installed.
  'samsung weather': 'com.sec.android.daemonapp',
  'oneplus weather': 'net.oneplus.weather',
  'xiaomi weather': 'com.miui.weather2',
  'google weather': 'com.google.android.googlequicksearchbox',
  'accuweather': 'com.accuweather.android',
  'weather channel': 'com.weather.Weather',
  'the weather channel': 'com.weather.Weather',

  // AI
  'chatgpt': 'com.openai.chatgpt',
  'claude': 'com.anthropic.claude',
  'gemini': 'com.google.android.apps.bard',
  'copilot': 'com.microsoft.copilot',
  'perplexity': 'ai.perplexity.app.android',
};

/**
 * Look up a package name from our static directory.
 * Returns undefined if not found.
 */
export function lookupPackage(appName: string): string | undefined {
  const key = appName.toLowerCase().trim()
    .replace(/^(the|a|an|my)\s+/i, '')
    .replace(/\s+app$/i, '');
  const pkg = KNOWN_APPS[key];
  UltraDevLog.push('SYSTEM', { event: 'app_directory_lookup', query: key, found: !!pkg, pkg: pkg ?? null });
  return pkg;
}

// ── Fuzzy Name Matching ────────────────────────────────

interface InstalledApp {
  packageName: string;
  appName: string;
}

interface MatchResult {
  packageName: string;
  appName: string;
  score: number;
  matchType: 'exact' | 'starts_with' | 'contains' | 'word_overlap' | 'directory';
}

/**
 * Score-based fuzzy matching against installed apps.
 * Returns the best match above the threshold, or null.
 */
export function findBestMatch(
  query: string,
  installedApps: InstalledApp[],
  threshold: number = 35
): MatchResult | null {
  const q = query.toLowerCase().trim()
    .replace(/^(the|a|an|my)\s+/i, '')
    .replace(/\s+app$/i, '');

  if (!q) return null;

  const knownPkg = KNOWN_APPS[q];
  if (knownPkg) {
    const installed = installedApps.find(a => a.packageName === knownPkg);
    const result: MatchResult = {
      packageName: knownPkg,
      appName: installed?.appName || q,
      score: 100,
      matchType: 'directory',
    };
    UltraDevLog.push('SYSTEM', { event: 'app_fuzzy_match', query: q, matchType: 'directory', pkg: knownPkg, score: 100 });
    return result;
  }

  let best: MatchResult | null = null;
  let bestScore = 0;

  for (const app of installedApps) {
    const name = app.appName.toLowerCase();
    const pkg = app.packageName.toLowerCase();
    let score = 0;
    let matchType: MatchResult['matchType'] = 'word_overlap';

    if (name === q || pkg === q) {
      score = 100;
      matchType = 'exact';
    } else if (name.startsWith(q + ' ') || name.startsWith(q)) {
      score = 80 + Math.min(15, (q.length / name.length) * 15);
      matchType = 'starts_with';
    } else if (q.startsWith(name + ' ') || q.startsWith(name)) {
      score = 55 + Math.min(10, (name.length / q.length) * 10);
      matchType = 'starts_with';
    } else if (name.includes(q) || q.includes(name)) {
      const longer = Math.max(name.length, q.length);
      const shorter = Math.min(name.length, q.length);
      score = 40 + (shorter / longer) * 20;
      matchType = 'contains';
    } else {
      const qWords = new Set(q.split(/\s+/));
      const nWords = new Set(name.split(/[\s\-:]+/));
      let overlap = 0;
      for (const w of qWords) {
        if (nWords.has(w)) overlap++;
        else if (w.length >= 4) {
          for (const nw of nWords) {
            if (nw.length >= 4 && (nw.includes(w) || w.includes(nw))) {
              overlap += 0.5;
              break;
            }
          }
        }
      }
      const union = new Set([...qWords, ...nWords]).size;
      if (overlap > 0 && union > 0) {
        score = 30 + (overlap / union) * 30;
        matchType = 'word_overlap';
      }
    }

    const pkgQuery = q.replace(/\s+/g, '');
    if (score < 50 && pkgQuery.length >= 5 && pkg.includes(pkgQuery)) {
      score = Math.max(score, 60);
      matchType = 'contains';
    }

    if (score > bestScore) {
      bestScore = score;
      best = { packageName: app.packageName, appName: app.appName, score, matchType };
    }
  }

  const finalResult = best && best.score >= threshold ? best : null;
  UltraDevLog.push('SYSTEM', { event: 'app_fuzzy_match', query: q, installedCount: installedApps.length, found: !!finalResult, matchType: finalResult?.matchType ?? null, score: finalResult?.score ?? 0, pkg: finalResult?.packageName ?? null });
  return finalResult;
}

/**
 * Packages that should NEVER be launched directly.
 */
export const UNLAUNCHABLE_PACKAGES = new Set([
  'com.sec.android.daemonapp',
  'com.android.systemui',
  'com.android.settings',
  'com.android.providers.calendar',
  'com.android.providers.contacts',
  'android',
  'com.google.android.gms',
  'com.google.android.gsf',
  'com.android.phone',
]);

export default { lookupPackage, findBestMatch, KNOWN_APPS, UNLAUNCHABLE_PACKAGES };
