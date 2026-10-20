import React, { useState, useEffect, useRef, useCallback } from 'react';
import {
  View, Text, Pressable, StyleSheet, ScrollView, TextInput,
  Platform,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { AppStorage } from '@/src/utils/AppStorage';
import { UltraDevLog } from '@/src/utils/UltraDevLog';
import type { GridCategory, GridAction, GridConfig, TaskTemplate } from '@/src/types/actionGrid';
import { DEFAULT_CATEGORIES, DEFAULT_GRID_CONFIG, getGridForMode } from '@/src/data/defaultGrid';
import { ZoneConfig, resolveActions, lookupAction as zoneLookup } from '@/src/data/ZoneConfig';

const BG = '#000';
const SURFACE = '#111';
const SURFACE2 = '#1a1a1a';
const ACCENT = '#e5e5e5';
const DIM = '#666';
const TEXT = '#e0e0e0';

interface ActionGridProps {
  collapsed: boolean;
  onToggle: () => void;
  onExecute: (capability: string, params: Record<string, any>) => void;
  onRunTask: (template: TaskTemplate) => void;
  savedTasks: TaskTemplate[];
  onOpenZoneEditor?: () => void;
  zoneConfig?: ZoneConfig | null;
}

interface InputState {
  actionId: string;
  value: string;
  action: GridAction;
}

export default function ActionGrid({
  collapsed,
  onToggle,
  onExecute,
  onRunTask,
  savedTasks,
  onOpenZoneEditor,
  zoneConfig: externalZoneConfig,
}: ActionGridProps) {
  const [config, setConfig] = useState<GridConfig>(DEFAULT_GRID_CONFIG);
  const zoneConfig = externalZoneConfig ?? null;
  const [editMode, setEditMode] = useState(false);
  const [inputState, setInputState] = useState<InputState | null>(null);
  const inputRef = useRef<TextInput>(null);

  useEffect(() => {
    AppStorage.get('action_grid_config').then(raw => {
      if (raw) {
        try {
          const parsed = JSON.parse(raw);
          setConfig(parsed);
          UltraDevLog.push('GRID_LOAD', { favCount: parsed.favorites?.length ?? 0, hiddenCount: parsed.hiddenCategories?.length ?? 0, source: 'storage' });
        } catch (e: any) {
          console.warn('[ActionGrid] config parse failed:', e?.message);
          UltraDevLog.push('GRID_LOAD', { favCount: DEFAULT_GRID_CONFIG.favorites.length, hiddenCount: 0, source: 'default' });
        }
      } else {
        UltraDevLog.push('GRID_LOAD', { favCount: DEFAULT_GRID_CONFIG.favorites.length, hiddenCount: 0, source: 'default' });
      }
    }).catch(() => {});
  }, []);

  // expandAnim removed — using conditional render

  const saveConfig = useCallback(async (newConfig: GridConfig) => {
    setConfig(newConfig);
    await AppStorage.set('action_grid_config', JSON.stringify(newConfig));
    UltraDevLog.push('GRID_SAVE', { favCount: newConfig.favorites.length, hiddenCount: newConfig.hiddenCategories.length });
  }, []);

  const allCategories = getGridForMode("chat", DEFAULT_CATEGORIES.map(c => {
    if (c.id === 'quick') {
      return {
        ...c,
        actions: savedTasks.map(t => ({
          id: `task_${t.id}`,
          label: t.name,
          icon: t.icon || 'flash',
          iconFamily: 'ionicons' as const,
          capability: '__task__',
          params: { templateId: t.id },
        })),
      };
    }
    return c;
  }));

  // Build grid categories: zone config if available, otherwise DEFAULT_CATEGORIES
  const orderedCategories: GridCategory[] = zoneConfig
    ? zoneConfig.grid.map(zg => ({
        id: zg.id,
        label: zg.label,
        icon: 'grid-outline',
        iconFamily: 'ionicons' as const,
        color: '#818cf8',
        actions: resolveActions(zg.items),
      }))
    : allCategories
      .filter(c => !config.hiddenCategories.includes(c.id))
      .sort((a, b) => {
        const ai = config.categoryOrder.indexOf(a.id);
        const bi = config.categoryOrder.indexOf(b.id);
        if (ai >= 0 && bi >= 0) return ai - bi;
        if (ai >= 0) return -1;
        if (bi >= 0) return 1;
        return 0;
      });

  // Always append saved tasks category
  const tasksCategory: GridCategory = {
    id: 'quick', label: 'My Tasks', icon: 'flash-outline', iconFamily: 'ionicons', color: '#e5e5e5',
    actions: savedTasks.map(t => ({
      id: `task_${t.id}`, label: t.name, icon: t.icon || 'flash',
      iconFamily: 'ionicons' as const, capability: '__task__', params: { templateId: t.id },
    })),
  };
  if (savedTasks.length > 0 && !orderedCategories.find(c => c.id === 'quick')) {
    orderedCategories.push(tasksCategory);
  }

  const allActions = allCategories.flatMap(c => c.actions);
  // Use zone favorites if available, otherwise fall back to GridConfig
  const favIds = zoneConfig ? zoneConfig.favorites : config.favorites;
  const favoriteActions = favIds
    .map(fid => zoneLookup(fid) || allActions.find(a => a.id === fid))
    .filter(Boolean) as GridAction[];

  const handleActionPress = useCallback((action: GridAction) => {
    UltraDevLog.push('GRID_TAP', { actionId: action.id, capability: action.capability, label: action.label, requiresInput: !!action.requiresInput });
    if (action.capability === '__task__') {
      const template = savedTasks.find(t => t.id === action.params.templateId);
      UltraDevLog.push('CHAIN', { component: 'ActionGrid', action: 'action_press', trigger: { actionId: action.id }, state: {}, data: { capability: '__task__' }, outcome: template ? 'run_task' : 'FAIL:task_template_missing' });
      if (template) onRunTask(template);
      return;
    }
    if (action.requiresInput && action.inputKey) {
      UltraDevLog.push('CHAIN', { component: 'ActionGrid', action: 'action_press', trigger: { actionId: action.id }, state: {}, data: { capability: action.capability }, outcome: 'show_input' });
      setInputState({ actionId: action.id, value: '', action });
      setTimeout(() => inputRef.current?.focus(), 80);
      return;
    }
    UltraDevLog.push('CHAIN', { component: 'ActionGrid', action: 'action_press', trigger: { actionId: action.id }, state: {}, data: { capability: action.capability }, outcome: 'execute' });
    const execStart = Date.now();
    try {
      onExecute(action.capability, action.params);
      UltraDevLog.push('EFFECT', { component: 'ActionGrid', action: 'action_execute_dispatched', capability: action.capability, durationMs: Date.now() - execStart, dispatched: true });
    } catch (execErr: any) {
      UltraDevLog.push('EFFECT', { component: 'ActionGrid', action: 'action_execute_dispatched', capability: action.capability, durationMs: Date.now() - execStart, dispatched: false, error: execErr?.message });
    }
  }, [savedTasks, onRunTask, onExecute]);

  const handleInputSubmit = useCallback(() => {
    if (!inputState) return;
    const { action, value } = inputState;
    if (!value.trim()) {
      UltraDevLog.push('CHAIN', { component: 'ActionGrid', action: 'input_submit', trigger: { actionId: inputState.actionId }, state: {}, data: { value: '' }, outcome: 'EMPTY:no_input_dismissed' });
      setInputState(null);
      return;
    }
    const params = { ...action.params, [action.inputKey!]: value.trim() };
    UltraDevLog.push('CHAIN', { component: 'ActionGrid', action: 'input_submit', trigger: { actionId: action.id }, state: {}, data: { capability: action.capability }, outcome: 'execute_with_input' });
    onExecute(action.capability, params);
    setInputState(null);
  }, [inputState, onExecute]);

  const toggleFavorite = useCallback((actionId: string) => {
    const isFav = config.favorites.includes(actionId);
    const newFavs = isFav
      ? config.favorites.filter(f => f !== actionId)
      : [...config.favorites, actionId];
    UltraDevLog.push('GRID_FAV_TOGGLE', { actionId, added: !isFav, totalFavs: newFavs.length });
    UltraDevLog.push('CHAIN', { component: 'ActionGrid', action: 'toggleFavorite', trigger: { actionId }, state: { wasFav: isFav }, data: { newTotal: newFavs.length }, outcome: isFav ? 'removed_favorite' : 'added_favorite' });
    saveConfig({ ...config, favorites: newFavs });
  }, [config, saveConfig]);

  const toggleHideCategory = useCallback((catId: string) => {
    const isHidden = config.hiddenCategories.includes(catId);
    const newHidden = isHidden
      ? config.hiddenCategories.filter(h => h !== catId)
      : [...config.hiddenCategories, catId];
    UltraDevLog.push('CHAIN', { component: 'ActionGrid', action: 'toggleHideCategory', trigger: { catId }, state: { wasHidden: isHidden }, data: { newHiddenCount: newHidden.length }, outcome: isHidden ? 'category_shown' : 'category_hidden' });
    saveConfig({ ...config, hiddenCategories: newHidden });
  }, [config, saveConfig]);

  const resetDefaults = useCallback(() => {
    UltraDevLog.push('CHAIN', { component: 'ActionGrid', action: 'reset_defaults', trigger: {}, state: { favCount: config.favorites.length, hiddenCount: config.hiddenCategories.length }, data: {}, outcome: 'reset_to_defaults' });
    saveConfig(DEFAULT_GRID_CONFIG);
    setEditMode(false);
  }, [saveConfig, config]);

  // Animation removed — using conditional render for reliable layout
  const expandedHeight = 280;

  return (
    <View style={styles.wrapper} testID="ActionGrid">
      {/* ── Collapsed: favorites row ── */}
      <View style={styles.favRow}>
        <ScrollView
          horizontal
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={styles.favContent}
          keyboardShouldPersistTaps="handled"
        >
          {favoriteActions.map(action => (
            <Pressable
              key={action.id}
              testID={`fav_${action.id}`}
              onPress={() => handleActionPress(action)}
              style={({ pressed }) => [styles.favBtn, pressed && styles.favBtnPressed]}
            >
              <Ionicons name={action.icon as any} size={20} color={DIM} />
              {inputState?.actionId === action.id && (
                <TextInput
                  ref={inputRef}
                  style={styles.inlineInput}
                  value={inputState.value}
                  onChangeText={v => setInputState(s => s ? { ...s, value: v } : null)}
                  placeholder={action.inputPlaceholder}
                  placeholderTextColor="#444"
                  onSubmitEditing={handleInputSubmit}
                  onBlur={() => setInputState(null)}
                  returnKeyType="go"
                  autoFocus
                />
              )}
            </Pressable>
          ))}
        </ScrollView>
        {onOpenZoneEditor && (
          <Pressable
            onPress={() => {
              UltraDevLog.push('CHAIN', { component: 'ActionGrid', action: 'zone_edit_tap', trigger: { source: 'favorites_row' }, state: {}, data: {}, outcome: 'opening_zone_editor' });
              onOpenZoneEditor();
            }}
            hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
            style={styles.chevronBtn}
          >
            <Ionicons name="pencil-outline" size={14} color={DIM} />
          </Pressable>
        )}
        <Pressable
          onPress={() => { UltraDevLog.push('CHAIN', { component: 'ActionGrid', action: 'chevron_toggle', trigger: {}, state: { collapsed }, data: {}, outcome: collapsed ? 'expanding' : 'collapsing' }); onToggle(); }}
          hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
          style={styles.chevronBtn}
        >
          <Ionicons
            name={collapsed ? 'chevron-up' : 'chevron-down'}
            size={16}
            color={DIM}
          />
        </Pressable>
      </View>

      {/* ── Expanded: full category grid ── */}
      {!collapsed && (<View
        style={[styles.expandedContainer, { height: expandedHeight }]}
        onLayout={(e) => {
          const h = Math.round(e.nativeEvent.layout.height);
          UltraDevLog.push('EFFECT', {
            component: 'ActionGrid',
            action: 'chevron_expand',
            measuredHeight: h,
            collapsed,
            expected: 280,
            match: h > 20,
            note: h > 20 ? `ok — expanded ${h}px` : `BUG: expanded but height=${h}px`,
          });
        }}
      >
        <ScrollView
          style={styles.expandedScroll}
          showsVerticalScrollIndicator={false}
          keyboardShouldPersistTaps="handled"
          nestedScrollEnabled
        >
          <View style={styles.expandedHeader}>
            <Text style={styles.expandedTitle}>Actions</Text>
            <View style={styles.expandedHeaderRight}>
              {onOpenZoneEditor && (
                <Pressable onPress={() => {
                  UltraDevLog.push('CHAIN', { component: 'ActionGrid', action: 'zone_edit_tap', trigger: { source: 'grid_header' }, state: {}, data: {}, outcome: 'opening_zone_editor' });
                  onOpenZoneEditor();
                }} hitSlop={{ top: 6, bottom: 6, left: 6, right: 6 }}>
                  <Ionicons name="options-outline" size={16} color={DIM} />
                </Pressable>
              )}
              {editMode && (
                <Pressable onPress={resetDefaults} style={styles.resetBtn}>
                  <Text style={styles.resetText}>Reset</Text>
                </Pressable>
              )}
              <Pressable
                onPress={() => { const next = !editMode; UltraDevLog.push('CHAIN', { component: 'ActionGrid', action: 'edit_mode_toggle', trigger: {}, state: { wasEditing: editMode }, data: {}, outcome: next ? 'edit_mode_on' : 'edit_mode_off' }); setEditMode(next); }}
                hitSlop={{ top: 6, bottom: 6, left: 6, right: 6 }}
              >
                <Ionicons name={editMode ? 'checkmark' : 'pencil'} size={16} color={editMode ? ACCENT : DIM} />
              </Pressable>
            </View>
          </View>

          {orderedCategories.map(cat => (
            <View key={cat.id} style={styles.categoryBlock}>
              <View style={styles.categoryHeader}>
                <Ionicons name={cat.icon as any} size={14} color={cat.color} />
                <Text style={[styles.categoryLabel, { color: cat.color }]}>{cat.label}</Text>
                {editMode && (
                  <Pressable
                    onPress={() => toggleHideCategory(cat.id)}
                    style={styles.hideBtn}
                  >
                    <Ionicons name="eye-off-outline" size={14} color={DIM} />
                  </Pressable>
                )}
              </View>
              <ScrollView
                horizontal
                showsHorizontalScrollIndicator={false}
                contentContainerStyle={styles.actionsRow}
                keyboardShouldPersistTaps="handled"
              >
                {cat.actions.map(action => (
                  <View key={action.id} style={styles.actionCell}>
                    <Pressable
                      testID={`action_${action.id}`}
                      onPress={() => {
                        if (editMode) return;
                        handleActionPress(action);
                      }}
                      style={({ pressed }) => [styles.actionBtn, pressed && !editMode && styles.actionBtnPressed]}
                    >
                      <Ionicons name={action.icon as any} size={22} color={TEXT} />
                      {editMode && (
                        <View style={styles.editOverlay}>
                          <Pressable
                            onPress={() => toggleFavorite(action.id)}
                            style={styles.editStarBtn}
                          >
                            <Ionicons
                              name={config.favorites.includes(action.id) ? 'star' : 'star-outline'}
                              size={11}
                              color={ACCENT}
                            />
                          </Pressable>
                        </View>
                      )}
                    </Pressable>
                    <Text style={styles.actionLabel} numberOfLines={1}>{action.label}</Text>
                    {inputState?.actionId === action.id && (
                      <TextInput
                        ref={inputRef}
                        style={styles.actionInlineInput}
                        value={inputState.value}
                        onChangeText={v => setInputState(s => s ? { ...s, value: v } : null)}
                        placeholder={action.inputPlaceholder}
                        placeholderTextColor="#444"
                        onSubmitEditing={handleInputSubmit}
                        onBlur={() => setInputState(null)}
                        returnKeyType="go"
                        autoFocus
                      />
                    )}
                  </View>
                ))}
                {cat.actions.length === 0 && cat.id === 'quick' && (
                  <Text style={styles.emptyTasksText}>No saved tasks yet. Use "Build a Task" to create one.</Text>
                )}
              </ScrollView>
            </View>
          ))}
        </ScrollView>
      </View>)}

    </View>
  );
}

const styles = StyleSheet.create({
  wrapper: { backgroundColor: BG },
  favRow: {
    flexDirection: 'row', alignItems: 'center',
    paddingHorizontal: 8, paddingVertical: 4, minHeight: 48,
  },
  favContent: { flexDirection: 'row', gap: 12, paddingRight: 8 },
  favBtn: {
    width: 36, height: 36, borderRadius: 18,
    backgroundColor: SURFACE, justifyContent: 'center', alignItems: 'center',
  },
  favBtnPressed: { backgroundColor: SURFACE2 },
  chevronBtn: { paddingHorizontal: 6, paddingVertical: 4 },
  inlineInput: {
    position: 'absolute', left: 40, width: 140, height: 32,
    backgroundColor: SURFACE2, borderRadius: 8, color: TEXT,
    paddingHorizontal: 8, fontSize: 13, borderWidth: 1, borderColor: '#333',
  },
  expandedContainer: { overflow: 'hidden' },
  expandedScroll: { flex: 1 },
  expandedHeader: {
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center',
    paddingHorizontal: 12, paddingTop: 6, paddingBottom: 4,
  },
  expandedTitle: { color: TEXT, fontSize: 13, fontWeight: '600' },
  expandedHeaderRight: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  resetBtn: { paddingHorizontal: 8, paddingVertical: 3, backgroundColor: '#2a0000', borderRadius: 6 },
  resetText: { color: '#ef4444', fontSize: 11 },
  categoryBlock: { marginBottom: 8 },
  categoryHeader: {
    flexDirection: 'row', alignItems: 'center', gap: 6,
    paddingHorizontal: 12, paddingBottom: 4,
  },
  categoryLabel: { fontSize: 11, fontWeight: '600', flex: 1 },
  hideBtn: { paddingHorizontal: 4 },
  actionsRow: { paddingHorizontal: 8, gap: 6 },
  actionCell: { alignItems: 'center', width: 54 },
  actionBtn: {
    width: 44, height: 44, borderRadius: 12,
    backgroundColor: SURFACE, justifyContent: 'center', alignItems: 'center',
  },
  actionBtnPressed: { backgroundColor: SURFACE2 },
  editOverlay: {
    position: 'absolute', top: -4, right: -4,
    backgroundColor: SURFACE2, borderRadius: 10, padding: 2,
  },
  editStarBtn: { padding: 2 },
  actionLabel: { color: DIM, fontSize: 10, marginTop: 3, textAlign: 'center', maxWidth: 54 },
  actionInlineInput: {
    marginTop: 4, width: 120, height: 28,
    backgroundColor: SURFACE2, borderRadius: 6, color: TEXT,
    paddingHorizontal: 6, fontSize: 12, borderWidth: 1, borderColor: '#333',
  },
  emptyTasksText: { color: DIM, fontSize: 11, fontStyle: 'italic', paddingHorizontal: 8, paddingVertical: 12 },
});
