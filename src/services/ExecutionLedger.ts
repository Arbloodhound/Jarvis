import { Platform } from 'react-native';
import * as ExpoFileSystem from 'expo-file-system/legacy';
import { ExecutionEvent, EventPhase, BudgetCheck, SessionStats } from '../types/ultra';
import { Logger } from '../utils/Logger';
import { UltraDevLog } from '../utils/UltraDevLog';

const FileSystem: any = Platform.OS !== 'web' ? ExpoFileSystem : null;
const isNative = Platform.OS !== 'web';

interface BudgetLimits {
  maxActionsPerSession: number;
  maxCostPerSession: number;
  maxHighRiskPerSession: number;
  maxCostPerTask: number;
  maxCostPerDay: number;
}

const DEFAULT_BUDGET: BudgetLimits = {
  maxActionsPerSession: 50,
  maxCostPerSession: 2.0,
  maxHighRiskPerSession: 3,
  maxCostPerTask: 0,
  maxCostPerDay: 0,
};

const SESSION_WINDOW_MS = 60 * 60 * 1000;
const DAILY_WINDOW_MS = 24 * 60 * 60 * 1000;

const LEDGER_FILE = 'execution-ledger.json';
const WEB_STORAGE_KEY = 'ultra_execution_ledger';

export class ExecutionLedger {
  private events: ExecutionEvent[] = [];
  private logger: Logger;
  budgetLimits: BudgetLimits;
  private baseDir: string;
  private initialized = false;

  constructor(budgetLimits?: Partial<BudgetLimits>) {
    this.logger = new Logger('ExecutionLedger');
    this.budgetLimits = { ...DEFAULT_BUDGET, ...budgetLimits };
    this.baseDir = (isNative && FileSystem?.documentDirectory) || '';
    UltraDevLog.push('SYSTEM', { event: 'execution_ledger_construct', platform: Platform.OS, budgetLimits: this.budgetLimits });
  }

  async initialize(): Promise<void> {
    if (this.initialized) return;
    try {
      await this.loadEvents();
      this.initialized = true;
      this.logger.info(`ExecutionLedger initialized with ${this.events.length} events`);
      UltraDevLog.push('SYSTEM', { event: 'execution_ledger_init_ok', eventCount: this.events.length, platform: Platform.OS });
    } catch (e: any) {
      this.logger.error('Failed to initialize ExecutionLedger', e);
      UltraDevLog.push('SYSTEM', { event: 'execution_ledger_init_fail', error: e?.message });
      this.events = [];
      this.initialized = true;
    }
  }

  async logEvent(event: Omit<ExecutionEvent, 'id' | 'timestamp'>): Promise<ExecutionEvent> {
    await this.initialize();

    const fullEvent: ExecutionEvent = {
      ...event,
      id: Date.now().toString() + Math.random().toString(36).substr(2, 9),
      timestamp: Date.now(),
    };

    this.events.push(fullEvent);

    const MAX_LEDGER_EVENTS = 500;
    if (this.events.length > MAX_LEDGER_EVENTS) {
      this.events = this.events.slice(-MAX_LEDGER_EVENTS);
    }

    await this.persistEvents();
    this.logger.info(`Event logged: ${fullEvent.phase} ${fullEvent.capability ?? ''}`);
    UltraDevLog.push('SYSTEM', {
      event: 'execution_ledger_log',
      eventId: fullEvent.id,
      phase: fullEvent.phase,
      capability: fullEvent.capability ?? null,
      conversationId: fullEvent.conversationId ?? null,
      success: fullEvent.success ?? null,
      cost: fullEvent.cost ?? null,
      totalEvents: this.events.length,
    });
    return fullEvent;
  }

  async getEvents(filters?: {
    phase?: EventPhase;
    capability?: string;
    conversationId?: string;
    since?: number;
    success?: boolean;
  }): Promise<ExecutionEvent[]> {
    await this.initialize();
    if (!filters) return [...this.events];
    return this.events.filter((e) => {
      if (filters.phase && e.phase !== filters.phase) return false;
      if (filters.capability && e.capability !== filters.capability) return false;
      if (filters.conversationId && e.conversationId !== filters.conversationId) return false;
      if (filters.since && e.timestamp < filters.since) return false;
      if (filters.success !== undefined && e.success !== filters.success) return false;
      return true;
    });
  }

  async checkIdempotency(key: string): Promise<boolean> {
    await this.initialize();
    return this.events.some((e) => e.idempotencyKey === key);
  }

  async getSessionStats(): Promise<SessionStats> {
    await this.initialize();
    const sessionStart = Date.now() - SESSION_WINDOW_MS;
    const sessionEvents = this.events.filter((e) => e.timestamp >= sessionStart);
    const actionCount = sessionEvents.filter((e) => e.phase === 'EXECUTE').length;
    const highRiskCount = sessionEvents.filter(
      (e) => e.phase === 'EXECUTE' && e.inputSummary.includes('[dangerous]')
    ).length;
    const totalCost = sessionEvents.reduce((sum, e) => sum + (e.cost ?? 0), 0);
    return { actionCount, highRiskCount, totalCost };
  }

  async getDailyStats(): Promise<{ totalCost: number }> {
    await this.initialize();
    const dayStart = Date.now() - DAILY_WINDOW_MS;
    const dailyEvents = this.events.filter(e => e.timestamp >= dayStart);
    const totalCost = dailyEvents.reduce((sum, e) => sum + (e.cost ?? 0), 0);
    return { totalCost };
  }

  async checkBudget(): Promise<BudgetCheck> {
    const stats = await this.getSessionStats();

    if (stats.actionCount >= this.budgetLimits.maxActionsPerSession) {
      UltraDevLog.push('SYSTEM', { event: 'execution_budget_exceeded', reason: 'max_actions', actionCount: stats.actionCount, limit: this.budgetLimits.maxActionsPerSession });
      return {
        allowed: false,
        reason: `Session action limit reached (${stats.actionCount}/${this.budgetLimits.maxActionsPerSession}). Wait for the session window to reset.`,
      };
    }

    if (stats.totalCost >= this.budgetLimits.maxCostPerSession) {
      UltraDevLog.push('SYSTEM', { event: 'execution_budget_exceeded', reason: 'max_cost_session', totalCost: stats.totalCost, limit: this.budgetLimits.maxCostPerSession });
      return {
        allowed: false,
        reason: `Session cost limit reached ($${stats.totalCost.toFixed(2)}/$${this.budgetLimits.maxCostPerSession.toFixed(2)}). Wait for the session window to reset.`,
      };
    }

    if (stats.highRiskCount >= this.budgetLimits.maxHighRiskPerSession) {
      UltraDevLog.push('SYSTEM', { event: 'execution_budget_exceeded', reason: 'max_high_risk', highRiskCount: stats.highRiskCount, limit: this.budgetLimits.maxHighRiskPerSession });
      return {
        allowed: false,
        reason: `High-risk operation limit reached (${stats.highRiskCount}/${this.budgetLimits.maxHighRiskPerSession}). Wait for the session window to reset.`,
      };
    }

    if (this.budgetLimits.maxCostPerDay > 0) {
      const dailyStats = await this.getDailyStats();
      if (dailyStats.totalCost >= this.budgetLimits.maxCostPerDay) {
        UltraDevLog.push('SYSTEM', { event: 'execution_budget_exceeded', reason: 'max_cost_daily', totalCost: dailyStats.totalCost, limit: this.budgetLimits.maxCostPerDay });
        return {
          allowed: false,
          reason: `Daily cost limit reached ($${dailyStats.totalCost.toFixed(2)}/$${this.budgetLimits.maxCostPerDay.toFixed(2)}). Resets after 24 hours.`,
        };
      }
    }

    UltraDevLog.push('SYSTEM', { event: 'execution_budget_ok', actionCount: stats.actionCount, totalCost: stats.totalCost, highRiskCount: stats.highRiskCount });
    return { allowed: true, reason: 'Within budget' };
  }

  checkTaskBudget(taskCostSoFar: number): BudgetCheck {
    if (this.budgetLimits.maxCostPerTask <= 0) return { allowed: true, reason: 'No per-task limit set' };
    if (taskCostSoFar >= this.budgetLimits.maxCostPerTask) {
      UltraDevLog.push('SYSTEM', { event: 'execution_task_budget_exceeded', taskCostSoFar, limit: this.budgetLimits.maxCostPerTask });
      return {
        allowed: false,
        reason: `Per-task cost limit reached ($${taskCostSoFar.toFixed(4)}/$${this.budgetLimits.maxCostPerTask.toFixed(2)}). Start a new conversation to continue.`,
      };
    }
    return { allowed: true, reason: 'Within per-task budget' };
  }

  private async loadEvents(): Promise<void> {
    if (isNative) {
      try {
        const filePath = this.baseDir + LEDGER_FILE;
        const info = await FileSystem.getInfoAsync(filePath);
        if (info.exists) {
          const raw = await FileSystem.readAsStringAsync(filePath);
          this.events = JSON.parse(raw) ?? [];
          UltraDevLog.push('SYSTEM', { event: 'execution_ledger_load_ok', source: 'file', eventCount: this.events.length });
        } else {
          UltraDevLog.push('SYSTEM', { event: 'execution_ledger_load_ok', source: 'file', eventCount: 0, note: 'no_file_yet' });
        }
      } catch (e: any) {
        this.logger.error('Failed to load ledger from file system', e);
        UltraDevLog.push('SYSTEM', { event: 'execution_ledger_load_fail', source: 'file', error: e?.message });
        this.events = [];
      }
    } else {
      try {
        const raw = localStorage.getItem(WEB_STORAGE_KEY);
        if (raw) {
          this.events = JSON.parse(raw) ?? [];
          UltraDevLog.push('SYSTEM', { event: 'execution_ledger_load_ok', source: 'localStorage', eventCount: this.events.length });
        } else {
          UltraDevLog.push('SYSTEM', { event: 'execution_ledger_load_ok', source: 'localStorage', eventCount: 0, note: 'no_data_yet' });
        }
      } catch (e: any) {
        this.logger.error('Failed to load ledger from localStorage', e);
        UltraDevLog.push('SYSTEM', { event: 'execution_ledger_load_fail', source: 'localStorage', error: e?.message });
        this.events = [];
      }
    }
  }

  private async persistEvents(): Promise<void> {
    const data = JSON.stringify(this.events);

    if (isNative) {
      try {
        const filePath = this.baseDir + LEDGER_FILE;
        await FileSystem.writeAsStringAsync(filePath, data);
        UltraDevLog.push('SYSTEM', { event: 'execution_ledger_persist_ok', source: 'file', eventCount: this.events.length });
      } catch (e: any) {
        this.logger.error('Failed to persist ledger to file system', e);
        UltraDevLog.push('SYSTEM', { event: 'execution_ledger_persist_fail', source: 'file', error: e?.message });
      }
    } else {
      try {
        localStorage.setItem(WEB_STORAGE_KEY, data);
        UltraDevLog.push('SYSTEM', { event: 'execution_ledger_persist_ok', source: 'localStorage', eventCount: this.events.length });
      } catch (e: any) {
        this.logger.error('Failed to persist ledger to localStorage', e);
        UltraDevLog.push('SYSTEM', { event: 'execution_ledger_persist_fail', source: 'localStorage', error: e?.message });
      }
    }
  }
}
