import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

export interface VerificationResult {
  verified: boolean;
  issues: string[];
}

type Verifier = (plan: { capability: string; params: Record<string, any> }, result: any) => VerificationResult;

const registry: Record<string, Verifier> = {

  app_launch: (_plan, result) => {
    const r = result?.data ?? result;
    if (r?.success === false) return { verified: false, issues: [r?.error || r?.summary || 'Launch failed'] };
    if (r?.launched || r?.action) return { verified: true, issues: [] };
    // Weather redirect: app_launch may return weather data instead of a launch confirmation
    const inner = r?.data ?? {};
    if (inner?.temperature !== undefined || inner?.condition !== undefined) return { verified: true, issues: [] };
    return { verified: false, issues: ['No launch confirmation'] };
  },

  sms_send: (_plan, result) => {
    const r = result?.data ?? result;
    if (r?.requiresDisambiguation) return { verified: true, issues: ['Awaiting contact disambiguation'] };
    const composed = r?.success === true || r?.to;
    return { verified: composed, issues: composed ? [] : ['SMS neither sent nor composed'] };
  },

  alarm_set: (_plan, result) => {
    const r = result?.data ?? result;
    if (r?.success === true) return { verified: true, issues: [] };
    return { verified: false, issues: ['Alarm intent may not have been accepted'] };
  },

  timer_set: (_plan, result) => {
    const r = result?.data ?? result;
    if (r?.success === true) return { verified: true, issues: [] };
    return { verified: false, issues: ['Timer intent may not have been accepted'] };
  },

  flashlight_toggle: (_plan, result) => {
    const r = result?.data ?? result;
    if (r?.success === true) return { verified: true, issues: [] };
    return { verified: false, issues: ['Flashlight call did not confirm success'] };
  },

  device_info: (_plan, result) => {
    const r = result?.data ?? result;
    if (r?.battery || r?.data?.battery || r?.success === true) return { verified: true, issues: [] };
    return { verified: false, issues: ['Device info missing expected fields'] };
  },

  web_search: (_plan, result) => {
    const r = result?.data ?? result;
    if (r?.success === true) return { verified: true, issues: [] };
    return { verified: false, issues: ['Web search intent may have failed'] };
  },

  open_url: (_plan, result) => {
    const r = result?.data ?? result;
    if (r?.success === true) return { verified: true, issues: [] };
    return { verified: false, issues: ['URL open intent may have failed'] };
  },

  calendar_create: (_plan, result) => {
    const r = result?.data ?? result;
    if (r?.success === true) return { verified: true, issues: [] };
    return { verified: false, issues: ['Calendar intent may have failed'] };
  },

  camera_capture: (_plan, result) => {
    const r = result?.data ?? result;
    if (r?.success === true && r?.uri) return { verified: true, issues: [] };
    if (r?.error?.includes('cancelled')) return { verified: false, issues: ['Camera cancelled by user'] };
    return { verified: false, issues: ['No photo URI in result'] };
  },

  device_location: (_plan, result) => {
    const r = result?.data ?? result;
    if (r?.latitude && r?.longitude) return { verified: true, issues: [] };
    return { verified: false, issues: ['No coordinates in location result'] };
  },

  react_navigate: (_plan, result) => {
    const r = result?.data ?? result;
    if (r?.goalAchieved === true) return { verified: true, issues: [] };
    if (r?.steps?.length > 0) return { verified: true, issues: ['Navigation ran but goal achievement uncertain'] };
    return { verified: false, issues: ['ReAct navigation did not achieve goal'] };
  },

  _default: (_plan, result) => {
    if (!result) return { verified: false, issues: ['No result returned'] };
    const r = result?.data ?? result;
    if (r?.error || result?.error) return { verified: false, issues: [r?.error || result?.error] };
    if (r?.success === false) return { verified: false, issues: [r?.summary || r?.error || 'Reported failure'] };
    return { verified: true, issues: [] };
  },
};

export function verify(
  plan: { capability: string; params: Record<string, any> },
  result: any
): VerificationResult {
  const verifier = registry[plan.capability] || registry._default;
  try {
    const vr = verifier(plan, result);
    DebugLog.systemEvent('VerificationRegistry', `${plan.capability}: verified=${vr.verified}${vr.issues.length ? ' issues=' + vr.issues.join(';') : ''}`);
    return vr;
  } catch (err: any) {
    DebugLog.error('VerificationRegistry', `Verifier error for ${plan.capability}: ${err.message}`);
    return { verified: false, issues: [`Verifier threw: ${err.message}`] };
  }
}