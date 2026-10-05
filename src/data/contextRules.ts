import type { ContextRule, ContextAction } from '../types/actionGrid';

export const CONTEXT_RULES: ContextRule[] = [
  {
    capability: 'app_launch',
    contentMatch: /amazon/i,
    actions: [
      { id: 'ctx_search_amazon', label: 'Search', icon: 'search-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'react_navigate', params: { appHint: 'amazon', goal: 'tap search bar' } } },
      { id: 'ctx_amazon_cart', label: 'Cart', icon: 'cart-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'react_navigate', params: { appHint: 'amazon', goal: 'navigate to cart' } } },
      { id: 'ctx_amazon_orders', label: 'Orders', icon: 'receipt-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'react_navigate', params: { appHint: 'amazon', goal: 'navigate to orders' } } },
    ],
  },
  {
    capability: 'device_location',
    actions: [
      { id: 'ctx_navigate', label: 'Navigate', icon: 'navigate-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Navigate to home' } },
      { id: 'ctx_share_loc', label: 'Share', icon: 'share-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Share my location' } },
      { id: 'ctx_nearby', label: 'Nearby', icon: 'restaurant-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'What restaurants are near me?' } },
      { id: 'ctx_weather', label: 'Weather', icon: 'partly-sunny-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'What is the weather at my location?' } },
    ],
  },
  {
    capability: 'camera_capture',
    actions: [
      { id: 'ctx_share_photo', label: 'Share', icon: 'share-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Share the last photo' } },
      { id: 'ctx_gallery', label: 'Gallery', icon: 'images-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'app_launch', params: { target: 'gallery' } } },
      { id: 'ctx_screenshot', label: 'Screenshot', icon: 'camera-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'screenshot', params: {} } },
    ],
  },
  {
    capability: 'app_launch',
    contentMatch: /call|dial|tel:/i,
    actions: [
      { id: 'ctx_text_them', label: 'Text Them', icon: 'chatbubble-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Send a text to who I just called' } },
      { id: 'ctx_call_again', label: 'Call Again', icon: 'call-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Call them again' } },
      { id: 'ctx_add_contact', label: 'Save Contact', icon: 'person-add-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'app_launch', params: { target: 'contacts' } } },
    ],
  },
  {
    capability: 'sms_send',
    actions: [
      { id: 'ctx_sms_another', label: 'New Text', icon: 'chatbubble-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Send another text' } },
      { id: 'ctx_read_sms', label: 'Read Texts', icon: 'mail-open-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Read my recent text messages' } },
    ],
  },
  {
    capability: 'wifi_toggle',
    actions: [
      { id: 'ctx_bt', label: 'Bluetooth', icon: 'bluetooth', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'bluetooth_toggle', params: {} } },
      { id: 'ctx_dnd', label: 'DND', icon: 'moon-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'do_not_disturb', params: {} } },
      { id: 'ctx_airplane', label: 'Airplane', icon: 'airplane-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'airplane_mode', params: {} } },
      { id: 'ctx_data', label: 'Data Usage', icon: 'cellular-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'app_launch', params: { target: 'data usage' } } },
    ],
  },
  {
    capability: 'bluetooth_toggle',
    actions: [
      { id: 'ctx_wifi2', label: 'WiFi', icon: 'wifi', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'wifi_toggle', params: {} } },
      { id: 'ctx_dnd2', label: 'DND', icon: 'moon-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'do_not_disturb', params: {} } },
      { id: 'ctx_devices', label: 'Devices', icon: 'headset-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'app_launch', params: { target: 'bluetooth settings' } } },
    ],
  },
  {
    capability: 'do_not_disturb',
    actions: [
      { id: 'ctx_wifi3', label: 'WiFi', icon: 'wifi', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'wifi_toggle', params: {} } },
      { id: 'ctx_bt3', label: 'Bluetooth', icon: 'bluetooth', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'bluetooth_toggle', params: {} } },
      { id: 'ctx_volume', label: 'Volume', icon: 'volume-high-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'volume_set', params: { direction: 'up' } } },
    ],
  },
  {
    capability: 'volume_set',
    actions: [
      { id: 'ctx_vol_up', label: 'Vol +', icon: 'volume-high-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'volume_set', params: { direction: 'up' } } },
      { id: 'ctx_vol_down', label: 'Vol -', icon: 'volume-low-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'volume_set', params: { direction: 'down' } } },
      { id: 'ctx_mute', label: 'Mute', icon: 'volume-mute-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'volume_set', params: { level: 0 } } },
    ],
  },
  {
    capability: 'device_info',
    actions: [
      { id: 'ctx_storage', label: 'Storage', icon: 'disc-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'app_launch', params: { target: 'storage settings' } } },
      { id: 'ctx_battery_saver', label: 'Battery Saver', icon: 'battery-charging-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'app_launch', params: { target: 'battery settings' } } },
      { id: 'ctx_running', label: 'Running Apps', icon: 'speedometer-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'app_launch', params: { target: 'running services' } } },
    ],
  },
  {
    capability: 'system_info',
    actions: [
      { id: 'ctx_full_info', label: 'Full Status', icon: 'information-circle-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'device_info', params: {} } },
      { id: 'ctx_gps2', label: 'GPS', icon: 'location-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'device_location', params: {} } },
    ],
  },
  {
    capability: 'screenshot',
    actions: [
      { id: 'ctx_share_ss', label: 'Share', icon: 'share-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Share the screenshot' } },
      { id: 'ctx_gallery2', label: 'Gallery', icon: 'images-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'app_launch', params: { target: 'gallery' } } },
      { id: 'ctx_another_ss', label: 'Another', icon: 'camera-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'screenshot', params: {} } },
    ],
  },
  {
    capability: 'web_search',
    actions: [
      { id: 'ctx_open_result', label: 'Open in Browser', icon: 'globe-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Open the first result in browser' } },
      { id: 'ctx_search_more', label: 'Search More', icon: 'search-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Search for more related results' } },
      { id: 'ctx_save_results', label: 'Copy Results', icon: 'clipboard-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Copy the search results to clipboard' } },
    ],
  },
  {
    capability: 'react_navigate',
    actions: [
      { id: 'ctx_scroll', label: 'Scroll Down', icon: 'chevron-down-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Scroll down on the current screen' } },
      { id: 'ctx_back', label: 'Go Back', icon: 'arrow-back-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Go back' } },
      { id: 'ctx_screenshot3', label: 'Screenshot', icon: 'camera-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'screenshot', params: {} } },
      { id: 'ctx_home', label: 'Home', icon: 'home-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Go to home screen' } },
    ],
  },
  {
    capability: 'image_generate',
    actions: [
      { id: 'ctx_variations', label: 'Variations', icon: 'copy-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Generate variations of this image' } },
      { id: 'ctx_diff_style', label: 'New Style', icon: 'color-palette-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Regenerate in a different style' } },
      { id: 'ctx_save_img', label: 'Save', icon: 'download-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Save this image to device' } },
    ],
  },
  {
    capability: 'flashlight_toggle',
    actions: [
      { id: 'ctx_flash_off', label: 'Flash Off', icon: 'flashlight-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'flashlight_toggle', params: { state: 'off' } } },
      { id: 'ctx_camera2', label: 'Camera', icon: 'camera-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'camera_capture', params: {} } },
    ],
  },
  {
    capability: 'app_launch',
    actions: [
      { id: 'ctx_screenshot4', label: 'Screenshot', icon: 'camera-outline', iconFamily: 'ionicons', execute: { type: 'plan', capability: 'screenshot', params: {} } },
      { id: 'ctx_interact', label: 'Navigate In', icon: 'hand-left-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'What can I do in this app?' } },
      { id: 'ctx_back2', label: 'Back', icon: 'arrow-back-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Go back' } },
    ],
  },
  {
    contentMatch: /error|failed|could not|couldn't|unable/i,
    actions: [
      { id: 'ctx_retry', label: 'Retry', icon: 'refresh-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Try that again' } },
      { id: 'ctx_diff_model', label: 'Different Model', icon: 'swap-horizontal-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Try this with a different model' } },
    ],
  },
  {
    capability: 'app_build',
    actions: [
      { id: 'ctx_test_build', label: 'Test', icon: 'flask-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Test the app I just built' } },
      { id: 'ctx_improve', label: 'Improve', icon: 'trending-up', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Improve this build' } },
    ],
  },
  {
    capability: 'code_generate',
    actions: [
      { id: 'ctx_explain', label: 'Explain', icon: 'book-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Explain this code' } },
      { id: 'ctx_build_it', label: 'Build It', icon: 'construct-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Build this into an app' } },
    ],
  },
];

export function getContextActions(
  capability: string | undefined,
  content: string,
  mode: string,
): ContextAction[] {
  for (const rule of CONTEXT_RULES) {
    if (rule.capability && rule.capability !== capability) continue;
    if (rule.contentMatch && !rule.contentMatch.test(content)) continue;
    if (rule.mode && rule.mode !== mode) continue;
    return rule.actions;
  }
  const defaults: ContextAction[] = [];
  if (content.length > 400) {
    defaults.push({ id: 'ctx_summarize', label: 'Summarize', icon: 'contract-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Summarize that more concisely' } });
  }
  if (content.length > 100) {
    defaults.push({ id: 'ctx_deeper', label: 'Go Deeper', icon: 'expand-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Tell me more about that' } });
  }
  defaults.push({ id: 'ctx_actionable', label: 'Make Actionable', icon: 'checkmark-done-outline', iconFamily: 'ionicons', execute: { type: 'prompt', text: 'Turn that into actionable steps' } });
  return defaults.slice(0, 3);
}
