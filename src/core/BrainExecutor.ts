import type { ModelRouter } from './ModelRouter';
import type { TaskExecutor } from './TaskExecutor';
import type { ConversationManager } from '../services/ConversationManager';
import type { UltraExecutionResult } from '../types/ultra';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import AsyncStorage from '@react-native-async-storage/async-storage';

// ─────────────────────────────────────────────────────────────────────────────
// P6: TASK MEMORY — track tool reliability + successful task shortcuts
// ─────────────────────────────────────────────────────────────────────────────
const TASK_MEMORY_KEY = 'brain_task_memory';

interface ToolReliability {
  successes: number;
  failures: number;
  lastFailure?: string;
}

interface TaskShortcut {
  goalPattern: string;
  tools: string[];
  count: number;
}

interface TaskMemory {
  toolReliability: Record<string, ToolReliability>;
  shortcuts: TaskShortcut[];
}

let _taskMemoryCache: TaskMemory | null = null;

async function loadTaskMemory(): Promise<TaskMemory> {
  if (_taskMemoryCache) return _taskMemoryCache;
  try {
    const raw = await AsyncStorage.getItem(TASK_MEMORY_KEY);
    if (raw) {
      _taskMemoryCache = JSON.parse(raw);
      return _taskMemoryCache!;
    }
  } catch {}
  _taskMemoryCache = { toolReliability: {}, shortcuts: [] };
  return _taskMemoryCache;
}

async function saveTaskMemory(mem: TaskMemory): Promise<void> {
  _taskMemoryCache = mem;
  try {
    await AsyncStorage.setItem(TASK_MEMORY_KEY, JSON.stringify(mem));
  } catch {}
}

async function recordToolResult(tool: string, success: boolean, errorMsg?: string): Promise<void> {
  const mem = await loadTaskMemory();
  if (!mem.toolReliability[tool]) mem.toolReliability[tool] = { successes: 0, failures: 0 };
  if (success) {
    mem.toolReliability[tool].successes++;
  } else {
    mem.toolReliability[tool].failures++;
    if (errorMsg) mem.toolReliability[tool].lastFailure = errorMsg.slice(0, 100);
  }
  await saveTaskMemory(mem);
}

async function recordTaskShortcut(userInput: string, tools: string[]): Promise<void> {
  if (tools.length < 2) return;
  const mem = await loadTaskMemory();
  // Extract a simplified goal pattern (first few action words)
  const pattern = userInput.toLowerCase().replace(/[^a-z ]/g, '').trim().slice(0, 60);
  const toolKey = tools.join(',');
  // Check if we already have this shortcut
  const existing = mem.shortcuts.find(s => s.tools.join(',') === toolKey);
  if (existing) {
    existing.count++;
    existing.goalPattern = pattern;
  } else {
    mem.shortcuts.push({ goalPattern: pattern, tools, count: 1 });
    // Keep max 20 shortcuts, remove least used
    if (mem.shortcuts.length > 20) {
      mem.shortcuts.sort((a, b) => b.count - a.count);
      mem.shortcuts = mem.shortcuts.slice(0, 20);
    }
  }
  await saveTaskMemory(mem);
}

// Build reliability + shortcut hints for the system prompt
async function getTaskMemoryHints(userInput: string): Promise<string> {
  const mem = await loadTaskMemory();
  const hints: string[] = [];

  // Reliability warnings for unreliable tools (>40% failure rate, min 3 uses)
  for (const [tool, stats] of Object.entries(mem.toolReliability)) {
    const total = stats.successes + stats.failures;
    if (total >= 3 && stats.failures / total > 0.4) {
      hints.push(`${tool}: unreliable (${stats.failures}/${total} recent fails). Consider alternatives.`);
    }
  }

  // Shortcut matches — find shortcuts whose tools might match this request
  const uLower = userInput.toLowerCase();
  for (const shortcut of mem.shortcuts) {
    if (shortcut.count >= 2) {
      // Simple keyword overlap check
      const patternWords = shortcut.goalPattern.split(' ').filter(w => w.length > 3);
      const matchCount = patternWords.filter(w => uLower.includes(w)).length;
      if (matchCount >= 2 || matchCount / patternWords.length > 0.5) {
        hints.push(`KNOWN APPROACH: For similar tasks, ${shortcut.tools.join(' → ')} has worked before.`);
        break; // Only one shortcut hint
      }
    }
  }

  return hints.length > 0 ? '\nTASK MEMORY:\n' + hints.join('\n') : '';
}

// ─────────────────────────────────────────────────────────────────────────────
// DESTRUCTIVE TOOLS — require explicit user confirmation before executing
// These tools make real-world changes the user cannot undo easily:
// sending messages, making calls, deleting files, making purchases
// ─────────────────────────────────────────────────────────────────────────────
const DESTRUCTIVE_TOOLS = new Set([
  'sms_send',
  'file_delete',
]);

// app_launch is destructive only when it's a phone call
function isDestructiveLaunch(params: Record<string, any>): boolean {
  const action = (params.action || '').toLowerCase();
  return action === 'android.intent.action.call' || action === 'android.intent.action.dial';
}

function requiresConfirmation(tool: string, params: Record<string, any>): boolean {
  if (DESTRUCTIVE_TOOLS.has(tool)) return true;
  if (tool === 'app_launch' && isDestructiveLaunch(params)) return true;
  return false;
}

function describeAction(tool: string, params: Record<string, any>): string {
  if (tool === 'sms_send') {
    return `Send a text message to ${params.to || 'unknown'}: "${params.message || ''}"`;
  }
  if (tool === 'file_delete') {
    return `Delete file: ${params.filename || params.path || 'unknown'}`;
  }
  if (tool === 'app_launch' && isDestructiveLaunch(params)) {
    const number = params.data?.replace('tel:', '') || params.extras?._contactName || 'unknown';
    return `Call ${number}`;
  }
  return `Execute ${tool}`;
}

// ─────────────────────────────────────────────────────────────────────────────
// TOOL DEFINITIONS
// ─────────────────────────────────────────────────────────────────────────────
const TOOLS = `
DEVICE CONTROL (instant, ~99% reliable):
  wifi_toggle, bluetooth_toggle, airplane_mode, do_not_disturb, flashlight_toggle
  volume_set, brightness_set, media_play, media_next

APPS & NAVIGATION (use app_launch to just open, react_navigate to open AND interact):
  app_launch — open app/settings/website (no interaction). params: {target}
  react_navigate — open app AND do things inside it (tap, type, scroll). params: {goal, appHint}
  open_url — open a URL in browser. params: {url}
  install_app — search Play Store and install an app. params: {appName}
  app_info — show app info/settings. params: {target}

INFORMATION (fast, no UI needed):
  web_search — search internet, returns text results directly. params: {query}
  web_research — deep research a topic. params: {query}
  weather, news_headlines, device_location, device_info, system_info, battery_status

COMMUNICATION:
  sms_send — send SMS. params: {to, message}
  sms_read — read inbox messages
  sms_conversation — read thread with contact. params: {address}
  contacts_read — search contacts. params: {name?}

SCREEN & CAPTURE:
  read_text_on_screen, describe_screen, screenshot, camera_capture, screen_record_start, notification_read

FILES & CLIPBOARD:
  file_read, file_write, file_open, clipboard_write, clipboard_read, share_content

CREATION:
  note_create, alarm_set, timer_set, reminder_create, calendar_create, image_generate, tts

MEMORY & PROFILE:
  memory_recall, knowledge_query, set_user_name, set_user_info
`.trim();

// ─────────────────────────────────────────────────────────────────────────────
// SYSTEM PROMPT
// ─────────────────────────────────────────────────────────────────────────────
async function buildSystemPrompt(): Promise<string> {
  const now = new Date();
  const dateStr = now.toLocaleDateString('en-US', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' });
  const timeStr = now.toLocaleTimeString('en-US', { hour: '2-digit', minute: '2-digit' });

  // Build environment snapshot — phone state + connected devices
  let envSnapshot = '';
  try {
    const { Platform } = require('react-native');
    if (Platform.OS === 'android') {
      const parts: string[] = [];
      try {
        const Battery = require('expo-battery');
        const level = await Battery.getBatteryLevelAsync();
        const state = await Battery.getBatteryStateAsync();
        const charging = state === 2 ? ' (charging)' : '';
        parts.push(`Battery: ${Math.round(level * 100)}%${charging}`);
      } catch {}
      try {
        const AgentNative = (await import('../native/AgentNative')).default;
        const apps = await AgentNative.getInstalledApps();
        parts.push(`${apps.length} apps installed`);
        // Connected Bluetooth devices
        const btDevices = await AgentNative.getConnectedBluetoothDevices();
        if (btDevices.length > 0) parts.push(`BT devices: ${btDevices.join(', ')}`);
        // WiFi network
        const ssid = await AgentNative.getWifiSSID();
        if (ssid) parts.push(`WiFi: ${ssid}`);
      } catch {}
      try {
        const AppCtrl = require('../native/AppController').default;
        const state = await AppCtrl.getSystemStateSnapshot();
        const parsed = JSON.parse(state);
        if (parsed.wifi) parts.push(`WiFi radio: ${parsed.wifi}`);
        if (parsed.bluetooth) parts.push(`BT radio: ${parsed.bluetooth}`);
      } catch {}
      if (parts.length > 0) envSnapshot = `\nPHONE STATE: ${parts.join(' | ')}`;
    }
  } catch {}

  // Load learned knowledge summary — user profile + people + devices + preferences
  let knowledgeSummary = '';
  try {
    const { getAgentCoreInstance } = await import('./AgentCore');
    const core = getAgentCoreInstance();
    const graph = core?.getCortex()?.getKnowledgeGraph();
    const vault = core?.getVault?.();
    if (graph) {
      const items: string[] = [];
      // User profile
      const userName = vault ? await vault.get('user_preferred_name').catch(() => null) : null;
      const userEmail = vault ? await vault.get('user_email').catch(() => null) : null;
      const userPhone = vault ? await vault.get('user_phone').catch(() => null) : null;
      if (userName) items.push(`User: ${userName}`);
      if (userEmail) items.push(`Email: ${userEmail}`);
      if (userPhone) items.push(`Phone: ${userPhone}`);
      // Known people
      const people = graph.getByType('person');
      for (const p of people.slice(0, 5)) {
        const rels = graph.getRelations(p.id);
        const nums = rels.filter(r => r.relation.type === 'has_number').map(r => r.targetEntity.name);
        const emails = rels.filter(r => r.relation.type === 'has_email').map(r => r.targetEntity.name);
        const detail = nums.length ? nums[0] : emails.length ? emails[0] : '';
        items.push(`${p.name}${detail ? ' (' + detail + ')' : ''}`);
      }
      // Known devices
      const devices = graph.getByType('device' as any);
      for (const d of devices.slice(0, 3)) items.push(`Device: ${d.name}${d.properties?.type ? ' (' + d.properties.type + ')' : ''}`);
      // Preferences
      const prefs = graph.getByType('preference');
      for (const pref of prefs.slice(0, 3)) items.push(`Prefers: ${pref.name}`);
      if (items.length > 0) knowledgeSummary = `\nKNOWN ABOUT USER: ${items.join(', ')}`;
    }
    // Check for proactive suggestions — things the agent noticed
    const proactive = core?.getProactiveEngine?.();
    if (proactive) {
      const active = proactive.getActive().filter((s: any) => s.urgency === 'high' || s.urgency === 'medium');
      if (active.length > 0) {
        const notices = active.slice(0, 2).map((s: any) => s.title).join('; ');
        knowledgeSummary += `\nNOTICED: ${notices}`;
      }
    }
  } catch {}

  return `You are Ultra — a capable, concise AI agent controlling this Android phone.
${envSnapshot}${knowledgeSummary}
TODAY: ${dateStr} at ${timeStr}

FORMAT: To use a tool: {"tool":"name","params":{...}} — To talk: plain text. ONE tool call per response.

${TOOLS}

RULES:
1. Understand what the user WANTS, break it into steps, execute each with a tool call. You get up to 12 tool calls.
2. ALWAYS prefer direct tools over UI automation: toggles > app_launch > react_navigate. Only use react_navigate when you need to interact INSIDE an app.
3. When web_search returns text results, READ THEM and answer directly. Do NOT open a browser to see results you already have. MAX 2 web_searches per task — after 2 searches, use what you have.
4. After every tool call, VERIFY the result. If it failed, try a different approach. If the same tool fails twice, stop and tell the user.
5. Read screen content (read_text_on_screen) to gather data, then use it in the next tool call. Example: read address on screen → sms_send it.
6. When you have enough information to answer, STOP calling tools and give a clear, complete answer.
7. If you hit a login screen, captcha, or permission dialog: STOP and ask the user to handle it.
8. NEVER send messages or make calls unless the user EXPLICITLY asks. Do NOT reply to SMS threads or call found numbers.`;
}

// ─────────────────────────────────────────────────────────────────────────────
// HELPERS
// ─────────────────────────────────────────────────────────────────────────────
// TOOL INFERENCE — when the model responds as plain text instead of JSON,
// detect common action patterns from the user's original request and
// synthesize the appropriate tool call.
// ─────────────────────────────────────────────────────────────────────────────
function inferToolFromText(userInput: string): { tool: string; params: Record<string, any> } | null {
  const u = userInput.toLowerCase().trim();
  const raw = userInput.trim();

  // ── TOGGLES ──────────────────────────────────────────────────────────
  if (/\b(flashlight|torch|flash)\b/.test(u)) {
    const state = /\b(off|disable)\b/.test(u) ? 'off' : /\b(on|enable)\b/.test(u) ? 'on' : undefined;
    return { tool: 'flashlight_toggle', params: state ? { state } : {} };
  }
  if (/\b(wi-?fi|wifi)\b/.test(u) && /\b(toggle|turn|switch|enable|disable|on|off)\b/.test(u)) {
    return { tool: 'wifi_toggle', params: {} };
  }
  if (/\bbluetooth\b/.test(u) && /\b(toggle|turn|switch|enable|disable|on|off)\b/.test(u)) {
    return { tool: 'bluetooth_toggle', params: {} };
  }
  if (/\b(airplane|flight)\s*mode\b/.test(u) && /\b(toggle|turn|switch|enable|disable|on|off)\b/.test(u)) {
    return { tool: 'airplane_mode', params: {} };
  }
  if (/\b(do not disturb|dnd|don'?t disturb)\b/.test(u)) {
    return { tool: 'do_not_disturb', params: {} };
  }

  // ── VOLUME / BRIGHTNESS ──────────────────────────────────────────────
  const volMatch = u.match(/\bvolume\b.*?(\d+)/);
  if (volMatch) return { tool: 'volume_set', params: { level: parseInt(volMatch[1], 10) } };
  if (/\bvolume\s+(up|down)\b/.test(u)) return { tool: 'volume_set', params: { direction: u.includes('up') ? 'up' : 'down' } };
  if (/\b(mute|unmute|silence)\b/.test(u)) return { tool: 'volume_set', params: { level: 0 } };
  const brightMatch = u.match(/\bbright(ness)?\b.*?(\d+)/);
  if (brightMatch) return { tool: 'brightness_set', params: { level: parseInt(brightMatch[2], 10) } };
  if (/\bbright(ness)?\s+(up|down|higher|lower)\b/.test(u)) return { tool: 'brightness_set', params: { direction: /up|higher/.test(u) ? 'up' : 'down' } };

  // ── MEDIA ────────────────────────────────────────────────────────────
  if (/\b(play|pause|resume)\s*(music|song|audio|media|track)?\b/.test(u) && !/\bplay\s*store\b/.test(u)) return { tool: 'media_play', params: {} };
  if (/\b(next|skip)\s*(track|song)?\b/.test(u)) return { tool: 'media_next', params: {} };

  // ── WEATHER ──────────────────────────────────────────────────────────
  if (/\b(weather|forecast|temperature outside|how (hot|cold|warm))\b/.test(u)) {
    const locMatch = raw.match(/(?:weather|forecast|temperature)\s+(?:in|at|for|near)\s+(.+)/i);
    return { tool: 'weather', params: locMatch ? { location: locMatch[1].trim() } : {} };
  }

  // ── WEB SEARCH ───────────────────────────────────────────────────────
  if (/\b(search|google|look\s*up|search\s*the\s*web)\b/.test(u) && /\b(for|web|internet|online|about)\b/.test(u)) {
    const queryMatch = raw.match(/(?:search|google|look\s*up)\s+(?:the\s+)?(?:web\s+)?(?:for\s+|about\s+)?(.+)/i);
    return { tool: 'web_search', params: { query: queryMatch ? queryMatch[1].trim() : userInput } };
  }

  // ── NEWS ─────────────────────────────────────────────────────────────
  if (/\b(news|headlines|what'?s happening)\b/.test(u)) {
    const topicMatch = raw.match(/\bnews\s+(?:about|on|for)\s+(.+)/i);
    return { tool: 'news_headlines', params: topicMatch ? { topic: topicMatch[1].trim() } : {} };
  }

  // ── DEVICE INFO / BATTERY / LOCATION ─────────────────────────────────
  if (/\bbatter(y|ies)\b/.test(u)) return { tool: 'battery_status', params: {} };
  if (/\b(device|phone|model|what am i using)\b/.test(u) && /\b(info|name|what|which|am i|specs?)\b/.test(u)) return { tool: 'device_info', params: {} };
  if (/\b(system|cpu|ram|memory|storage)\b/.test(u) && /\b(info|status|usage|how much|free)\b/.test(u)) return { tool: 'system_info', params: {} };
  if (/\b(location|where am i|gps|coordinates)\b/.test(u)) return { tool: 'device_location', params: {} };

  // ── COMMUNICATION ────────────────────────────────────────────────────
  const smsMatch = raw.match(/\b(?:send|text)\s+(?:a\s+)?(?:message|text|sms)\s+to\s+(.+?)(?:\s+(?:saying|that says|:)\s+(.+))?$/i)
    || raw.match(/\btext\s+(.+?)\s+(?:saying|that says|:)\s+(.+)$/i);
  if (smsMatch) return { tool: 'sms_send', params: { to: smsMatch[1].trim(), message: (smsMatch[2] || '').trim() } };
  if (/\b(read|show|check)\s*(my\s+)?(messages?|texts?|sms|inbox)\b/.test(u)) return { tool: 'sms_read', params: { limit: 10 } };
  if (/\b(messages?|texts?|conversation)\s+(?:with|from)\s+(.+)/i.test(u)) {
    const convMatch = raw.match(/(?:messages?|texts?|conversation)\s+(?:with|from)\s+(.+)/i);
    return { tool: 'sms_conversation', params: { address: convMatch ? convMatch[1].trim() : '' } };
  }
  if (/\b(contacts?|address\s*book|phone\s*book)\b/.test(u) && /\b(read|show|list|find|search|who)\b/.test(u)) {
    const nameMatch = raw.match(/(?:contact|find)\s+(.+)/i);
    return { tool: 'contacts_read', params: nameMatch ? { name: nameMatch[1].trim() } : {} };
  }

  // ── SCREEN READING (before clipboard — "read" must not match clipboard_read) ──
  if (/\b(what'?s?\s+on\s+(?:the\s+)?screen|read\s+(?:the\s+)?screen|what\s+(?:do\s+)?(?:i|you)\s+see)\b/.test(u)) return { tool: 'read_text_on_screen', params: {} };
  if (/\b(describe|what'?s\s+showing|what\s+is\s+this)\b/.test(u) && /\bscreen\b/.test(u)) return { tool: 'describe_screen', params: {} };

  // ── CLIPBOARD ────────────────────────────────────────────────────────
  if (/\b(copy|clipboard)\b/.test(u) && /\b(to clipboard|copy)\b/.test(u)) {
    const textMatch = raw.match(/(?:copy)\s+(?:this\s+)?(?:to\s+clipboard\s*:?\s*)?(.+?)(?:\s+to\s+clipboard)?$/i);
    return { tool: 'clipboard_write', params: { text: textMatch ? textMatch[1].trim() : '' } };
  }
  if (/\b(paste|what'?s\s+(?:on|in)\s+(?:the\s+)?clipboard)\b/.test(u)) return { tool: 'clipboard_read', params: {} };

  // ── FILES ────────────────────────────────────────────────────────────
  if (/\b(read|show|cat|view)\s+(?:the\s+)?(?:file|document)\b/.test(u)) {
    const pathMatch = raw.match(/(?:read|show|view)\s+(?:the\s+)?(?:file\s+)?(.+)/i);
    return { tool: 'file_read', params: { path: pathMatch ? pathMatch[1].trim() : '' } };
  }
  if (/\b(write|save|create)\s+(?:a\s+)?(?:file|note|document)\b/.test(u)) {
    const writeMatch = raw.match(/(?:write|save|create)\s+(?:a\s+)?(?:file|note|document)\s+(?:called\s+)?(.+?)(?:\s+(?:with|containing|:)\s+(.+))?$/i);
    return { tool: 'file_write', params: { filename: writeMatch ? writeMatch[1].trim() : 'note.txt', content: writeMatch?.[2]?.trim() || '' } };
  }
  if (/\bshare\b/.test(u)) {
    const shareMatch = raw.match(/share\s+(.+)/i);
    return { tool: 'share_content', params: { content: shareMatch ? shareMatch[1].trim() : '' } };
  }

  // ── ALARMS / TIMERS / REMINDERS / CALENDAR ───────────────────────────
  if (/\b(alarm)\b/.test(u)) {
    const timeMatch = raw.match(/(?:alarm)\s+(?:for|at)\s+(.+)/i) || raw.match(/(?:set|create)\s+(?:an?\s+)?alarm\s+(.+)/i);
    return { tool: 'alarm_set', params: { time: timeMatch ? timeMatch[1].trim() : '' } };
  }
  if (/\btimer\b/.test(u)) {
    const durMatch = raw.match(/(?:timer)\s+(?:for|of)\s+(.+)/i) || raw.match(/(?:set|start)\s+(?:a\s+)?timer\s+(.+)/i);
    return { tool: 'timer_set', params: { duration: durMatch ? durMatch[1].trim() : '' } };
  }
  if (/\bremind(er)?\b/.test(u)) {
    const remMatch = raw.match(/remind\s+(?:me\s+)?(?:to\s+)?(.+)/i);
    return { tool: 'reminder_create', params: { text: remMatch ? remMatch[1].trim() : userInput } };
  }
  if (/\b(calendar|event|schedule|appointment)\b/.test(u) && /\b(create|add|schedule|new|set)\b/.test(u)) {
    const evtMatch = raw.match(/(?:create|add|schedule)\s+(?:a\s+)?(?:calendar\s+)?(?:event\s+)?(?:for\s+|called\s+)?(.+)/i);
    return { tool: 'calendar_create', params: { title: evtMatch ? evtMatch[1].trim() : '' } };
  }

  // ── NOTES ────────────────────────────────────────────────────────────
  if (/\b(note|write\s+down|jot\s+down)\b/.test(u)) {
    const noteMatch = raw.match(/(?:note|write down|jot down)\s*:?\s*(.+)/i) || raw.match(/(?:create|make)\s+(?:a\s+)?note\s*:?\s*(.+)/i);
    return { tool: 'note_create', params: { content: noteMatch ? noteMatch[1].trim() : userInput } };
  }

  // ── CAMERA / SCREENSHOT / SCREEN RECORD ──────────────────────────────
  if (/\bscreenshot\b/.test(u)) return { tool: 'screenshot', params: {} };
  if (/\b(take\s+a\s+photo|take\s+a\s+picture|camera|selfie|capture\s+photo)\b/.test(u)) return { tool: 'camera_capture', params: {} };
  if (/\b(screen\s*record|record\s+(?:the\s+)?screen|start\s+recording)\b/.test(u)) return { tool: 'screen_record_start', params: {} };

  // ── IMAGE / TTS / VIDEO ──────────────────────────────────────────────
  if (/\b(generate|create|make|draw)\s+(?:an?\s+)?(?:image|picture|art|illustration)\b/.test(u)) {
    const promptMatch = raw.match(/(?:generate|create|make|draw)\s+(?:an?\s+)?(?:image|picture|art|illustration)\s+(?:of\s+)?(.+)/i);
    return { tool: 'image_generate', params: { prompt: promptMatch ? promptMatch[1].trim() : userInput } };
  }
  if (/\b(read\s+aloud|say\s+this|speak|text\s+to\s+speech|tts)\b/.test(u)) {
    const ttsMatch = raw.match(/(?:read aloud|say|speak)\s+(.+)/i);
    return { tool: 'tts', params: { text: ttsMatch ? ttsMatch[1].trim() : userInput } };
  }

  // (screen reading moved above clipboard)

  // ── NOTIFICATIONS ────────────────────────────────────────────────────
  if (/\b(notification|notifications)\b/.test(u) && /\b(read|show|check|any|what)\b/.test(u)) return { tool: 'notification_read', params: {} };

  // ── USER PROFILE / MEMORY ────────────────────────────────────────────
  const nameMatch = raw.match(/\bmy\s+name\s+is\s+(.+)/i) || raw.match(/\bcall\s+me\s+(.+)/i);
  if (nameMatch) return { tool: 'set_user_name', params: { name: nameMatch[1].trim() } };
  if (/\bmy\s+(email|phone|address)\s+is\s+/i.test(u)) {
    const infoMatch = raw.match(/my\s+(email|phone|address)\s+is\s+(.+)/i);
    if (infoMatch) return { tool: 'set_user_info', params: { [infoMatch[1].toLowerCase()]: infoMatch[2].trim() } };
  }
  if (/\b(remember|recall|what\s+do\s+you\s+know\s+about|do\s+you\s+know)\b/.test(u)) {
    const memMatch = raw.match(/(?:remember|recall|know about)\s+(.+)/i);
    return { tool: 'memory_recall', params: { query: memMatch ? memMatch[1].trim() : userInput } };
  }
  if (/\bknowledge\b/.test(u) && /\b(query|what|search)\b/.test(u)) {
    const kgMatch = raw.match(/knowledge\s+(?:query\s+)?(.+)/i);
    return { tool: 'knowledge_query', params: { query: kgMatch ? kgMatch[1].trim() : userInput } };
  }

  // ── APP MANAGEMENT ───────────────────────────────────────────────────
  if (/\b(install|download|get)\s+(?:the\s+)?(.+?)(?:\s+app)?\s*$/i.test(u)) {
    const installMatch = raw.match(/(?:install|download|get)\s+(?:the\s+)?(.+?)(?:\s+app)?\s*$/i);
    return { tool: 'install_app', params: { appName: installMatch ? installMatch[1].trim() : '' } };
  }
  if (/\b(app\s*info|info\s+(?:about|for|on)\s+(?:the\s+)?app)\b/.test(u)) {
    const appInfoMatch = raw.match(/(?:app\s*info|info\s+(?:about|for|on))\s+(?:the\s+)?(.+)/i);
    return { tool: 'app_info', params: { target: appInfoMatch ? appInfoMatch[1].trim() : '' } };
  }

  // ── URL ──────────────────────────────────────────────────────────────
  const urlMatch = u.match(/\b(?:open|go to|visit|navigate to)\s+(https?:\/\/\S+)/i)
    || u.match(/\b(?:open|go to|visit)\s+([\w-]+\.(?:com|org|net|io|co|ai|dev|gov|edu)\S*)/i);
  if (urlMatch) {
    const url = urlMatch[1].startsWith('http') ? urlMatch[1] : `https://${urlMatch[1]}`;
    return { tool: 'open_url', params: { url } };
  }

  // ── REACT NAVIGATE (do something inside an app) ──────────────────────
  const reactMatch = raw.match(/\b(?:search|find|look for|order|book|buy|play)\s+(.+?)\s+(?:on|in|using|with)\s+(.+)/i);
  if (reactMatch) {
    return { tool: 'react_navigate', params: { goal: `${reactMatch[1].trim()}`, appHint: reactMatch[2].trim() } };
  }

  // ── APP LAUNCH (simple "open X" — must be last, catches broadly) ─────
  const openMatch = raw.match(/\b(?:open|launch|start|run)\s+(?:the\s+)?(.+)/i);
  if (openMatch) {
    const target = openMatch[1].trim();
    // Don't match if it's a URL (handled above) or a file
    if (!/^https?:\/\//.test(target) && !/\.\w{2,4}$/.test(target)) {
      return { tool: 'app_launch', params: { target } };
    }
  }

  return null;
}

function uid(): string {
  return `${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 7)}`;
}

function parseToolCall(text: string): { tool: string; params: Record<string, any> } | null {
  const cleaned = text.replace(/```json|```/g, '').trim();
  const start = cleaned.indexOf('{');
  if (start < 0) return null;
  let depth = 0;
  let end = -1;
  for (let i = start; i < cleaned.length; i++) {
    if (cleaned[i] === '{') depth++;
    else if (cleaned[i] === '}') { depth--; if (depth === 0) { end = i; break; } }
  }
  if (end < 0) return null;
  try {
    const obj = JSON.parse(cleaned.slice(start, end + 1));
    if (obj?.tool && typeof obj.tool === 'string') {
      return { tool: obj.tool, params: obj.params || {} };
    }
  } catch {}
  return null;
}

function formatToolResult(result: any): string {
  if (!result) return 'No result returned.';
  if (typeof result === 'string') return result;
  if (result.error) return `Error: ${result.error}`;
  if (result.summary) return result.summary;
  try {
    const str = JSON.stringify(result);
    return str.length > 2500 ? str.slice(0, 2500) + '...' : str;
  } catch {
    return String(result);
  }
}

// P4: Structured tool result feedback with turn budget, plan context, and verification
function buildToolFeedback(
  toolName: string,
  resultText: string,
  turn: number,
  maxTurns: number,
  plan: string[] | null,
  planStep: number,
  isSuccess: boolean,
): string {
  const status = isSuccess ? 'success' : 'failed';
  const turnsLeft = maxTurns - turn - 1;
  let feedback = `[RESULT: ${toolName}] STATUS: ${status}\nDATA: ${resultText}`;
  feedback += `\nTURNS_LEFT: ${turnsLeft}/${maxTurns}`;
  if (plan && planStep < plan.length) {
    feedback += `\nCURRENT_STEP: ${planStep + 1}/${plan.length} — "${plan[planStep]}"`;
  }
  // Post-action verification for ambiguous results
  if (isSuccess && !resultText.startsWith('Error:') && toolName === 'react_navigate') {
    feedback += '\nVERIFY: Check the result — did this achieve the intended outcome? If not, try a different approach.';
  }
  // Web search special handling
  if (toolName === 'web_search' && resultText.includes('Search results')) {
    feedback += '\nYou have the search results above. Answer the user directly. Do NOT open a browser.';
  } else {
    feedback += '\nDECIDE: Answer the user, or call the next tool.';
  }
  return feedback;
}

// ─────────────────────────────────────────────────────────────────────────────
// BRAIN EXECUTOR
// ─────────────────────────────────────────────────────────────────────────────
// Multi-step intent detection — does the user want more than one action?
function isMultiStepIntent(input: string): boolean {
  const u = input.toLowerCase();
  const actionVerbs = /\b(find|search|open|navigate|send|text|call|set|create|make|play|turn|toggle|install|download|order|book|buy|get|show|take|go)\b/g;
  const matches = u.match(actionVerbs);
  if (matches && matches.length >= 2) return true;
  // Connectors that imply multi-step: "and then", "then", "after that", "and"
  if (/\b(and then|then|after that)\b/.test(u)) return true;
  // "find X and send/text/navigate" patterns
  if (/\b(find|search|look up)\b.*\b(send|text|navigate|go|open|call)\b/.test(u)) return true;
  return false;
}

export class BrainExecutor {
  constructor(
    private ai: ModelRouter,
    private executor: TaskExecutor,
    private conversations: ConversationManager,
  ) {}

  // Plan a multi-step task before entering the tool loop
  private async planTask(
    userInput: string,
    messages: Array<{ role: string; content: string }>,
    taskId: string,
  ): Promise<string[] | null> {
    try {
      const planPrompt = [
        { role: 'system', content: 'You are a task planner. Given a user request and available tools, produce a concise plan of 2-5 concrete steps. Return ONLY a JSON array of strings. No explanation.' },
        { role: 'user', content: `Task: "${userInput}"\nAvailable tools: app_launch, react_navigate, web_search, web_research, weather, news_headlines, device_location, device_info, system_info, battery_status, contacts_read, sms_send, sms_read, sms_conversation, camera_capture, screenshot, note_create, alarm_set, timer_set, reminder_create, calendar_create, file_read, file_write, open_url, share_content, clipboard_write, clipboard_read, volume_set, brightness_set, flashlight_toggle, wifi_toggle, bluetooth_toggle, airplane_mode, do_not_disturb, media_play, media_next, image_generate, tts, read_text_on_screen, describe_screen, notification_read, memory_recall, knowledge_query, set_user_name, set_user_info, install_app, app_info` },
      ];
      const result = await this.ai.completeWithConversation(planPrompt, {
        taskId,
        agentId: 'brain',
        maxTokens: 500,
        temperature: 0.1,
      });
      const text = result.content.trim();
      // Extract JSON array from response
      const arrMatch = text.match(/\[[\s\S]*\]/);
      if (arrMatch) {
        const steps = JSON.parse(arrMatch[0]);
        if (Array.isArray(steps) && steps.length >= 2 && steps.every((s: any) => typeof s === 'string')) {
          console.warn(`[BRAIN] PLAN: ${steps.length} steps: ${steps.join(' → ')}`);
          return steps;
        }
      }
    } catch (e: any) {
      console.warn(`[BRAIN] planTask failed: ${e.message}`);
    }
    return null;
  }

  async execute(
    userInput: string,
    conversationId: string,
    taskId: string,
    approvedAction?: boolean,
    pendingState?: { messages: Array<{ role: string; content: string }>; toolCall: { tool: string; params: Record<string, any> } },
  ): Promise<UltraExecutionResult> {
    DebugLog.systemEvent('BrainExecutor', `START input="${userInput.slice(0, 80)}" approved=${!!approvedAction}`);

    // ── RESUME PATH: user approved a pending destructive action ──────────
    if (approvedAction && pendingState) {
      DebugLog.systemEvent('BrainExecutor', `RESUMING from pending: ${pendingState.toolCall.tool}`);
      const { messages, toolCall } = pendingState;

      let toolResult: any;
      try {
        toolResult = await (this.executor as any).execWithParams(toolCall.tool, toolCall.params, userInput, taskId);
      } catch (e: any) {
        toolResult = { error: e.message };
      }
      const resultText = formatToolResult(toolResult);
      DebugLog.systemEvent('BrainExecutor', `RESUME TOOL RESULT: ${resultText.slice(0, 120)}`);

      // Continue the loop with the result
      messages.push({ role: 'assistant', content: JSON.stringify({ tool: toolCall.tool, params: toolCall.params }) });
      messages.push({ role: 'user', content: `Tool result for ${toolCall.tool}:\n${resultText}\n\nNow respond to the user or call another tool.` });

      return this.runLoop(userInput, conversationId, taskId, messages, 1);
    }

    // ── NORMAL PATH: save user message and build fresh context ───────────
    // Read the current screen so the brain knows what the user is looking at
    let screenContext = '';
    try {
      const { Platform } = require('react-native');
      if (Platform.OS === 'android') {
        const AppCtrl = require('../native/AppController').default;
        if (AppCtrl.isAvailable()) {
          const flat = await AppCtrl.getScreenContentFlat();
          const nodes = JSON.parse(flat);
          if (Array.isArray(nodes) && nodes.length > 0) {
            const labels = nodes
              .filter((n: any) => (n.t || n.d || '').trim())
              .slice(0, 15)
              .map((n: any) => (n.t || n.d || '').trim());
            if (labels.length > 0) {
              const pkg = await AppCtrl.getActivePackage().catch(() => 'unknown');
              screenContext = `\n[Current screen: ${pkg} — ${labels.join(' | ')}]`;
            }
          }
        }
      }
    } catch {}

    await this.conversations.addMessage(conversationId, {
      id: uid(),
      role: 'user',
      content: userInput,
      createdAt: Date.now(),
    });

    let systemPrompt = await buildSystemPrompt();
    // P6: Inject task memory hints (reliability warnings + known shortcuts)
    const memoryHints = await getTaskMemoryHints(userInput);
    if (memoryHints) systemPrompt += memoryHints;
    const { payload } = await this.buildContextFromConversation(conversationId, systemPrompt);
    // Inject screen context + knowledge into the last user message
    let knowledgeContext = '';
    try {
      const { getAgentCoreInstance: getCore } = await import('./AgentCore');
      const coreInst = getCore();
      const graph = coreInst?.getCortex()?.getKnowledgeGraph();
      if (graph) {
        const resolved = graph.resolve(userInput);
        if (resolved.entity) {
          const relStr = resolved.relations.slice(0, 5).map(r => `${r.relation.type}: ${r.targetEntity.name}`).join(', ');
          knowledgeContext = `\n[Known: ${resolved.entity.name} (${resolved.entity.type})${relStr ? ' — ' + relStr : ''}]`;
        }
      }
    } catch {}
    const extraContext = screenContext + knowledgeContext;
    if (extraContext && payload.length > 0) {
      const lastMsg = payload[payload.length - 1];
      if (lastMsg.role === 'user') {
        lastMsg.content = lastMsg.content + extraContext;
      }
    }
    return this.runLoop(userInput, conversationId, taskId, payload, 0);
  }

  private async runLoop(
    userInput: string,
    conversationId: string,
    taskId: string,
    messages: Array<{ role: string; content: string }>,
    resumeTurn: number,
  ): Promise<UltraExecutionResult> {
    const MAX_TOOL_TURNS = 12;
    let finalText = '';
    let lastCapability = '';
    let lastToolResult: any = undefined;
    let hasBeenPushed = false;

    // ── TASK PLANNING: decompose multi-step tasks before tool loop ──────
    let plan: string[] | null = null;
    let planStep = 0;
    if (resumeTurn === 0 && isMultiStepIntent(userInput)) {
      DebugLog.systemEvent('BrainExecutor', 'Multi-step intent detected, planning...');
      plan = await this.planTask(userInput, messages, taskId);
      if (plan) {
        DebugLog.systemEvent('BrainExecutor', `Plan: ${plan.length} steps`);
        // Inject plan into context so the brain knows the strategy
        const planText = plan.map((s, i) => `${i + 1}. ${s}`).join('\n');
        messages.push({ role: 'user', content: `[PLAN for this task]\n${planText}\n\nStart with step 1. Call the appropriate tool.` });
      }
    }

    // Track tool sequence for task memory (P6)
    const toolSequence: Array<{ tool: string; success: boolean }> = [];

    for (let turn = resumeTurn; turn < MAX_TOOL_TURNS; turn++) {
      DebugLog.systemEvent('BrainExecutor', `AI turn ${turn + 1}`);

      // P5: Dynamic maxTokens — more for initial reasoning and final synthesis
      const maxTokens = turn === 0 ? 2000 : turn >= MAX_TOOL_TURNS - 2 ? 2500 : 1500;

      const aiResult = await this.ai.completeWithConversation(messages, {
        taskId,
        agentId: 'brain',
        maxTokens,
        temperature: 0.2,
      });

      const rawResponse = aiResult.content.trim();
      DebugLog.systemEvent('BrainExecutor', `AI response (${rawResponse.length} chars): ${rawResponse.slice(0, 120)}`);

      let toolCall = parseToolCall(rawResponse);

      // ── FALLBACK: if model responded as plain text on turn 0, try deterministic
      // intent detection as a safety net. Trust the model's JSON when it provides it.
      if (!toolCall && turn === 0) {
        const inferred = inferToolFromText(userInput);
        if (inferred) {
          console.warn('[BRAIN] tool_inferred:', inferred.tool, 'from user input (model returned plain text)');
          toolCall = inferred;
        }
      }

      console.warn('[BRAIN] tool_selected:', toolCall ? toolCall.tool : 'NONE (plain text)');

      if (!toolCall) {
        // ── COMPLETION CHECK: did the brain actually finish the job? ──────
        // If the user asked for an ACTION (navigate, send, open, set, create, find)
        // but the brain only returned informational text, push it to follow through.
        // Only push once (turn > 0 means a tool already ran) and only if we have turns left.
        if (turn > 0 && turn < MAX_TOOL_TURNS - 2 && !hasBeenPushed) {
          const actionWords = /\b(navigate|send|text|open|go to|take me|set|create|make|call|play|turn on|turn off|toggle|install|download|share|copy|find me|get me|show me|order|book|buy)\b/i;
          const userWantsAction = actionWords.test(userInput);
          const brainOnlyDescribed = !rawResponse.includes('"tool"') && rawResponse.length < 800;
          if (userWantsAction && brainOnlyDescribed) {
            hasBeenPushed = true;
            console.warn('[BRAIN] PUSH: user requested action but brain gave text — pushing to follow through');
            DebugLog.systemEvent('BrainExecutor', `PUSH: brain returned text but user requested action, pushing for follow-through`);
            messages.push({ role: 'assistant', content: rawResponse });
            messages.push({ role: 'user', content: 'You described what to do but didn\'t do it. Use a tool to actually complete the action. Don\'t explain — execute.' });
            continue;
          }
        }
        finalText = rawResponse;
        DebugLog.systemEvent('BrainExecutor', `TEXT response, done after ${turn + 1} turns`);
        break;
      }

      // ── CONFIRMATION GATE ─────────────────────────────────────────────
      if (requiresConfirmation(toolCall.tool, toolCall.params)) {
        const description = describeAction(toolCall.tool, toolCall.params);
        DebugLog.systemEvent('BrainExecutor', `CONFIRMATION REQUIRED: ${description}`);

        // Save state so we can resume exactly here if approved
        const pendingState = {
          messages: [...messages, { role: 'assistant', content: rawResponse }],
          toolCall,
        };

        // Add approval message to the conversation so it renders in chat
        const approvalText = `Approval required: **${description}**`;
        await this.conversations.addMessage(conversationId, {
          id: uid(),
          role: 'assistant',
          content: approvalText,
          createdAt: Date.now(),
          source: 'ultra',
        });

        return {
          type: 'approval_required',
          message: approvalText,
          taskId,
          data: {
            replayUserInput: userInput,
            pendingState,
          },
        };
      }

      // ── EXECUTE TOOL ──────────────────────────────────────────────────
      DebugLog.systemEvent('BrainExecutor', `TOOL CALL: ${toolCall.tool} params=${JSON.stringify(toolCall.params).slice(0, 120)}`);
      lastCapability = toolCall.tool;

      let toolResult: any;
      try {
        toolResult = await (this.executor as any).execWithParams(toolCall.tool, toolCall.params, userInput, taskId);
      } catch (e: any) {
        toolResult = { error: e.message };
      }

      lastToolResult = toolResult;
      const resultText = formatToolResult(toolResult);
      const isFailure = resultText.startsWith('Error:') || resultText.startsWith('Could not');
      const isSuccess = !isFailure;
      DebugLog.systemEvent('BrainExecutor', `TOOL RESULT (${isSuccess ? 'ok' : 'fail'}): ${resultText.slice(0, 120)}`);

      // Track tool sequence for task memory
      toolSequence.push({ tool: toolCall.tool, success: isSuccess });
      // P6: Record tool reliability
      recordToolResult(toolCall.tool, isSuccess, isFailure ? resultText.slice(0, 100) : undefined).catch(() => {});

      // Advance plan step on success
      if (isSuccess && plan && planStep < plan.length) {
        planStep++;
        DebugLog.systemEvent('BrainExecutor', `Plan step advanced to ${planStep}/${plan.length}`);
      }

      // Learn from every interaction — build persistent knowledge
      try {
        const { getAgentCoreInstance } = await import('./AgentCore');
        const core = getAgentCoreInstance();
        const graph = core?.getCortex()?.getKnowledgeGraph();
        if (graph) {
          graph.learnFromInteraction(userInput, toolCall.tool, resultText.slice(0, 300)).catch(() => {});
        }
      } catch {}

      messages.push({ role: 'assistant', content: rawResponse });

      // Stuck detector: if the same tool fails twice in a row, stop and report honestly
      if (isFailure) {
        const prevMsg = messages.length >= 4 ? messages[messages.length - 3].content : '';
        const prevWasSameTool = prevMsg.includes(`"tool":"${toolCall.tool}"`);
        if (prevWasSameTool) {
          finalText = `That didn't work — ${toolCall.tool} failed twice. ${resultText.slice(0, 150)}`;
          DebugLog.systemEvent('BrainExecutor', `STUCK STOP: ${toolCall.tool} failed twice, stopping`);
          break;
        }
      }

      // P4: Structured feedback with turn budget, plan context, and verification
      const feedback = buildToolFeedback(toolCall.tool, resultText, turn, MAX_TOOL_TURNS, plan, planStep, isSuccess);
      messages.push({
        role: 'user',
        content: feedback,
      });

      if (turn === MAX_TOOL_TURNS - 1) {
        finalText = `Ran ${toolCall.tool}: ${resultText}`;
      }
    }

    // P6: Save successful tool sequence as shortcut for future reuse
    const successfulTools = toolSequence.filter(t => t.success).map(t => t.tool);
    if (successfulTools.length >= 2 && finalText && !finalText.startsWith('That didn\'t work')) {
      recordTaskShortcut(userInput, successfulTools).catch(() => {});
    }

    if (finalText) {
      // Extract image path from the last tool result if present
      let lastToolData: any = undefined;
      if (lastToolResult && typeof lastToolResult === 'object' && lastToolResult.path) {
        lastToolData = { path: lastToolResult.path };
      }
      await this.conversations.addMessage(conversationId, {
        id: uid(),
        role: 'assistant',
        content: finalText,
        createdAt: Date.now(),
        source: 'ultra',
        meta: { capability: lastCapability || undefined, data: lastToolData },
      });
    }

    DebugLog.systemEvent('BrainExecutor', `DONE taskId=${taskId}`);
    return { type: 'action_result', message: finalText || 'Done.', taskId, data: lastToolResult && typeof lastToolResult === 'object' ? { path: lastToolResult.path } : undefined };
  }

  private async buildContextFromConversation(
    conversationId: string,
    systemPrompt: string,
  ): Promise<{ payload: Array<{ role: string; content: string }> }> {
    const conv = await this.conversations.loadConversation(conversationId);
    if (!conv || conv.messages.length === 0) {
      return { payload: [{ role: 'system', content: systemPrompt }] };
    }

    const allMsgs = conv.messages.map((m: any) => ({ role: m.role, content: m.content }));

    // Priority-based context allocation:
    // 1. Always keep the first user message (original request) and the latest messages
    // 2. Prioritize tool results (contain actionable data) over assistant messages
    // 3. 12KB total budget, 3KB per tool result, 2KB for others
    const MAX_CONV_CHARS = 12000;
    const MAX_TOOL_RESULT_CHARS = 3000;
    const MAX_OTHER_CHARS = 2000;

    // Truncate individual messages based on type
    const truncated = allMsgs.map(m => {
      const isToolResult = m.role === 'user' && m.content.startsWith('[Tool result:');
      const limit = isToolResult ? MAX_TOOL_RESULT_CHARS : MAX_OTHER_CHARS;
      return {
        role: m.role,
        content: m.content.length > limit ? m.content.slice(0, limit) + '...(truncated)' : m.content,
      };
    });

    // Always preserve: first user message + last 10 messages (sliding window)
    let msgs: typeof truncated = [];
    if (truncated.length <= 12) {
      msgs = truncated;
    } else {
      const firstUser = truncated.find(m => m.role === 'user');
      const recent = truncated.slice(-10);
      // Only add first user if it's not already in the recent window
      if (firstUser && !recent.includes(firstUser)) {
        msgs = [firstUser, ...recent];
      } else {
        msgs = recent;
      }
    }

    // Trim from oldest (after first) if still over budget
    let totalChars = msgs.reduce((sum, m) => sum + m.content.length, 0);
    while (totalChars > MAX_CONV_CHARS && msgs.length > 2) {
      totalChars -= msgs[1].content.length;
      msgs.splice(1, 1);
    }

    // Ensure proper role alternation — prefix tool results to distinguish from user messages
    // instead of merging which destroys semantic boundaries
    const alternated: typeof msgs = [];
    for (const m of msgs) {
      if (alternated.length > 0 && alternated[alternated.length - 1].role === m.role) {
        // Same role consecutive — merge with separator to satisfy API role alternation
        alternated[alternated.length - 1].content += '\n---\n' + m.content;
      } else {
        alternated.push({ ...m });
      }
    }

    return { payload: [{ role: 'system', content: systemPrompt }, ...alternated] };
  }
}
