import { SecureVault } from '../security/SecureVault';
import AppController from '../native/AppController';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import type { AuthType } from './AuthGate';

const CRED_INDEX_KEY = 'cred_vault_index';
const CRED_PREFIX = 'cred_';

interface StoredCredential {
  id: string;
  appPackage: string;
  label: string;
  username: string;
  password: string;
  twoFactorMethod?: 'sms' | 'email' | 'authenticator';
  createdAt: number;
  lastUsedAt: number;
}

export class CredentialVault {
  private index: Map<string, string> = new Map();
  private enabled = false;

  constructor(private vault: SecureVault) {}

  async initialize(): Promise<void> {
    try {
      const raw = await this.vault.get(CRED_INDEX_KEY).catch(() => null);
      if (raw) {
        const entries: Array<[string, string]> = JSON.parse(raw);
        this.index = new Map(entries);
      }
      this.enabled = this.index.size > 0;
      DebugLog.push('SYSTEM' as any, { event: 'cred_vault_init', credentialCount: this.index.size });
    } catch (err: any) {
      DebugLog.error('CredentialVault', `Init failed: ${err.message}`);
    }
  }

  async store(appPackage: string, label: string, username: string, password: string, twoFactorMethod?: 'sms' | 'email' | 'authenticator'): Promise<void> {
    const id = `${CRED_PREFIX}${Date.now().toString(36)}`;
    const cred: StoredCredential = {
      id, appPackage, label, username, password,
      twoFactorMethod, createdAt: Date.now(), lastUsedAt: 0,
    };
    await this.vault.set(id, JSON.stringify(cred));
    this.index.set(appPackage, id);
    await this.vault.set(CRED_INDEX_KEY, JSON.stringify(Array.from(this.index.entries())));
    this.enabled = true;
    DebugLog.push('SYSTEM' as any, { event: 'cred_stored', app: appPackage, label, has2FA: !!twoFactorMethod });
  }

  async hasCredentials(appPackage: string): Promise<boolean> {
    return this.index.has(appPackage);
  }

  async autoFill(appPackage: string, authType: AuthType): Promise<boolean> {
    const credId = this.index.get(appPackage);
    if (!credId) return false;

    try {
      const raw = await this.vault.get(credId);
      if (!raw) return false;
      const cred: StoredCredential = JSON.parse(raw);

      DebugLog.push('SYSTEM' as any, {
        event: 'cred_autofill_attempt',
        app: appPackage,
        authType,
      });

      if (authType === 'password' || authType === 'pin') {
        const { getScreenContentFlat } = await import('../native/AppController');
        const flat = await getScreenContentFlat();
        const nodes = JSON.parse(flat) as Array<{ i: number; t: string; d: string; e: boolean; x: number; y: number }>;
        const editables = nodes.filter(n => n.e);

        if (editables.length >= 2) {
          const { performTap } = await import('../native/AppController');
          await performTap(editables[0].x, editables[0].y);
          await new Promise(r => setTimeout(r, 500));
          await AppController.performText('', cred.username);
          await new Promise(r => setTimeout(r, 500));
          await performTap(editables[1].x, editables[1].y);
          await new Promise(r => setTimeout(r, 500));
          await AppController.performText('', cred.password);
          await new Promise(r => setTimeout(r, 500));
          const flat2 = await getScreenContentFlat();
          const nodes2 = JSON.parse(flat2) as Array<{ i: number; t: string; c: boolean; x: number; y: number }>;
          const submitBtn = nodes2.find(n => n.c && /(sign in|log in|login|submit|continue|next)/i.test(n.t || ''));
          if (submitBtn) await performTap(submitBtn.x, submitBtn.y);

          cred.lastUsedAt = Date.now();
          await this.vault.set(credId, JSON.stringify(cred));

          DebugLog.push('SYSTEM' as any, { event: 'cred_autofill_done', app: appPackage, fieldsFound: editables.length });
          return true;
        } else if (editables.length === 1) {
          const { performTap } = await import('../native/AppController');
          await performTap(editables[0].x, editables[0].y);
          await new Promise(r => setTimeout(r, 500));
          await AppController.performText('', cred.password);
          cred.lastUsedAt = Date.now();
          await this.vault.set(credId, JSON.stringify(cred));
          return true;
        }
      }

      return false;
    } catch (e: any) {
      DebugLog.error('CredentialVault', `Auto-fill failed for ${appPackage}: ${e.message}`);
      return false;
    }
  }

  async remove(appPackage: string): Promise<void> {
    const credId = this.index.get(appPackage);
    if (credId) {
      await this.vault.delete(credId);
      this.index.delete(appPackage);
      await this.vault.set(CRED_INDEX_KEY, JSON.stringify(Array.from(this.index.entries())));
    }
  }

  async listApps(): Promise<Array<{ appPackage: string; label: string; lastUsed: number }>> {
    const result: Array<{ appPackage: string; label: string; lastUsed: number }> = [];
    for (const [pkg, id] of this.index) {
      try {
        const raw = await this.vault.get(id);
        if (raw) {
          const cred: StoredCredential = JSON.parse(raw);
          result.push({ appPackage: pkg, label: cred.label, lastUsed: cred.lastUsedAt });
        }
      } catch {}
    }
    return result;
  }

  isEnabled(): boolean { return this.enabled; }
}
