import * as BackgroundFetch from 'expo-background-fetch';
import * as TaskManager from 'expo-task-manager';
import { Logger } from '../utils/Logger';
import { UltraDevLog } from '../utils/UltraDevLog';

const BG_TASK = 'agent-ultra-bg';

export class BackgroundTaskManager {
  private logger: Logger;
  private isRegistered: boolean;

  constructor() {
    this.logger = new Logger('BackgroundTaskManager');
    this.isRegistered = false;
    UltraDevLog.push('SYSTEM', { event: 'bg_task_manager_construct', taskName: BG_TASK });
  }

  async initialize(): Promise<void> {
    TaskManager.defineTask(BG_TASK, async () => {
      UltraDevLog.push('SYSTEM', { event: 'bg_task_execute', taskName: BG_TASK });
      try {
        this.logger.info('Background task executed');
        return BackgroundFetch.BackgroundFetchResult.NewData;
      } catch (err: any) {
        UltraDevLog.push('SYSTEM', { event: 'bg_task_execute_fail', taskName: BG_TASK, error: err?.message });
        return BackgroundFetch.BackgroundFetchResult.Failed;
      }
    });
    this.logger.info('BackgroundTaskManager initialized');
    UltraDevLog.push('SYSTEM', { event: 'bg_task_manager_init_ok', taskName: BG_TASK });
  }

  async start(): Promise<void> {
    if (this.isRegistered) {
      UltraDevLog.push('SYSTEM', { event: 'bg_task_start_skip', reason: 'already_registered', taskName: BG_TASK });
      return;
    }
    try {
      await BackgroundFetch.registerTaskAsync(BG_TASK, {
        minimumInterval: 900,
        stopOnTerminate: false,
        startOnBoot: true,
      });
      this.isRegistered = true;
      this.logger.info('Background tasks started');
      UltraDevLog.push('SYSTEM', { event: 'bg_task_start_ok', taskName: BG_TASK, minimumInterval: 900 });
    } catch (error: any) {
      this.logger.error('Failed to start bg tasks: ' + error.message);
      UltraDevLog.push('SYSTEM', { event: 'bg_task_start_fail', taskName: BG_TASK, error: error?.message });
    }
  }

  async stop(): Promise<void> {
    if (!this.isRegistered) {
      UltraDevLog.push('SYSTEM', { event: 'bg_task_stop_skip', reason: 'not_registered', taskName: BG_TASK });
      return;
    }
    await BackgroundFetch.unregisterTaskAsync(BG_TASK);
    this.isRegistered = false;
    this.logger.info('Background tasks stopped');
    UltraDevLog.push('SYSTEM', { event: 'bg_task_stop_ok', taskName: BG_TASK });
  }
}
