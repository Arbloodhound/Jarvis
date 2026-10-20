import * as ExpoFileSystem from 'expo-file-system/legacy';
import { Platform } from 'react-native';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import type { ChatMessage, Conversation, ConversationMeta } from '../types/ultra';

const FileSystem: any = Platform.OS !== 'web' ? ExpoFileSystem : null;

const PREFIX = 'ultra:conv:';
const INDEX_KEY = 'ultra:conv:index';
const MEMORY = new Map<string, Conversation>();

function now() { return Date.now(); }
function uid(prefix = 'id') { return `${prefix}_${Math.random().toString(36).slice(2)}_${Date.now()}`; }
function titleFromText(text: string) {
  const t = (text || 'New Chat').replace(/\s+/g, ' ').trim();
  return t.length > 40 ? `${t.slice(0, 40)}…` : t;
}

export class ConversationManager {
  private mode: 'native' | 'web' | 'memory' = 'memory';
  private dir: string = '';

  constructor() {
    if (Platform.OS === 'web') {
      this.mode = 'web';
    } else if (FileSystem && FileSystem.documentDirectory) {
      this.mode = 'native';
      this.dir = `${FileSystem.documentDirectory}conversations`;
    } else {
      this.mode = 'memory';
    }
  }

  private async ensureReady() {
    if (this.mode !== 'native') return;
    const info = await FileSystem.getInfoAsync(this.dir);
    if (!info.exists) await FileSystem.makeDirectoryAsync(this.dir, { intermediates: true });
  }

  private filePath(id: string) { return `${this.dir}/${id}.json`; }

  private getWebIndex(): string[] {
    const raw = localStorage.getItem(INDEX_KEY);
    return raw ? JSON.parse(raw) : [];
  }

  private setWebIndex(ids: string[]) {
    localStorage.setItem(INDEX_KEY, JSON.stringify(ids));
  }

  async createConversation(initialUserMessage?: string): Promise<Conversation> {
    const id = uid('conv');
    const createdAt = now();
    const title = initialUserMessage ? titleFromText(initialUserMessage) : 'New Chat';
    const conv: Conversation = {
      id,
      title,
      createdAt,
      updatedAt: createdAt,
      summary: '',
      summaryUpdatedAt: 0,
      messageCountSinceSummary: 0,
      messages: [],
    };
    await this.saveConversation(conv);
    DebugLog.conversationCreated(id, title);
    return conv;
  }

  private saveQueue = Promise.resolve();

  async saveConversation(conv: Conversation): Promise<void> {
    // Serialize writes to prevent concurrent file operations
    this.saveQueue = this.saveQueue.then(() => this._doSave(conv)).catch(() => this._doSave(conv));
    return this.saveQueue;
  }

  private async _doSave(conv: Conversation): Promise<void> {
    conv.updatedAt = now();
    if (this.mode === 'native') {
      try {
        await this.ensureReady();
        const tmpPath = `${this.dir}/${conv.id}.tmp`;
        const finalPath = this.filePath(conv.id);
        await FileSystem.writeAsStringAsync(tmpPath, JSON.stringify(conv), { encoding: FileSystem.EncodingType.UTF8 });
        try {
          await FileSystem.moveAsync({ from: tmpPath, to: finalPath });
        } catch (e: any) {
          DebugLog.error('ConvManager', `Atomic move failed, direct write: ${e?.message}`, e?.stack);
          await FileSystem.writeAsStringAsync(finalPath, JSON.stringify(conv), { encoding: FileSystem.EncodingType.UTF8 });
          try { await FileSystem.deleteAsync(tmpPath, { idempotent: true }); } catch {}
        }
        DebugLog.conversationSaved(conv.id, conv.messages.length);
      } catch (err: any) {
        DebugLog.conversationError(conv.id, `[save] ${err.message}`);
        throw err;
      }
      return;
    }
    if (this.mode === 'web') {
      localStorage.setItem(`${PREFIX}${conv.id}`, JSON.stringify(conv));
      const idx = this.getWebIndex();
      if (!idx.includes(conv.id)) this.setWebIndex([conv.id, ...idx]);
      DebugLog.conversationSaved(conv.id, conv.messages.length);
      return;
    }
    MEMORY.set(conv.id, conv);
    DebugLog.conversationSaved(conv.id, conv.messages.length);
  }

  async loadConversation(id: string): Promise<Conversation | null> {
    try {
      if (this.mode === 'native') {
        await this.ensureReady();
        const info = await FileSystem.getInfoAsync(this.filePath(id));
        if (!info.exists) return null;
        const raw = await FileSystem.readAsStringAsync(this.filePath(id), { encoding: FileSystem.EncodingType.UTF8 });
        const conv = JSON.parse(raw) as Conversation;
        DebugLog.conversationLoaded(id, conv.messages.length);
        return conv;
      }
      if (this.mode === 'web') {
        const raw = localStorage.getItem(`${PREFIX}${id}`);
        if (!raw) return null;
        const conv = JSON.parse(raw) as Conversation;
        DebugLog.conversationLoaded(id, conv.messages.length);
        return conv;
      }
      const conv = MEMORY.get(id) ?? null;
      if (conv) DebugLog.conversationLoaded(id, conv.messages.length);
      return conv;
    } catch (err: any) {
      DebugLog.conversationError(id, `[load] ${err.message}`);
      return null;
    }
  }

  async listConversations(): Promise<ConversationMeta[]> {
    let convs: Conversation[] = [];

    if (this.mode === 'native') {
      await this.ensureReady();
      const files = await FileSystem.readDirectoryAsync(this.dir);
      const jsonFiles = files.filter((f: string) => f.endsWith('.json'));
      const loaded = await Promise.all(jsonFiles.map(async (f: string) => {
        try {
          const raw = await FileSystem.readAsStringAsync(`${this.dir}/${f}`, { encoding: FileSystem.EncodingType.UTF8 });
          return JSON.parse(raw) as Conversation;
        } catch (e: any) {
          DebugLog.error('ConvManager', `Failed to load conversation ${f}: ${e?.message}`, e?.stack);
          return null;
        }
      }));
      convs = loaded.filter((c): c is Conversation => c !== null);
    } else if (this.mode === 'web') {
      const idx = this.getWebIndex();
      convs = idx
        .map((id) => localStorage.getItem(`${PREFIX}${id}`))
        .filter(Boolean)
        .map((raw) => JSON.parse(raw as string) as Conversation);
    } else {
      convs = Array.from(MEMORY.values());
    }

    const result = convs
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .map((c) => ({
        id: c.id,
        title: c.title,
        createdAt: c.createdAt,
        updatedAt: c.updatedAt,
        preview: c.messages[c.messages.length - 1]?.content ?? '',
        messageCount: c.messages.length,
        starred: !!c.meta?.starred,
      }));
    DebugLog.conversationList(result.length);
    return result;
  }

  async deleteConversation(id: string): Promise<void> {
    DebugLog.conversationDeleted(id);
    if (this.mode === 'native') {
      const info = await FileSystem.getInfoAsync(this.filePath(id));
      if (info.exists) await FileSystem.deleteAsync(this.filePath(id), { idempotent: true });
      return;
    }
    if (this.mode === 'web') {
      localStorage.removeItem(`${PREFIX}${id}`);
      const idx = this.getWebIndex();
      this.setWebIndex(idx.filter(x => x !== id));
      return;
    }
    MEMORY.delete(id);
  }

  async addMessage(conversationId: string, msg: ChatMessage): Promise<Conversation> {
    const conv = await this.loadConversation(conversationId);
    if (!conv) throw new Error(`Conversation ${conversationId} not found`);

    const MAX_MESSAGES = 200;
    if (conv.messages.length >= MAX_MESSAGES) {
      const head = conv.messages.slice(0, 5);
      const tail = conv.messages.slice(-145);
      conv.messages = [...head, ...tail];
      DebugLog.systemEvent('ConversationManager', `Trimmed conversation ${conversationId}: ${MAX_MESSAGES} → ${conv.messages.length} messages (kept first 5 + last 145)`);
    }

    conv.messages.push(msg);
    conv.updatedAt = now();
    conv.messageCountSinceSummary = (conv.messageCountSinceSummary ?? 0) + 1;
    if (conv.title === 'New Chat' && msg.role === 'user') conv.title = titleFromText(msg.content);
    await this.saveConversation(conv);
    DebugLog.conversationMessage(conversationId, msg.role, msg.content?.length ?? 0, msg.source);
    return conv;
  }

  async updateTitle(conversationId: string, title: string): Promise<void> {
    const conv = await this.loadConversation(conversationId);
    if (!conv) return;
    conv.title = titleFromText(title);
    await this.saveConversation(conv);
  }

  async updateSummary(conversationId: string, summary: string): Promise<void> {
    const conv = await this.loadConversation(conversationId);
    if (!conv) return;
    conv.summary = summary;
    conv.summaryUpdatedAt = now();
    conv.messageCountSinceSummary = 0;
    await this.saveConversation(conv);
  }

  async updateMeta(conversationId: string, patch: Record<string, unknown>): Promise<void> {
    const conv = await this.loadConversation(conversationId);
    if (!conv) return;
    conv.meta = { ...(conv.meta || {}), ...patch };
    await this.saveConversation(conv);
  }

  async getMostRecentConversationId(): Promise<string | null> {
    const list = await this.listConversations();
    return list[0]?.id ?? null;
  }
}
