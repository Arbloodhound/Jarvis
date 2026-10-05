import { NativeEventEmitter, NativeModules, Platform } from 'react-native';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import { MemoryManager } from '../core/MemoryManager';
import type { TaskExecutor } from '../core/TaskExecutor';
import type { ModelRouter } from '../core/ModelRouter';

export interface EventTrigger {
  id: string;
  type: 'sms' | 'battery' | 'notification' | 'schedule' | 'sms_content' | 'battery_level' | 'time_range';
  condition: string; action: string; enabled: boolean; createdAt: number;
  lastFired?: number; metadata?: Record<string, any>;
}

export class EventMonitor {
  private triggers: EventTrigger[] = [];
  private scheduleInterval: ReturnType<typeof setInterval> | null = null;
  private batteryInterval: ReturnType<typeof setInterval> | null = null;
  private smsInterval: ReturnType<typeof setInterval> | null = null;
  private isRunning = false;
  private uiEventEmitter: NativeEventEmitter | null = null;
  private uiTreeSubscription: { remove: () => void } | null = null;

  constructor(
    private executor: TaskExecutor,
    private memory: MemoryManager,
    private ai: ModelRouter
  ) {}

  async start(): Promise<void> {
    if (this.isRunning || Platform.OS !== 'android') return;
    this.isRunning = true;

    await this.loadTriggers();

    if (NativeModules.AppController) {
      this.uiEventEmitter = new NativeEventEmitter(NativeModules.AppController);
      this.uiTreeSubscription = this.uiEventEmitter.addListener('onUiTreeChanged', (event) => {
        this.evaluateNotificationTriggers(event.packageName).catch(() => {});
      });
    }

    this.scheduleInterval = setInterval(() => {
      this.evaluateScheduleTriggers().catch(() => {});
    }, 60000);

    this.batteryInterval = setInterval(() => {
      this.evaluateBatteryTriggers().catch(() => {});
    }, 30000);

    this.smsInterval = setInterval(() => {
      this.evaluateSmsContentTriggers().catch(() => {});
    }, 120000);

    DebugLog.systemEvent('EventMonitor', `Started. Triggers: ${this.triggers.length}`);
  }

  async stop(): Promise<void> {
    if (!this.isRunning) return;
    this.isRunning = false;
    if (this.scheduleInterval) clearInterval(this.scheduleInterval);
    if (this.batteryInterval) clearInterval(this.batteryInterval);
    if (this.smsInterval) clearInterval(this.smsInterval);
    this.scheduleInterval = null;
    this.batteryInterval = null;
    this.smsInterval = null;
    this.uiTreeSubscription?.remove();
    this.uiTreeSubscription = null;
    this.uiEventEmitter = null;
  }

  private async evaluateScheduleTriggers(): Promise<void> {
    const now = new Date();
    const hour = now.getHours();
    const minute = now.getMinutes();
    const dayOfWeek = now.getDay();
    for (const trigger of this.triggers.filter(t => t.enabled && t.type === 'schedule')) {
      const conditionMet = this.evaluateCondition(trigger.condition, { hour, minute, dayOfWeek });
      if (conditionMet) {
        const cooldown = 60000;
        if (!trigger.lastFired || Date.now() - trigger.lastFired > cooldown) {
          trigger.lastFired = Date.now();
          await this.fireTrigger(trigger);
        }
      }
    }
  }

  private async evaluateNotificationTriggers(packageName: string): Promise<void> {
    for (const trigger of this.triggers.filter(t => t.enabled && t.type === 'notification')) {
      const conditionMet = this.evaluateCondition(trigger.condition, { packageName });
      if (conditionMet) {
        const cooldown = 5000;
        if (!trigger.lastFired || Date.now() - trigger.lastFired > cooldown) {
          trigger.lastFired = Date.now();
          await this.fireTrigger(trigger);
        }
      }
    }
  }

  private async evaluateBatteryTriggers(): Promise<void> {
    const batteryTriggers = this.triggers.filter(t => t.enabled && (t.type === 'battery' || t.type === 'battery_level'));
    if (batteryTriggers.length === 0) return;
    try {
      const { NativeModules: NM } = require('react-native');
      const batteryLevel: number = NM.DeviceInfo?.getBatteryLevel ? await NM.DeviceInfo.getBatteryLevel() : -1;
      if (batteryLevel < 0) return;
      const pct = Math.round(batteryLevel * 100);
      for (const trigger of batteryTriggers) {
        const conditionMet = this.evaluateCondition(trigger.condition, { batteryPct: pct });
        if (conditionMet) {
          const cooldown = 300000;
          if (!trigger.lastFired || Date.now() - trigger.lastFired > cooldown) {
            trigger.lastFired = Date.now();
            await this.fireTrigger(trigger);
          }
        }
      }
    } catch {}
  }

  private async evaluateSmsContentTriggers(): Promise<void> {
    const smsTriggers = this.triggers.filter(t => t.enabled && t.type === 'sms_content');
    if (smsTriggers.length === 0) return;
    try {
      const AgentNative = (await import('../native/AgentNative')).default;
      const recent = await AgentNative.readSms(10, 'inbox');
      for (const trigger of smsTriggers) {
        const keyword = trigger.condition.replace(/sms.*contains?/i, '').trim().toLowerCase();
        const match = recent.find((s: any) => s.body.toLowerCase().includes(keyword));
        if (match) {
          const cooldown = 300000;
          if (!trigger.lastFired || Date.now() - trigger.lastFired > cooldown) {
            trigger.lastFired = Date.now();
            await this.fireTrigger(trigger);
          }
        }
      }
    } catch {}
  }

  private evaluateCondition(condition: string, data: Record<string, any>): boolean {
    const c = condition.toLowerCase();
    if (data.batteryPct !== undefined) {
      const underMatch = condition.match(/battery.*(?:under|below|<)\s*(\d+)%/i);
      if (underMatch) return data.batteryPct < parseInt(underMatch[1], 10);
      const overMatch = condition.match(/battery.*(?:over|above|>)\s*(\d+)%/i);
      if (overMatch) return data.batteryPct > parseInt(overMatch[1], 10);
    }
    if (data.hour !== undefined && data.minute !== undefined) {
      // time range: "between 9am and 5pm"
      const rangeMatch = condition.match(/between\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\s+and\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?/i);
      if (rangeMatch) {
        let startH = parseInt(rangeMatch[1], 10);
        let startM = rangeMatch[2] ? parseInt(rangeMatch[2], 10) : 0;
        let endH = parseInt(rangeMatch[4], 10);
        let endM = rangeMatch[5] ? parseInt(rangeMatch[5], 10) : 0;
        if (rangeMatch[3]?.toLowerCase() === 'pm' && startH < 12) startH += 12;
        if (rangeMatch[6]?.toLowerCase() === 'pm' && endH < 12) endH += 12;
        const nowMins = data.hour * 60 + data.minute;
        return nowMins >= startH * 60 + startM && nowMins <= endH * 60 + endM;
      }
      const timeMatch = condition.match(/at\s+(\d{1,2})(?::(\d{2}))?\s*(am|pm)?/i);
      if (timeMatch) {
        let targetHour = parseInt(timeMatch[1], 10);
        const targetMin = timeMatch[2] ? parseInt(timeMatch[2], 10) : 0;
        const ampm = timeMatch[3]?.toLowerCase();
        if (ampm === 'pm' && targetHour < 12) targetHour += 12;
        if (ampm === 'am' && targetHour === 12) targetHour = 0;
        if (targetHour < 0 || targetHour > 23 || targetMin < 0 || targetMin > 59) return false;
        return data.hour === targetHour && Math.abs(data.minute - targetMin) <= 1;
      }
    }
    if (data.packageName) {
      return c.includes(String(data.packageName).toLowerCase());
    }
    // Day of week: "every sunday" / "on mondays" / "weekdays" / "weekends"
    const dayNames: Record<string, number> = { sunday: 0, monday: 1, tuesday: 2, wednesday: 3, thursday: 4, friday: 5, saturday: 6, sun: 0, mon: 1, tue: 2, wed: 3, thu: 4, fri: 5, sat: 6 };
    const dayMatch = condition.match(/(?:every|on)\s+(sunday|monday|tuesday|wednesday|thursday|friday|saturday|sun|mon|tue|wed|thu|fri|sat)s?/i);
    if (dayMatch && data.dayOfWeek !== undefined) {
      const targetDay = dayNames[dayMatch[1].toLowerCase()];
      if (targetDay !== undefined) return data.dayOfWeek === targetDay;
    }
    if (/weekday/i.test(condition) && data.dayOfWeek !== undefined) return data.dayOfWeek >= 1 && data.dayOfWeek <= 5;
    if (/weekend/i.test(condition) && data.dayOfWeek !== undefined) return data.dayOfWeek === 0 || data.dayOfWeek === 6;
    return false;
  }

  private async fireTrigger(trigger: EventTrigger): Promise<void> {
    DebugLog.systemEvent('EventMonitor', `TRIGGER_FIRE id=${trigger.id} action="${trigger.action.slice(0, 60)}"`);
    try {
      const { CommandParser } = await import('../core/CommandParser');
      const parser = new CommandParser();
      const plan = parser.parse(trigger.action);
      if (plan) {
        await this.executor.runWithPlan(plan, `trigger_${trigger.id}`);
      }
    } catch (err: any) {
      DebugLog.error('EventMonitor', `Trigger ${trigger.id} failed: ${err.message}`);
    }
  }

  async addTrigger(trigger: Omit<EventTrigger, 'id' | 'createdAt'>): Promise<string> {
    const id = `trigger_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
    const full: EventTrigger = { ...trigger, id, createdAt: Date.now() };
    this.triggers.push(full);
    await this.memory.storeLongterm(`trigger:${id}`, 'event_trigger', JSON.stringify(full));
    return id;
  }

  async removeTrigger(id: string): Promise<void> {
    this.triggers = this.triggers.filter(t => t.id !== id);
    await this.memory.storeLongterm(`trigger:${id}`, 'event_trigger', JSON.stringify({ deleted: true, id }));
  }

  getTriggers(): EventTrigger[] {
    return [...this.triggers];
  }

  private async loadTriggers(): Promise<void> {
    this.triggers = [];
    const stored = await this.memory.retrieveRelevant('event_trigger', 50);
    for (const record of stored) {
      if (record.capability === 'event_trigger') {
        try {
          const trigger: EventTrigger = JSON.parse(record.outcome);
          if (trigger.id && trigger.type && trigger.action && !(trigger as any).deleted) {
            this.triggers.push(trigger);
          }
        } catch (e) {
          DebugLog.error('EventMonitor', `Failed to parse trigger: ${e instanceof Error ? e.message : 'unknown error'}`);
        }
      }
    }
  }
}