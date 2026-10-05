import { SecureVault } from '../security/SecureVault';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

// ═══════════════════════════════════════════════════════
// TIERS:
//   free    — agent works, ads shown, API locked
//   no_ads  — agent works, no ads, API locked ($0.99/mo)
//   pro     — agent works, no ads, API unlocked ($9.99/mo)
//   dev     — everything, no restrictions (7-tap toggle)
//
// No backend. No credits. No proxy. BYO API only.
// Subscriptions handled by Google Play / App Store.
// ═══════════════════════════════════════════════════════

export type UserTier = 'free' | 'no_ads' | 'pro' | 'dev';

export const AGENT_ONLY_CAPABILITIES = new Set([
  'app_launch', 'camera_capture', 'media_access', 'flashlight_toggle',
  'wifi_toggle', 'bluetooth_toggle', 'airplane_mode', 'do_not_disturb',
  'volume_set', 'brightness', 'screen_rotate', 'phone_call',
  'sms_send', 'sms_read', 'sms_conversation', 'contacts_read',
  'device_info', 'battery_status', 'system_info', 'device_location',
  'open_url', 'web_search', 'clipboard_read', 'clipboard_write',
  'screenshot', 'alarm_set', 'timer_set', 'file_read', 'file_write',
  'file_delete', 'file_list', 'note_create', 'notification_read',
  'media_play', 'media_pause', 'media_next', 'media_previous', 'open_settings',
]);

export const AI_REQUIRED_CAPABILITIES = new Set([
  'conversation', 'ai_instruction', 'image_generate', 'video_generate',
  'tts', 'code_generate', 'app_build', 'self_modify', 'self_replicate',
  'react_navigate', 'app_control', 'app_test',
]);

export interface UsageRecord {
  date: string;
  messageCount: number;
  aiCallCount: number;
  modelUsage: Record<string, number>;
}

export class TierService {
  private vault: SecureVault;
  private tier: UserTier = 'free';
  private usage: UsageRecord = { date: '', messageCount: 0, aiCallCount: 0, modelUsage: {} };
  private initialized = false;

  constructor(vault: SecureVault) { this.vault = vault; }

  async initialize(): Promise<void> {
    if (this.initialized) return;

    const savedTier = await this.vault.get('user_tier');
    if (savedTier === 'dev' || savedTier === 'pro' || savedTier === 'no_ads' || savedTier === 'free') {
      this.tier = savedTier;
    }

    // Dev mode override — reads from AppStorage (which routes to SecureVault for this key)
    try {
      const { AppStorage } = require('../utils/AppStorage');
      const devMode = await AppStorage.get('dev_mode_enabled');
      if (devMode === '1') this.tier = 'dev';
    } catch {}

    await this.loadTodayUsage();

    // ══════════════════════════════════════════════════════
    // SUBSCRIPTION CHECK: When Google Play Billing is added,
    // verify subscription status here and set tier accordingly.
    //
    // const subStatus = await GooglePlayBilling.getSubscriptionStatus();
    // if (subStatus === 'pro') this.tier = 'pro';
    // else if (subStatus === 'no_ads') this.tier = 'no_ads';
    // ══════════════════════════════════════════════════════

    this.initialized = true;
    DebugLog.systemEvent('TierService', `Initialized: tier=${this.tier} msgs=${this.usage.messageCount} ai=${this.usage.aiCallCount}`);
  }

  getTier(): UserTier { return this.tier; }
  getUsage(): UsageRecord { return { ...this.usage }; }

  async setTier(tier: UserTier): Promise<void> {
    this.tier = tier;
    await this.vault.set('user_tier', tier);
    DebugLog.systemEvent('TierService', `Tier set: ${tier}`);
  }

  hasAiAccess(): boolean {
    return this.tier === 'dev' || this.tier === 'pro';
  }

  isApiUnlocked(): boolean {
    return this.tier === 'dev' || this.tier === 'pro';
  }

  showAds(): boolean {
    return this.tier === 'free';
  }

  canUseCapability(capability: string): { allowed: boolean; reason: string; needsUpgrade: boolean } {
    if (AGENT_ONLY_CAPABILITIES.has(capability)) {
      return { allowed: true, reason: '', needsUpgrade: false };
    }
    if (!this.hasAiAccess()) {
      return {
        allowed: false, needsUpgrade: true,
        reason: 'AI features require Pro ($9.99/mo).\n\nThe agent still works! Try:\n• "Open camera"\n• "Turn on flashlight"\n• "Call Mom"\n• "Read my texts"',
      };
    }
    return { allowed: true, reason: '', needsUpgrade: false };
  }

  canSendMessage(needsAi: boolean): { allowed: boolean; reason: string; needsUpgrade: boolean } {
    if (!needsAi) return { allowed: true, reason: '', needsUpgrade: false };
    if (!this.hasAiAccess()) {
      return {
        allowed: false, needsUpgrade: true,
        reason: 'AI chat requires Pro ($9.99/mo).\n\nThe agent still works! Try commands like:\n• "Open camera"\n• "Turn on flashlight"\n• "Call Mom"\n• "Read my texts"',
      };
    }
    return { allowed: true, reason: '', needsUpgrade: false };
  }

  // No model filtering — Pro/Dev users see everything their APIs provide
  filterModelsForTier(allModels: Array<{ id: string; [key: string]: any }>): Array<{ id: string; [key: string]: any }> {
    if (this.hasAiAccess()) return allModels;
    return []; // free/no_ads: no models
  }

  async recordMessage(modelId: string, wasAiCall: boolean): Promise<void> {
    await this.ensureTodayUsage();
    this.usage.messageCount++;
    if (wasAiCall) {
      this.usage.aiCallCount++;
      this.usage.modelUsage[modelId] = (this.usage.modelUsage[modelId] || 0) + 1;
    }
    await this.saveUsage();
  }

  private todayKey(): string { return new Date().toISOString().slice(0, 10); }
  private async loadTodayUsage(): Promise<void> {
    try { const raw = await this.vault.get('tier_usage_today'); if (raw) { const p = JSON.parse(raw); if (p.date === this.todayKey()) { this.usage = p; return; } } } catch {}
    this.usage = { date: this.todayKey(), messageCount: 0, aiCallCount: 0, modelUsage: {} };
  }
  private async ensureTodayUsage(): Promise<void> { if (this.usage.date !== this.todayKey()) this.usage = { date: this.todayKey(), messageCount: 0, aiCallCount: 0, modelUsage: {} }; }
  private async saveUsage(): Promise<void> { try { await this.vault.set('tier_usage_today', JSON.stringify(this.usage)); } catch (e: any) { DebugLog.error('TierService', e.message, e.stack); } }
}
