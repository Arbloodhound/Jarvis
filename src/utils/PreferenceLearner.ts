import { SecureVault } from '../security/SecureVault';
import { Logger } from './Logger';
import { UltraDevLog } from './UltraDevLog';
import * as FileSystem from 'expo-file-system/legacy';

interface Pattern {
  id: string;
  trigger: string;
  capabilities: string[];
  outcome: string;
  success: boolean;
  confidence: number;
  usageCount: number;
  lastUsed: number;
  createdAt: number;
}

interface UserPreference {
  key: string;
  value: string;
  source: string;
  confidence: number;
  updatedAt: number;
}

export class PreferenceLearner {
  private vault: SecureVault;
  private logger: Logger;
  private patterns: Pattern[];
  private preferences: Map<string, UserPreference>;
  private static readonly PATTERNS_KEY = 'learned_patterns';
  private static readonly PREFS_KEY = 'user_preferences';
  private static readonly MAX_PATTERNS = 200;

  constructor(vault: SecureVault) {
    this.vault = vault;
    this.logger = new Logger('PreferenceLearner');
    this.patterns = [];
    this.preferences = new Map();
  }

  async initialize(): Promise<void> {
    try {
      const pd = await this.vault.get(PreferenceLearner.PATTERNS_KEY);
      if (pd) {
        const all: Pattern[] = JSON.parse(pd);
        const aliases = all.filter(p => p.capabilities.includes('app_launch') && p.confidence >= 0.8);
        const rest = all.filter(p => !p.capabilities.includes('app_launch') || p.confidence < 0.8);
        this.patterns = [...aliases, ...rest];
      }
      const prd = await this.vault.get(PreferenceLearner.PREFS_KEY);
      if (prd) {
        const prefs: UserPreference[] = JSON.parse(prd);
        for (const p of prefs) this.preferences.set(p.key, p);
      }
      this.logger.info(`Loaded ${this.patterns.length} patterns (${this.patterns.filter(p => p.capabilities.includes('app_launch')).length} aliases first), ${this.preferences.size} preferences`);
    } catch (error: any) {
      this.logger.error('Init failed: ' + error.message);
    }
  }

  async learnFromExecution(
    request: string,
    capabilities: string[],
    outcome: string,
    success: boolean
  ): Promise<void> {
    const existing = this.findPattern(request);
    if (existing) {
      existing.usageCount++;
      existing.lastUsed = Date.now();
      existing.confidence = success
        ? Math.min(1.0, existing.confidence + 0.1)
        : Math.max(0.0, existing.confidence - 0.2);
      existing.success = success;
    } else {
      const pattern: Pattern = {
        id: Date.now().toString(36),
        trigger: request.toLowerCase().trim(),
        capabilities,
        outcome,
        success,
        confidence: success ? 0.5 : 0.1,
        usageCount: 1,
        lastUsed: Date.now(),
        createdAt: Date.now(),
      };
      this.patterns.unshift(pattern);
      if (this.patterns.length > PreferenceLearner.MAX_PATTERNS) {
        this.patterns = this.patterns
          .sort((a, b) => b.confidence * b.usageCount - a.confidence * a.usageCount)
          .slice(0, PreferenceLearner.MAX_PATTERNS);
      }
    }
    await this.persist();
  }

  async learnPackage(trigger: string, packageName: string): Promise<void> {
    const existing = this.patterns.find(
      p => p.trigger === trigger.toLowerCase().trim() &&
           p.capabilities.includes('app_launch')
    );
    if (existing) {
      existing.confidence = 1.0;
      existing.outcome = packageName;
      existing.lastUsed = Date.now();
    } else {
      this.patterns.unshift({
        id: Date.now().toString(36),
        trigger: trigger.toLowerCase().trim(),
        capabilities: ['app_launch'],
        outcome: packageName,
        success: true,
        confidence: 1.0,
        usageCount: 1,
        lastUsed: Date.now(),
        createdAt: Date.now(),
      });
      if (this.patterns.length > PreferenceLearner.MAX_PATTERNS) {
        this.patterns = this.patterns
          .sort((a, b) => b.confidence * b.usageCount - a.confidence * a.usageCount)
          .slice(0, PreferenceLearner.MAX_PATTERNS);
      }
    }
    await this.persist();
    UltraDevLog.learnPackage(trigger, packageName, !!existing);
  }

  findPattern(request: string): Pattern | null {
    const words = request.toLowerCase().split(/\s+/);
    let bestMatch: Pattern | null = null;
    let bestScore = 0;
    for (const pattern of this.patterns) {
      const pw = pattern.trigger.split(/\s+/);
      const common = words.filter((w) => pw.includes(w));
      const score = (common.length / Math.max(words.length, pw.length)) * pattern.confidence;
      if (score > bestScore && score > 0.3) {
        bestScore = score;
        bestMatch = pattern;
      }
    }
    return bestMatch;
  }

  async setPreference(key: string, value: string, source: string): Promise<void> {
    this.preferences.set(key, { key, value, source, confidence: 1.0, updatedAt: Date.now() });
    await this.persist();
  }

  getPreference(key: string): string | null {
    const p = this.preferences.get(key);
    return p ? p.value : null;
  }

  /**
   * Permanently store an app name → package name alias so future lookups skip directory/fuzzy.
   * The key is stored as "app_alias:{normalizedName}" in preferences.
   */
  async learnAppAlias(appName: string, packageName: string): Promise<void> {
    const key = `app_alias:${appName.toLowerCase().trim()}`;
    this.preferences.set(key, { key, value: packageName, source: 'user_confirmed', confidence: 1.0, updatedAt: Date.now() });
    await this.persist();
    UltraDevLog.systemEvent('PreferenceLearner', `Learned alias: "${appName}" → ${packageName}`);
  }

  /**
   * Look up a permanently learned app alias.
   * Returns the package name if found, null otherwise.
   */
  getAppAlias(appName: string): string | null {
    const key = `app_alias:${appName.toLowerCase().trim()}`;
    const p = this.preferences.get(key);
    return p ? p.value : null;
  }

  /**
   * Remove a permanently learned app alias when it is discovered to be poisoned
   * or misleading (for example, a generic noun mapped to a search surface).
   */
  async forgetAppAlias(appName: string): Promise<boolean> {
    const key = `app_alias:${appName.toLowerCase().trim()}`;
    const existed = this.preferences.delete(key);
    if (existed) {
      await this.persist();
      UltraDevLog.systemEvent('PreferenceLearner', `Forgot alias: "${appName}"`);
    }
    return existed;
  }

  /**
   * Get all learned app aliases (app_alias:* keys).
   */
  getLearnedAliases(): Array<{ appName: string; packageName: string }> {
    const results: Array<{ appName: string; packageName: string }> = [];
    for (const [key, pref] of this.preferences.entries()) {
      if (key.startsWith('app_alias:')) {
        results.push({ appName: key.slice('app_alias:'.length), packageName: pref.value });
      }
    }
    return results;
  }

  getAllPreferences(): UserPreference[] {
    return Array.from(this.preferences.values());
  }

  getTopPatterns(limit: number = 10): Pattern[] {
    return this.patterns
      .sort((a, b) => b.confidence * b.usageCount - a.confidence * a.usageCount)
      .slice(0, limit);
  }

  getContextForAI(): string {
    const prefs = this.getAllPreferences()
      .map((p) => `${p.key}: ${p.value}`)
      .join('\n');
    const pats = this.getTopPatterns(5)
      .map((p) => `When asked "${p.trigger}", used [${p.capabilities.join(', ')}] -> ${p.outcome}`)
      .join('\n');
    let ctx = '';
    if (prefs) ctx += `User preferences:\n${prefs}\n\n`;
    if (pats) ctx += `Successful past patterns:\n${pats}\n`;
    return ctx;
  }

  private async persist(): Promise<void> {
    try {
      await this.vault.set(PreferenceLearner.PATTERNS_KEY, JSON.stringify(this.patterns));
      await this.vault.set(PreferenceLearner.PREFS_KEY, JSON.stringify(Array.from(this.preferences.values())));
    } catch (error: any) {
      this.logger.error('Persist failed: ' + error.message);
    }
  }

  async exportToFile(): Promise<string> {
    try {
      const [preferredModel, learnedPatterns, userPreferences] =
        await Promise.all([
          this.vault.get('preferred_model').catch(() => null),
          this.vault.get('learned_patterns').catch(() => null),
          this.vault.get('user_preferences').catch(() => null),
        ]);

      const exportData = {
        version: '1.0',
        exportedAt: new Date().toISOString(),
        source: 'agent-ultra',
        preferred_model: preferredModel,
        learned_patterns: learnedPatterns,
        user_preferences: userPreferences,
      };

      const timestamp = Date.now();
      const filename = `agent_ultra_prefs_${timestamp}.json`;
      const docDir = FileSystem.documentDirectory;
      if (!docDir) {
        throw new Error('FileSystem document directory not available');
      }
      const exportPath = docDir + filename;
      await FileSystem.writeAsStringAsync(exportPath, JSON.stringify(exportData, null, 2));
      return exportPath;
    } catch (err: any) {
      this.logger.error('Export failed: ' + err.message);
      throw err;
    }
  }

  async cleanup(): Promise<void> {
    const before = this.patterns.length;
    this.patterns = this.patterns.filter((p) => p.confidence > 0.1 || p.usageCount > 2);
    if (this.patterns.length < before) {
      await this.persist();
      this.logger.info(`Cleaned ${before - this.patterns.length} low-confidence patterns`);
    }
  }
}
