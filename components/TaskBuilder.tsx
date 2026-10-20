import React, { useState, useCallback } from 'react';
import {
  View, Text, Pressable, StyleSheet, Modal, ScrollView,
  TextInput, ActivityIndicator, Platform,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import type { GridCategory, GridAction, TaskTemplate, TaskStep, TaskRunResult } from '@/src/types/actionGrid';
import { UltraDevLog } from '@/src/utils/UltraDevLog';

const BG = '#000';
const SURFACE = '#111';
const SURFACE2 = '#1a1a1a';
const ACCENT = '#e5e5e5';
const DIM = '#666';
const TEXT = '#e0e0e0';
const DANGER = '#ef4444';

interface TaskBuilderProps {
  visible: boolean;
  onClose: () => void;
  onSave: (template: TaskTemplate) => void;
  onTestRun: (steps: TaskStep[]) => Promise<TaskRunResult>;
  editTemplate?: TaskTemplate;
  categories: GridCategory[];
}

type BuilderView = 'main' | 'addStep' | 'editStep';

function makeId(): string {
  return Date.now().toString() + Math.random().toString(36).substr(2, 9);
}

function makeStep(action: GridAction, catLabel: string): TaskStep {
  return {
    id: makeId(),
    capability: action.capability,
    label: `${catLabel}: ${action.label}`,
    params: { ...action.params },
    inputRequired: action.requiresInput ? action.inputKey : undefined,
    inputPlaceholder: action.inputPlaceholder,
  };
}

export default function TaskBuilder({
  visible,
  onClose,
  onSave,
  onTestRun,
  editTemplate,
  categories,
}: TaskBuilderProps) {
  const [taskName, setTaskName] = useState(editTemplate?.name || '');
  const [steps, setSteps] = useState<TaskStep[]>(editTemplate?.steps || []);
  const [view, setView] = useState<BuilderView>('main');
  const [selectedCatId, setSelectedCatId] = useState(categories[0]?.id || '');
  const [editingStep, setEditingStep] = useState<TaskStep | null>(null);
  const [testResult, setTestResult] = useState<TaskRunResult | null>(null);
  const [testing, setTesting] = useState(false);
  const [fixingStepId, setFixingStepId] = useState<string | null>(null);
  const [paramEdits, setParamEdits] = useState<Record<string, string>>({});

  const selectedCat = categories.find(c => c.id === selectedCatId) || categories[0];

  const addStep = useCallback((action: GridAction) => {
    const cat = categories.find(c => c.actions.some(a => a.id === action.id));
    const step = makeStep(action, cat?.label || '');
    UltraDevLog.push('CHAIN', { component: 'TaskBuilder', action: 'add_step', trigger: { actionId: action.id }, state: { stepCount: steps.length }, data: { capability: action.capability, cat: cat?.label }, outcome: 'step_added' });
    setSteps(prev => [...prev, step]);
    setView('main');
  }, [categories, steps.length]);

  const deleteStep = useCallback((stepId: string) => {
    UltraDevLog.push('CHAIN', { component: 'TaskBuilder', action: 'delete_step', trigger: { stepId }, state: { stepCount: steps.length }, data: {}, outcome: 'step_deleted' });
    setSteps(prev => prev.filter(s => s.id !== stepId));
  }, [steps.length]);

  const handleSave = useCallback(() => {
    if (!taskName.trim() || steps.length === 0) {
      UltraDevLog.push('CHAIN', { component: 'TaskBuilder', action: 'save', trigger: {}, state: { taskName: taskName.trim(), stepCount: steps.length }, data: {}, outcome: 'EMPTY:validation_failed' });
      return;
    }
    const template: TaskTemplate = {
      id: editTemplate?.id || makeId(),
      name: taskName.trim(),
      icon: 'flash',
      color: ACCENT,
      steps,
      lastUsed: 0,
      useCount: 0,
      createdAt: editTemplate?.createdAt || Date.now(),
    };
    UltraDevLog.push('CHAIN', { component: 'TaskBuilder', action: 'save', trigger: {}, state: { stepCount: steps.length }, data: { name: taskName.trim(), isEdit: !!editTemplate }, outcome: 'task_saved' });
    onSave(template);
    onClose();
    setTaskName('');
    setSteps([]);
    setTestResult(null);
    setView('main');
  }, [taskName, steps, editTemplate, onSave, onClose]);

  const handleTestRun = useCallback(async () => {
    if (steps.length === 0) {
      UltraDevLog.push('CHAIN', { component: 'TaskBuilder', action: 'test_run', trigger: {}, state: { stepCount: 0 }, data: {}, outcome: 'EMPTY:no_steps' });
      return;
    }
    UltraDevLog.push('CHAIN', { component: 'TaskBuilder', action: 'test_run', trigger: {}, state: { stepCount: steps.length }, data: {}, outcome: 'running' });
    setTesting(true);
    setTestResult(null);
    try {
      const result = await onTestRun(steps);
      UltraDevLog.push('CHAIN', { component: 'TaskBuilder', action: 'test_run', trigger: {}, state: {}, data: { success: result.overallSuccess, stepCount: result.steps.length }, outcome: result.overallSuccess ? 'all_passed' : 'FAIL:some_steps_failed' });
      setTestResult(result);
    } catch (e: any) {
      UltraDevLog.push('CHAIN', { component: 'TaskBuilder', action: 'test_run', trigger: {}, state: {}, data: { error: e.message }, outcome: 'FAIL:exception' });
      setTestResult({
        templateId: 'test',
        startedAt: Date.now(),
        completedAt: Date.now(),
        steps: [],
        overallSuccess: false,
        error: e.message,
      });
    } finally {
      setTesting(false);
    }
  }, [steps, onTestRun]);

  const applyParamFix = useCallback((step: TaskStep) => {
    UltraDevLog.push('CHAIN', { component: 'TaskBuilder', action: 'apply_param_fix', trigger: { stepId: step.id }, state: { editCount: Object.keys(paramEdits).length }, data: {}, outcome: 'params_updated' });
    const updated = steps.map(s => {
      if (s.id !== step.id) return s;
      const newParams = { ...s.params };
      Object.entries(paramEdits).forEach(([k, v]) => { newParams[k] = v; });
      return { ...s, params: newParams };
    });
    setSteps(updated);
    setFixingStepId(null);
    setParamEdits({});
  }, [steps, paramEdits]);

  const handleClose = useCallback(() => {
    UltraDevLog.push('CHAIN', { component: 'TaskBuilder', action: 'close', trigger: {}, state: { stepCount: steps.length, isEdit: !!editTemplate }, data: {}, outcome: 'dismissed' });
    onClose();
    setView('main');
    setTestResult(null);
    setFixingStepId(null);
    setParamEdits({});
    if (!editTemplate) {
      setTaskName('');
      setSteps([]);
    }
  }, [onClose, editTemplate, steps.length]);

  if (!visible) return null;

  return (
    <Modal
      visible={visible}
      animationType="slide"
      presentationStyle="fullScreen"
      onRequestClose={handleClose}
    >
      <View style={styles.container} testID="TaskBuilder">
        {/* Header */}
        <View style={styles.header}>
          <Pressable onPress={handleClose} hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}>
            <Ionicons name="close" size={22} color={TEXT} />
          </Pressable>
          <Text style={styles.headerTitle}>
            {view === 'addStep' ? 'Add Step' : 'Build a Task'}
          </Text>
          {view === 'main' ? (
            <Pressable
              onPress={handleSave}
              disabled={!taskName.trim() || steps.length === 0}
              style={[styles.saveBtn, (!taskName.trim() || steps.length === 0) && styles.saveBtnDisabled]}
            >
              <Text style={styles.saveBtnText}>Save</Text>
            </Pressable>
          ) : (
            <Pressable onPress={() => { UltraDevLog.push('CHAIN', { component: 'TaskBuilder', action: 'back_to_main', trigger: {}, state: { view }, data: {}, outcome: 'main_view' }); setView('main'); }}>
              <Text style={styles.backText}>Back</Text>
            </Pressable>
          )}
        </View>

        {/* ── MAIN VIEW ── */}
        {view === 'main' && (
          <ScrollView style={styles.scroll} keyboardShouldPersistTaps="handled">
            {/* Task name */}
            <TextInput
              style={styles.nameInput}
              value={taskName}
              onChangeText={setTaskName}
              placeholder="Task name..."
              placeholderTextColor="#444"
            />

            {/* Steps */}
            <Text style={styles.sectionLabel}>STEPS</Text>
            {steps.map((step, idx) => {
              const stepResult = testResult?.steps.find(r => r.stepId === step.id);
              const isFailing = stepResult && !stepResult.success;
              const isFixing = fixingStepId === step.id;
              return (
                <View key={step.id} style={[styles.stepCard, isFailing && styles.stepCardFail]}>
                  <View style={styles.stepRow}>
                    <View style={styles.stepBadge}>
                      <Text style={styles.stepBadgeText}>{idx + 1}</Text>
                    </View>
                    <View style={styles.stepInfo}>
                      <Text style={styles.stepLabel}>{step.label}</Text>
                      <Text style={styles.stepParams} numberOfLines={1}>
                        {Object.entries(step.params).map(([k, v]) => `${k}: ${v}`).join(', ') || 'no params'}
                      </Text>
                    </View>
                    <View style={styles.stepActions}>
                      {stepResult && (
                        <View style={[styles.stepBadgeStatus, stepResult.success ? styles.stepPass : styles.stepFail]}>
                          <Ionicons
                            name={stepResult.success ? 'checkmark' : 'close'}
                            size={12}
                            color="#fff"
                          />
                        </View>
                      )}
                      <Pressable onPress={() => deleteStep(step.id)}>
                        <Ionicons name="trash-outline" size={16} color={DANGER} />
                      </Pressable>
                    </View>
                  </View>
                  {isFailing && (
                    <View style={styles.stepError}>
                      <Text style={styles.stepErrorText}>{stepResult?.result}</Text>
                      <Pressable
                        onPress={() => {
                          setFixingStepId(isFixing ? null : step.id);
                          setParamEdits(
                            Object.fromEntries(Object.entries(step.params).map(([k, v]) => [k, String(v)]))
                          );
                        }}
                        style={styles.fixBtn}
                      >
                        <Text style={styles.fixBtnText}>{isFixing ? 'Cancel Fix' : 'Fix'}</Text>
                      </Pressable>
                    </View>
                  )}
                  {isFixing && (
                    <View style={styles.fixEditor}>
                      {Object.keys(step.params).map(k => (
                        <View key={k} style={styles.fixRow}>
                          <Text style={styles.fixKey}>{k}</Text>
                          <TextInput
                            style={styles.fixInput}
                            value={paramEdits[k] ?? String(step.params[k])}
                            onChangeText={v => setParamEdits(prev => ({ ...prev, [k]: v }))}
                            placeholderTextColor="#444"
                          />
                        </View>
                      ))}
                      {step.inputRequired && (
                        <View style={styles.fixRow}>
                          <Text style={styles.fixKey}>{step.inputRequired}</Text>
                          <TextInput
                            style={styles.fixInput}
                            value={paramEdits[step.inputRequired] ?? ''}
                            onChangeText={v => setParamEdits(prev => ({ ...prev, [step.inputRequired!]: v }))}
                            placeholder={step.inputPlaceholder}
                            placeholderTextColor="#444"
                          />
                        </View>
                      )}
                      <Pressable onPress={() => applyParamFix(step)} style={styles.applyFixBtn}>
                        <Text style={styles.applyFixText}>Apply Fix</Text>
                      </Pressable>
                    </View>
                  )}
                </View>
              );
            })}

            <Pressable onPress={() => setView('addStep')} style={styles.addStepBtn}>
              <Ionicons name="add-circle-outline" size={18} color={ACCENT} />
              <Text style={styles.addStepText}>Add Step</Text>
            </Pressable>

            {/* Test / Debug */}
            {steps.length > 0 && (
              <View style={styles.testSection}>
                <Pressable
                  onPress={handleTestRun}
                  disabled={testing}
                  style={[styles.testBtn, testing && styles.testBtnDisabled]}
                >
                  {testing ? (
                    <ActivityIndicator size="small" color={BG} />
                  ) : (
                    <Ionicons name="flask-outline" size={16} color={BG} />
                  )}
                  <Text style={styles.testBtnText}>{testing ? 'Running...' : 'Test Run'}</Text>
                </Pressable>

                {testResult && (
                  <View style={styles.debugBox}>
                    <Text style={[styles.debugOverall, testResult.overallSuccess ? styles.debugPass : styles.debugFail]}>
                      {testResult.overallSuccess ? '✓ All steps passed' : '✗ Some steps failed'}
                    </Text>
                    {testResult.error && (
                      <Text style={styles.debugError}>{testResult.error}</Text>
                    )}
                    {testResult.steps.map((sr, i) => (
                      <Text key={sr.stepId} style={[styles.debugLine, sr.success ? styles.debugPass : styles.debugFail]}>
                        {sr.success ? '✓' : '✗'} Step {i + 1}: {sr.result} ({sr.durationMs}ms)
                      </Text>
                    ))}
                  </View>
                )}
              </View>
            )}
          </ScrollView>
        )}

        {/* ── ADD STEP VIEW ── */}
        {view === 'addStep' && (
          <View style={styles.addStepView}>
            {/* Category tabs */}
            <ScrollView
              horizontal
              showsHorizontalScrollIndicator={false}
              contentContainerStyle={styles.catTabs}
            >
              {categories.map(cat => (
                <Pressable
                  key={cat.id}
                  onPress={() => { UltraDevLog.push('CHAIN', { component: 'TaskBuilder', action: 'category_select', trigger: { catId: cat.id }, state: { prev: selectedCatId }, data: { label: cat.label }, outcome: `category_${cat.id}` }); setSelectedCatId(cat.id); }}
                  style={[styles.catTab, selectedCatId === cat.id && { backgroundColor: cat.color + '33', borderColor: cat.color }]}
                >
                  <Ionicons name={cat.icon as any} size={14} color={selectedCatId === cat.id ? cat.color : DIM} />
                  <Text style={[styles.catTabText, selectedCatId === cat.id && { color: cat.color }]}>
                    {cat.label}
                  </Text>
                </Pressable>
              ))}
            </ScrollView>

            {/* Actions for selected category */}
            <ScrollView style={styles.actionList} keyboardShouldPersistTaps="handled">
              {selectedCat?.actions.map(action => (
                <Pressable
                  key={action.id}
                  onPress={() => addStep(action)}
                  style={({ pressed }) => [styles.actionListItem, pressed && styles.actionListItemPressed]}
                >
                  <View style={[styles.actionIcon, { backgroundColor: (selectedCat.color || ACCENT) + '22' }]}>
                    <Ionicons name={action.icon as any} size={18} color={selectedCat.color || ACCENT} />
                  </View>
                  <View style={styles.actionListInfo}>
                    <Text style={styles.actionListLabel}>{action.label}</Text>
                    <Text style={styles.actionListCap}>{action.capability}</Text>
                    {action.requiresInput && (
                      <Text style={styles.actionListInput}>Requires: {action.inputPlaceholder}</Text>
                    )}
                  </View>
                  <Ionicons name="add" size={18} color={DIM} />
                </Pressable>
              ))}
              {(!selectedCat?.actions || selectedCat.actions.length === 0) && (
                <Text style={styles.emptyText}>No actions in this category.</Text>
              )}
            </ScrollView>
          </View>
        )}
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: BG, paddingTop: Platform.OS === 'ios' ? 50 : 30 },
  header: {
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center',
    paddingHorizontal: 16, paddingBottom: 12, borderBottomWidth: 1, borderBottomColor: SURFACE,
  },
  headerTitle: { color: TEXT, fontSize: 16, fontWeight: '600' },
  saveBtn: { backgroundColor: ACCENT, paddingHorizontal: 14, paddingVertical: 6, borderRadius: 8 },
  saveBtnDisabled: { backgroundColor: '#1a3a30', opacity: 0.5 },
  saveBtnText: { color: BG, fontSize: 14, fontWeight: '600' },
  backText: { color: ACCENT, fontSize: 14 },
  scroll: { flex: 1, padding: 16 },
  nameInput: {
    backgroundColor: SURFACE, color: TEXT, fontSize: 16,
    borderRadius: 10, paddingHorizontal: 14, paddingVertical: 12,
    marginBottom: 20, borderWidth: 1, borderColor: '#222',
  },
  sectionLabel: { color: DIM, fontSize: 11, fontWeight: '600', marginBottom: 8, letterSpacing: 1 },
  stepCard: {
    backgroundColor: SURFACE, borderRadius: 12, padding: 12, marginBottom: 8,
    borderWidth: 1, borderColor: '#1a1a1a',
  },
  stepCardFail: { borderColor: DANGER + '66' },
  stepRow: { flexDirection: 'row', alignItems: 'center', gap: 10 },
  stepBadge: {
    width: 26, height: 26, borderRadius: 13, backgroundColor: SURFACE2,
    justifyContent: 'center', alignItems: 'center',
  },
  stepBadgeText: { color: TEXT, fontSize: 12, fontWeight: '700' },
  stepInfo: { flex: 1 },
  stepLabel: { color: TEXT, fontSize: 13, fontWeight: '500' },
  stepParams: { color: DIM, fontSize: 11, marginTop: 2 },
  stepActions: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  stepBadgeStatus: { width: 20, height: 20, borderRadius: 10, justifyContent: 'center', alignItems: 'center' },
  stepPass: { backgroundColor: ACCENT },
  stepFail: { backgroundColor: DANGER },
  stepError: { marginTop: 8, paddingTop: 8, borderTopWidth: 1, borderTopColor: DANGER + '33' },
  stepErrorText: { color: DANGER, fontSize: 11, fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace' },
  fixBtn: { marginTop: 6, alignSelf: 'flex-start', backgroundColor: '#2a1a00', paddingHorizontal: 10, paddingVertical: 4, borderRadius: 6 },
  fixBtnText: { color: '#fbbf24', fontSize: 12 },
  fixEditor: { marginTop: 10, gap: 8 },
  fixRow: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  fixKey: { color: DIM, fontSize: 12, width: 80 },
  fixInput: {
    flex: 1, backgroundColor: SURFACE2, color: TEXT, fontSize: 13,
    borderRadius: 6, paddingHorizontal: 8, paddingVertical: 6,
    borderWidth: 1, borderColor: '#333',
  },
  applyFixBtn: { backgroundColor: ACCENT + '22', borderRadius: 8, paddingVertical: 8, alignItems: 'center', borderWidth: 1, borderColor: ACCENT },
  applyFixText: { color: ACCENT, fontSize: 13, fontWeight: '600' },
  addStepBtn: {
    flexDirection: 'row', alignItems: 'center', gap: 8, justifyContent: 'center',
    paddingVertical: 14, borderRadius: 12, borderWidth: 1, borderColor: ACCENT + '44',
    borderStyle: 'dashed', marginBottom: 20,
  },
  addStepText: { color: ACCENT, fontSize: 14 },
  testSection: { marginBottom: 32 },
  testBtn: {
    flexDirection: 'row', alignItems: 'center', gap: 8, justifyContent: 'center',
    backgroundColor: ACCENT, paddingVertical: 12, borderRadius: 10, marginBottom: 12,
  },
  testBtnDisabled: { opacity: 0.6 },
  testBtnText: { color: BG, fontSize: 14, fontWeight: '600' },
  debugBox: {
    backgroundColor: SURFACE2, borderRadius: 10, padding: 12,
    borderWidth: 1, borderColor: '#222',
  },
  debugOverall: { fontSize: 13, fontWeight: '700', marginBottom: 8 },
  debugPass: { color: ACCENT },
  debugFail: { color: DANGER },
  debugLine: { fontSize: 12, fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace', marginBottom: 2 },
  debugError: { color: DANGER, fontSize: 12, marginBottom: 6 },
  addStepView: { flex: 1 },
  catTabs: { paddingHorizontal: 12, paddingVertical: 10, gap: 8 },
  catTab: {
    flexDirection: 'row', alignItems: 'center', gap: 5,
    paddingHorizontal: 12, paddingVertical: 6, borderRadius: 20,
    backgroundColor: SURFACE, borderWidth: 1, borderColor: SURFACE2,
  },
  catTabText: { color: DIM, fontSize: 12 },
  actionList: { flex: 1 },
  actionListItem: {
    flexDirection: 'row', alignItems: 'center', gap: 12,
    paddingHorizontal: 16, paddingVertical: 12,
    borderBottomWidth: 1, borderBottomColor: SURFACE,
  },
  actionListItemPressed: { backgroundColor: SURFACE },
  actionIcon: { width: 36, height: 36, borderRadius: 10, justifyContent: 'center', alignItems: 'center' },
  actionListInfo: { flex: 1 },
  actionListLabel: { color: TEXT, fontSize: 14 },
  actionListCap: { color: DIM, fontSize: 11, marginTop: 1 },
  actionListInput: { color: '#fbbf24', fontSize: 11, marginTop: 2 },
  emptyText: { color: DIM, fontSize: 13, padding: 20, textAlign: 'center' },
});
