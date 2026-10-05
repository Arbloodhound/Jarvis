import { ModelRouter } from './ModelRouter';
import { AgentBus } from './AgentBus';
import { Logger } from '../utils/Logger';
import { UltraDevLog } from '../utils/UltraDevLog';

export class TaskAgent {
  readonly id: string;
  readonly taskId: string;
  private modelRouter: ModelRouter;
  private bus: AgentBus;
  private logger: Logger;
  private model: string;
  private context: Array<{ role: string; content: string }>;
  private inbox: any[];

  constructor(id: string, taskId: string, model: string, modelRouter: ModelRouter, bus: AgentBus) {
    this.id = id;
    this.taskId = taskId;
    this.model = model;
    this.modelRouter = modelRouter;
    this.bus = bus;
    this.logger = new Logger(`Agent:${id}`);
    this.context = [];
    this.inbox = [];
    this.bus.subscribe(id, (message) => {
      this.inbox.push(message.payload);
    });
  }

  async execute(description: string, dependencyResults: Record<string, any>): Promise<string> {
    this.logger.info(`Executing: ${description}`);
    let prompt = `Task: ${description}\n`;
    if (Object.keys(dependencyResults).length > 0) {
      prompt += `\nResults from prior steps:\n`;
      for (const [depId, result] of Object.entries(dependencyResults)) {
        const rs = typeof result === 'string' ? result : JSON.stringify(result);
        prompt += `[${depId}]: ${rs.substring(0, 2000)}\n`;
      }
    }
    if (this.inbox.length > 0) {
      prompt += `\nMessages from other agents:\n`;
      for (const msg of this.inbox) {
        const ms = typeof msg === 'string' ? msg : JSON.stringify(msg);
        prompt += `${ms.substring(0, 1000)}\n`;
      }
      this.inbox = [];
    }
    prompt += `\nProvide a complete, actionable response. If generating code, provide ALL code with no omissions.`;
    this.context.push({ role: 'user', content: prompt });
    const result = await this.modelRouter.completeWithConversation(
      [
        { role: 'system', content: `You are Agent ${this.id}, part of a multi-agent swarm. Your job: ${description}. Be precise and complete.` },
        ...this.context,
      ],
      { model: this.model, taskId: this.taskId, agentId: this.id, temperature: 0.5 }
    );
    this.context.push({ role: 'assistant', content: result.content });
    this.bus.broadcast(this.id, 'result', { agentId: this.id, task: description, result: result.content.substring(0, 2000) });
    this.logger.info(`Completed. Cost: $${result.cost.toFixed(6)}`);
    UltraDevLog.push('SYSTEM', { event: 'task_agent_execute_done', agentId: this.id, taskId: this.taskId, cost: result.cost, contentLen: result.content.length });
    return result.content;
  }

  sendTo(targetId: string, data: any): void {
    this.bus.send(this.id, targetId, 'data', data);
  }

  destroy(): void {
    this.bus.unsubscribe(this.id);
    this.context = [];
    this.inbox = [];
  }
}
