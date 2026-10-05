import { UltraDevLog } from '../utils/UltraDevLog';

interface BusMessage {
  id: string;
  fromAgent: string;
  toAgent: string;
  type: 'data' | 'status' | 'error' | 'result';
  payload: any;
  timestamp: number;
}

type MessageHandler = (message: BusMessage) => void;

export class AgentBus {
  private subscribers: Map<string, MessageHandler[]>;
  private messageLog: BusMessage[];
  private static readonly MAX_LOG = 200;

  constructor() {
    this.subscribers = new Map();
    this.messageLog = [];
    UltraDevLog.push('SYSTEM', { event: 'agent_bus_init' });
  }

  subscribe(agentId: string, handler: MessageHandler): void {
    if (!this.subscribers.has(agentId)) this.subscribers.set(agentId, []);
    this.subscribers.get(agentId)!.push(handler);
    UltraDevLog.push('SYSTEM', { event: 'agent_bus_subscribe', agentId, listenerCount: this.subscribers.get(agentId)!.length });
  }

  unsubscribe(agentId: string): void {
    this.subscribers.delete(agentId);
    UltraDevLog.push('SYSTEM', { event: 'agent_bus_unsubscribe', agentId });
  }

  send(from: string, to: string, type: BusMessage['type'], payload: any): void {
    const message: BusMessage = {
      id: Date.now().toString(36) + Math.random().toString(36).substring(2, 6),
      fromAgent: from,
      toAgent: to,
      type,
      payload,
      timestamp: Date.now(),
    };
    this.messageLog.unshift(message);
    if (this.messageLog.length > AgentBus.MAX_LOG) {
      this.messageLog = this.messageLog.slice(0, AgentBus.MAX_LOG);
    }
    const handlers = this.subscribers.get(to);
    const handlerCount = handlers?.length ?? 0;
    let dispatchErrors = 0;
    if (handlers) {
      for (const h of handlers) {
        try { h(message); } catch { dispatchErrors++; /* bus isolation */ }
      }
    }
    UltraDevLog.push('SYSTEM', { event: 'agent_bus_send', msgId: message.id, from, to, type, handlerCount, dispatchErrors, hasHandlers: handlerCount > 0 });
  }

  broadcast(from: string, type: BusMessage['type'], payload: any): void {
    const targets = Array.from(this.subscribers.keys()).filter(id => id !== from);
    UltraDevLog.push('SYSTEM', { event: 'agent_bus_broadcast', from, type, targetCount: targets.length });
    for (const agentId of targets) {
      this.send(from, agentId, type, payload);
    }
  }

  getMessages(agentId: string): BusMessage[] {
    return this.messageLog.filter((m) => m.toAgent === agentId || m.fromAgent === agentId);
  }

  clear(): void {
    this.messageLog = [];
    this.subscribers.clear();
    UltraDevLog.push('SYSTEM', { event: 'agent_bus_clear' });
  }
}
