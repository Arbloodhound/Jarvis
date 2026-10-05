import { SecureVault } from '../security/SecureVault';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

interface MemoryRecord {
  id: string;
  type: 'session' | 'longterm';
  trigger: string;
  capability: string;
  outcome: string;
  keywords: string[];
  timestamp: number;
  useCount: number;
}

export class MemoryManager {
  private workingMemory: Map<string, string> = new Map();
  private sessionCache: MemoryRecord[] = [];
  private longtermIndex: string[] = [];
  private longtermCache: Map<string, MemoryRecord> | null = null;
  private longtermCacheDirty = true;

  constructor(private vault: SecureVault) {}

  async initialize(): Promise<void> {
    try {
      const indexRaw = await this.vault.get('lt_memory_index').catch(() => null);
      if (indexRaw) {
        this.longtermIndex = JSON.parse(indexRaw);
      }
      DebugLog.systemEvent('MemoryManager', `Initialized. Longterm entries: ${this.longtermIndex.length}`);
    } catch (err: any) {
      DebugLog.error('MemoryManager', `Init failed: ${err.message}`);
    }
  }

  setWorking(key: string, value: string): void {
    this.workingMemory.set(key, value);
  }

  getWorking(key: string): string | undefined {
    return this.workingMemory.get(key);
  }

  clearWorking(): void {
    this.workingMemory.clear();
  }

  async storeSession(trigger: string, capability: string, outcome: string): Promise<void> {
    const keywords = this.extractKeywords(trigger);
    const record: MemoryRecord = {
      id: `session_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`,
      type: 'session',
      trigger,
      capability,
      outcome,
      keywords,
      timestamp: Date.now(),
      useCount: 1,
    };
    this.sessionCache.push(record);
    await this.vault.set(record.id, JSON.stringify(record));
    DebugLog.systemEvent('MemoryManager', `SESSION_SET cap=${capability}`);
  }

  async storeLongterm(key: string, category: string, content: string): Promise<void> {
    const id = `lt_${category}_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
    const record: MemoryRecord = {
      id,
      type: 'longterm',
      trigger: key,
      capability: category,
      outcome: content,
      keywords: this.extractKeywords(key + ' ' + content),
      timestamp: Date.now(),
      useCount: 1,
    };
    await this.vault.set(id, JSON.stringify(record));
    this.longtermIndex.push(id);
    await this.vault.set('lt_memory_index', JSON.stringify(this.longtermIndex));
    this.longtermCacheDirty = true;
    DebugLog.systemEvent('MemoryManager', `LONGTERM_SET key=${key} category=${category}`);
  }

  async promoteLongterm(trigger: string, capability: string, outcome: string): Promise<void> {
    if (!outcome || outcome.toLowerCase().includes('error') || outcome.toLowerCase().includes('failed')) return;
    await this.storeLongterm(trigger, capability, outcome);
  }

  async retrieveRelevant(query: string, limit: number = 5): Promise<MemoryRecord[]> {
    const queryKeywords = this.extractKeywords(query);
    const results: Array<{ record: MemoryRecord; score: number }> = [];

    for (const record of this.sessionCache) {
      const score = this.keywordScore(queryKeywords, record.keywords);
      if (score > 0) results.push({ record, score: score + 10 });
    }

    if (this.longtermCacheDirty || !this.longtermCache) {
      this.longtermCache = new Map();
      const recentIds = this.longtermIndex.slice(-200);
      for (const id of recentIds) {
        try {
          const raw = await this.vault.get(id);
          if (!raw) continue;
          const record: MemoryRecord = JSON.parse(raw);
          this.longtermCache.set(id, record);
        } catch (err: any) {
          DebugLog.error('MemoryManager', `Failed to retrieve record ${id}: ${err.message}`);
        }
      }
      this.longtermCacheDirty = false;
    }
    for (const record of this.longtermCache.values()) {
      const score = this.keywordScore(queryKeywords, record.keywords);
      if (score > 0) results.push({ record, score });
    }

    const sorted = results
      .sort((a, b) => b.score - a.score)
      .slice(0, limit)
      .map(r => r.record);

    if (sorted.length > 0) {
      DebugLog.systemEvent('MemoryManager', `RETRIEVE query="${query.slice(0, 40)}" matches=${sorted.length}`);
    }

    return sorted;
  }

  formatForPrompt(records: MemoryRecord[]): string {
    if (records.length === 0) return '';
    return 'Relevant memory:\n' + records.map(r =>
      `- "${r.trigger}" -> ${r.capability}: ${r.outcome.slice(0, 100)}`
    ).join('\n');
  }

  async rememberContact(name: string, number: string, label: string): Promise<void> {
    const key = `contact_pref_${name.toLowerCase().trim().replace(/[^a-z0-9]/g, '_')}`;
    const value = JSON.stringify({ name, number, label, storedAt: Date.now() });
    await this.vault.set(key, value);
    DebugLog.systemEvent('MemoryManager', `Stored contact pref: "${name}" → ${number.slice(0, 6)}****`);
  }

  async recallContact(name: string): Promise<{ name: string; number: string; label: string } | null> {
    try {
      const key = `contact_pref_${name.toLowerCase().trim().replace(/[^a-z0-9]/g, '_')}`;
      const raw = await this.vault.get(key);
      if (!raw) return null;
      return JSON.parse(raw);
    } catch {
      return null;
    }
  }

  async rememberApp(label: string, packageName: string): Promise<void> {
    await this.storeLongterm(`app:${label.toLowerCase()}`, 'app_discovery', JSON.stringify({ label, packageName }));
  }

  async recallApp(label: string): Promise<string | null> {
    const records = await this.retrieveRelevant(`app:${label.toLowerCase()}`, 1);
    if (records.length === 0) return null;
    try {
      const data = JSON.parse(records[0].outcome);
      return data.packageName || null;
    } catch { return null; }
  }

  private extractKeywords(text: string): string[] {
    const stopWords = new Set(['the','a','an','and','or','but','in','on','at','to','for','of','with','my','me','i','is','it','this','that','be','do','up','can','will','just','now']);
    return text.toLowerCase()
      .replace(/[^a-z0-9\s]/g, ' ')
      .split(/\s+/)
      .filter(w => w.length > 2 && !stopWords.has(w))
      .slice(0, 15);
  }

  private keywordScore(queryKws: string[], recordKws: string[]): number {
    let score = 0;
    for (const qk of queryKws) {
      for (const rk of recordKws) {
        if (qk === rk) score += 2;
        else if (rk.includes(qk) || qk.includes(rk)) score += 1;
      }
    }
    return score;
  }
}