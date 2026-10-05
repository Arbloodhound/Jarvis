// Augment expo-intent-launcher ActivityAction with generic Android intent actions
// that are not included in the upstream settings-only enum.
import 'expo-intent-launcher';

declare module 'expo-intent-launcher' {
  export enum ActivityAction {
    VIEW = 'android.intent.action.VIEW',
    DEVICE_ADMIN_SETTINGS = 'android.settings.DEVICE_ADMIN_SETTINGS',
    PERMISSION_USAGE_SETTINGS = 'android.settings.USAGE_ACCESS_SETTINGS',
  }
}
