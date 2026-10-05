// Adapter registry — maps adapter IDs to implementations

import { ALL_ADAPTERS, type CapabilityAdapter } from './CapabilityAdapters';
import { UltraDevLog } from '../../utils/UltraDevLog';
import type { AllowedOperation } from '../../types/provider';

export class AdapterRegistry {
  private adapters: Map<string, CapabilityAdapter> = new Map();

  constructor() {
    for (const adapter of ALL_ADAPTERS) {
      this.adapters.set(adapter.id, adapter);
    }
    UltraDevLog.push('SYSTEM', { event: 'adapter_registry_init', adapterIds: ALL_ADAPTERS.map(a => a.id) });
  }

  register(adapter: CapabilityAdapter): void {
    const existed = this.adapters.has(adapter.id);
    this.adapters.set(adapter.id, adapter);
    UltraDevLog.push('SYSTEM', { event: existed ? 'adapter_overwrite' : 'adapter_register', adapterId: adapter.id, ops: adapter.supportedOperations });
  }

  get(id: string): CapabilityAdapter | null {
    const result = this.adapters.get(id) ?? null;
    if (!result) {
      UltraDevLog.push('SYSTEM', { event: 'adapter_lookup_miss', adapterId: id });
    }
    return result;
  }

  getForOperation(adapterIds: string[], operation: AllowedOperation): CapabilityAdapter | null {
    for (const id of adapterIds) {
      const adapter = this.adapters.get(id);
      if (adapter && adapter.supportsOperation(operation)) {
        UltraDevLog.push('SYSTEM', { event: 'adapter_op_match', adapterId: id, operation });
        return adapter;
      }
    }
    UltraDevLog.push('SYSTEM', { event: 'adapter_op_no_match', candidates: adapterIds, operation });
    return null;
  }

  listAll(): CapabilityAdapter[] {
    return Array.from(this.adapters.values());
  }

  listIds(): string[] {
    return Array.from(this.adapters.keys());
  }
}

let _instance: AdapterRegistry | null = null;
export function getAdapterRegistry(): AdapterRegistry {
  if (!_instance) _instance = new AdapterRegistry();
  return _instance;
}
