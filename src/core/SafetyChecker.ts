import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import type { ActionPlan, SafetyCheckResult } from '../types/ultra';

const DANGEROUS_PATTERNS: RegExp[] = [
  /\brm\s+-rf\b/i,
  /\bformat\b/i,
  /\bdrop\s+table\b/i,
  /\bdelete\s+\*\b/i,
  /\bwipe\b/i,
  /\breboot\b/i,
  /\bshutdown\b/i,
  /\bsu\b/i,
  /\broot\b/i,
  /\bfork\s*bomb\b/i,
  /\bwhile\s*\(\s*true\s*\)/i,
];

const SCOPE_MAP: Record<string, string[]> = {
  app_launch: [
    'open', 'launch', 'start', 'run', 'camera',
    'play', 'call', 'dial', 'navigate', 'directions', 'drive',
    'search', 'google', 'look up', 'map', 'browse', 'visit',
    'set alarm', 'set timer', 'email', 'mail',
  ],
  sms_send: ['send text', 'sms', 'message', 'text to', 'text'],
  file_read: ['read', 'show', 'open file', 'list files', 'show files'],
  file_delete: ['delete', 'remove'],
  file_write: ['write', 'create file', 'save'],
  file_organize: ['organize', 'move', 'sort', 'make', 'create', 'folder', 'album'],
  contacts_read: ['contacts', 'contact', 'find the number', 'find number', 'number', 'phone number', 'you listed', 'in this chat', 'call it', 'listed'],
  camera_capture: ['photo', 'camera', 'picture', 'take a photo', 'selfie', 'take a picture'],
  media_access: ['photos', 'gallery', 'media', 'images', 'pick', 'choose', 'select', 'count', 'how many', 'show'],
  app_share: ['share'],
  device_location: ['location', 'where am i', 'gps', 'coordinates', 'position', 'device location', 'my location', 'city', 'what city', 'am i in', 'where is', 'near me', 'nearby', 'current location', 'find me'],
  device_info: ['device', 'status', 'info', 'battery', 'ram', 'memory', 'storage', 'phone status', 'device info', 'system info'],
  system_info: ['cpu', 'temp', 'temperature', 'battery', 'ram', 'storage', 'device status', 'about phone', 'phone info', 'device name', 'my name', 'find', 'look in', 'what is', 'tell me'],
  code_generate: ['code', 'generate', 'write code'],
  app_build: ['build', 'compile'],
  app_install: ['install'],
  network_request: ['fetch', 'request', 'download', 'http', 'api'],
  ai_query: ['ask', 'query', 'ai', 'find out', 'how do', 'what is', 'tell me'],
  dependency_resolve: ['resolve', 'dependency', 'dependencies', 'maven', 'download library', 'jar'],
  app_control: ['control', 'interact', 'tap', 'click', 'scroll', 'type into', 'automate'],
  app_test: ['test', 'run tests', 'verify', 'check app', 'e2e'],
  self_modify: ['improve', 'evolve', 'mutate', 'self-improve', 'upgrade yourself', 'self improve'],
  self_replicate: ['replicate', 'reproduce', 'clone', 'spawn', 'offspring', 'self-replicate', 'self replicate'],
  image_generate: ['generate', 'image', 'picture', 'photo', 'draw', 'create image'],
};

const DANGEROUS_CAPABILITIES_REQUIRING_APPROVAL = [
  'app_control',
  'self_modify',
  'self_replicate',
];

const MODERATE_CAPABILITIES = [
  'file_delete',
  'file_write',
  'sms_send',
  'network_request',
  'app_install',
  'device_location',
];

export class SafetyChecker {
  check(userRequest: string, plan: ActionPlan): SafetyCheckResult {
    DebugLog.permissionCheck(plan.capability, 'checked');
    const reasons: string[] = [];
    const blob = `${plan.capability} ${JSON.stringify(plan.params || {})} ${plan.raw || ''}`;

    for (const p of DANGEROUS_PATTERNS) {
      if (p.test(blob)) {
        reasons.push(`Matched dangerous pattern: ${p}`);
      }
    }

    const scopeOk = this.scopeMatches(userRequest, plan.capability, plan.params || {});
    if (!scopeOk) {
      // Exception: URLs are valid for both app_launch and open_url
      const isUrl = /\S+\.(?:com|org|net|io|co|app|dev|ai|edu|gov|me|tv|us|uk|ca|info)(?:\/\S*)?|https?:\/\/|www\./.test(userRequest);
      const isBothValid = isUrl && (plan.capability === 'app_launch' || plan.capability === 'open_url');
      if (!isBothValid) {
        reasons.push(`Scope mismatch: requested "${userRequest}" but planned "${plan.capability}"`);
      }
    }

    if (reasons.length > 0) {
      const blocked = reasons.some(r => r.includes('Scope mismatch'));
      return {
        allowed: !blocked,
        risk: blocked ? 'blocked' : 'dangerous',
        requiresApproval: true,
        reasons,
      };
    }

    if (DANGEROUS_CAPABILITIES_REQUIRING_APPROVAL.includes(plan.capability)) {
      return {
        allowed: true,
        risk: 'dangerous',
        requiresApproval: true,
        reasons: ['Dangerous capability always requires explicit user approval.'],
      };
    }

    const moderate = MODERATE_CAPABILITIES.includes(plan.capability);
    return {
      allowed: true,
      risk: moderate ? 'moderate' : 'safe',
      requiresApproval: moderate,
      reasons: moderate
        ? ['Moderate-risk capability requires explicit execution log and confirmation policy.']
        : [],
    };
  }

  verifyResult(
    plan: ActionPlan,
    result: any
  ): { verified: boolean; issues: string[] } {
    try {
      const { verify } = require('./VerificationRegistry');
      return verify(plan, result);
    } catch {
      const issues: string[] = [];

      if (result === null || result === undefined) {
        issues.push('Result is null or undefined');
        return { verified: false, issues };
      }

      if (typeof result === 'object' && 'success' in result && result.success === false) {
        issues.push(`Capability returned failure: ${result.error || result.message || 'unknown error'}`);
      }

      if (typeof result === 'object' && 'success' in result && result.success === true) {
        if ('data' in result && result.data === undefined) {
          issues.push('Capability returned success but data is undefined');
        }
      }

      if (typeof result === 'string' && result.trim() === '') {
        issues.push('Result is an empty string');
      }

      if (Array.isArray(result) && result.length === 0) {
        issues.push('Result is an empty array — expected data may be missing');
      }

      return { verified: issues.length === 0, issues };
    }
  }

  private scopeMatches(userRequest: string, capability: string, params: Record<string, any> = {}): boolean {
    const t = userRequest.toLowerCase().trim();
    const verbs = SCOPE_MAP[capability];
    if (!verbs) return true;
    if (verbs.some(v => t.includes(v))) return true;

    const target = String(params.target || '').toLowerCase().trim();
    const action = String(params.action || '').toLowerCase().trim();
    const data = String(params.data || '').toLowerCase().trim();
    const query = String(params.query || params.goal || '').toLowerCase().trim();
    const isOrdinalReply = /\b(first|1st|one|1|second|2nd|two|2|third|3rd|three|3|fourth|4th|four|4)\b/.test(t);
    const isShortApproval = /^(yes|yeah|yep|ok|okay|sure|do it|that one|this one)$/i.test(t);

    if (capability === 'app_launch') {
      if (target && (t.includes(target) || target.includes(t))) return true;
      if (/weather/.test(target) && /weather|forecast|temperature|temp|rain|snow/.test(t)) return true;
      if (/maps|map|navigation/.test(target) && /map|maps|navigate|navigation|directions|route|drive/.test(t)) return true;
      if (/call|dial/.test(action) || data.startsWith('tel:')) {
        if (/call|dial|phone|contact/.test(t) || /\d{3,}/.test(t) || isOrdinalReply || isShortApproval) return true;
      }
      if (action.includes('view') && query && (t.includes(query) || query.includes(t))) return true;
    }

    if (capability === 'media_access') {
      if (/photo|photos|image|images|gallery|media/.test(t)) return true;
      if ((params.action === 'count' || params.action === 'list') && /count|how many|number|show|list/.test(t)) return true;
    }

    if (capability === 'device_location' && /where am i|where i am|my location|current location|gps|coordinates/.test(t)) return true;
    if ((capability === 'device_info' || capability === 'system_info') && /battery|ram|memory|storage|network|system|device|status|info|temperature|temp/.test(t)) return true;
    if (capability === 'web_research' && (query ? (t.includes(query) || query.includes(t)) : /research|search|look up|google|find out/.test(t))) return true;
    if (capability === 'react_navigate' && (query ? (t.includes(query) || query.includes(t)) : /go to|open|navigate|search for|find/.test(t))) return true;

    return false;
  }
}
