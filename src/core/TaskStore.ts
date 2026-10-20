import { SecureVault } from '../security/SecureVault';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

export type TaskStatus = 'pending' | 'active' | 'paused' | 'completed' | 'failed' | 'cancelled';

export interface SubTask {
  id: string;
  description: string;
  capability: string;
  params: Record<string, any>;
  status: TaskStatus;
  result?: any;
  error?: string;
  dependsOn: string[];
  startedAt?: number;
  completedAt?: number;
  retries: number;
}

export interface TaskProgressEntry {
  timestamp: number;
  subTaskId: string;
  event: string;
  detail: string;
}

export interface StoredTask {
  id: string;
  goal: string;
  status: TaskStatus;
  subTasks: SubTask[];
  progress: TaskProgressEntry[];
  contextSnapshot: string;
  createdAt: number;
  updatedAt: number;
  completedAt?: number;
  resumable: boolean;
  result?: string;
  error?: string;
  conversationId: string;
  metadata: Record<string, any>;
}

const TASK_INDEX_KEY = 'cortex_task_index';
const TASK_PREFIX = 'cortex_task_';
const MAX_STORED_TASKS = 50;

export class TaskStore {
  private index: string[] = [];
  private cache: Map<string, StoredTask> = new Map();

  constructor(private vault: SecureVault) {}

  async initialize(): Promise<void> {
    try {
      const raw = await this.vault.get(TASK_INDEX_KEY).catch(() => null);
      if (raw) this.index = JSON.parse(raw);
      DebugLog.push('TASK_STORE' as any, { event: 'initialized', taskCount: this.index.length });
    } catch (err: any) {
      DebugLog.error('TaskStore', `Init failed: ${err.message}`);
      this.index = [];
    }
  }

  async create(params: {
    goal: string; conversationId: string; subTasks?: SubTask[];
    contextSnapshot?: string; resumable?: boolean; metadata?: Record<string, any>;
  }): Promise<StoredTask> {
    const id = `task_${Date.now().toString(36)}_${Math.random().toString(36).slice(2, 6)}`;
    const now = Date.now();
    const task: StoredTask = {
      id, goal: params.goal, status: 'pending', subTasks: params.subTasks || [],
      progress: [], contextSnapshot: params.contextSnapshot || '',
      createdAt: now, updatedAt: now, resumable: params.resumable ?? true,
      conversationId: params.conversationId, metadata: params.metadata || {},
    };
    await this.persist(task);
    this.index.push(id);
    await this.saveIndex();
    while (this.index.length > MAX_STORED_TASKS) {
      const oldId = this.index.shift()!;
      try { await this.vault.delete(TASK_PREFIX + oldId); } catch {}
      this.cache.delete(oldId);
    }
    DebugLog.push('TASK_STORE' as any, { event: 'created', taskId: id, goal: params.goal.slice(0, 60), subTaskCount: task.subTasks.length });
    return task;
  }

  async get(id: string): Promise<StoredTask | null> {
    if (this.cache.has(id)) return this.cache.get(id)!;
    try {
      const raw = await this.vault.get(TASK_PREFIX + id);
      if (!raw) return null;
      const task: StoredTask = JSON.parse(raw);
      this.cache.set(id, task);
      return task;
    } catch { return null; }
  }

  async update(id: string, updates: Partial<StoredTask>): Promise<StoredTask | null> {
    const task = await this.get(id);
    if (!task) return null;
    Object.assign(task, updates, { updatedAt: Date.now() });
    await this.persist(task);
    DebugLog.push('TASK_STORE' as any, { event: 'updated', taskId: id, status: task.status });
    return task;
  }

  async addProgress(taskId: string, subTaskId: string, event: string, detail: string): Promise<void> {
    const task = await this.get(taskId);
    if (!task) return;
    task.progress.push({ timestamp: Date.now(), subTaskId, event, detail });
    task.updatedAt = Date.now();
    if (task.progress.length > 200) task.progress = task.progress.slice(-200);
    await this.persist(task);
    DebugLog.push('TASK_STORE' as any, { event: 'progress', taskId, subTaskId, progressEvent: event });
  }

  async updateSubTask(taskId: string, subTaskId: string, updates: Partial<SubTask>): Promise<void> {
    const task = await this.get(taskId);
    if (!task) return;
    const sub = task.subTasks.find(s => s.id === subTaskId);
    if (!sub) return;
    Object.assign(sub, updates);
    task.updatedAt = Date.now();
    await this.persist(task);
  }

  async replaceSubTasks(taskId: string, newSubTasks: SubTask[]): Promise<void> {
    const task = await this.get(taskId);
    if (!task) return;
    task.subTasks = newSubTasks;
    task.updatedAt = Date.now();
    task.progress.push({ timestamp: Date.now(), subTaskId: '*', event: 'replanned', detail: `New count: ${newSubTasks.length}` });
    await this.persist(task);
  }

  getReadySubTasks(task: StoredTask): SubTask[] {
    return task.subTasks.filter(st => {
      if (st.status !== 'pending') return false;
      return st.dependsOn.every(depId => {
        const dep = task.subTasks.find(d => d.id === depId);
        return dep && dep.status === 'completed';
      });
    });
  }

  isComplete(task: StoredTask): boolean {
    return task.subTasks.length > 0 && task.subTasks.every(st => st.status === 'completed' || st.status === 'failed');
  }

  async getResumableTasks(): Promise<StoredTask[]> {
    const tasks: StoredTask[] = [];
    for (const id of this.index) {
      const task = await this.get(id);
      if (task && task.resumable && (task.status === 'active' || task.status === 'paused')) tasks.push(task);
    }
    return tasks;
  }

  async listAll(limit: number = 20): Promise<StoredTask[]> {
    const tasks: StoredTask[] = [];
    const ids = this.index.slice(-limit).reverse();
    for (const id of ids) { const task = await this.get(id); if (task) tasks.push(task); }
    return tasks;
  }

  summarize(task: StoredTask): string {
    const done = task.subTasks.filter(s => s.status === 'completed').length;
    const failed = task.subTasks.filter(s => s.status === 'failed').length;
    const active = task.subTasks.filter(s => s.status === 'active').length;
    return `Task "${task.goal.slice(0, 50)}": ${done}/${task.subTasks.length} done, ${active} active, ${failed} failed [${task.status}]`;
  }

  private async persist(task: StoredTask): Promise<void> {
    this.cache.set(task.id, task);
    await this.vault.set(TASK_PREFIX + task.id, JSON.stringify(task));
  }

  private async saveIndex(): Promise<void> {
    await this.vault.set(TASK_INDEX_KEY, JSON.stringify(this.index));
  }
}
