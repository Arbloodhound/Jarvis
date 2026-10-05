import React, { useState, useEffect, useCallback, useRef } from "react";
import { AppStorage } from "@/src/utils/AppStorage";
import {
  View,
  Text,
  TextInput,
  ScrollView,
  Pressable,
  StyleSheet,
  Alert,
  Platform,
  ActivityIndicator,
} from "react-native";
import { useRouter, useLocalSearchParams } from "expo-router";
import { exportPreferences, importPreferences } from '@/src/services/PreferenceBackup';
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { Ionicons } from "@expo/vector-icons";
import * as Sharing from "expo-sharing";
import { LogFolder, type LogFile } from "@/src/services/LogFolder";
import { SecureVault } from "@/src/security/SecureVault";
import { getAgentCoreInstance } from "@/src/core/AgentCore";
import { UltraDevLog as DebugLog, UltraDevLog } from "@/src/utils/UltraDevLog";
import UsageIndicator, { ModelUsage } from "@/components/UsageIndicator";
import BlockedAppsTab from "@/components/BlockedAppsTab";
import type {
  ApiProvider,
  AuthMode,
} from "@/src/types/provider";

// ── Palette ──────────────────────────────────────────────
const ACCENT = "#e5e5e5";
const BG = "#000000";
const SURFACE = "#0e0e0e";
const SURFACE2 = "#161616";
const SURFACE3 = "#222222";
const DIM = "#666666";
const TEXT = "#e0e0e0";
const DANGER = "#ef4444";
const SUCCESS = "#22c55e";

// ── Types ────────────────────────────────────────────────
type SettingsTab = "apis" | "costs" | "security" | "devtools" | "blocked";

const AUTH_MODES: AuthMode[] = ['bearer', 'api_key_header', 'basic', 'custom_header', 'none'];

const PRESETS = [
  { name: 'OpenRouter', baseUrl: 'https://openrouter.ai/api/v1', authMode: 'bearer' as AuthMode },
  { name: 'OpenAI', baseUrl: 'https://api.openai.com/v1', authMode: 'bearer' as AuthMode },
  { name: 'Anthropic', baseUrl: 'https://api.anthropic.com/v1', authMode: 'bearer' as AuthMode },
  { name: 'Mistral', baseUrl: 'https://api.mistral.ai/v1', authMode: 'bearer' as AuthMode },
  { name: 'Local Ollama', baseUrl: 'http://localhost:11434/v1', authMode: 'none' as AuthMode },
];

// ── ProviderForm state ───────────────────────────────────
interface ProviderDraft {
  id: string;
  name: string;
  baseUrl: string;
  authMode: AuthMode;
  apiKey: string;
  password: string;
  customAuthHeaderName: string;
  customAuthHeaderPrefix: string;
  enabled: boolean;
  hasStoredKey?: boolean;
  hasStoredPassword?: boolean;
}

function emptyDraft(): ProviderDraft {
  return {
    id: `p_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`,
    name: '',
    baseUrl: '',
    authMode: 'bearer',
    apiKey: '',
    password: '',
    customAuthHeaderName: '',
    customAuthHeaderPrefix: '',
    enabled: true,
  };
}


// ── Main Component ────────────────────────────────────────
export default function SettingsScreen() {
  const insets = useSafeAreaInsets();
  const router = useRouter();
  const params = useLocalSearchParams<{ tab?: string }>();

  useEffect(() => {
    UltraDevLog.setCurrentScreen('SettingsScreen');
    return () => UltraDevLog.setCurrentScreen('ChatScreen');
  }, []);

  const initialTab = (['costs', 'security', 'devtools', 'blocked'] as SettingsTab[]).includes(params.tab as SettingsTab)
    ? params.tab as SettingsTab
    : 'apis';
  const [tab, setTab] = useState<SettingsTab>(initialTab);

  // ── Provider state ──────────────────────────────────────
  const [providers, setProviders] = useState<ApiProvider[]>([]);
  const [providerDraft, setProviderDraft] = useState<ProviderDraft | null>(null);
  const [isNewProvider, setIsNewProvider] = useState(false);
  const [probingId, setProbingId] = useState<string | null>(null);

  // ── Cost state ──────────────────────────────────────────
  const [dailyLimit, setDailyLimit] = useState('0');
  const [taskLimit, setTaskLimit] = useState('0');
  const [totalCost, setTotalCost] = useState(0);
  const [totalCalls, setTotalCalls] = useState(0);
  const [modelUsages, setModelUsages] = useState<ModelUsage[]>([]);

  // ── Dev mode ────────────────────────────────────────────
  const [isDevMode, setIsDevMode] = useState(false);
  const [tierVersion, setTierVersion] = useState(0);
  const devTapCountRef = useRef(0);
  const devTapTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // ── Logs ────────────────────────────────────────────────
  const [logFiles, setLogFiles] = useState<LogFile[]>([]);
  const [logsLoaded, setLogsLoaded] = useState(false);
  const [sessionStatus, setSessionStatus] = useState<{
    currentSessionId: string;
    distinctSessions: number;
    allSessionIds: string[];
    lastFlushedSeq: number;
    durableAvailable: boolean;
    prevSessionId: string | null;
    lastBugReportTs: string | null;
  } | null>(null);

  // ── Security ─────────────────────────────────────────────
  const [backupExporting, setBackupExporting] = useState(false);
  const [backupImporting, setBackupImporting] = useState(false);

  // ── Load on mount ────────────────────────────────────────
  useEffect(() => {
    AppStorage.get('dev_mode_enabled').then(v => { if (v === '1') setIsDevMode(true); });
    loadProviders();
    loadCostData();
    loadLimits();
  }, []);

  useEffect(() => {
    if (tab === 'devtools' && !isDevMode) setTab('apis');
  }, [isDevMode]);

  useEffect(() => {
    if (tab === 'devtools' && !logsLoaded) loadLogs();
  }, [tab, logsLoaded]);

  useEffect(() => {
    if (tab === 'costs') loadCostData();
  }, [tab]);

  const loadProviders = useCallback(() => {
    const core = getAgentCoreInstance();
    if (!core) return;
    try {
      const pm = (core as any).getProviderManager?.();
      if (pm) setProviders(pm.getAll());
    } catch {}
  }, []);


  const loadCostData = useCallback(() => {
    const core = getAgentCoreInstance();
    if (!core) return;
    try {
      const summary = core.getCostSummary();
      setTotalCost(summary.totalCost);
      setTotalCalls(summary.totalCalls);
      const usages: ModelUsage[] = Object.entries(summary.costByModel || {}).map(([modelId, cost]) => ({
        modelId,
        modelName: modelId,
        apiName: modelId.split('/')[0] ?? '',
        calls: summary.callsByModel?.[modelId] || 0,
        inputTokens: 0,
        outputTokens: 0,
        cost: cost as number,
      }));
      setModelUsages(usages);
    } catch {}
  }, []);

  const loadLimits = useCallback(async () => {
    try {
      const vault = await SecureVault.initialize();
      const dl = await vault.get('daily_cost_limit');
      setDailyLimit(dl || '0');
      const tl = await vault.get('task_cost_limit');
      setTaskLimit(tl || '0');
    } catch {}
  }, []);

  const loadLogs = useCallback(async () => {
    try {
      const files = await LogFolder.listLogs();
      setLogFiles(files);
      setLogsLoaded(true);
    } catch {
      setLogFiles([]);
      setLogsLoaded(true);
    }
    // Refresh session status whenever logs load
    try {
      const { sessionIndex, sessionIds, currentSessionId, prevSessionId } =
        await UltraDevLog.buildSessionIndexFromDurableFile();
      const bugReportFile = (await LogFolder.listLogs()).find(f => f.name === 'bug-report.txt');
      setSessionStatus({
        currentSessionId,
        distinctSessions: sessionIds.length > 0 ? sessionIds.length : 1,
        allSessionIds: sessionIds.length > 0 ? sessionIds : [currentSessionId],
        lastFlushedSeq: UltraDevLog.getLastFlushedSeq(),
        durableAvailable: sessionIndex.size > 0,
        prevSessionId: prevSessionId ?? null,
        lastBugReportTs: bugReportFile
          ? new Date(bugReportFile.createdAt).toISOString().slice(11, 19)
          : null,
      });
    } catch {
      setSessionStatus({
        currentSessionId: UltraDevLog.getSessionId(),
        distinctSessions: 1,
        allSessionIds: [UltraDevLog.getSessionId()],
        lastFlushedSeq: UltraDevLog.getLastFlushedSeq(),
        durableAvailable: false,
        prevSessionId: null,
        lastBugReportTs: null,
      });
    }
  }, []);

  // ── Dev mode tap ─────────────────────────────────────────
  const handleVersionTap = useCallback(() => {
    devTapCountRef.current += 1;
    if (devTapTimerRef.current) clearTimeout(devTapTimerRef.current);
    devTapTimerRef.current = setTimeout(() => { devTapCountRef.current = 0; }, 2000);
    if (devTapCountRef.current >= 7) {
      devTapCountRef.current = 0;
      const next = !isDevMode;
      setIsDevMode(next);
      AppStorage.set('dev_mode_enabled', next ? '1' : '0');
      const core = getAgentCoreInstance();
      if (core?.getTierService()) {
        core.getTierService()!.setTier(next ? 'dev' : 'free');
        setTierVersion(v => v + 1);
      }
      Alert.alert(next ? 'Dev Mode ON' : 'Dev Mode OFF', next ? 'Logs tab enabled.' : 'Dev mode off.');
    }
  }, [isDevMode]);

  // ── Tier check ───────────────────────────────────────────
  const isApiUnlocked = () => {
    const core = getAgentCoreInstance();
    const ts = core?.getTierService?.();
    return !ts || ts.isApiUnlocked?.() || ts.getTier?.() === 'dev' || ts.getTier?.() === 'pro';
  };

  // ── Provider CRUD ────────────────────────────────────────
  const startNewProvider = useCallback((preset?: typeof PRESETS[0]) => {
    const d = emptyDraft();
    if (preset) {
      d.name = preset.name;
      d.baseUrl = preset.baseUrl;
      d.authMode = preset.authMode;
    }
    setProviderDraft(d);
    setIsNewProvider(true);
  }, []);

  const startEditProvider = useCallback((p: ApiProvider) => {
    DebugLog.push('SETTINGS_SAVE', { event: 'edit_provider_opened', providerId: p.id, hasStoredApiKey: !!p.apiKeyRef, hasStoredPassword: !!p.passwordRef });
    setProviderDraft({
      id: p.id,
      name: p.name,
      baseUrl: p.baseUrl,
      authMode: p.authMode ?? 'bearer',
      apiKey: '',
      password: '',
      customAuthHeaderName: p.customAuthHeaderName ?? '',
      customAuthHeaderPrefix: p.customAuthHeaderPrefix ?? '',
      enabled: p.isActive,
      hasStoredKey: !!p.apiKeyRef,
      hasStoredPassword: !!p.passwordRef,
    });
    setIsNewProvider(false);
  }, []);

  const saveProvider = useCallback(async () => {
    if (!providerDraft?.name.trim() || !providerDraft?.baseUrl.trim()) {
      Alert.alert('Error', 'Name and Base URL are required.');
      return;
    }
    const core = getAgentCoreInstance();
    const pm = (core as any)?.getProviderManager?.();
    if (!pm) { Alert.alert('Error', 'Provider manager not available. Is the agent running?'); return; }
    const existingProvider = providers.find(p => p.id === providerDraft.id);
    const keyReplaced = !!providerDraft.apiKey.trim();
    DebugLog.push('SETTINGS_SAVE', { event: 'provider_save_tapped', isNew: isNewProvider, apiKeyReplaced: keyReplaced });
    try {
      let savedId = providerDraft.id;
      if (isNewProvider) {
        const created = await pm.addProvider({
          name: providerDraft.name.trim(),
          baseUrl: providerDraft.baseUrl.trim(),
          authMode: providerDraft.authMode,
          apiKey: providerDraft.apiKey.trim() || '',
          password: providerDraft.authMode === 'basic' ? providerDraft.password.trim() : undefined,
          customAuthHeaderName: providerDraft.authMode === 'custom_header' || providerDraft.authMode === 'api_key_header' ? providerDraft.customAuthHeaderName.trim() : undefined,
          customAuthHeaderPrefix: providerDraft.authMode === 'custom_header' ? providerDraft.customAuthHeaderPrefix.trim() : undefined,
        });
        savedId = created.id;
        DebugLog.push('SETTINGS_SAVE', { event: 'provider_created', providerId: created.id });
      } else {
        await pm.updateProvider(providerDraft.id, {
          name: providerDraft.name.trim(),
          baseUrl: providerDraft.baseUrl.trim(),
          authMode: providerDraft.authMode,
          isActive: providerDraft.enabled,
          customAuthHeaderName: providerDraft.customAuthHeaderName.trim() || undefined,
          customAuthHeaderPrefix: providerDraft.customAuthHeaderPrefix.trim() || undefined,
        });
        if (keyReplaced) {
          await pm.updateApiKey(providerDraft.id, providerDraft.apiKey.trim());
          DebugLog.push('SETTINGS_SAVE', { event: 'provider_key_replaced', providerId: providerDraft.id });
        } else {
          DebugLog.push('SETTINGS_SAVE', { event: 'provider_updated_no_key_change', providerId: providerDraft.id });
        }
        if (providerDraft.authMode === 'basic' && providerDraft.password.trim()) {
          await pm.updatePassword(providerDraft.id, providerDraft.password.trim());
          DebugLog.push('SETTINGS_SAVE', { event: 'provider_password_saved', providerId: providerDraft.id });
        }
      }
      setProviderDraft(null);
      // Auto-probe the saved provider to populate models and verify connectivity.
      DebugLog.push('SETTINGS_SAVE', { event: 'provider_probe_started', providerId: savedId });
      setProbingId(savedId);
      try {
        await pm.probe(savedId);
        DebugLog.push('SETTINGS_SAVE', { event: 'provider_probe_succeeded', providerId: savedId });
      } catch (probeErr: any) {
        DebugLog.push('SETTINGS_SAVE', { event: 'provider_probe_failed', providerId: savedId, error: probeErr.message });
      } finally {
        setProbingId(null);
      }
      // Re-wire the bridge so the runtime immediately sees the new provider.
      (core as any)?.wireModelRouterBridge?.();
      // Sync provider-backed model list so the picker reflects the new state.
      await (core as any)?.refreshBridgeState?.().catch(() => {});
      loadProviders();
      DebugLog.push('SETTINGS_SAVE', { event: 'provider_reloaded_after_save', providerId: savedId });
    } catch (err: any) {
      Alert.alert('Error', err.message);
    }
  }, [providerDraft, isNewProvider, loadProviders]);

  const deleteProvider = useCallback(async (id: string, name: string) => {
    Alert.alert('Delete Provider', `Remove "${name}"? Groups using this provider will need updating.`, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Delete', style: 'destructive', onPress: async () => {
        const core = getAgentCoreInstance();
        const pm = (core as any)?.getProviderManager?.();
        if (!pm) return;
        try {
          await pm.deleteProvider(id);
          // Rebuild model inventory so the picker immediately reflects the removal.
          await (core as any)?.refreshBridgeState?.().catch(() => {});
          loadProviders();
        } catch (err: any) { Alert.alert('Error', err.message); }
      }},
    ]);
  }, [loadProviders]);

  const toggleProvider = useCallback(async (id: string, enabled: boolean) => {
    const core = getAgentCoreInstance();
    const pm = (core as any)?.getProviderManager?.();
    if (!pm) return;
    try {
      await pm.setActive(id, enabled);
      // Rebuild model inventory so picker adds/removes this provider's models immediately.
      await (core as any)?.refreshBridgeState?.().catch(() => {});
      loadProviders();
    } catch {}
  }, [loadProviders]);

  const probeProvider = useCallback(async (id: string) => {
    setProbingId(id);
    try {
      const core = getAgentCoreInstance();
      const pm = (core as any)?.getProviderManager?.();
      if (!pm) return;
      await pm.probe(id);
      // Rebuild model inventory so newly discovered models appear in the picker.
      await (core as any)?.refreshBridgeState?.().catch(() => {});
      loadProviders();
    } catch (err: any) {
      Alert.alert('Probe failed', err.message);
    } finally {
      setProbingId(null);
    }
  }, [loadProviders]);




  // ── Cost limits ───────────────────────────────────────────
  const saveLimits = useCallback(async () => {
    const parsedDaily = parseFloat(dailyLimit);
    const parsedTask = parseFloat(taskLimit);
    if (dailyLimit.trim() !== '' && (isNaN(parsedDaily) || parsedDaily < 0)) {
      Alert.alert('Invalid', 'Daily limit must be a non-negative number (or blank for no limit).');
      return;
    }
    if (taskLimit.trim() !== '' && (isNaN(parsedTask) || parsedTask < 0)) {
      Alert.alert('Invalid', 'Per-task limit must be a non-negative number (or blank for no limit).');
      return;
    }
    try {
      const vault = await SecureVault.initialize();
      await vault.set('daily_cost_limit', dailyLimit.trim() || '0');
      await vault.set('task_cost_limit', taskLimit.trim() || '0');
      // Propagate limits to the live ledger without requiring a restart.
      const core = getAgentCoreInstance();
      const ledger = (core as any)?.getLedger?.();
      if (ledger) {
        if (parsedDaily > 0) ledger.budgetLimits.maxCostPerDay = parsedDaily;
        if (parsedTask > 0) ledger.budgetLimits.maxCostPerTask = parsedTask;
      }
      Alert.alert('Saved', 'Cost limits updated.');
    } catch (err: any) {
      Alert.alert('Error', err.message);
    }
  }, [dailyLimit, taskLimit]);

  const webTopInset = Platform.OS === 'web' ? 67 : 0;

  return (
    <View style={[styles.container, { paddingTop: insets.top + webTopInset }]}>
      {/* Header */}
      <View style={styles.header}>
        <Pressable onPress={() => router.back()} style={styles.backBtn}>
          <Ionicons name="chevron-back" size={24} color={TEXT} />
        </Pressable>
        <Text style={styles.headerTitle}>Settings</Text>
        <View style={{ width: 32 }} />
      </View>

      {/* Tab bar */}
      <View style={styles.tabBar}>
        {(['apis', 'costs', 'security', ...(isDevMode ? ['devtools' as SettingsTab] : []), 'blocked'] as SettingsTab[]).map(t => (
          <Pressable
            key={t}
            testID={`SettingsTab-${t}`}
            onPress={() => {
              setTab(t);
              if (t === 'devtools') loadLogs();
              if (t === 'costs') loadCostData();
            }}
            style={[styles.tab, tab === t && styles.tabActive]}
          >
            <Text style={[styles.tabText, tab === t && styles.tabTextActive]}>
              {t === 'apis' ? 'AI Providers' : t === 'costs' ? 'Costs' : t === 'security' ? 'Security' : t === 'devtools' ? 'Dev Tools' : 'Blocked'}
            </Text>
          </Pressable>
        ))}
      </View>

      <ScrollView style={styles.body} contentContainerStyle={styles.bodyContent} showsVerticalScrollIndicator={false} keyboardShouldPersistTaps="handled">

        {/* ══ TAB: AI Providers ══ */}
        {tab === 'apis' && (
          <>
            {/* Tier gate */}
            {!isApiUnlocked() && (
              <View style={[styles.card, { alignItems: 'center' }]}>
                <Ionicons name="lock-closed" size={32} color={DIM} style={{ marginBottom: 8 }} />
                <Text style={[styles.cardTitle, { textAlign: 'center' }]}>AI Setup — Pro Feature</Text>
                <Text style={[styles.cardSubtitle, { textAlign: 'center' }]}>
                  Upgrade to Pro to connect your own AI providers.
                </Text>
                <Pressable
                  onPress={() => Alert.alert('Coming Soon', 'Subscription will be available on Google Play.')}
                  style={[styles.btn, styles.primaryBtn, { marginTop: 12 }]}
                >
                  <Ionicons name="arrow-up-circle" size={16} color={BG} />
                  <Text style={styles.primaryBtnText}>Upgrade to Pro</Text>
                </Pressable>
              </View>
            )}

            {isApiUnlocked() && (
              <>
                {/* Provider form */}
                    {providerDraft ? (
                      <View style={styles.card}>
                        <Text style={styles.cardTitle}>{isNewProvider ? 'Add Provider' : 'Edit Provider'}</Text>

                        <Text style={styles.fieldLabel}>Name *</Text>
                        <TextInput
                          value={providerDraft.name}
                          onChangeText={t => setProviderDraft({ ...providerDraft, name: t })}
                          placeholder='e.g. My Cloud API'
                          placeholderTextColor="#444"
                          style={styles.textInput}
                        />

                        <Text style={styles.fieldLabel}>Base URL *</Text>
                        <TextInput
                          value={providerDraft.baseUrl}
                          onChangeText={t => setProviderDraft({ ...providerDraft, baseUrl: t })}
                          placeholder="https://api.example.com/v1"
                          placeholderTextColor="#444"
                          style={styles.textInput}
                          autoCapitalize="none"
                          autoCorrect={false}
                          keyboardType="url"
                        />

                        <Text style={styles.fieldLabel}>Auth Type</Text>
                        <View style={styles.chipRow}>
                          {AUTH_MODES.map(at => (
                            <Pressable
                              key={at}
                              onPress={() => setProviderDraft({ ...providerDraft, authMode: at })}
                              style={[styles.chip, providerDraft.authMode === at && styles.chipActive]}
                            >
                              <Text style={[styles.chipText, providerDraft.authMode === at && styles.chipTextActive]}>{at}</Text>
                            </Pressable>
                          ))}
                        </View>

                        {providerDraft.authMode !== 'none' && (
                          <>
                            <Text style={styles.fieldLabel}>API Key</Text>
                            {!isNewProvider && providerDraft.hasStoredKey && !providerDraft.apiKey ? (
                              <Pressable
                                onPress={() => setProviderDraft({ ...providerDraft, apiKey: ' ', hasStoredKey: false })}
                                style={[styles.textInput, { justifyContent: 'center', flexDirection: 'row', alignItems: 'center', gap: 8 }]}
                              >
                                <Ionicons name="checkmark-circle" size={14} color={SUCCESS} />
                                <Text style={{ color: SUCCESS, fontSize: 13 }}>API key saved — tap to replace</Text>
                              </Pressable>
                            ) : (
                              <TextInput
                                value={providerDraft.apiKey.trim() === '' && !isNewProvider && providerDraft.hasStoredKey === false ? '' : providerDraft.apiKey}
                                onChangeText={t => setProviderDraft({ ...providerDraft, apiKey: t })}
                                placeholder={isNewProvider ? 'Your API key' : 'Enter new API key'}
                                placeholderTextColor="#444"
                                style={styles.textInput}
                                autoCapitalize="none"
                                autoCorrect={false}
                                secureTextEntry
                              />
                            )}
                          </>
                        )}

                        {providerDraft.authMode === 'basic' && (
                          <>
                            <Text style={styles.fieldLabel}>Password</Text>
                            {!isNewProvider && providerDraft.hasStoredPassword && !providerDraft.password ? (
                              <Pressable
                                onPress={() => setProviderDraft({ ...providerDraft, password: ' ', hasStoredPassword: false })}
                                style={[styles.textInput, { justifyContent: 'center', flexDirection: 'row', alignItems: 'center', gap: 8 }]}
                              >
                                <Ionicons name="checkmark-circle" size={14} color={SUCCESS} />
                                <Text style={{ color: SUCCESS, fontSize: 13 }}>Password saved — tap to replace</Text>
                              </Pressable>
                            ) : (
                              <TextInput
                                value={providerDraft.password}
                                onChangeText={t => setProviderDraft({ ...providerDraft, password: t })}
                                placeholder="Password for basic auth"
                                placeholderTextColor="#444"
                                style={styles.textInput}
                                autoCapitalize="none"
                                secureTextEntry
                              />
                            )}
                          </>
                        )}

                        {(providerDraft.authMode === 'custom_header' || providerDraft.authMode === 'api_key_header') && (
                          <>
                            <Text style={styles.fieldLabel}>Header Name</Text>
                            <TextInput
                              value={providerDraft.customAuthHeaderName}
                              onChangeText={t => setProviderDraft({ ...providerDraft, customAuthHeaderName: t })}
                              placeholder={providerDraft.authMode === 'api_key_header' ? 'e.g. X-Api-Key' : 'e.g. X-Custom-Token'}
                              placeholderTextColor="#444"
                              style={styles.textInput}
                              autoCapitalize="none"
                              autoCorrect={false}
                            />
                            {providerDraft.authMode === 'custom_header' && (
                              <>
                                <Text style={styles.fieldLabel}>Header Value Prefix <Text style={{ color: DIM }}>(optional)</Text></Text>
                                <TextInput
                                  value={providerDraft.customAuthHeaderPrefix}
                                  onChangeText={t => setProviderDraft({ ...providerDraft, customAuthHeaderPrefix: t })}
                                  placeholder='e.g. "Token " (leave blank for raw value)'
                                  placeholderTextColor="#444"
                                  style={styles.textInput}
                                  autoCapitalize="none"
                                  autoCorrect={false}
                                />
                              </>
                            )}
                          </>
                        )}

                        <View style={styles.btnRow}>
                          <Pressable onPress={saveProvider} style={[styles.btn, styles.primaryBtn]}>
                            <Ionicons name="save-outline" size={16} color={BG} />
                            <Text style={styles.primaryBtnText}>Save</Text>
                          </Pressable>
                          <Pressable onPress={() => setProviderDraft(null)} style={[styles.btn, styles.secondaryBtn]}>
                            <Text style={styles.secondaryBtnText}>Cancel</Text>
                          </Pressable>
                        </View>
                      </View>
                    ) : (
                      <>
                        {/* Provider list */}
                        {providers.length === 0 && (
                          <View style={[styles.card, { alignItems: 'center', paddingVertical: 24 }]}>
                            <Ionicons name="server-outline" size={28} color={DIM} />
                            <Text style={[styles.emptyText, { marginTop: 8 }]}>No providers yet</Text>
                            <Text style={{ color: '#444', fontSize: 12, marginTop: 4, textAlign: 'center' }}>
                              Add a provider to start using AI features.
                            </Text>
                          </View>
                        )}
                        {providers.map(p => (
                          <View key={p.id} style={[styles.card, !p.isActive && { opacity: 0.55 }]}>
                            <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
                              <View style={{ flexDirection: 'row', alignItems: 'center', flex: 1 }}>
                                <View style={{
                                  width: 8, height: 8, borderRadius: 4, marginRight: 8,
                                  backgroundColor: p.isActive && p.lastProbeSummary?.probedAt && !p.probeError ? SUCCESS : p.isActive ? ACCENT : DIM,
                                }} />
                                <Text style={styles.apiName} numberOfLines={1}>{p.name}</Text>
                              </View>
                              <View style={{ flexDirection: 'row', gap: 12 }}>
                                <Pressable onPress={() => startEditProvider(p)}>
                                  <Ionicons name="pencil-outline" size={16} color={DIM} />
                                </Pressable>
                                <Pressable onPress={() => deleteProvider(p.id, p.name)}>
                                  <Ionicons name="trash-outline" size={16} color={DANGER} />
                                </Pressable>
                              </View>
                            </View>

                            <Text style={styles.apiUrl} numberOfLines={1}>{p.baseUrl}</Text>

                            {/* Probe result */}
                            {p.probeError && (
                              <Text style={{ color: DANGER, fontSize: 11, marginTop: 4 }}>{p.probeError}</Text>
                            )}
                            {p.lastProbeSummary?.probedAt && !p.probeError && (
                              <Text style={{ color: SUCCESS, fontSize: 11, marginTop: 4 }}>
                                Connected · {(p.lastProbeSummary?.modelsFound ?? 0)} model{(p.lastProbeSummary?.modelsFound ?? 0) === 1 ? '' : 's'}
                              </Text>
                            )}

                            {/* Actions */}
                            <View style={{ flexDirection: 'row', gap: 8, marginTop: 10 }}>
                              <Pressable
                                onPress={() => toggleProvider(p.id, !p.isActive)}
                                style={[styles.btn, p.isActive ? styles.secondaryBtn : styles.primaryBtn, { flex: 1, justifyContent: 'center', paddingVertical: 6 }]}
                              >
                                <Ionicons name={p.isActive ? 'pause-circle-outline' : 'play-circle-outline'} size={14} color={p.isActive ? TEXT : BG} />
                                <Text style={[p.isActive ? styles.secondaryBtnText : styles.primaryBtnText, { fontSize: 12 }]}>
                                  {p.isActive ? 'Disable' : 'Enable'}
                                </Text>
                              </Pressable>
                              <Pressable
                                onPress={() => probeProvider(p.id)}
                                disabled={probingId === p.id}
                                style={[styles.btn, styles.secondaryBtn, { flex: 1, justifyContent: 'center', paddingVertical: 6 }]}
                              >
                                {probingId === p.id
                                  ? <ActivityIndicator size="small" color={DIM} />
                                  : <Ionicons name="radio-outline" size={14} color={DIM} />}
                                <Text style={[styles.secondaryBtnText, { fontSize: 12 }]}>
                                  {probingId === p.id ? 'Probing…' : 'Probe'}
                                </Text>
                              </Pressable>
                            </View>
                          </View>
                        ))}

                        {/* Add provider */}
                        <Pressable
                          onPress={() => startNewProvider()}
                          style={[styles.btn, styles.secondaryBtn, { alignSelf: 'stretch', justifyContent: 'center', marginBottom: 12 }]}
                        >
                          <Ionicons name="add-circle-outline" size={16} color={ACCENT} />
                          <Text style={[styles.secondaryBtnText, { color: ACCENT }]}>Add Provider</Text>
                        </Pressable>

                        {/* Quick presets */}
                        <View style={styles.card}>
                          <Text style={styles.cardTitle}>Quick Setup</Text>
                          <Text style={[styles.cardSubtitle, { marginBottom: 8 }]}>
                            Tap to auto-fill a preset. You still need your own API key.
                          </Text>
                          <View style={styles.chipRow}>
                            {PRESETS.map(preset => (
                              <Pressable
                                key={preset.name}
                                onPress={() => startNewProvider(preset)}
                                style={styles.chip}
                              >
                                <Text style={styles.chipText}>{preset.name}</Text>
                              </Pressable>
                            ))}
                          </View>
                        </View>
                      </>
                    )}
              </>
            )}
          </>
        )}

        {/* ══ TAB: COSTS ══ */}
        {tab === 'costs' && (
          <>
            <View style={styles.card}>
              <Text style={styles.cardTitle}>Usage Overview</Text>
              <UsageIndicator
                totalCost={totalCost}
                totalCalls={totalCalls}
                modelUsages={modelUsages}
                dailyLimit={parseFloat(dailyLimit) || 0}
              />
            </View>
            <View style={styles.card}>
              <Text style={styles.cardTitle}>Spending Limits</Text>
              <Text style={styles.cardSubtitle}>
                Set to 0 for no limit. The agent will refuse actions that exceed these limits.
              </Text>
              <Text style={styles.fieldLabel}>Daily Limit (USD)</Text>
              <TextInput
                value={dailyLimit}
                onChangeText={setDailyLimit}
                placeholder="0"
                placeholderTextColor="#444"
                style={styles.textInput}
                keyboardType="decimal-pad"
              />
              {dailyLimit === '0' && <Text style={styles.noLimitHint}>No daily limit set</Text>}
              <Text style={styles.fieldLabel}>Per-Task Limit (USD)</Text>
              <TextInput
                value={taskLimit}
                onChangeText={setTaskLimit}
                placeholder="0"
                placeholderTextColor="#444"
                style={styles.textInput}
                keyboardType="decimal-pad"
              />
              {taskLimit === '0' && <Text style={styles.noLimitHint}>No per-task limit set</Text>}
              <Pressable onPress={saveLimits} style={[styles.btn, styles.primaryBtn, { marginTop: 12 }]}>
                <Ionicons name="save-outline" size={16} color={BG} />
                <Text style={styles.primaryBtnText}>Save Limits</Text>
              </Pressable>
            </View>
          </>
        )}

        {/* ══ TAB: SECURITY ══ */}
        {tab === 'security' && (
          <>
            <View style={styles.card}>
              <Text style={styles.cardTitle}>Preferences Backup</Text>
              <Text style={styles.cardSubtitle}>
                Export or import your settings. API keys are excluded from export.
              </Text>
              <View style={styles.btnRow}>
                <Pressable
                  style={[styles.btn, styles.primaryBtn, { flex: 1, justifyContent: 'center' }, backupExporting && { opacity: 0.6 }]}
                  disabled={backupExporting}
                  onPress={async () => {
                    setBackupExporting(true);
                    try {
                      const result = await exportPreferences();
                      Alert.alert(result.success ? 'Export Complete' : 'Export Failed', result.message);
                    } finally {
                      setBackupExporting(false);
                    }
                  }}
                >
                  {backupExporting ? <ActivityIndicator size="small" color={BG} /> : <Ionicons name="share-outline" size={16} color={BG} />}
                  <Text style={styles.primaryBtnText}>{backupExporting ? 'Exporting…' : 'Export'}</Text>
                </Pressable>
                <Pressable
                  style={[styles.btn, styles.secondaryBtn, { flex: 1, justifyContent: 'center' }, backupImporting && { opacity: 0.6 }]}
                  disabled={backupImporting}
                  onPress={async () => {
                    setBackupImporting(true);
                    try {
                      const result = await importPreferences();
                      Alert.alert(result.success ? 'Import Complete' : 'Import Failed', result.message);
                    } finally {
                      setBackupImporting(false);
                    }
                  }}
                >
                  {backupImporting ? <ActivityIndicator size="small" color={TEXT} /> : <Ionicons name="download-outline" size={16} color={TEXT} />}
                  <Text style={styles.secondaryBtnText}>{backupImporting ? 'Importing…' : 'Import'}</Text>
                </Pressable>
              </View>
            </View>
          </>
        )}

        {/* ══ TAB: DEV TOOLS ══ */}
        {tab === 'devtools' && (
          <>
            <View style={styles.card}>
              <Text style={styles.cardTitle}>Dev Tier Override</Text>
              <View style={{ flexDirection: 'row', gap: 8, flexWrap: 'wrap', marginTop: 8 }}>
                {(['free', 'no_ads', 'pro', 'dev'] as const).map(t => {
                  const current = getAgentCoreInstance()?.getTierService?.()?.getTier?.();
                  const isActive = current === t;
                  return (
                    <Pressable
                      key={t}
                      onPress={() => {
                        const core = getAgentCoreInstance();
                        if (core?.getTierService?.()) {
                          core.getTierService()!.setTier(t);
                          setTierVersion(v => v + 1);
                        }
                      }}
                      style={[styles.btn, isActive ? styles.primaryBtn : styles.secondaryBtn]}
                    >
                      <Text style={isActive ? styles.primaryBtnText : styles.secondaryBtnText}>{t}</Text>
                    </Pressable>
                  );
                })}
              </View>
            </View>
            <View style={styles.card}>
              <View style={{ flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', marginBottom: 8 }}>
                <Text style={styles.cardTitle}>Log Files</Text>
                <Pressable onPress={loadLogs}>
                  <Ionicons name="refresh" size={16} color={DIM} />
                </Pressable>
              </View>
              {!logsLoaded ? (
                <Text style={styles.emptyText}>Loading logs…</Text>
              ) : logFiles.length === 0 ? (
                <Text style={styles.emptyText}>No log files yet.</Text>
              ) : (
                <ScrollView style={{ maxHeight: 300 }} nestedScrollEnabled>
                  {logFiles.map(file => (
                    <Pressable
                      key={file.path}
                      onPress={async () => {
                        const canShare = await Sharing.isAvailableAsync();
                        if (canShare) await Sharing.shareAsync(file.path, { mimeType: 'text/plain', dialogTitle: file.name });
                        else Alert.alert('Sharing unavailable');
                      }}
                      style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingVertical: 10, borderBottomWidth: 1, borderBottomColor: SURFACE3 }}
                    >
                      <View style={{ flex: 1 }}>
                        <Text style={{ color: TEXT, fontSize: 13 }}>{file.name}</Text>
                        <Text style={{ color: DIM, fontSize: 11 }}>{(file.size / 1024).toFixed(1)} KB</Text>
                      </View>
                      <Ionicons name="download-outline" size={16} color={ACCENT} />
                    </Pressable>
                  ))}
                </ScrollView>
              )}
            </View>
            <View style={styles.card}>
              <Text style={styles.cardTitle}>Session Status</Text>
              {sessionStatus ? (
                <View style={{ gap: 3, marginTop: 6 }}>
                  <Text style={{ color: DIM, fontSize: 11, fontFamily: 'Inter_400Regular' }}>
                    Session: <Text style={{ color: TEXT }}>{sessionStatus.currentSessionId}</Text>
                  </Text>
                  <Text style={{ color: DIM, fontSize: 11, fontFamily: 'Inter_400Regular' }}>
                    Sessions in log: <Text style={{ color: TEXT }}>{sessionStatus.distinctSessions}</Text>
                    {sessionStatus.prevSessionId ? <Text style={{ color: SUCCESS }}>  (prev: {sessionStatus.prevSessionId.slice(-8)})</Text> : <Text style={{ color: DIM }}>  (no prior session)</Text>}
                  </Text>
                  <Text style={{ color: DIM, fontSize: 11, fontFamily: 'Inter_400Regular' }}>
                    Last flushed seq: <Text style={{ color: TEXT }}>{sessionStatus.lastFlushedSeq}</Text>
                  </Text>
                  <Text style={{ color: DIM, fontSize: 11, fontFamily: 'Inter_400Regular' }}>
                    Durable source: <Text style={{ color: sessionStatus.durableAvailable ? SUCCESS : DANGER }}>{sessionStatus.durableAvailable ? 'available' : 'unavailable'}</Text>
                  </Text>
                  {sessionStatus.lastBugReportTs && (
                    <Text style={{ color: DIM, fontSize: 11, fontFamily: 'Inter_400Regular' }}>
                      Last bug report: <Text style={{ color: TEXT }}>{sessionStatus.lastBugReportTs}</Text>
                    </Text>
                  )}
                </View>
              ) : (
                <Text style={styles.emptyText}>Load logs to refresh status.</Text>
              )}
            </View>
            <View style={styles.card}>
              <Text style={styles.cardTitle}>Bug Report</Text>
              <Text style={styles.cardSubtitle}>
                Generates a session-scoped summary of failures, traces, and key events.
              </Text>
              <Pressable
                style={[styles.btn, styles.primaryBtn, { marginTop: 4 }]}
                onPress={async () => {
                  const ok = await UltraDevLog.generateBugReportFile();
                  if (ok) { await loadLogs(); Alert.alert('Done', 'bug-report.txt created (current session).'); }
                  else Alert.alert('Error', 'Could not write bug-report.txt');
                }}
              >
                <Ionicons name="bug-outline" size={16} color={BG} />
                <Text style={styles.primaryBtnText}>Current Session Report</Text>
              </Pressable>
              <Pressable
                style={[styles.btn, sessionStatus?.prevSessionId ? styles.secondaryBtn : styles.secondaryBtn, { marginTop: 8, opacity: sessionStatus?.prevSessionId ? 1 : 0.4 }]}
                disabled={!sessionStatus?.prevSessionId}
                onPress={async () => {
                  const { ok, prevSessionId } = await UltraDevLog.generateBugReportFilePrevSession();
                  if (ok) {
                    await loadLogs();
                    Alert.alert('Done', `Previous session report created.\nSession: ${prevSessionId?.slice(-8)}`);
                  } else if (!prevSessionId) {
                    Alert.alert('No Prior Session', 'No previous session found in the durable log file.');
                  } else {
                    Alert.alert('Error', 'Could not write previous session report.');
                  }
                }}
              >
                <Ionicons name="time-outline" size={16} color={sessionStatus?.prevSessionId ? ACCENT : DIM} />
                <Text style={[styles.secondaryBtnText, { color: sessionStatus?.prevSessionId ? ACCENT : DIM }]}>
                  Previous Session Report{sessionStatus?.prevSessionId ? ` (…${sessionStatus.prevSessionId.slice(-8)})` : ' (none)'}
                </Text>
              </Pressable>
            </View>
          </>
        )}

        {/* ══ TAB: BLOCKED ══ */}
        {tab === 'blocked' && (
          <BlockedAppsTab isNative={Platform.OS === 'android'} />
        )}

        {/* Version tap area */}
        <Pressable onPress={handleVersionTap} style={styles.versionTap}>
          <Text style={styles.versionText}>Agent Ultra{isDevMode ? '  [DEV]' : ''}</Text>
        </Pressable>

      </ScrollView>
    </View>
  );
}

// ── Styles ───────────────────────────────────────────────
const styles = StyleSheet.create({
  container: { flex: 1, backgroundColor: BG },
  header: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 12, paddingVertical: 14,
    borderBottomWidth: 1, borderBottomColor: SURFACE3,
  },
  backBtn: { padding: 4, width: 32 },
  headerTitle: { color: TEXT, fontSize: 17, fontFamily: 'Inter_700Bold' },

  tabBar: {
    flexDirection: 'row', paddingHorizontal: 12, paddingVertical: 10, gap: 8,
    borderBottomWidth: 1, borderBottomColor: '#1a1a1a',
  },
  tab: { paddingHorizontal: 12, paddingVertical: 7, borderRadius: 16, backgroundColor: SURFACE2 },
  tabActive: { backgroundColor: ACCENT },
  tabText: { color: DIM, fontSize: 12, fontFamily: 'Inter_500Medium' },
  tabTextActive: { color: BG, fontFamily: 'Inter_600SemiBold' },

  subTabBar: { flexDirection: 'row', gap: 8, marginBottom: 14 },
  subTab: { flex: 1, alignItems: 'center', paddingVertical: 9, borderRadius: 10, backgroundColor: SURFACE2 },
  subTabActive: { backgroundColor: SURFACE3, borderBottomWidth: 2, borderBottomColor: ACCENT },
  subTabText: { color: DIM, fontSize: 13, fontFamily: 'Inter_500Medium' },
  subTabTextActive: { color: ACCENT, fontFamily: 'Inter_600SemiBold' },

  body: { flex: 1 },
  bodyContent: { padding: 16, paddingBottom: 50 },

  card: {
    backgroundColor: SURFACE2, borderRadius: 14, padding: 16, marginBottom: 14,
    borderWidth: 1, borderColor: '#1e1e1e',
  },
  cardTitle: { color: TEXT, fontSize: 15, fontFamily: 'Inter_600SemiBold', marginBottom: 4 },
  cardSubtitle: { color: DIM, fontSize: 12, fontFamily: 'Inter_400Regular', marginBottom: 12, lineHeight: 17 },

  fieldLabel: { color: DIM, fontSize: 12, fontFamily: 'Inter_500Medium', marginTop: 10, marginBottom: 4 },
  textInput: {
    backgroundColor: SURFACE, borderRadius: 10, paddingHorizontal: 14, paddingVertical: 10,
    color: TEXT, fontSize: 14, fontFamily: 'Inter_400Regular', borderWidth: 1, borderColor: '#222',
  },
  noLimitHint: { color: ACCENT, fontSize: 11, fontFamily: 'Inter_400Regular', marginTop: 3, opacity: 0.7 },

  btnRow: { flexDirection: 'row', gap: 10, marginTop: 14 },
  btn: { flexDirection: 'row', alignItems: 'center', gap: 6, paddingHorizontal: 16, paddingVertical: 10, borderRadius: 10 },
  primaryBtn: { backgroundColor: ACCENT },
  primaryBtnText: { color: BG, fontSize: 13, fontFamily: 'Inter_600SemiBold' },
  secondaryBtn: { backgroundColor: SURFACE3 },
  secondaryBtnText: { color: TEXT, fontSize: 13, fontFamily: 'Inter_500Medium' },

  chipRow: { flexDirection: 'row', flexWrap: 'wrap', gap: 6, marginTop: 4 },
  chip: { paddingHorizontal: 10, paddingVertical: 5, borderRadius: 8, backgroundColor: SURFACE3 },
  chipActive: { backgroundColor: ACCENT },
  chipText: { color: DIM, fontSize: 12, fontFamily: 'Inter_500Medium' },
  chipTextActive: { color: BG, fontFamily: 'Inter_600SemiBold' },

  capBadge: { paddingHorizontal: 8, paddingVertical: 2, backgroundColor: '#1a1a1a', borderRadius: 4 },
  capBadgeText: { color: '#888', fontSize: 10 },

  apiName: { color: TEXT, fontSize: 14, fontFamily: 'Inter_600SemiBold', flex: 1 },
  apiUrl: { color: DIM, fontSize: 11, fontFamily: 'Inter_400Regular', marginTop: 4 },

  emptyText: { color: DIM, fontSize: 13, fontFamily: 'Inter_400Regular' },

  versionTap: { alignItems: 'center', marginTop: 20, paddingVertical: 12 },
  versionText: { color: '#333', fontSize: 11, fontFamily: 'Inter_400Regular' },
});
