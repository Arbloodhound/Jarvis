import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

interface FallbackEntry {
  appNames: string[];
  browserUrl: string;
  installUrl: string;
  description: string;
}

const FALLBACK_MAP: FallbackEntry[] = [
  { appNames: ['google flights', 'flights'], browserUrl: 'https://www.google.com/travel/flights', installUrl: 'https://play.google.com/store/apps/details?id=com.google.android.apps.flights', description: 'Google Flights' },
  { appNames: ['google maps', 'maps', 'navigation'], browserUrl: 'https://www.google.com/maps', installUrl: 'https://play.google.com/store/apps/details?id=com.google.android.apps.maps', description: 'Google Maps' },
  { appNames: ['booking', 'booking.com', 'hotels'], browserUrl: 'https://www.booking.com', installUrl: 'https://play.google.com/store/apps/details?id=com.booking', description: 'Booking.com' },
  { appNames: ['airbnb'], browserUrl: 'https://www.airbnb.com', installUrl: 'https://play.google.com/store/apps/details?id=com.airbnb.android', description: 'Airbnb' },
  { appNames: ['uber'], browserUrl: 'https://m.uber.com', installUrl: 'https://play.google.com/store/apps/details?id=com.ubercab', description: 'Uber' },
  { appNames: ['lyft'], browserUrl: 'https://www.lyft.com', installUrl: 'https://play.google.com/store/apps/details?id=me.lyft.android', description: 'Lyft' },
  { appNames: ['amazon', 'amazon shopping'], browserUrl: 'https://www.amazon.com', installUrl: 'https://play.google.com/store/apps/details?id=com.amazon.mShop.android.shopping', description: 'Amazon' },
  { appNames: ['ebay'], browserUrl: 'https://www.ebay.com', installUrl: 'https://play.google.com/store/apps/details?id=com.ebay.mobile', description: 'eBay' },
  { appNames: ['walmart'], browserUrl: 'https://www.walmart.com', installUrl: 'https://play.google.com/store/apps/details?id=com.walmart.android', description: 'Walmart' },
  { appNames: ['doordash', 'food delivery'], browserUrl: 'https://www.doordash.com', installUrl: 'https://play.google.com/store/apps/details?id=com.dd.doordash', description: 'DoorDash' },
  { appNames: ['grubhub'], browserUrl: 'https://www.grubhub.com', installUrl: 'https://play.google.com/store/apps/details?id=com.grubhub.android', description: 'Grubhub' },
  { appNames: ['weather', 'weather forecast'], browserUrl: 'https://weather.com', installUrl: 'https://play.google.com/store/apps/details?id=com.weather.Weather', description: 'The Weather Channel' },
  { appNames: ['calculator', 'calc'], browserUrl: 'https://www.google.com/search?q=calculator', installUrl: '', description: 'Calculator' },
  { appNames: ['translate', 'translator'], browserUrl: 'https://translate.google.com', installUrl: 'https://play.google.com/store/apps/details?id=com.google.android.apps.translate', description: 'Google Translate' },
  { appNames: ['youtube'], browserUrl: 'https://m.youtube.com', installUrl: 'https://play.google.com/store/apps/details?id=com.google.android.youtube', description: 'YouTube' },
  { appNames: ['reddit'], browserUrl: 'https://www.reddit.com', installUrl: 'https://play.google.com/store/apps/details?id=com.reddit.frontpage', description: 'Reddit' },
  { appNames: ['twitter', 'x'], browserUrl: 'https://x.com', installUrl: 'https://play.google.com/store/apps/details?id=com.twitter.android', description: 'X (Twitter)' },
  { appNames: ['instagram'], browserUrl: 'https://www.instagram.com', installUrl: 'https://play.google.com/store/apps/details?id=com.instagram.android', description: 'Instagram' },
  { appNames: ['facebook'], browserUrl: 'https://m.facebook.com', installUrl: 'https://play.google.com/store/apps/details?id=com.facebook.katana', description: 'Facebook' },
  { appNames: ['linkedin'], browserUrl: 'https://www.linkedin.com', installUrl: 'https://play.google.com/store/apps/details?id=com.linkedin.android', description: 'LinkedIn' },
  { appNames: ['zillow', 'real estate'], browserUrl: 'https://www.zillow.com', installUrl: 'https://play.google.com/store/apps/details?id=com.zillow.android.zillowmap', description: 'Zillow' },
  { appNames: ['kayak', 'travel search'], browserUrl: 'https://www.kayak.com', installUrl: 'https://play.google.com/store/apps/details?id=com.kayak.android', description: 'Kayak' },
  { appNames: ['expedia'], browserUrl: 'https://www.expedia.com', installUrl: 'https://play.google.com/store/apps/details?id=com.expedia.bookings', description: 'Expedia' },
];

export interface FallbackResult {
  type: 'browser' | 'install_suggestion' | 'rephrase_suggestion' | 'none';
  browserUrl?: string;
  installUrl?: string;
  appName?: string;
  message: string;
}

export class AppFallback {
  static resolve(appName: string): FallbackResult {
    const lower = appName.toLowerCase().trim();

    for (const entry of FALLBACK_MAP) {
      if (entry.appNames.some(n => lower.includes(n) || n.includes(lower))) {
        DebugLog.push('SYSTEM' as any, {
          event: 'app_fallback_matched',
          app: appName,
          fallback: entry.description,
          type: 'browser',
        });
        return {
          type: 'browser',
          browserUrl: entry.browserUrl,
          installUrl: entry.installUrl || undefined,
          appName: entry.description,
          message: `${entry.description} isn't installed. Opening in browser instead.`,
        };
      }
    }

    const sanitized = lower.replace(/\s+/g, '').replace(/[^a-z0-9]/g, '');
    const likelyUrl = `https://www.${sanitized}.com`;

    if (sanitized.length >= 3 && sanitized.length <= 20) {
      DebugLog.push('SYSTEM' as any, {
        event: 'app_fallback_generic',
        app: appName,
        guessedUrl: likelyUrl,
      });
      return {
        type: 'browser',
        browserUrl: likelyUrl,
        appName: appName,
        message: `"${appName}" isn't installed. Trying ${likelyUrl} in browser.`,
      };
    }

    DebugLog.push('SYSTEM' as any, { event: 'app_fallback_none', app: appName });
    return {
      type: 'rephrase_suggestion',
      message: `I couldn't find "${appName}" on your device. You can install it from the Play Store, or tell me what you're trying to do and I'll find another way.`,
    };
  }

  static suggestAlternatives(goal: string, failedApp: string): string[] {
    const suggestions: string[] = [];
    const g = goal.toLowerCase();

    if (/flight|travel|trip|fly/i.test(g)) {
      suggestions.push('Try: "research flights to [destination]" — I\'ll use Google search');
      suggestions.push('Install Google Flights or Kayak for better results');
    }
    if (/hotel|stay|accommodation/i.test(g)) {
      suggestions.push('Try: "research hotels in [city]" — I\'ll search the web');
      suggestions.push('Install Booking.com or Airbnb');
    }
    if (/bank|balance|money|transfer/i.test(g)) {
      suggestions.push('I need your banking app installed to check balances');
      suggestions.push('Try checking your recent SMS for bank notifications');
    }
    if (/weather|forecast|rain|temperature/i.test(g)) {
      suggestions.push('Try: "research weather in [city]" — I\'ll use Google');
    }
    if (/price|buy|shop|order/i.test(g)) {
      suggestions.push('Try: "research [product] prices" — I\'ll search online');
      suggestions.push('Install Amazon, eBay, or the specific store\'s app');
    }

    if (suggestions.length === 0) {
      suggestions.push(`Try rephrasing what you need — I might find another way`);
      suggestions.push(`Say "research [your topic]" and I'll search using Google`);
    }

    return suggestions;
  }
}
