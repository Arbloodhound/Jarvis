import { SecureVault } from '../security/SecureVault';
import { AppStorage } from '../utils/AppStorage';
import { Logger } from '../utils/Logger';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

interface CostEntry {
  timestamp: number;
  providerId: string;
  providerName: string;
  modelId: string;
  operation: string;
  inputTokens: number;
  outputTokens: number;
  cost: number | null;
  taskId: string;
  agentId: string;
}

interface CostSummary {
  totalCost: number;
  unknownCostCount: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  totalCalls: number;
  costByModel: Record<string, number>;
  callsByModel: Record<string, number>;
  costByProvider: Record<string, number>;
  costByOperation: Record<string, number>;
  costByDay: Record<string, number>;
}

export class CostTracker {
  private vault: SecureVault;
  private logger: Logger;
  private entries: CostEntry[];
  private dailyLimit: number;
  private taskLimit: number;
  private static readonly STORAGE_KEY = 'cost_history';
  private static readonly MAX_ENTRIES = 1000;
  private modelRatesCache: Record<string, { inputPer1kTokens: number; outputPer1kTokens: number } | null> = {};

  constructor(vault: SecureVault) {
    this.vault = vault;
    this.logger = new Logger('CostTracker');
    this.entries = [];
    this.dailyLimit = 0;
    this.taskLimit = 0;
  }

  async initialize(): Promise<void> {
    try {
      const stored = await AppStorage.get(CostTracker.STORAGE_KEY);
      if (stored) {
        const parsed = JSON.parse(stored);
        // Migrate legacy entries (model string only, no providerId)
        this.entries = parsed.map((e: any) => ({
          timestamp: e.timestamp ?? Date.now(),
          providerId: e.providerId ?? 'legacy',
          providerName: e.providerName ?? e.providerId ?? 'legacy',
          modelId: e.modelId ?? e.model ?? 'unknown',
          operation: e.operation ?? 'chat',
          inputTokens: e.inputTokens ?? 0,
          outputTokens: e.outputTokens ?? 0,
          cost: e.cost ?? null,
          taskId: e.taskId ?? '',
          agentId: e.agentId ?? '',
        }));
      }
      const limit = await this.vault.get('daily_cost_limit');
      if (limit) this.dailyLimit = parseFloat(limit);
      const tLimit = await this.vault.get('task_cost_limit');
      if (tLimit) this.taskLimit = parseFloat(tLimit);
      this.logger.info(`CostTracker initialized. ${this.entries.length} entries.`);
      DebugLog.systemEvent('CostTracker', `Initialized with ${this.entries.length} entries`);
    } catch (error: any) {
      DebugLog.error('CostTracker', 'init failed: ' + error.message);
    }
  }

  async record(
    modelOrObj: string | {
      modelId: string;
      providerId?: string;
      providerName?: string;
      operation?: string;
    },
    inputTokens: number,
    outputTokens: number,
    taskId: string,
    agentId: string,
    pricingHint?: { inputPer1kTokens?: number; outputPer1kTokens?: number } | null
  ): Promise<number> {
    let modelId: string;
    let providerId: string;
    let providerName: string;
    let operation: string;
    if (typeof modelOrObj === 'string') {
      modelId = modelOrObj;
      providerId = 'unknown';
      providerName = 'unknown';
      operation = 'chat';
    } else {
      modelId = modelOrObj.modelId;
      providerId = modelOrObj.providerId ?? 'unknown';
      providerName = modelOrObj.providerName ?? modelOrObj.providerId ?? 'unknown';
      operation = modelOrObj.operation ?? 'chat';
    }

    let cost: number | null = null;
    const rates = pricingHint ?? this.modelRatesCache[modelId];
    if (rates && (rates.inputPer1kTokens !== undefined || rates.outputPer1kTokens !== undefined)) {
      cost = ((inputTokens / 1000) * (rates.inputPer1kTokens ?? 0)) +
             ((outputTokens / 1000) * (rates.outputPer1kTokens ?? 0));
    } else if (this.modelRatesCache[modelId] === undefined) {
      cost = null; // unknown
    }

    const entry: CostEntry = {
      timestamp: Date.now(),
      providerId,
      providerName,
      modelId,
      operation,
      inputTokens,
      outputTokens,
      cost,
      taskId,
      agentId,
    };
    this.entries.unshift(entry);
    if (this.entries.length > CostTracker.MAX_ENTRIES) {
      this.entries = this.entries.slice(0, CostTracker.MAX_ENTRIES);
    }
    await this.persist();
    DebugLog.costRecord(modelId, cost ?? 0, taskId);
    return cost ?? 0;
  }

  getDailySpend(): number {
    const today = new Date().toDateString();
    return this.entries
      .filter(e => new Date(e.timestamp).toDateString() === today && e.cost !== null)
      .reduce((sum, e) => sum + (e.cost ?? 0), 0);
  }

  getTaskSpend(taskId: string): number {
    return this.entries
      .filter(e => e.taskId === taskId && e.cost !== null)
      .reduce((sum, e) => sum + (e.cost ?? 0), 0);
  }

  isWithinDailyLimit(): boolean {
    if (this.dailyLimit <= 0) return true;
    const spent = this.getDailySpend();
    const allowed = spent < this.dailyLimit;
    DebugLog.costLimitCheck('daily', allowed, spent, this.dailyLimit);
    return allowed;
  }

  isWithinTaskLimit(taskId: string): boolean {
    if (this.taskLimit <= 0) return true;
    const spent = this.getTaskSpend(taskId);
    const allowed = spent < this.taskLimit;
    DebugLog.costLimitCheck('task', allowed, spent, this.taskLimit);
    return allowed;
  }

  getRemainingDailyBudget(): number {
    return Math.max(0, this.dailyLimit - this.getDailySpend());
  }

  getSummary(): CostSummary {
    const costByModel: Record<string, number> = {};
    const callsByModel: Record<string, number> = {};
    const costByProvider: Record<string, number> = {};
    const costByOperation: Record<string, number> = {};
    const costByDay: Record<string, number> = {};
    let totalInput = 0;
    let totalOutput = 0;
    let totalCost = 0;
    let unknownCostCount = 0;
    for (const entry of this.entries) {
      if (entry.cost !== null) {
        totalCost += entry.cost;
        costByModel[entry.modelId] = (costByModel[entry.modelId] || 0) + entry.cost;
        costByProvider[entry.providerId] = (costByProvider[entry.providerId] || 0) + entry.cost;
        costByOperation[entry.operation] = (costByOperation[entry.operation] || 0) + entry.cost;
        const day = new Date(entry.timestamp).toDateString();
        costByDay[day] = (costByDay[day] || 0) + entry.cost;
      } else {
        unknownCostCount++;
      }
      totalInput += entry.inputTokens;
      totalOutput += entry.outputTokens;
      callsByModel[entry.modelId] = (callsByModel[entry.modelId] || 0) + 1;
    }
    return {
      totalCost, unknownCostCount,
      totalInputTokens: totalInput, totalOutputTokens: totalOutput,
      totalCalls: this.entries.length,
      costByModel, callsByModel, costByProvider, costByOperation, costByDay,
    };
  }

  async setDailyLimit(limit: number): Promise<void> {
    this.dailyLimit = limit;
    await this.vault.set('daily_cost_limit', limit.toString());
    DebugLog.systemEvent('CostTracker', `Daily limit set to $${limit}`);
  }

  async setTaskLimit(limit: number): Promise<void> {
    this.taskLimit = limit;
    await this.vault.set('task_cost_limit', limit.toString());
    DebugLog.systemEvent('CostTracker', `Task limit set to $${limit}`);
  }

  setModelRates(model: string, input: number, output: number): void {
    this.modelRatesCache[model] = { inputPer1kTokens: input, outputPer1kTokens: output };
  }

  private async persist(): Promise<void> {
    try {
      await AppStorage.set(CostTracker.STORAGE_KEY, JSON.stringify(this.entries));
    } catch (error: any) {
      DebugLog.error('CostTracker', 'persist failed: ' + error.message);
    }
  }

  async cleanup(maxAgeDays: number = 30): Promise<void> {
    const cutoff = Date.now() - maxAgeDays * 86400000;
    const before = this.entries.length;
    this.entries = this.entries.filter(e => e.timestamp > cutoff);
    if (this.entries.length < before) {
      await this.persist();
      DebugLog.systemEvent('CostTracker', `Cleaned ${before - this.entries.length} old entries`);
    }
  }
}
