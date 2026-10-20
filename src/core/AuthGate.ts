import { Platform } from 'react-native';
import AppController from '../native/AppController';
import { getScreenContentFlat } from '../native/AppController';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

export type AuthType = 'biometric' | 'pin' | 'password' | 'pattern' | 'two_factor' | 'unknown';

export interface AuthWallDetection {
  detected: boolean;
  authType: AuthType;
  appPackage: string;
  confidence: number;
  screenHints: string[];
}

export interface AuthResult {
  success: boolean;
  method: 'user_biometric' | 'credential_vault' | 'skipped' | 'failed';
  message: string;
}

const AUTH_KEYWORDS = [
  'fingerprint', 'biometric', 'face id', 'unlock', 'sign in', 'log in', 'login',
  'password', 'passcode', 'pin', 'enter pin', 'enter password', 'enter passcode',
  'authenticate', 'verify', 'verification', 'confirm identity', 'touch sensor',
  'use fingerprint', 'use face', 'pattern', 'draw pattern', 'swipe to unlock',
  'two-factor', '2fa', 'verification code', 'enter code', 'sms code',
  'security check', 'confirm it\'s you', 'verify your identity',
];

const BIOMETRIC_KEYWORDS = ['fingerprint', 'biometric', 'face id', 'touch sensor', 'use fingerprint', 'use face'];
const PIN_KEYWORDS = ['enter pin', 'passcode', 'pin code', '4-digit', '6-digit'];
const PASSWORD_KEYWORDS = ['password', 'enter password', 'sign in', 'log in'];
const TWO_FA_KEYWORDS = ['verification code', '2fa', 'two-factor', 'sms code', 'enter code'];

export class AuthGate {
  static async detect(): Promise<AuthWallDetection> {
    const result: AuthWallDetection = {
      detected: false, authType: 'unknown', appPackage: '', confidence: 0, screenHints: [],
    };

    if (Platform.OS !== 'android' || !AppController.isAvailable()) return result;

    try {
      result.appPackage = await AppController.getActivePackage();
      const flat = await getScreenContentFlat();
      const nodes = JSON.parse(flat) as Array<{ i: number; t: string; d: string; c: boolean; e: boolean }>;

      const allText = nodes.map(n => ((n.t || '') + ' ' + (n.d || '')).toLowerCase()).join(' ');

      const matchedKeywords: string[] = [];
      for (const kw of AUTH_KEYWORDS) {
        if (allText.includes(kw)) matchedKeywords.push(kw);
      }

      if (matchedKeywords.length === 0) return result;

      result.detected = true;
      result.screenHints = matchedKeywords;
      result.confidence = Math.min(1, matchedKeywords.length * 0.25);

      if (matchedKeywords.some(kw => BIOMETRIC_KEYWORDS.includes(kw))) result.authType = 'biometric';
      else if (matchedKeywords.some(kw => PIN_KEYWORDS.includes(kw))) result.authType = 'pin';
      else if (matchedKeywords.some(kw => TWO_FA_KEYWORDS.includes(kw))) result.authType = 'two_factor';
      else if (matchedKeywords.some(kw => PASSWORD_KEYWORDS.includes(kw))) result.authType = 'password';

      const hasPasswordField = nodes.some(n => n.e && /(password|pin|code|passcode)/i.test((n.t || '') + (n.d || '')));
      if (hasPasswordField && result.confidence < 0.8) result.confidence = 0.8;

      DebugLog.push('SYSTEM' as any, {
        event: 'auth_wall_detected',
        authType: result.authType,
        app: result.appPackage,
        confidence: result.confidence,
        hints: result.screenHints.slice(0, 5),
      });

      return result;
    } catch (e: any) {
      DebugLog.error('AuthGate', `Detection failed: ${e.message}`);
      return result;
    }
  }

  static async handle(
    detection: AuthWallDetection,
    options: {
      userPresent: boolean;
      credentialVault?: {
        hasCredentials: (app: string) => Promise<boolean>;
        autoFill: (app: string, authType: AuthType) => Promise<boolean>;
      };
      onNeedUserAuth?: () => Promise<boolean>;
    },
  ): Promise<AuthResult> {
    DebugLog.push('SYSTEM' as any, {
      event: 'auth_gate_handle',
      authType: detection.authType,
      app: detection.appPackage,
      userPresent: options.userPresent,
      hasVault: !!options.credentialVault,
    });

    if (options.userPresent && detection.authType === 'biometric') {
      if (options.onNeedUserAuth) {
        try {
          const authed = await options.onNeedUserAuth();
          if (authed) {
            await new Promise(r => setTimeout(r, 2000));
            const recheck = await AuthGate.detect();
            if (!recheck.detected) {
              return { success: true, method: 'user_biometric', message: 'Authenticated via biometric' };
            }
          }
        } catch {}
      }

      await new Promise(r => setTimeout(r, 5000));
      const recheck = await AuthGate.detect();
      if (!recheck.detected) {
        return { success: true, method: 'user_biometric', message: 'User completed biometric authentication' };
      }
    }

    if (options.userPresent && (detection.authType === 'password' || detection.authType === 'pin')) {
      return {
        success: false,
        method: 'skipped',
        message: `${detection.appPackage} needs your ${detection.authType}. Please unlock it and I'll continue.`,
      };
    }

    if (!options.userPresent && options.credentialVault) {
      const hasCreds = await options.credentialVault.hasCredentials(detection.appPackage);
      if (hasCreds) {
        DebugLog.push('SYSTEM' as any, { event: 'auth_gate_auto_fill', app: detection.appPackage, authType: detection.authType });
        const filled = await options.credentialVault.autoFill(detection.appPackage, detection.authType);
        if (filled) {
          await new Promise(r => setTimeout(r, 3000));
          const recheck = await AuthGate.detect();
          if (!recheck.detected) {
            return { success: true, method: 'credential_vault', message: 'Authenticated automatically' };
          }
        }
      }
    }

    if (detection.authType === 'two_factor') {
      try {
        const AgentNative = (await import('../native/AgentNative')).default;
        if (AgentNative.readSms) {
          const recent = await AgentNative.readSms(5, '');
          const twoMinAgo = Date.now() - 120_000;
          const codeMsg = recent?.find((m: any) =>
            m.date > twoMinAgo && /\b\d{4,8}\b/.test(m.body) &&
            /(code|verify|otp|confirm|2fa)/i.test(m.body)
          );
          if (codeMsg) {
            const codeMatch = codeMsg.body.match(/\b(\d{4,8})\b/);
            if (codeMatch) {
              DebugLog.push('SYSTEM' as any, { event: 'auth_gate_2fa_sms', codeLength: codeMatch[1].length });
              const flat = await getScreenContentFlat();
              const nodes = JSON.parse(flat) as Array<{ i: number; t: string; d: string; e: boolean; x: number; y: number }>;
              const codeField = nodes.find(n => n.e && /(code|otp|verify|digit)/i.test((n.t || '') + (n.d || ''))) || nodes.find(n => n.e);
              if (codeField) {
                await AppController.performText('', codeMatch[1]);
                await new Promise(r => setTimeout(r, 2000));
                const flat2 = await getScreenContentFlat();
                const nodes2 = JSON.parse(flat2) as Array<{ i: number; t: string; c: boolean; x: number; y: number }>;
                const submitBtn = nodes2.find(n => n.c && /(verify|submit|confirm|continue|next)/i.test(n.t || ''));
                if (submitBtn) {
                  const { performTap } = await import('../native/AppController');
                  await performTap(submitBtn.x, submitBtn.y);
                }
                await new Promise(r => setTimeout(r, 3000));
                const recheck = await AuthGate.detect();
                if (!recheck.detected) {
                  return { success: true, method: 'credential_vault', message: 'Auto-filled 2FA code from SMS' };
                }
              }
            }
          }
        }
      } catch (e: any) {
        DebugLog.error('AuthGate', `2FA SMS auto-fill failed: ${e.message}`);
      }
    }

    return {
      success: false,
      method: 'failed',
      message: `Could not get past ${detection.authType} authentication on ${detection.appPackage}. ${options.userPresent ? 'Please unlock the app manually.' : 'Task deferred until you unlock.'}`,
    };
  }
}
