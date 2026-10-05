import { UltraDevLog } from '../utils/UltraDevLog';

export interface TaskNode {
  id: string;
  description: string;
  assignedModel: string;
  dependencies: string[];
  status: 'pending' | 'running' | 'completed' | 'failed';
  result?: any;
  error?: string;
}

export class TaskGraph {
  private nodes: Map<string, TaskNode>;

  constructor() {
    this.nodes = new Map();
    UltraDevLog.push('SYSTEM', { event: 'task_graph_init' });
  }

  addNode(node: TaskNode): void {
    this.nodes.set(node.id, node);
    UltraDevLog.push('SYSTEM', { event: 'task_graph_add_node', nodeId: node.id, deps: node.dependencies, status: node.status, model: node.assignedModel });
  }

  getNode(id: string): TaskNode | undefined {
    const n = this.nodes.get(id);
    if (!n) UltraDevLog.push('SYSTEM', { event: 'task_graph_node_not_found', nodeId: id });
    return n;
  }

  getAllNodes(): TaskNode[] { return Array.from(this.nodes.values()); }
  getSize(): number { return this.nodes.size; }

  getReady(): TaskNode[] {
    return this.getAllNodes().filter((n) => {
      if (n.status !== 'pending') return false;
      return n.dependencies.every((d) => {
        const dep = this.nodes.get(d);
        return dep && dep.status === 'completed';
      });
    });
  }

  getRunning(): TaskNode[] { return this.getAllNodes().filter((n) => n.status === 'running'); }
  isComplete(): boolean { return this.getAllNodes().every((n) => n.status === 'completed' || n.status === 'failed'); }

  markRunning(id: string): void {
    const n = this.nodes.get(id);
    if (n) {
      n.status = 'running';
      UltraDevLog.push('SYSTEM', { event: 'task_graph_mark_running', nodeId: id });
    }
  }

  markCompleted(id: string, result: any): void {
    const n = this.nodes.get(id);
    if (n) {
      n.status = 'completed';
      n.result = result;
      UltraDevLog.push('SYSTEM', { event: 'task_graph_mark_completed', nodeId: id });
    }
  }

  markFailed(id: string, error: string): void {
    const n = this.nodes.get(id);
    if (n) {
      n.status = 'failed';
      n.error = error;
      UltraDevLog.push('SYSTEM', { event: 'task_graph_mark_failed', nodeId: id, error: error.slice(0, 200) });
    }
  }

  getDependencyResults(nodeId: string): Record<string, any> {
    const node = this.nodes.get(nodeId);
    if (!node) return {};
    const results: Record<string, any> = {};
    for (const depId of node.dependencies) {
      const dep = this.nodes.get(depId);
      if (dep?.result) results[depId] = dep.result;
    }
    return results;
  }

  getExecutionOrder(): string[][] {
    const order: string[][] = [];
    const visited = new Set<string>();
    while (visited.size < this.nodes.size) {
      const batch: string[] = [];
      for (const [id, node] of this.nodes) {
        if (visited.has(id)) continue;
        if (node.dependencies.every((d) => visited.has(d))) batch.push(id);
      }
      if (batch.length === 0) break;
      batch.forEach((id) => visited.add(id));
      order.push(batch);
    }
    UltraDevLog.push('SYSTEM', { event: 'task_graph_execution_order', batchCount: order.length, totalNodes: this.nodes.size });
    return order;
  }

  toSummary(): string {
    return this.getAllNodes().map((n) => `[${n.status}] ${n.id}: ${n.description}`).join('\n');
  }
}
