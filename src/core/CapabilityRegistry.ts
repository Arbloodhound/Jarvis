import { Logger } from '../utils/Logger';
import { UltraDevLog } from '../utils/UltraDevLog';

export interface Capability {
  id: string;
  name: string;
  description: string;
  riskLevel: 'safe' | 'moderate' | 'sensitive' | 'dangerous';
  available: boolean;
  permissionsRequired: string[];
}

export class CapabilityRegistry {
  private capabilities: Map<string, Capability>;
  private logger: Logger;

  constructor() {
    this.capabilities = new Map();
    this.logger = new Logger('CapabilityRegistry');
  }

  async initialize(): Promise<void> {
    UltraDevLog.push('SYSTEM', { event: 'capability_registry_init_start' });
    const caps: Capability[] = [
      { id: 'file_read', name: 'File Read', description: 'Read files from device storage', riskLevel: 'safe', available: true, permissionsRequired: ['READ_EXTERNAL_STORAGE'] },
      { id: 'file_write', name: 'File Write', description: 'Write files to device storage', riskLevel: 'moderate', available: true, permissionsRequired: ['WRITE_EXTERNAL_STORAGE'] },
      { id: 'file_delete', name: 'File Delete', description: 'Delete files', riskLevel: 'dangerous', available: true, permissionsRequired: ['WRITE_EXTERNAL_STORAGE'] },
      { id: 'file_organize', name: 'File Organize', description: 'Move and organize files into folders', riskLevel: 'moderate', available: true, permissionsRequired: ['READ_EXTERNAL_STORAGE', 'WRITE_EXTERNAL_STORAGE'] },
      { id: 'contacts_read', name: 'Read Contacts', description: 'Read device contacts', riskLevel: 'sensitive', available: true, permissionsRequired: ['READ_CONTACTS'] },
      { id: 'sms_send', name: 'Send SMS', description: 'Send text messages', riskLevel: 'dangerous', available: true, permissionsRequired: ['SEND_SMS'] },
      { id: 'sms_read', name: 'Read SMS', description: 'Read text messages from the inbox', riskLevel: 'sensitive', available: true, permissionsRequired: ['READ_SMS'] },
      { id: 'sms_conversation', name: 'SMS Conversation', description: 'Read SMS thread with a specific contact', riskLevel: 'sensitive', available: true, permissionsRequired: ['READ_SMS'] },
      { id: 'camera_capture', name: 'Camera', description: 'Take photos', riskLevel: 'moderate', available: true, permissionsRequired: ['CAMERA'] },
      { id: 'media_access', name: 'Media Library', description: 'Access photos and videos', riskLevel: 'safe', available: true, permissionsRequired: ['READ_EXTERNAL_STORAGE', 'READ_MEDIA_IMAGES'] },
      { id: 'app_launch', name: 'Launch App', description: 'Open other installed apps', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'app_share', name: 'Share Data', description: 'Share data between apps', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'code_generate', name: 'Code Gen', description: 'Generate source code via AI', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'app_build', name: 'Build App', description: 'Compile Android APK on device', riskLevel: 'moderate', available: true, permissionsRequired: ['WRITE_EXTERNAL_STORAGE'] },
      { id: 'app_install', name: 'Install App', description: 'Install built APK', riskLevel: 'dangerous', available: true, permissionsRequired: [] },
      { id: 'network_request', name: 'Network', description: 'Make HTTP requests', riskLevel: 'moderate', available: true, permissionsRequired: [] },
      { id: 'ai_query', name: 'AI Query', description: 'Query AI for assistance', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'dependency_resolve', name: 'Resolve Dependencies', description: 'Download Maven/JAR dependencies from Maven Central', riskLevel: 'moderate', available: true, permissionsRequired: ['INTERNET'] },
      { id: 'device_location', name: 'Device Location', description: 'Get current GPS coordinates', riskLevel: 'moderate', available: true, permissionsRequired: ['ACCESS_FINE_LOCATION'] },
      { id: 'app_control', name: 'App Control', description: 'Control other apps via accessibility service', riskLevel: 'dangerous', available: true, permissionsRequired: ['BIND_ACCESSIBILITY_SERVICE'] },
      { id: 'app_test', name: 'Test App', description: 'Run E2E tests on a built app via accessibility service', riskLevel: 'moderate', available: true, permissionsRequired: ['BIND_ACCESSIBILITY_SERVICE'] },
      { id: 'self_modify', name: 'Self Modify', description: 'Evolve own genome via mutation and fitness evaluation', riskLevel: 'dangerous', available: true, permissionsRequired: [] },
      { id: 'self_replicate', name: 'Self Replicate', description: 'Compile genome into offspring APK', riskLevel: 'dangerous', available: true, permissionsRequired: ['WRITE_EXTERNAL_STORAGE'] },
      { id: 'image_generate', name: 'Image Generate', description: 'Generate images from text prompts', riskLevel: 'moderate', available: true, permissionsRequired: [] },
      { id: 'system_info', name: 'System Info', description: 'Get device system information: battery, RAM, storage, CPU temperature', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'flashlight_toggle', name: 'Flashlight', description: 'Toggle device flashlight/torch on or off', riskLevel: 'safe', available: true, permissionsRequired: ['CAMERA'] },
      { id: 'alarm_set', name: 'Set Alarm', description: 'Set an alarm via AlarmClock intent', riskLevel: 'safe', available: true, permissionsRequired: ['com.android.alarm.permission.SET_ALARM'] },
      { id: 'timer_set', name: 'Set Timer', description: 'Set a countdown timer via AlarmClock intent', riskLevel: 'safe', available: true, permissionsRequired: ['SET_ALARM'] },
      { id: 'volume_set', name: 'Volume Control', description: 'Set media/ringer/notification volume level', riskLevel: 'moderate', available: true, permissionsRequired: ['MODIFY_AUDIO_SETTINGS'] },
      { id: 'brightness_set', name: 'Brightness Control', description: 'Set screen brightness level', riskLevel: 'moderate', available: true, permissionsRequired: [] },
      { id: 'wifi_toggle', name: 'WiFi Toggle', description: 'Open WiFi settings (Android 10+ restriction)', riskLevel: 'moderate', available: true, permissionsRequired: ['CHANGE_WIFI_STATE'] },
      { id: 'bluetooth_toggle', name: 'Bluetooth Toggle', description: 'Open Bluetooth settings', riskLevel: 'moderate', available: true, permissionsRequired: ['BLUETOOTH'] },
      { id: 'airplane_mode', name: 'Airplane Mode', description: 'Open airplane mode settings', riskLevel: 'moderate', available: true, permissionsRequired: [] },
      { id: 'do_not_disturb', name: 'Do Not Disturb', description: 'Open DND settings', riskLevel: 'moderate', available: true, permissionsRequired: [] },
      { id: 'battery_status', name: 'Battery Status', description: 'Read battery level and charging state', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'clipboard_read', name: 'Clipboard Read', description: 'Read clipboard contents', riskLevel: 'sensitive', available: true, permissionsRequired: [] },
      { id: 'clipboard_write', name: 'Clipboard Write', description: 'Write text to clipboard', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'media_play', name: 'Media Play/Pause', description: 'Play or pause media playback', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'media_next', name: 'Media Next', description: 'Skip to next track', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'screenshot', name: 'Screenshot', description: 'Take a screenshot via AccessibilityService', riskLevel: 'moderate', available: true, permissionsRequired: ['BIND_ACCESSIBILITY_SERVICE'] },
      { id: 'screen_record_start', name: 'Screen Record', description: 'Start screen recording intent', riskLevel: 'moderate', available: true, permissionsRequired: [] },
      { id: 'open_url', name: 'Open URL', description: 'Open a URL in default browser', riskLevel: 'safe', available: true, permissionsRequired: ['INTERNET'] },
      { id: 'web_search', name: 'Web Search', description: 'Open browser with search query', riskLevel: 'safe', available: true, permissionsRequired: ['INTERNET'] },
      { id: 'calendar_create', name: 'Calendar Create', description: 'Create a calendar event via intent', riskLevel: 'moderate', available: true, permissionsRequired: ['WRITE_CALENDAR'] },
      { id: 'reminder_create', name: 'Reminder Create', description: 'Create a reminder via intent', riskLevel: 'moderate', available: true, permissionsRequired: [] },
      { id: 'note_create', name: 'Note Create', description: 'Create a note in Samsung Notes or Google Keep', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'file_open', name: 'File Open', description: 'Open a file by path or type', riskLevel: 'safe', available: true, permissionsRequired: ['READ_EXTERNAL_STORAGE', 'READ_MEDIA_IMAGES'] },
      { id: 'share_content', name: 'Share Content', description: 'Share text or file via Android share sheet', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'app_info', name: 'App Info', description: 'Open app info settings for a specific app', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'notification_read', name: 'Notification Read', description: 'Read notifications via AccessibilityService', riskLevel: 'sensitive', available: true, permissionsRequired: ['BIND_ACCESSIBILITY_SERVICE'] },
      { id: 'device_info', name: 'Device Info', description: 'Return full device stats: battery, RAM, storage, network, OS', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'react_navigate', name: 'UI Navigation', description: 'Navigate inside any app using accessibility service ReAct loop', riskLevel: 'moderate', available: true, permissionsRequired: ['BIND_ACCESSIBILITY_SERVICE'] },
      { id: 'event_trigger_set', name: 'Set Event Trigger', description: 'Set up autonomous event-based triggers', riskLevel: 'moderate', available: true, permissionsRequired: [] },
      { id: 'event_trigger_list', name: 'List Triggers', description: 'List active autonomous triggers', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'memory_recall', name: 'Memory Recall', description: 'Recall stored memory about a topic', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'event_trigger_remove', name: 'Remove Trigger', description: 'Remove an active event trigger by ID', riskLevel: 'moderate', available: true, permissionsRequired: [] },
      { id: 'multi_step', name: 'Multi-Step Task', description: 'Execute a sequence of capability steps', riskLevel: 'moderate', available: true, permissionsRequired: [] },
      { id: 'tts', name: 'Text to Speech', description: 'Convert text to audio using AI TTS', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'video_generate', name: 'Video Generate', description: 'Generate a short video from a text prompt via AI', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'knowledge_query', name: 'Knowledge Query', description: 'Query the knowledge graph about known entities', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'user_correction', name: 'User Correction', description: 'Correct agent knowledge when user says something is wrong', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'proactive_suggestions', name: 'Proactive Suggestions', description: 'Show AI-generated proactive suggestions', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'task_resume', name: 'Task Resume', description: 'Resume a paused or interrupted complex task', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'behavior_patterns', name: 'Behavior Patterns', description: 'Show detected behavioral patterns', riskLevel: 'safe', available: true, permissionsRequired: [] },
      { id: 'web_research', name: 'Web Research', description: 'Research a topic using installed apps', riskLevel: 'moderate', available: true, permissionsRequired: ['BIND_ACCESSIBILITY_SERVICE'] },
      { id: 'vision_read', name: 'Vision Read', description: 'Understand what is on screen using AI', riskLevel: 'safe', available: true, permissionsRequired: ['BIND_ACCESSIBILITY_SERVICE'] },
      { id: 'describe_screen', name: 'Describe Screen', description: 'Describe what is currently on screen using AI vision', riskLevel: 'safe', available: true, permissionsRequired: ['BIND_ACCESSIBILITY_SERVICE'] },
      { id: 'read_text_on_screen', name: 'Read Screen Text', description: 'OCR and read all visible text from the current screen', riskLevel: 'safe', available: true, permissionsRequired: ['BIND_ACCESSIBILITY_SERVICE'] },
      { id: 'weather', name: 'Weather', description: 'Get current weather conditions and forecast from Open-Meteo', riskLevel: 'safe', available: true, permissionsRequired: ['INTERNET'] },
      { id: 'news_headlines', name: 'News Headlines', description: 'Fetch top news headlines from RSS feeds', riskLevel: 'safe', available: true, permissionsRequired: ['INTERNET'] },
    ];
    for (const c of caps) this.capabilities.set(c.id, c);
    this.logger.info(`Registered ${this.capabilities.size} capabilities`);
    UltraDevLog.push('SYSTEM', { event: 'capability_registry_init_done', count: this.capabilities.size });
  }

  get(id: string): Capability | undefined { return this.capabilities.get(id); }
  has(id: string): boolean { return this.capabilities.has(id); }
  getAll(): Capability[] { return Array.from(this.capabilities.values()); }

  getCapabilityList(): string {
    return this.getAll().map((c) => `${c.id}: ${c.description} [${c.riskLevel}]`).join('\n');
  }

  getRequiredPermissions(capIds: string[]): string[] {
    const perms = new Set<string>();
    for (const id of capIds) {
      const cap = this.capabilities.get(id);
      if (cap) cap.permissionsRequired.forEach((p) => perms.add(p));
    }
    return Array.from(perms);
  }

  getHighestRisk(capIds: string[]): Capability['riskLevel'] {
    const levels: Capability['riskLevel'][] = ['safe', 'moderate', 'sensitive', 'dangerous'];
    let highest = 0;
    for (const id of capIds) {
      const cap = this.capabilities.get(id);
      if (cap) {
        const idx = levels.indexOf(cap.riskLevel);
        if (idx > highest) highest = idx;
      }
    }
    return levels[highest];
  }
}
