import { NativeModules, Platform, AppState } from 'react-native';
import { ProactiveEngine, ProactiveSuggestion } from './ProactiveEngine';
import { DeviceSignals } from './DeviceSignals';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

const isNative = Platform.OS !== 'web';
let _orchestratorInstance: BackgroundOrchestrator | null = null;

export class BackgroundOrchestrator {
  private isRunning = false;
  private onSuggestion: ((suggestions: ProactiveSuggestion[]) => void) | null = null;
  private pendingTasks: Map<string, { type: string; startedAt: number; timeoutHandle: ReturnType<typeof setTimeout> }> = new Map();
  private appStateSubscription: { remove: () => void } | null = null;
  private warmupTimer: ReturnType<typeof setTimeout> | null = null;
  private static readonly TASK_TIMEOUT_MS = 55_000;

  constructor(private proactive: ProactiveEngine, private signals: DeviceSignals) { _orchestratorInstance = this; }

  async start(onSuggestion?: (suggestions: ProactiveSuggestion[]) => void): Promise<void> {
    if (this.isRunning) return;
    this.isRunning = true;
    this.onSuggestion = onSuggestion || null;
    DebugLog.push('BG_SERVICE' as any, { event: 'start' });
    if (isNative) {
      try { const native = NativeModules.AgentNative; if (native?.startBackgroundAgent) { await native.startBackgroundAgent(); DebugLog.push('BG_SERVICE' as any, { event: 'foreground_service_started' }); } } catch (e: any) { DebugLog.error('BackgroundOrchestrator', `FG service failed: ${e.message}`); }
    }
    // Request notification permission
    try {
      const Notifications = await import('expo-notifications');
      const { status } = await Notifications.requestPermissionsAsync();
      DebugLog.push('BG_SERVICE' as any, { event: 'notification_permission', status });
    } catch {}
    this.warmupTimer = setTimeout(async () => { try { await this.runSignalRead(); await this.runProactiveEval(); } catch {} }, 30_000);
    this.appStateSubscription = AppState.addEventListener('change', this.handleAppStateChange);
  }

  async runSignalRead(): Promise<void> {
    const sid = `sig_${Date.now().toString(36)}`;
    this.startSentinel(sid, 'signal_read');
    try { const snap = await this.signals.read(); DebugLog.push('HEADLESS_TASK' as any, { event: 'signal_read_complete', sentinelId: sid, activity: snap.inferredActivity, battery: snap.batteryLevel }); } catch (e: any) { DebugLog.push('HEADLESS_TASK' as any, { event: 'signal_read_error', sentinelId: sid, error: e.message }); } finally { this.endSentinel(sid); }
  }

  async runProactiveEval(): Promise<void> {
    const sid = `eval_${Date.now().toString(36)}`;
    this.startSentinel(sid, 'proactive_eval');
    try {
      const sug = await this.proactive.evaluate();
      DebugLog.push('HEADLESS_TASK' as any, { event: 'eval_complete', sentinelId: sid, newSuggestions: sug.length });
      if (sug.length > 0 && this.onSuggestion) this.onSuggestion(sug);
      // Send push notification for high/medium urgency suggestions
      if (sug.length > 0) {
        try {
          const Notifications = await import('expo-notifications');
          for (const s of sug) {
            if (s.urgency === 'high' || s.urgency === 'medium') {
              await Notifications.scheduleNotificationAsync({
                content: {
                  title: `💡 ${s.title}`,
                  body: s.body.slice(0, 200),
                  data: { suggestionId: s.id, command: s.suggestedCommand || '' },
                },
                trigger: null,
              });
              DebugLog.push('PROACTIVE_SUGGEST' as any, {
                event: 'notification_sent',
                suggestionId: s.id,
                title: s.title,
                urgency: s.urgency,
              });
            }
          }
        } catch (e: any) {
          DebugLog.error('BackgroundOrchestrator', `Notification send failed: ${e.message}`);
        }
      }
    } catch (e: any) { DebugLog.push('HEADLESS_TASK' as any, { event: 'eval_error', sentinelId: sid, error: e.message }); } finally { this.endSentinel(sid); }
  }

  private startSentinel(id: string, type: string): void {
    const th = setTimeout(() => { DebugLog.push('HEADLESS_TASK' as any, { event: 'task_timeout', sentinelId: id, type, note: 'Likely killed by OS' }); this.pendingTasks.delete(id); }, BackgroundOrchestrator.TASK_TIMEOUT_MS);
    this.pendingTasks.set(id, { type, startedAt: Date.now(), timeoutHandle: th });
    DebugLog.push('HEADLESS_TASK' as any, { event: 'task_start', sentinelId: id, type });
  }

  private endSentinel(id: string): void { const e = this.pendingTasks.get(id); if (e) { clearTimeout(e.timeoutHandle); this.pendingTasks.delete(id); } }

  async stop(): Promise<void> {
    if (!this.isRunning) return; this.isRunning = false;
    if (this.warmupTimer) { clearTimeout(this.warmupTimer); this.warmupTimer = null; }
    this.appStateSubscription?.remove();
    this.appStateSubscription = null;
    for (const entry of this.pendingTasks.values()) clearTimeout(entry.timeoutHandle);
    this.pendingTasks.clear();
    if (isNative) { try { const n = NativeModules.AgentNative; if (n?.stopBackgroundAgent) await n.stopBackgroundAgent(); } catch {} }
    _orchestratorInstance = null;
    DebugLog.push('BG_SERVICE' as any, { event: 'stopped' });
  }

  private handleAppStateChange = (state: string) => {
    DebugLog.push('BG_SERVICE' as any, { event: 'app_state_change', state });
    if (state === 'active') this.runSignalRead().catch(() => {});
  };

  isActive(): boolean { return this.isRunning; }
}
