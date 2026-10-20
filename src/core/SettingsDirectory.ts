import { ActivityAction } from 'expo-intent-launcher';
import { UltraDevLog } from '../utils/UltraDevLog';

export interface SettingsEntry {
  triggers: string[];
  action: string;
  label: string;
}

export const SETTINGS_MAP: SettingsEntry[] = [
  { triggers: ['settings', 'open settings', 'system settings', 'main settings'],
    action: ActivityAction.SETTINGS, label: 'Settings' },
  { triggers: ['wifi', 'wifi settings', 'wi-fi', 'wi-fi settings', 'wireless'],
    action: ActivityAction.WIFI_SETTINGS, label: 'Wi-Fi Settings' },
  { triggers: ['bluetooth', 'bluetooth settings'],
    action: ActivityAction.BLUETOOTH_SETTINGS, label: 'Bluetooth Settings' },
  { triggers: ['airplane mode', 'flight mode', 'airplane mode settings'],
    action: ActivityAction.AIRPLANE_MODE_SETTINGS, label: 'Airplane Mode' },
  { triggers: ['mobile data', 'data roaming', 'cellular', 'data settings'],
    action: ActivityAction.DATA_ROAMING_SETTINGS, label: 'Mobile Data' },
  { triggers: ['hotspot', 'tethering', 'personal hotspot'],
    action: ActivityAction.TETHER_SETTINGS, label: 'Hotspot & Tethering' },
  { triggers: ['vpn', 'vpn settings'],
    action: ActivityAction.VPN_SETTINGS, label: 'VPN Settings' },
  { triggers: ['nfc', 'nfc settings'],
    action: ActivityAction.NFC_SETTINGS, label: 'NFC Settings' },
  { triggers: ['network', 'network settings', 'wireless settings'],
    action: ActivityAction.WIRELESS_SETTINGS, label: 'Network Settings' },
  { triggers: ['data usage', 'data limit', 'data consumption'],
    action: ActivityAction.DATA_USAGE_SETTINGS, label: 'Data Usage' },
  { triggers: ['display', 'display settings', 'screen settings', 'brightness',
               'dark mode', 'night mode', 'screen timeout', 'font size', 'text size'],
    action: ActivityAction.DISPLAY_SETTINGS, label: 'Display Settings' },
  { triggers: ['sound', 'sound settings', 'volume', 'ringtone', 'notification sound'],
    action: ActivityAction.SOUND_SETTINGS, label: 'Sound Settings' },
  { triggers: ['do not disturb', 'dnd', 'zen mode', 'focus mode'],
    action: ActivityAction.ZEN_MODE_PRIORITY_SETTINGS, label: 'Do Not Disturb' },
  { triggers: ['notifications', 'notification settings'],
    action: ActivityAction.APP_NOTIFICATION_SETTINGS, label: 'Notifications' },
  { triggers: ['apps', 'app settings', 'application settings'],
    action: ActivityAction.APPLICATION_SETTINGS, label: 'App Settings' },
  { triggers: ['installed apps', 'all apps', 'manage apps', 'manage applications'],
    action: ActivityAction.MANAGE_APPLICATIONS_SETTINGS, label: 'Manage Apps' },
  { triggers: ['default apps', 'default applications'],
    action: ActivityAction.MANAGE_DEFAULT_APPS_SETTINGS, label: 'Default Apps' },
  { triggers: ['unknown sources', 'install unknown apps', 'sideload'],
    action: ActivityAction.MANAGE_UNKNOWN_APP_SOURCES, label: 'Install Unknown Apps' },
  { triggers: ['security', 'security settings', 'lock screen', 'screen lock'],
    action: ActivityAction.SECURITY_SETTINGS, label: 'Security Settings' },
  { triggers: ['fingerprint', 'fingerprint settings', 'add fingerprint'],
    action: ActivityAction.FINGERPRINT_ENROLL, label: 'Fingerprint Settings' },
  { triggers: ['device admin', 'device administrators'],
    action: ActivityAction.DEVICE_ADMIN_SETTINGS, label: 'Device Admin' },
  { triggers: ['privacy', 'privacy settings'],
    action: ActivityAction.PRIVACY_SETTINGS, label: 'Privacy Settings' },
  { triggers: ['location', 'location settings', 'gps'],
    action: ActivityAction.LOCATION_SOURCE_SETTINGS, label: 'Location Settings' },
  { triggers: ['permissions', 'app permissions', 'permission manager'],
    action: ActivityAction.PERMISSION_USAGE_SETTINGS, label: 'Permission Manager' },
  { triggers: ['accounts', 'account settings', 'sync', 'sync settings'],
    action: ActivityAction.SYNC_SETTINGS, label: 'Accounts & Sync' },
  { triggers: ['date', 'time', 'date and time', 'clock settings', 'date time'],
    action: ActivityAction.DATE_SETTINGS, label: 'Date & Time' },
  { triggers: ['language', 'locale', 'region', 'language settings'],
    action: ActivityAction.LOCALE_SETTINGS, label: 'Language & Region' },
  { triggers: ['keyboard', 'input method', 'keyboard settings', 'input settings'],
    action: ActivityAction.INPUT_METHOD_SETTINGS, label: 'Keyboard Settings' },
  { triggers: ['accessibility', 'accessibility settings', 'talkback'],
    action: ActivityAction.ACCESSIBILITY_SETTINGS, label: 'Accessibility Settings' },
  { triggers: ['developer', 'developer options', 'developer settings', 'dev options'],
    action: ActivityAction.APPLICATION_DEVELOPMENT_SETTINGS, label: 'Developer Options' },
  { triggers: ['battery', 'battery settings', 'battery saver', 'power saving', 'power saver'],
    action: ActivityAction.BATTERY_SAVER_SETTINGS, label: 'Battery Settings' },
  { triggers: ['battery optimization', 'optimize battery', 'background apps'],
    action: ActivityAction.IGNORE_BATTERY_OPTIMIZATION_SETTINGS, label: 'Battery Optimization' },
  { triggers: ['storage', 'storage settings', 'internal storage', 'free up space'],
    action: ActivityAction.INTERNAL_STORAGE_SETTINGS, label: 'Storage Settings' },
  { triggers: ['about phone', 'about device', 'device info', 'software version',
               'android version', 'software update', 'about'],
    action: ActivityAction.DEVICE_INFO_SETTINGS, label: 'About Phone' },
  { triggers: ['cast', 'screen cast', 'smart view', 'cast settings'],
    action: ActivityAction.CAST_SETTINGS, label: 'Cast Settings' },
  { triggers: ['home app', 'default home', 'launcher', 'home settings'],
    action: ActivityAction.HOME_SETTINGS, label: 'Home App Settings' },
  { triggers: ['hearing', 'hearing aid', 'hearing devices'],
    action: ActivityAction.HEARING_DEVICES_SETTINGS, label: 'Hearing Devices' },
  { triggers: ['screen timeout', 'display timeout', 'sleep timeout'],
    action: 'android.settings.DISPLAY_SETTINGS', label: 'Display Settings' },
  { triggers: ['font size', 'text size', 'font settings'],
    action: 'android.settings.DISPLAY_SETTINGS', label: 'Display Settings' },
  { triggers: ['dark mode', 'night mode', 'theme'],
    action: 'android.settings.DISPLAY_SETTINGS', label: 'Display Settings' },
  { triggers: ['screen resolution', 'resolution', 'display quality'],
    action: 'android.settings.DISPLAY_SETTINGS', label: 'Display Settings' },
  { triggers: ['always on display', 'aod'],
    action: 'android.settings.DISPLAY_SETTINGS', label: 'Display Settings' },
  { triggers: ['refresh rate', 'motion smoothness'],
    action: 'android.settings.DISPLAY_SETTINGS', label: 'Display Settings' },
  { triggers: ['sound settings', 'audio', 'sounds'],
    action: 'android.settings.SOUND_SETTINGS', label: 'Sound Settings' },
  { triggers: ['ringtone', 'ring tone'],
    action: 'android.settings.SOUND_SETTINGS', label: 'Sound Settings' },
  { triggers: ['vibration', 'vibrate'],
    action: 'android.settings.SOUND_SETTINGS', label: 'Sound Settings' },
  { triggers: ['do not disturb', 'dnd', 'silent mode'],
    action: 'android.settings.ZEN_MODE_SETTINGS', label: 'Do Not Disturb' },
  { triggers: ['battery', 'battery saver', 'power saving', 'battery usage', 'power'],
    action: 'android.settings.BATTERY_SAVER_SETTINGS', label: 'Battery Settings' },
  { triggers: ['battery optimization', 'optimize battery'],
    action: 'android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS', label: 'Battery Optimization' },
  { triggers: ['app settings', 'application settings', 'apps', 'application manager', 'manage apps'],
    action: 'android.settings.APPLICATION_SETTINGS', label: 'App Settings' },
  { triggers: ['default apps', 'default applications'],
    action: 'android.settings.MANAGE_DEFAULT_APPS_SETTINGS', label: 'Default Apps' },
  { triggers: ['storage settings', 'device storage'],
    action: 'android.settings.INTERNAL_STORAGE_SETTINGS', label: 'Storage Settings' },
  { triggers: ['security', 'security settings'],
    action: 'android.settings.SECURITY_SETTINGS', label: 'Security Settings' },
  { triggers: ['lock screen', 'screen lock', 'lockscreen'],
    action: 'android.settings.SECURITY_SETTINGS', label: 'Lock Screen Settings' },
  { triggers: ['fingerprint', 'biometrics', 'face recognition', 'face id', 'face unlock'],
    action: 'android.settings.SECURITY_SETTINGS', label: 'Biometric Settings' },
  { triggers: ['device admin', 'device administrators'],
    action: 'android.settings.DEVICE_ADMIN_SETTINGS', label: 'Device Admin' },
  { triggers: ['install unknown apps', 'unknown sources', 'sideload'],
    action: 'android.settings.MANAGE_UNKNOWN_APP_SOURCES', label: 'Install Unknown Apps' },
  { triggers: ['privacy', 'privacy settings'],
    action: 'android.settings.PRIVACY_SETTINGS', label: 'Privacy Settings' },
  { triggers: ['permission manager', 'permissions', 'app permissions'],
    action: 'android.settings.APPLICATION_SETTINGS', label: 'App Permissions' },
  { triggers: ['location', 'location settings', 'gps settings'],
    action: 'android.settings.LOCATION_SOURCE_SETTINGS', label: 'Location Settings' },
  { triggers: ['location history', 'google location'],
    action: 'android.settings.LOCATION_SOURCE_SETTINGS', label: 'Location Settings' },
  { triggers: ['wifi settings', 'wi-fi settings', 'wireless'],
    action: 'android.settings.WIFI_SETTINGS', label: 'WiFi Settings' },
  { triggers: ['mobile data', 'cellular', 'data settings', 'mobile network'],
    action: 'android.settings.DATA_ROAMING_SETTINGS', label: 'Mobile Data' },
  { triggers: ['hotspot', 'tethering', 'wifi hotspot', 'personal hotspot'],
    action: 'android.settings.WIRELESS_SETTINGS', label: 'Hotspot & Tethering' },
  { triggers: ['vpn', 'vpn settings'],
    action: 'android.settings.VPN_SETTINGS', label: 'VPN Settings' },
  { triggers: ['nfc', 'nfc settings'],
    action: 'android.settings.NFC_SETTINGS', label: 'NFC Settings' },
  { triggers: ['connected devices', 'bluetooth devices', 'paired devices'],
    action: 'android.settings.BLUETOOTH_SETTINGS', label: 'Connected Devices' },
  { triggers: ['airplane mode', 'flight mode'],
    action: 'android.settings.AIRPLANE_MODE_SETTINGS', label: 'Airplane Mode' },
  { triggers: ['accounts', 'account settings', 'google account', 'samsung account', 'sync'],
    action: 'android.settings.SYNC_SETTINGS', label: 'Account Settings' },
  { triggers: ['developer options', 'developer settings', 'dev options'],
    action: 'android.settings.APPLICATION_DEVELOPMENT_SETTINGS', label: 'Developer Options' },
  { triggers: ['usb debugging', 'adb'],
    action: 'android.settings.APPLICATION_DEVELOPMENT_SETTINGS', label: 'Developer Options' },
  { triggers: ['system trace', 'tracing', 'systrace'],
    action: 'android.settings.APPLICATION_DEVELOPMENT_SETTINGS', label: 'Developer Options' },
  { triggers: ['about phone', 'about device', 'phone info', 'device info settings'],
    action: 'android.settings.DEVICE_INFO_SETTINGS', label: 'About Phone' },
  { triggers: ['software update', 'system update', 'update phone', 'check for updates'],
    action: 'android.settings.SYSTEM_UPDATE_SETTINGS', label: 'Software Update' },
  { triggers: ['build number', 'android version', 'os version'],
    action: 'android.settings.DEVICE_INFO_SETTINGS', label: 'About Phone' },
  { triggers: ['talkback', 'screen reader'],
    action: 'android.settings.ACCESSIBILITY_SETTINGS', label: 'Accessibility Settings' },
  { triggers: ['magnification', 'zoom', 'magnifier'],
    action: 'android.settings.ACCESSIBILITY_SETTINGS', label: 'Accessibility Settings' },
  { triggers: ['samsung pass', 'password manager'],
    action: 'android.settings.SECURITY_SETTINGS', label: 'Security Settings' },
  { triggers: ['edge panel', 'edge screen'],
    action: 'android.settings.DISPLAY_SETTINGS', label: 'Display Settings' },
  { triggers: ['good lock', 'bixby', 'bixby routines'],
    action: 'android.settings.SETTINGS', label: 'Settings' },
  { triggers: ['samsung health', 'health settings'],
    action: 'android.settings.SETTINGS', label: 'Settings' },
  { triggers: ['game booster', 'game mode'],
    action: 'android.settings.SETTINGS', label: 'Settings' },
];

export function resolveSettingsIntent(query: string): SettingsEntry | null {
  const q = query.toLowerCase().trim()
    .replace(/^(?:hey\s+)?(?:ultra|agent)\s*,?\s*/i, '')
    .replace(/^(?:open|go\s+to|show|show\s+me|launch|take\s+me\s+to|navigate\s+to|bring\s+(?:up|me\s+to)|get\s+to)\s+/i, '')
    .replace(/\s+(?:please|now|for\s+me)$/i, '');

  if (!q) return null;

  let best: SettingsEntry | null = null;
  let bestScore = 0;

  for (const entry of SETTINGS_MAP) {
    for (const trigger of entry.triggers) {
      let score = 0;

      if (q === trigger) {
        score = trigger.length * 3 + 100;
      } else if (q.startsWith(trigger + ' ') || q.endsWith(' ' + trigger) || q.includes(trigger)) {
        score = trigger.length * 2;
      }

      if (score > bestScore) {
        bestScore = score;
        best = entry;
      }
    }
  }

  UltraDevLog.push('SYSTEM', { event: 'settings_intent_resolve', query: q, found: !!best, label: best?.label ?? null, action: best?.action ?? null, score: bestScore });
  return best;
}
