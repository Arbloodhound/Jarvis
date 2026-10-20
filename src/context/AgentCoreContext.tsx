import React, { createContext, useContext, useEffect, useRef, useState } from 'react';
import { SecureVault } from '@/src/security/SecureVault';
import { AgentCore, setAgentCoreInstance } from '@/src/core/AgentCore';
import { UltraDevLog } from '@/src/utils/UltraDevLog';

export interface AgentCoreContextValue {
  core: AgentCore | null;
  vault: SecureVault | null;
  isReady: boolean;
  status: string;
  setStatus: (s: string) => void;
  buildPhase: string | null;
  setBuildPhase: (p: string | null) => void;
  genomePhase: string | null;
  setGenomePhase: (p: string | null) => void;
}

const AgentCoreContext = createContext<AgentCoreContextValue>({
  core: null,
  vault: null,
  isReady: false,
  status: 'Initializing...',
  setStatus: () => {},
  buildPhase: null,
  setBuildPhase: () => {},
  genomePhase: null,
  setGenomePhase: () => {},
});

export function AgentCoreProvider({ children }: { children: React.ReactNode }) {
  const [core, setCore] = useState<AgentCore | null>(null);
  const [vault, setVault] = useState<SecureVault | null>(null);
  const [isReady, setIsReady] = useState(false);
  const [status, setStatus] = useState('Initializing...');
  const [buildPhase, setBuildPhase] = useState<string | null>(null);
  const [genomePhase, setGenomePhase] = useState<string | null>(null);

  const coreRef = useRef<AgentCore | null>(null);
  const mountedRef = useRef(true);

  useEffect(() => {
    mountedRef.current = true;

    async function initCore() {
      try {
        const v = await SecureVault.initialize();
        if (!mountedRef.current) return;
        setVault(v);

        const c = new AgentCore(v, (msg: string, type: string) => {
          if (!mountedRef.current) return;
          setStatus(msg);
          if (type === 'build_progress') setBuildPhase(msg);
          if (type === 'genome_progress') setGenomePhase(msg);
        });

        await c.initialize();

        if (!mountedRef.current) {
          c.destroy('AgentCoreProvider: unmounted before init completed');
          return;
        }

        coreRef.current = c;
        setCore(c);
        setAgentCoreInstance(c);
        setIsReady(true);

        UltraDevLog.push('CONTEXT_PROVIDER', {
          providerName: 'AgentCoreProvider',
          renderCount: 1,
          coreInstanceId: c.getInstanceId(),
          note: 'AgentCore initialized and owned by context',
        });
      } catch (err: any) {
        if (!mountedRef.current) return;
        const msg = 'Init failed: ' + (err?.message ?? 'unknown');
        setStatus(msg);
        UltraDevLog.push('ERROR', {
          context: 'AgentCoreProvider',
          message: err?.message ?? 'unknown',
          stack: (err?.stack ?? '').slice(0, 500),
        });
      }
    }

    initCore();

    return () => {
      mountedRef.current = false;
      const c = coreRef.current;
      if (c) {
        try { c.destroy('AgentCoreProvider unmount'); } catch {}
        setAgentCoreInstance(null);
        coreRef.current = null;
      }
    };
  }, []);

  return (
    <AgentCoreContext.Provider
      value={{
        core,
        vault,
        isReady,
        status,
        setStatus,
        buildPhase,
        setBuildPhase,
        genomePhase,
        setGenomePhase,
      }}
    >
      {children}
    </AgentCoreContext.Provider>
  );
}

export function useAgentCore(): AgentCoreContextValue {
  return useContext(AgentCoreContext);
}
