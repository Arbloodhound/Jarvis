/**
 * ZoneEditor — Customize favorites row, grid categories, and sidebar toggles.
 * All three zones draw from the same action pool (DEFAULT_CATEGORIES).
 */
import React, { useState, useEffect, useCallback } from 'react';
import {
  View, Text, Pressable, ScrollView, StyleSheet, Modal,
  TouchableWithoutFeedback, TextInput, Alert,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { UltraDevLog } from '@/src/utils/UltraDevLog';
import type { GridAction } from '@/src/types/actionGrid';
import {
  ZoneConfig, ZoneGridCategory,
  loadZoneConfig, saveZoneConfig, getPoolActions, lookupAction,
  DEFAULT_ZONE_CONFIG,
} from '@/src/data/ZoneConfig';

const BG = '#000';
const SURFACE = '#111';
const SURFACE2 = '#1a1a1a';
const SURFACE3 = '#222';
const ACCENT = '#e5e5e5';
const DIM = '#666';
const TEXT_COLOR = '#e0e0e0';
const DANGER = '#ef4444';

interface ZoneEditorProps {
  visible: boolean;
  onClose: () => void;
  onSaved: (config: ZoneConfig) => void;
  savedTasks?: Array<{ id: string; name: string; icon?: string }>;
}

type ZoneName = 'favorites' | 'grid' | 'sidebar';

export default function ZoneEditor({ visible, onClose, onSaved, savedTasks }: ZoneEditorProps) {
  const [config, setConfig] = useState<ZoneConfig | null>(null);
  const [pickerZone, setPickerZone] = useState<{ zone: ZoneName; catIdx?: number } | null>(null);
  const [renamingCat, setRenamingCat] = useState<{ idx: number; label: string } | null>(null);

  useEffect(() => {
    if (visible) {
      UltraDevLog.push('CHAIN', { component: 'ZoneEditor', action: 'open', trigger: {}, state: {}, data: {}, outcome: 'opened' });
      loadZoneConfig().then(setConfig);
    }
  }, [visible]);

  const handleSave = useCallback(async () => {
    if (!config) return;
    const ok = await saveZoneConfig(config);
    if (ok) {
      onSaved(config);
      onClose();
    } else {
      Alert.alert('Error', 'Failed to save zone config');
    }
  }, [config, onSaved, onClose]);

  const handleClose = useCallback(() => {
    UltraDevLog.push('CHAIN', { component: 'ZoneEditor', action: 'close', trigger: {}, state: {}, data: {}, outcome: 'dismissed' });
    onClose();
  }, [onClose]);

  // ── Zone mutation helpers ────────────────────────────

  const addItem = useCallback((zone: ZoneName, itemId: string, catIdx?: number) => {
    if (!config) return;
    UltraDevLog.push('CHAIN', { component: 'ZoneEditor', action: 'add_item', trigger: {}, state: {}, data: { zone, itemId, catIdx }, outcome: 'added' });
    const next = { ...config };
    if (zone === 'favorites') {
      if (next.favorites.length >= 7) { Alert.alert('Limit', 'Maximum 7 favorites'); return; }
      if (!next.favorites.includes(itemId)) next.favorites = [...next.favorites, itemId];
    } else if (zone === 'sidebar') {
      if (!next.sidebar.includes(itemId)) next.sidebar = [...next.sidebar, itemId];
    } else if (zone === 'grid' && catIdx !== undefined) {
      const cat = { ...next.grid[catIdx], items: [...next.grid[catIdx].items] };
      if (!cat.items.includes(itemId)) cat.items.push(itemId);
      next.grid = next.grid.map((g, i) => i === catIdx ? cat : g);
    }
    setConfig(next);
  }, [config]);

  const removeItem = useCallback((zone: ZoneName, itemId: string, catIdx?: number) => {
    if (!config) return;
    UltraDevLog.push('CHAIN', { component: 'ZoneEditor', action: 'remove_item', trigger: {}, state: {}, data: { zone, itemId, catIdx }, outcome: 'removed' });
    const next = { ...config };
    if (zone === 'favorites') {
      next.favorites = next.favorites.filter(id => id !== itemId);
    } else if (zone === 'sidebar') {
      next.sidebar = next.sidebar.filter(id => id !== itemId);
    } else if (zone === 'grid' && catIdx !== undefined) {
      const cat = { ...next.grid[catIdx], items: next.grid[catIdx].items.filter(id => id !== itemId) };
      next.grid = next.grid.map((g, i) => i === catIdx ? cat : g);
    }
    setConfig(next);
  }, [config]);

  const moveItem = useCallback((zone: ZoneName, fromIdx: number, toIdx: number, catIdx?: number) => {
    if (!config) return;
    UltraDevLog.push('CHAIN', { component: 'ZoneEditor', action: 'reorder', trigger: {}, state: {}, data: { zone, from: fromIdx, to: toIdx, catIdx }, outcome: 'reordered' });
    const next = { ...config };
    let arr: string[];
    if (zone === 'favorites') arr = [...next.favorites];
    else if (zone === 'sidebar') arr = [...next.sidebar];
    else if (zone === 'grid' && catIdx !== undefined) arr = [...next.grid[catIdx].items];
    else return;
    const [item] = arr.splice(fromIdx, 1);
    arr.splice(toIdx, 0, item);
    if (zone === 'favorites') next.favorites = arr;
    else if (zone === 'sidebar') next.sidebar = arr;
    else if (zone === 'grid' && catIdx !== undefined) {
      next.grid = next.grid.map((g, i) => i === catIdx ? { ...g, items: arr } : g);
    }
    setConfig(next);
  }, [config]);

  const addCategory = useCallback(() => {
    if (!config) return;
    if (config.grid.length >= 5) { Alert.alert('Limit', 'Maximum 5 grid categories'); return; }
    const newCat: ZoneGridCategory = { id: `cat_${Date.now().toString(36)}`, label: 'New Category', items: [] };
    UltraDevLog.push('CHAIN', { component: 'ZoneEditor', action: 'add_category', trigger: {}, state: {}, data: { label: newCat.label }, outcome: 'category_added' });
    setConfig({ ...config, grid: [...config.grid, newCat] });
  }, [config]);

  const deleteCategory = useCallback((idx: number) => {
    if (!config) return;
    UltraDevLog.push('CHAIN', { component: 'ZoneEditor', action: 'delete_category', trigger: {}, state: {}, data: { index: idx }, outcome: 'category_deleted' });
    setConfig({ ...config, grid: config.grid.filter((_, i) => i !== idx) });
  }, [config]);

  const renameCategory = useCallback((idx: number, newLabel: string) => {
    if (!config || !newLabel.trim()) return;
    UltraDevLog.push('CHAIN', { component: 'ZoneEditor', action: 'rename_category', trigger: {}, state: {}, data: { index: idx, newLabel: newLabel.trim() }, outcome: 'renamed' });
    setConfig({
      ...config,
      grid: config.grid.map((g, i) => i === idx ? { ...g, label: newLabel.trim() } : g),
    });
    setRenamingCat(null);
  }, [config]);

  const resetToDefaults = useCallback(() => {
    Alert.alert('Reset Zones', 'Reset all zones to defaults?', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Reset', style: 'destructive', onPress: () => {
        UltraDevLog.push('CHAIN', { component: 'ZoneEditor', action: 'reset_defaults', trigger: {}, state: {}, data: {}, outcome: 'reset' });
        setConfig({
          ...DEFAULT_ZONE_CONFIG,
          grid: DEFAULT_ZONE_CONFIG.grid.map(g => ({ ...g, items: [...g.items] })),
        });
      }},
    ]);
  }, []);

  // ── Render helpers ───────────────────────────────────

  const renderItemChip = (itemId: string, zone: ZoneName, idx: number, total: number, catIdx?: number) => {
    const action = lookupAction(itemId);
    if (!action) return null;
    return (
      <View key={itemId} style={s.chip}>
        <Ionicons name={action.icon as any} size={14} color={TEXT_COLOR} />
        <Text style={s.chipLabel} numberOfLines={1}>{action.label}</Text>
        <View style={s.chipActions}>
          {idx > 0 && (
            <Pressable onPress={() => moveItem(zone, idx, idx - 1, catIdx)} hitSlop={6}>
              <Ionicons name="chevron-up" size={12} color={DIM} />
            </Pressable>
          )}
          {idx < total - 1 && (
            <Pressable onPress={() => moveItem(zone, idx, idx + 1, catIdx)} hitSlop={6}>
              <Ionicons name="chevron-down" size={12} color={DIM} />
            </Pressable>
          )}
          <Pressable onPress={() => removeItem(zone, itemId, catIdx)} hitSlop={6}>
            <Ionicons name="close-circle" size={14} color={DANGER} />
          </Pressable>
        </View>
      </View>
    );
  };

  const renderZoneSection = (title: string, zone: ZoneName, items: string[], catIdx?: number, maxLabel?: string) => (
    <View style={s.zoneSection}>
      <View style={s.zoneSectionHeader}>
        <Text style={s.zoneSectionTitle}>{title}</Text>
        {maxLabel && <Text style={s.zoneLimit}>{maxLabel}</Text>}
        <Pressable
          onPress={() => setPickerZone({ zone, catIdx })}
          style={s.addBtn}
        >
          <Ionicons name="add" size={14} color={ACCENT} />
          <Text style={s.addBtnText}>Add</Text>
        </Pressable>
      </View>
      {items.length === 0 ? (
        <Text style={s.emptyText}>No items. Tap Add to choose from the action pool.</Text>
      ) : (
        items.map((id, idx) => renderItemChip(id, zone, idx, items.length, catIdx))
      )}
    </View>
  );

  if (!visible || !config) return null;

  // ── Pool Picker Modal ────────────────────────────────
  const poolActions = getPoolActions();
  const currentZoneItems = pickerZone
    ? pickerZone.zone === 'favorites' ? config.favorites
      : pickerZone.zone === 'sidebar' ? config.sidebar
      : pickerZone.catIdx !== undefined ? config.grid[pickerZone.catIdx]?.items ?? []
      : []
    : [];

  return (
    <Modal visible animationType="slide" transparent>
      <View style={s.overlay}>
        <View style={s.container}>
          {/* Header */}
          <View style={s.header}>
            <Text style={s.headerTitle}>Customize Zones</Text>
            <View style={s.headerRight}>
              <Pressable onPress={resetToDefaults} style={s.resetBtn}>
                <Text style={s.resetText}>Reset</Text>
              </Pressable>
              <Pressable onPress={handleSave} style={s.saveBtn}>
                <Ionicons name="checkmark" size={16} color={BG} />
                <Text style={s.saveBtnText}>Save</Text>
              </Pressable>
              <Pressable onPress={handleClose} hitSlop={8}>
                <Ionicons name="close" size={22} color={DIM} />
              </Pressable>
            </View>
          </View>

          <ScrollView style={s.body} showsVerticalScrollIndicator={false}>
            {/* ── FAVORITES ZONE ── */}
            {renderZoneSection('Favorites Row', 'favorites', config.favorites, undefined, `${config.favorites.length}/7`)}

            {/* ── GRID ZONES ── */}
            <View style={s.gridHeader}>
              <Text style={s.gridHeaderTitle}>Grid Categories</Text>
              {config.grid.length < 5 && (
                <Pressable onPress={addCategory} style={s.addBtn}>
                  <Ionicons name="add" size={14} color={ACCENT} />
                  <Text style={s.addBtnText}>Category</Text>
                </Pressable>
              )}
            </View>
            {config.grid.map((cat, catIdx) => (
              <View key={cat.id} style={s.gridCatBlock}>
                <View style={s.gridCatHeader}>
                  {renamingCat?.idx === catIdx ? (
                    <TextInput
                      style={s.renameInput}
                      value={renamingCat.label}
                      onChangeText={t => setRenamingCat({ idx: catIdx, label: t })}
                      onSubmitEditing={() => renameCategory(catIdx, renamingCat.label)}
                      onBlur={() => renameCategory(catIdx, renamingCat.label)}
                      autoFocus
                      returnKeyType="done"
                    />
                  ) : (
                    <Pressable onPress={() => setRenamingCat({ idx: catIdx, label: cat.label })}>
                      <Text style={s.gridCatLabel}>{cat.label}</Text>
                    </Pressable>
                  )}
                  <View style={s.gridCatActions}>
                    <Pressable onPress={() => setRenamingCat({ idx: catIdx, label: cat.label })} hitSlop={6}>
                      <Ionicons name="pencil-outline" size={12} color={DIM} />
                    </Pressable>
                    <Pressable onPress={() => deleteCategory(catIdx)} hitSlop={6}>
                      <Ionicons name="trash-outline" size={12} color={DANGER} />
                    </Pressable>
                  </View>
                </View>
                {renderZoneSection('', 'grid', cat.items, catIdx)}
              </View>
            ))}

            {/* ── SIDEBAR ZONE ── */}
            {renderZoneSection('Sidebar Toggles', 'sidebar', config.sidebar)}

            <View style={{ height: 40 }} />
          </ScrollView>
        </View>
      </View>

      {/* ── Item Pool Picker ── */}
      {pickerZone && (
        <Modal visible animationType="fade" transparent>
          <TouchableWithoutFeedback onPress={() => setPickerZone(null)}>
            <View style={s.pickerOverlay}>
              <TouchableWithoutFeedback>
                <View style={s.pickerContainer}>
                  <View style={s.pickerHeader}>
                    <Text style={s.pickerTitle}>Add Action</Text>
                    <Pressable onPress={() => setPickerZone(null)} hitSlop={8}>
                      <Ionicons name="close" size={20} color={DIM} />
                    </Pressable>
                  </View>
                  <ScrollView style={s.pickerScroll} showsVerticalScrollIndicator={false}>
                    {poolActions
                      .filter(a => !currentZoneItems.includes(a.id))
                      .map(action => (
                        <Pressable
                          key={action.id}
                          onPress={() => {
                            addItem(pickerZone.zone, action.id, pickerZone.catIdx);
                            setPickerZone(null);
                          }}
                          style={({ pressed }) => [s.pickerItem, pressed && s.pickerItemPressed]}
                        >
                          <Ionicons name={action.icon as any} size={18} color={TEXT_COLOR} />
                          <View style={s.pickerItemText}>
                            <Text style={s.pickerItemLabel}>{action.label}</Text>
                            <Text style={s.pickerItemCap}>{action.capability}</Text>
                          </View>
                          <Ionicons name="add-circle-outline" size={18} color={ACCENT} />
                        </Pressable>
                      ))}
                    {poolActions.filter(a => !currentZoneItems.includes(a.id)).length === 0 && (
                      <Text style={s.emptyText}>All actions are already in this zone.</Text>
                    )}
                  </ScrollView>
                </View>
              </TouchableWithoutFeedback>
            </View>
          </TouchableWithoutFeedback>
        </Modal>
      )}
    </Modal>
  );
}

const s = StyleSheet.create({
  overlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.85)' },
  container: { flex: 1, marginTop: 60, backgroundColor: BG, borderTopLeftRadius: 16, borderTopRightRadius: 16 },
  header: {
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center',
    paddingHorizontal: 16, paddingVertical: 14, borderBottomWidth: 1, borderBottomColor: '#1a1a1a',
  },
  headerTitle: { color: TEXT_COLOR, fontSize: 17, fontWeight: '700' },
  headerRight: { flexDirection: 'row', alignItems: 'center', gap: 12 },
  resetBtn: { paddingHorizontal: 10, paddingVertical: 4, backgroundColor: '#2a0000', borderRadius: 6 },
  resetText: { color: DANGER, fontSize: 12 },
  saveBtn: { flexDirection: 'row', alignItems: 'center', gap: 4, backgroundColor: ACCENT, paddingHorizontal: 12, paddingVertical: 6, borderRadius: 8 },
  saveBtnText: { color: BG, fontSize: 13, fontWeight: '600' },
  body: { flex: 1, paddingHorizontal: 16, paddingTop: 12 },

  zoneSection: { marginBottom: 16 },
  zoneSectionHeader: { flexDirection: 'row', alignItems: 'center', marginBottom: 8 },
  zoneSectionTitle: { color: TEXT_COLOR, fontSize: 13, fontWeight: '600', flex: 1 },
  zoneLimit: { color: DIM, fontSize: 11, marginRight: 8 },
  addBtn: { flexDirection: 'row', alignItems: 'center', gap: 3, paddingHorizontal: 8, paddingVertical: 4, backgroundColor: SURFACE2, borderRadius: 6 },
  addBtnText: { color: ACCENT, fontSize: 11, fontWeight: '600' },
  emptyText: { color: DIM, fontSize: 12, fontStyle: 'italic', paddingVertical: 8 },

  chip: {
    flexDirection: 'row', alignItems: 'center', gap: 6,
    backgroundColor: SURFACE, paddingHorizontal: 10, paddingVertical: 8,
    borderRadius: 8, marginBottom: 4,
  },
  chipLabel: { color: TEXT_COLOR, fontSize: 13, flex: 1 },
  chipActions: { flexDirection: 'row', alignItems: 'center', gap: 8 },

  gridHeader: { flexDirection: 'row', alignItems: 'center', marginBottom: 8, marginTop: 8 },
  gridHeaderTitle: { color: ACCENT, fontSize: 14, fontWeight: '700', flex: 1 },
  gridCatBlock: { backgroundColor: SURFACE, borderRadius: 10, padding: 10, marginBottom: 10 },
  gridCatHeader: { flexDirection: 'row', alignItems: 'center', marginBottom: 6 },
  gridCatLabel: { color: TEXT_COLOR, fontSize: 13, fontWeight: '600', flex: 1 },
  gridCatActions: { flexDirection: 'row', gap: 10 },
  renameInput: {
    flex: 1, color: TEXT_COLOR, fontSize: 13, fontWeight: '600',
    backgroundColor: SURFACE2, borderRadius: 6, paddingHorizontal: 8, paddingVertical: 4,
    borderWidth: 1, borderColor: ACCENT,
  },

  pickerOverlay: { flex: 1, backgroundColor: 'rgba(0,0,0,0.7)', justifyContent: 'center', paddingHorizontal: 20 },
  pickerContainer: { backgroundColor: SURFACE, borderRadius: 14, maxHeight: '70%', overflow: 'hidden' },
  pickerHeader: {
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center',
    paddingHorizontal: 16, paddingVertical: 12, borderBottomWidth: 1, borderBottomColor: '#222',
  },
  pickerTitle: { color: TEXT_COLOR, fontSize: 15, fontWeight: '600' },
  pickerScroll: { paddingHorizontal: 12, paddingVertical: 8 },
  pickerItem: {
    flexDirection: 'row', alignItems: 'center', gap: 10,
    paddingVertical: 10, paddingHorizontal: 8, borderRadius: 8,
  },
  pickerItemPressed: { backgroundColor: SURFACE2 },
  pickerItemText: { flex: 1 },
  pickerItemLabel: { color: TEXT_COLOR, fontSize: 13 },
  pickerItemCap: { color: DIM, fontSize: 10 },
});
