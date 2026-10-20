// Non-blocking route switch toast — shown when the runtime switches to a different model

import { JarvisDevLog } from '../../utils/JarvisDevLog';
import type { ResolvedRoute } from '../../types/provider';

type ToastListener = (message: string, route: ResolvedRoute) => void;

let _listener: ToastListener | null = null;

export const RouteToast = {
  setListener(fn: ToastListener | null): void {
    _listener = fn;
    JarvisDevLog.push('SYSTEM', { event: 'route_toast_listener_set', hasListener: !!fn });
  },

  notify(prev: ResolvedRoute | null, next: ResolvedRoute): void {
    if (!_listener) {
      JarvisDevLog.push('SYSTEM', { event: 'route_toast_suppressed', reason: 'no_listener', modelId: next.modelId, providerId: next.providerId });
      return;
    }
    if (
      prev &&
      prev.providerId === next.providerId &&
      prev.modelId === next.modelId &&
      prev.adapterId === next.adapterId
    ) {
      JarvisDevLog.push('SYSTEM', { event: 'route_toast_suppressed', reason: 'same_route', modelId: next.modelId, providerId: next.providerId });
      return;
    }
    const message = `Using ${next.modelId} via ${next.groupName}`;
    JarvisDevLog.push('SYSTEM', { event: 'route_toast_notify', message, modelId: next.modelId, providerId: next.providerId, groupId: next.groupId, adapterId: next.adapterId, prevModelId: prev?.modelId ?? null });
    _listener(message, next);
  },
};
