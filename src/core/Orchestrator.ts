import { TaskAgent } from './TaskAgent';
import { AgentBus } from './AgentBus';
import { TaskGraph } from './TaskGraph';
import { ModelRouter } from './ModelRouter';
import { CostTracker } from '../services/CostTracker';
import { Logger } from '../utils/Logger';
import { UltraDevLog } from '../utils/UltraDevLog';

interface SwarmConfig {
  maxConcurrentAgents: number;
  perTaskBudget: number;
}

export class Orchestrator {
  private ai: ModelRouter;
  private costTracker: CostTracker;
  private bus: AgentBus;
  private logger: Logger;
  private activeAgents: Map<string, TaskAgent>;
  private config: SwarmConfig;

  constructor(ai: ModelRouter, costTracker: CostTracker) {
    this.ai = ai;
    this.costTracker = costTracker;
    this.bus = new AgentBus();
    this.logger = new Logger('Orchestrator');
    this.activeAgents = new Map();
    this.config = { maxConcurrentAgents: 6, perTaskBudget: 1.0 };
  }

  async decompose(request: string, taskId: string): Promise<TaskGraph> {
    const prompt = `Decompose this task into parallel subtasks for a multi-agent system.\n\nTask: "${request}"\n\nRules:\n- Each subtask independently executable\n- Mark dependencies\n- Assign complexity: low/medium/high\n- Max ${this.config.maxConcurrentAgents} subtasks\n\nRespond ONLY JSON:\n{"subtasks":[{"id":"t1","description":"what","complexity":"high","dependencies":[]}]}`;
    const result = await this.ai.complete(prompt, { taskId, agentId: 'orchestrator', temperature: 0.3, maxTokens: 2000 });
    const graph = new TaskGraph();
    try {
      const parsed = JSON.parse(result.content);
      for (const st of parsed.subtasks) {
        graph.addNode({
          id: st.id,
          description: st.description,
          assignedModel: this.modelForComplexity(st.complexity),
          dependencies: st.dependencies || [],
          status: 'pending',
        });
      }
    } catch {
      graph.addNode({ id: 't1', description: request, assignedModel: this.ai.selectModel('code'), dependencies: [], status: 'pending' });
    }
    this.logger.info(`Decomposed into ${graph.getSize()} subtasks`);
    UltraDevLog.push('SYSTEM', { event: 'orchestrator_decompose_done', taskId, subtaskCount: graph.getSize() });
    return graph;
  }

  async executeGraph(graph: TaskGraph, taskId: string): Promise<Record<string, any>> {
    const results: Record<string, any> = {};
    while (!graph.isComplete()) {
      const ready = graph.getReady();
      if (ready.length === 0 && graph.getRunning().length === 0) { this.logger.error('Deadlock'); break; }
      if (!this.costTracker.isWithinTaskLimit(taskId)) { this.logger.warn('Budget exceeded'); break; }
      const running = graph.getRunning().length;
      const batch = ready.slice(0, Math.min(ready.length, this.config.maxConcurrentAgents - running));
      const promises = batch.map(async (node) => {
        graph.markRunning(node.id);
        const agent = new TaskAgent(node.id, taskId, node.assignedModel, this.ai, this.bus);
        this.activeAgents.set(node.id, agent);
        try {
          const depResults = graph.getDependencyResults(node.id);
          const result = await agent.execute(node.description, depResults);
          graph.markCompleted(node.id, result);
          results[node.id] = result;
        } catch (e: any) {
          graph.markFailed(node.id, e.message);
          results[node.id] = { error: e.message };
        } finally {
          agent.destroy();
          this.activeAgents.delete(node.id);
        }
      });
      await Promise.allSettled(promises);
    }
    this.bus.clear();
    return results;
  }

  async orchestrate(request: string, taskId: string): Promise<{ results: Record<string, any>; graph: TaskGraph; totalCost: number }> {
    this.logger.info(`Orchestrating: ${request.substring(0, 50)}...`);
    const graph = await this.decompose(request, taskId);
    const results = await this.executeGraph(graph, taskId);
    return { results, graph, totalCost: this.costTracker.getTaskSpend(taskId) };
  }

  private modelForComplexity(c: string): string {
    if (c === 'high') return this.ai.selectModel('code');
    if (c === 'low') return this.ai.selectModel('simple');
    return this.ai.selectModel('analysis');
  }

  killAll(): void {
    for (const [, agent] of this.activeAgents) agent.destroy();
    this.activeAgents.clear();
    this.bus.clear();
  }

  setConfig(cfg: Partial<SwarmConfig>): void { this.config = { ...this.config, ...cfg }; }
  getActiveCount(): number { return this.activeAgents.size; }
}
