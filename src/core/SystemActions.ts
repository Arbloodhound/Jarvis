import { ActivityAction, startActivityAsync } from 'expo-intent-launcher';
import { UltraDevLog } from '../utils/UltraDevLog';

export interface SystemActionResult {
  success: boolean;
  message: string;
  routedToSettings?: boolean;
}

interface SystemActionEntry {
  triggers: string[];
  handler: () => Promise<SystemActionResult>;
}

const SYSTEM_ACTIONS: SystemActionEntry[] = [
  {
    triggers: ['turn on wifi', 'enable wifi', 'turn off wifi', 'disable wifi',
               'toggle wifi', 'wifi on', 'wifi off', 'switch on wifi', 'switch off wifi'],
    handler: async () => {
      await startActivityAsync(ActivityAction.WIFI_SETTINGS);
      UltraDevLog.systemAction('wifi toggle', true, "Opened Wi-Fi settings", true);
      return { success: true, routedToSettings: true,
        message: "I've opened Wi-Fi settings. Android no longer allows apps to toggle Wi-Fi directly — tap the switch at the top to turn it on or off." };
    },
  },
  {
    triggers: ['turn on bluetooth', 'enable bluetooth', 'turn off bluetooth',
               'disable bluetooth', 'toggle bluetooth', 'bluetooth on', 'bluetooth off'],
    handler: async () => {
      await startActivityAsync(ActivityAction.BLUETOOTH_SETTINGS);
      UltraDevLog.systemAction('bluetooth toggle', true, "Opened Bluetooth settings", true);
      return { success: true, routedToSettings: true,
        message: "I've opened Bluetooth settings. Android no longer allows apps to toggle Bluetooth directly — tap the switch at the top to turn it on or off." };
    },
  },
  {
    triggers: ['airplane mode on', 'airplane mode off', 'toggle airplane mode',
               'enable airplane mode', 'disable airplane mode', 'flight mode on', 'flight mode off'],
    handler: async () => {
      await startActivityAsync(ActivityAction.AIRPLANE_MODE_SETTINGS);
      UltraDevLog.systemAction('airplane mode toggle', true, "Opened Airplane Mode settings", true);
      return { success: true, routedToSettings: true,
        message: "I've opened Airplane Mode settings. Tap the switch to toggle it." };
    },
  },
  {
    triggers: ['increase brightness', 'decrease brightness', 'set brightness',
               'brightness up', 'brightness down', 'max brightness', 'lower brightness'],
    handler: async () => {
      await startActivityAsync(ActivityAction.DISPLAY_SETTINGS);
      UltraDevLog.systemAction('brightness adjust', true, "Opened Display Settings", true);
      return { success: true, routedToSettings: true,
        message: "I've opened Display Settings where you can adjust brightness." };
    },
  },
];

export function resolveSystemAction(query: string): SystemActionEntry | null {
  const q = query.toLowerCase().trim();
  for (const action of SYSTEM_ACTIONS) {
    for (const trigger of action.triggers) {
      if (q.includes(trigger)) return action;
    }
  }
  return null;
}
