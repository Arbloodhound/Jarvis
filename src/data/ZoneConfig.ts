/**
 * ZoneConfig — Customizable Zones for Agent Ultra
 *
 * Three zones (favorites row, grid categories, sidebar toggles)
 * all draw items from the same pool (DEFAULT_CATEGORIES actions).
 * Stored in AppStorage key 'zone_config'.
 */

import { AppStorage } from '@/src/utils/AppStorage';
import { UltraDevLog } from '@/src/utils/UltraDevLog';
import { DEFAULT_CATEGORIES } from '@/src/data/defaultGrid';
import type { GridAction } from '@/src/types/actionGrid';

// ── Types ──────────────────────────────────────────────

export interface ZoneGridCategory {
  id: string;
  label: string;
  items: string[];  // action IDs
}

export interface ZoneConfig {
  favorites: string[];               // action IDs, max 7
  grid: ZoneGridCategory[];          // max 5 categories
  sidebar: string[];                 // action IDs for sidebar toggles
}

// ── Default Config (matches current app behavior) ──────

export const DEFAULT_ZONE_CONFIG: ZoneConfig = {
  favorites: ['flashlight', 'screenshot', 'gps', 'open_camera', 'call'],
  grid: [
    { id: 'system', label: 'System', items: ['wifi', 'bluetooth', 'dnd', 'airplane', 'brightness'] },
    { id: 'device', label: 'Device', items: ['device_info', 'battery', 'volume_up', 'volume_down'] },
    { id: 'tasks', label: 'My Tasks', items: [] },
  ],
  sidebar: ['flashlight', 'screenshot', 'dnd', 'wifi'],
};

// ── Action Pool ────────────────────────────────────────

let _poolCache: Map<string, GridAction> | null = null;

/** Build a lookup map of all available actions from DEFAULT_CATEGORIES */
export function getActionPool(): Map<string, GridAction> {
  if (_poolCache) return _poolCache;
  _poolCache = new Map();
  for (const cat of DEFAULT_CATEGORIES) {
    for (const action of cat.actions) {
      _poolCache.set(action.id, action);
    }
  }
  return _poolCache;
}

/** Get a flat array of all pool actions (for picker UI) */
export function getPoolActions(): GridAction[] {
  return Array.from(getActionPool().values());
}

/** Look up a single action by ID. Returns undefined if not found. */
export function lookupAction(id: string): GridAction | undefined {
  return getActionPool().get(id);
}

/** Resolve an array of action IDs to GridAction objects, skipping missing */
export function resolveActions(ids: string[]): GridAction[] {
  const pool = getActionPool();
  const resolved: GridAction[] = [];
  for (const id of ids) {
    const action = pool.get(id);
    if (action) {
      resolved.push(action);
    } else {
      UltraDevLog.push('EFFECT', {
        component: 'ZoneConfig', action: 'resolve_orphan',
        itemId: id, success: false,
        note: `Action "${id}" in zone config but not found in action pool — skipped`,
      });
    }
  }
  return resolved;
}

// ── Storage ────────────────────────────────────────────

/** Load zone config from AppStorage. Returns DEFAULT_ZONE_CONFIG if not set. */
export async function loadZoneConfig(): Promise<ZoneConfig> {
  try {
    const raw = await AppStorage.get('zone_config');
    if (raw) {
      const parsed = JSON.parse(raw) as ZoneConfig;
      // Validate structure
      if (Array.isArray(parsed.favorites) && Array.isArray(parsed.grid) && Array.isArray(parsed.sidebar)) {
        UltraDevLog.push('EFFECT', {
          component: 'ZoneConfig', action: 'load_config',
          source: 'storage', success: true,
          favCount: parsed.favorites.length,
          gridCats: parsed.grid.length,
          sidebarCount: parsed.sidebar.length,
        });
        return parsed;
      }
    }
  } catch (e: any) {
    UltraDevLog.error('ZoneConfig', `Failed to load zone_config: ${e?.message}`);
  }
  UltraDevLog.push('EFFECT', {
    component: 'ZoneConfig', action: 'load_config',
    source: 'default_fallback', success: true,
    note: 'No saved zone_config or parse failed — using defaults',
  });
  return { ...DEFAULT_ZONE_CONFIG, grid: DEFAULT_ZONE_CONFIG.grid.map(g => ({ ...g, items: [...g.items] })) };
}

/** Save zone config to AppStorage */
export async function saveZoneConfig(config: ZoneConfig): Promise<boolean> {
  try {
    // Enforce limits
    const clamped: ZoneConfig = {
      favorites: config.favorites.slice(0, 7),
      grid: config.grid.slice(0, 5),
      sidebar: config.sidebar,
    };
    await AppStorage.set('zone_config', JSON.stringify(clamped));
    UltraDevLog.push('EFFECT', {
      component: 'ZoneConfig', action: 'save_config',
      zones: { favCount: clamped.favorites.length, gridCats: clamped.grid.length, sidebarCount: clamped.sidebar.length },
      success: true,
    });
    return true;
  } catch (e: any) {
    UltraDevLog.push('EFFECT', {
      component: 'ZoneConfig', action: 'save_config',
      success: false, error: e?.message,
    });
    return false;
  }
}
