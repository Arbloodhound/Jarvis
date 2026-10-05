import type { GridCategory, GridConfig, GridAction } from '../types/actionGrid';

export const DEFAULT_CATEGORIES: GridCategory[] = [
  {
    id: 'comms',
    label: 'Communicate',
    icon: 'chatbubbles-outline',
    iconFamily: 'ionicons',
    color: '#34d399',
    actions: [
      { id: 'call', label: 'Call', icon: 'call-outline', iconFamily: 'ionicons', capability: 'app_launch', params: { target: 'phone', action: 'android.intent.action.DIAL' } },
      { id: 'call_contact', label: 'Call Contact', icon: 'person-outline', iconFamily: 'ionicons', capability: 'app_launch', params: { action: 'android.intent.action.CALL' }, requiresInput: true, inputPlaceholder: 'Contact name', inputKey: '_contactName' },
      { id: 'text', label: 'Text', icon: 'chatbubble-outline', iconFamily: 'ionicons', capability: 'sms_send', params: {}, requiresInput: true, inputPlaceholder: 'Who and what?', inputKey: '_smsInput' },
      // Email entries removed — they produce no_target errors because app_launch
      // executor expects a 'target' field but SENDTO actions don't provide one.
      // TODO: Re-add when IntentResolver handles SENDTO actions directly.
    ],
  },
  {
    id: 'device',
    label: 'Device',
    icon: 'phone-portrait-outline',
    iconFamily: 'ionicons',
    color: '#60a5fa',
    actions: [
      { id: 'screenshot', label: 'Screenshot', icon: 'camera-outline', iconFamily: 'ionicons', capability: 'screenshot', params: {} },
      { id: 'device_info', label: 'Status', icon: 'information-circle-outline', iconFamily: 'ionicons', capability: 'device_info', params: {} },
      { id: 'gps', label: 'GPS', icon: 'location-outline', iconFamily: 'ionicons', capability: 'device_location', params: {} },
      { id: 'battery', label: 'Battery', icon: 'battery-half-outline', iconFamily: 'ionicons', capability: 'system_info', params: { focus: 'battery' } },
    ],
  },
  {
    id: 'system',
    label: 'System Toggles',
    icon: 'toggle-outline',
    iconFamily: 'ionicons',
    color: '#818cf8',
    actions: [
      { id: 'wifi', label: 'WiFi', icon: 'wifi', iconFamily: 'ionicons', capability: 'wifi_toggle', params: {} },
      { id: 'bluetooth', label: 'Bluetooth', icon: 'bluetooth', iconFamily: 'ionicons', capability: 'bluetooth_toggle', params: {} },
      { id: 'dnd', label: 'DND', icon: 'moon-outline', iconFamily: 'ionicons', capability: 'do_not_disturb', params: {} },
      { id: 'airplane', label: 'Airplane', icon: 'airplane-outline', iconFamily: 'ionicons', capability: 'airplane_mode', params: {} },
      { id: 'flashlight', label: 'Flashlight', icon: 'flashlight-outline', iconFamily: 'ionicons', capability: 'flashlight_toggle', params: {} },
      { id: 'volume_up', label: 'Vol +', icon: 'volume-high-outline', iconFamily: 'ionicons', capability: 'volume_set', params: { direction: 'up' } },
      { id: 'volume_down', label: 'Vol -', icon: 'volume-low-outline', iconFamily: 'ionicons', capability: 'volume_set', params: { direction: 'down' } },
      { id: 'brightness', label: 'Brightness', icon: 'sunny-outline', iconFamily: 'ionicons', capability: 'brightness_set', params: {} },
    ],
  },
  {
    id: 'apps',
    label: 'Apps',
    icon: 'apps-outline',
    iconFamily: 'ionicons',
    color: '#c084fc',
    actions: [
      { id: 'open_app', label: 'Open App', icon: 'open-outline', iconFamily: 'ionicons', capability: 'app_launch', params: {}, requiresInput: true, inputPlaceholder: 'App name', inputKey: 'target' },
      { id: 'open_google', label: 'Google', icon: 'logo-google', iconFamily: 'ionicons', capability: 'app_launch', params: { target: 'google' } },
      { id: 'open_camera', label: 'Camera', icon: 'camera', iconFamily: 'ionicons', capability: 'camera_capture', params: {} },
      { id: 'open_gallery', label: 'Gallery', icon: 'images-outline', iconFamily: 'ionicons', capability: 'app_launch', params: { target: 'gallery' } },
      { id: 'open_settings', label: 'Settings', icon: 'settings-outline', iconFamily: 'ionicons', capability: 'app_launch', params: { target: 'settings' } },
      { id: 'open_amazon', label: 'Amazon', icon: 'cart-outline', iconFamily: 'ionicons', capability: 'app_launch', params: { target: 'amazon' } },
      { id: 'open_youtube', label: 'YouTube', icon: 'logo-youtube', iconFamily: 'ionicons', capability: 'app_launch', params: { target: 'youtube' } },
    ],
  },
  {
    id: 'search',
    label: 'Search',
    icon: 'search-outline',
    iconFamily: 'ionicons',
    color: '#fbbf24',
    actions: [
      { id: 'web_search', label: 'Web', icon: 'globe-outline', iconFamily: 'ionicons', capability: 'web_search', params: {}, requiresInput: true, inputPlaceholder: 'Search for...', inputKey: 'query' },
      { id: 'search_amazon', label: 'Amazon', icon: 'cart-outline', iconFamily: 'ionicons', capability: 'react_navigate', params: { appHint: 'amazon' }, requiresInput: true, inputPlaceholder: 'Search Amazon for...', inputKey: 'goal' },
      { id: 'search_youtube', label: 'YouTube', icon: 'logo-youtube', iconFamily: 'ionicons', capability: 'react_navigate', params: { appHint: 'youtube' }, requiresInput: true, inputPlaceholder: 'Search YouTube for...', inputKey: 'goal' },
      { id: 'search_contacts', label: 'Contacts', icon: 'people-outline', iconFamily: 'ionicons', capability: 'contacts_read', params: {}, requiresInput: true, inputPlaceholder: 'Find contact...', inputKey: 'query' },
      { id: 'search_reddit', label: 'Reddit', icon: 'logo-reddit', iconFamily: 'ionicons', capability: 'react_navigate', params: { appHint: 'reddit' }, requiresInput: true, inputPlaceholder: 'Search Reddit for...', inputKey: 'goal' },
    ],
  },
  {
    id: 'media',
    label: 'Media',
    icon: 'musical-notes-outline',
    iconFamily: 'ionicons',
    color: '#f472b6',
    modes: ['chat', 'image', 'video'],
    actions: [
      { id: 'take_photo', label: 'Photo', icon: 'camera', iconFamily: 'ionicons', capability: 'camera_capture', params: {} },
      { id: 'gallery', label: 'Gallery', icon: 'images', iconFamily: 'ionicons', capability: 'app_launch', params: { target: 'gallery' } },
      { id: 'play_music', label: 'Music', icon: 'musical-note-outline', iconFamily: 'ionicons', capability: 'app_launch', params: { target: 'music' } },
      { id: 'gen_image', label: 'Generate', icon: 'color-wand-outline', iconFamily: 'ionicons', capability: 'image_generate', params: {}, requiresInput: true, inputPlaceholder: 'Describe the image...', inputKey: 'prompt' },
    ],
  },
  {
    id: 'files',
    label: 'Files',
    icon: 'folder-outline',
    iconFamily: 'ionicons',
    color: '#fb923c',
    actions: [
      { id: 'clipboard_write', label: 'Copy Text', icon: 'clipboard-outline', iconFamily: 'ionicons', capability: 'clipboard_write', params: {}, requiresInput: true, inputPlaceholder: 'Text to copy', inputKey: 'text' },
      { id: 'clipboard_read', label: 'Paste', icon: 'clipboard', iconFamily: 'ionicons', capability: 'clipboard_read', params: {} },
      { id: 'file_browse', label: 'Browse', icon: 'folder-open-outline', iconFamily: 'ionicons', capability: 'app_launch', params: { target: 'files' } },
      { id: 'file_share', label: 'Share', icon: 'share-outline', iconFamily: 'ionicons', capability: 'app_share', params: {} },
    ],
  },
  {
    id: 'navigate',
    label: 'Navigate',
    icon: 'navigate-outline',
    iconFamily: 'ionicons',
    color: '#2dd4bf',
    actions: [
      { id: 'my_location', label: 'My Location', icon: 'location', iconFamily: 'ionicons', capability: 'device_location', params: {} },
      { id: 'open_maps', label: 'Maps', icon: 'map-outline', iconFamily: 'ionicons', capability: 'app_launch', params: { target: 'maps' } },
      { id: 'directions', label: 'Directions', icon: 'navigate-circle-outline', iconFamily: 'ionicons', capability: 'app_launch', params: { action: 'android.intent.action.VIEW', data: 'google.navigation:' }, requiresInput: true, inputPlaceholder: 'Where to?', inputKey: '_destination' },
      { id: 'share_location', label: 'Share GPS', icon: 'share-social-outline', iconFamily: 'ionicons', capability: 'device_location', params: { share: true } },
    ],
  },
  {
    id: 'schedule',
    label: 'Schedule',
    icon: 'time-outline',
    iconFamily: 'ionicons',
    color: '#a78bfa',
    actions: [
      { id: 'set_alarm', label: 'Alarm', icon: 'alarm-outline', iconFamily: 'ionicons', capability: 'alarm_set', params: {}, requiresInput: true, inputPlaceholder: 'Time (e.g. 7:30am)', inputKey: 'time' },
      { id: 'set_timer', label: 'Timer', icon: 'timer-outline', iconFamily: 'ionicons', capability: 'timer_set', params: {}, requiresInput: true, inputPlaceholder: 'Duration (e.g. 5 min)', inputKey: 'duration' },
      { id: 'open_calendar', label: 'Calendar', icon: 'calendar-outline', iconFamily: 'ionicons', capability: 'app_launch', params: { target: 'calendar' } },
      { id: 'open_clock', label: 'Clock', icon: 'time', iconFamily: 'ionicons', capability: 'app_launch', params: { target: 'clock' } },
    ],
  },
  {
    id: 'quick',
    label: 'My Tasks',
    icon: 'flash-outline',
    iconFamily: 'ionicons',
    color: '#34d399',
    actions: [],
  },
];

export const DEFAULT_GRID_CONFIG: GridConfig = {
  categoryOrder: ['device', 'system', 'comms', 'apps', 'search', 'media', 'files', 'navigate', 'schedule', 'quick'],
  hiddenCategories: [],
  favorites: ['wifi', 'dnd', 'flashlight', 'screenshot', 'gps'],
  customActions: [],
};

export function getGridForMode(mode: string, categories: GridCategory[]): GridCategory[] {
  const modeBoosts: Record<string, string[]> = {
    image: ['media', 'files', 'search'],
    video: ['media', 'files', 'search'],
    code: ['files', 'search', 'apps'],
    chat: [],
    reasoning: ['search', 'files'],
  };

  const boosted = modeBoosts[mode] || [];
  if (boosted.length === 0) return categories;

  const sorted = [...categories].sort((a, b) => {
    const aBoost = boosted.indexOf(a.id);
    const bBoost = boosted.indexOf(b.id);
    if (aBoost >= 0 && bBoost >= 0) return aBoost - bBoost;
    if (aBoost >= 0) return -1;
    if (bBoost >= 0) return 1;
    return 0;
  });

  if (mode === 'image') {
    const media = sorted.find(c => c.id === 'media');
    if (media) {
      const genAction: GridAction = {
        id: 'gen_image_quick', label: 'Generate Image', icon: 'color-wand',
        iconFamily: 'ionicons', capability: 'image_generate', params: {},
        requiresInput: true, inputPlaceholder: 'Describe the image...', inputKey: 'prompt',
      };
      if (!media.actions.find(a => a.id === 'gen_image_quick')) {
        media.actions = [genAction, ...media.actions];
      }
    }
  }

  if (mode === 'video') {
    const media = sorted.find(c => c.id === 'media');
    if (media) {
      const vidAction: GridAction = {
        id: 'gen_video_quick', label: 'Generate Video', icon: 'videocam',
        iconFamily: 'ionicons', capability: 'image_generate', params: { mode: 'video' },
        requiresInput: true, inputPlaceholder: 'Describe the video...', inputKey: 'prompt',
      };
      if (!media.actions.find(a => a.id === 'gen_video_quick')) {
        media.actions = [vidAction, ...media.actions];
      }
    }
  }

  return sorted;
}
