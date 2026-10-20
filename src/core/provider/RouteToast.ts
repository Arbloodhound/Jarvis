// Non-blocking route switch toast — shown when the runtime switches to a different model

import { UltraDevLog } from '../../utils/UltraDevLog';
import type { ResolvedRoute } from '../../types/provider';

type ToastListener = (message: string, route: ResolvedRoute) => void;

let _listener: ToastListener | null = null;

export const RouteToast = {
  setListener(fn: ToastListener | null): void {
    _listener = fn;
    UltraDevLog.push('SYSTEM', { event: 'route_toast_listener_set', hasListener: !!fn });
  },

  notify(prev: ResolvedRoute | null, next: ResolvedRoute): void {
    if (!_listener) {
      UltraDevLog.push('SYSTEM', { event: 'route_toast_suppressed', reason: 'no_listener', modelId: next.modelId, providerId: next.providerId });
      return;
    }
    if (
      prev &&
      prev.providerId === next.providerId &&
      prev.modelId === next.modelId &&
      prev.adapterId === next.adapterId
    ) {
      UltraDevLog.push('SYSTEM', { event: 'route_toast_suppressed', reason: 'same_route', modelId: next.modelId, providerId: next.providerId });
      return;
    }
    const message = `Using ${next.modelId} via ${next.groupName}`;
    UltraDevLog.push('SYSTEM', { event: 'route_toast_notify', message, modelId: next.modelId, providerId: next.providerId, groupId: next.groupId, adapterId: next.adapterId, prevModelId: prev?.modelId ?? null });
    _listener(message, next);
  },
};
