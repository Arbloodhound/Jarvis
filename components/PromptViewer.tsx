import { Modal, View, Text, ScrollView, Pressable, StyleSheet, Platform, Share, Alert } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import * as Clipboard from 'expo-clipboard';
import { UltraDevLog } from '@/src/utils/UltraDevLog';
// FileSystem and Sharing removed — copy to clipboard only
import type { PromptTrace, MessageRole } from '@/src/types/ultra';


interface PromptViewerProps {
  visible: boolean;
  trace: PromptTrace | null;
  onClose: () => void;
}

const ROLE_COLORS: Record<MessageRole, string> = {
  system: '#ff6b6b',
  user: '#e5e5e5',
  assistant: '#6bc5ff',
  tool: '#ffd166',
};

function RoleLabel({ role }: { role: string }) {
  const color = ROLE_COLORS[role as MessageRole] ?? '#aaa';
  return (
    <View style={[styles.roleTag, { borderColor: color }]}>
      <Text style={[styles.roleText, { color }]}>{role.toUpperCase()}</Text>
    </View>
  );
}

function SectionHeader({ title }: { title: string }) {
  return <Text style={styles.label}>{title}</Text>;
}

function StepIndicator({ success }: { success: boolean }) {
  return (
    <Ionicons
      name={success ? 'checkmark-circle' : 'close-circle'}
      size={14}
      color={success ? '#e5e5e5' : '#ff4444'}
    />
  );
}

function formatTraceToText(trace: PromptTrace): string {
  const lines: string[] = [];
  const hr = '='.repeat(60);
  const ts = (t: number) => new Date(t).toISOString();

  lines.push(hr);
  lines.push('AGENT ULTRA — EXECUTION TRACE');
  lines.push(hr);
  lines.push(`Timestamp: ${ts(trace.createdAt)}`);
  lines.push(`Model: ${trace.model}`);
  if (trace.taskId) lines.push(`Task ID: ${trace.taskId}`);
  if (trace.mode) lines.push(`Mode: ${trace.mode}`);
  if (trace.deterministic !== undefined) lines.push(`Deterministic: ${trace.deterministic}`);
  if (trace.durationMs !== undefined) lines.push(`Duration: ${trace.durationMs}ms`);
  lines.push('');

  if (trace.executionSteps && trace.executionSteps.length > 0) {
    lines.push(hr);
    lines.push('EXECUTION STEPS');
    lines.push(hr);
    trace.executionSteps.forEach((s, i) => {
      lines.push(`[${i + 1}] ${s.step} ${s.success ? 'PASS' : 'FAIL'} @ ${ts(s.timestamp)}`);
      lines.push(`    ${s.detail}`);
      lines.push('');
    });
  }

  if (trace.plan) {
    lines.push(hr);
    lines.push('ACTION PLAN');
    lines.push(hr);
    lines.push(JSON.stringify(trace.plan, null, 2));
    lines.push('');
  }

  lines.push(hr);
  lines.push('SYSTEM PROMPT');
  lines.push(hr);
  lines.push(trace.systemPrompt);
  lines.push('');

  lines.push(hr);
  lines.push('FRAMED USER MESSAGE');
  lines.push(hr);
  lines.push(trace.framedUserMessage);
  lines.push('');

  if (trace.rawResult) {
    lines.push(hr);
    lines.push('RAW RESULT');
    lines.push(hr);
    try {
      lines.push(JSON.stringify(JSON.parse(trace.rawResult), null, 2));
    } catch {
      lines.push(trace.rawResult);
    }
    lines.push('');
  }

  if (trace.safetyCheck) {
    lines.push(hr);
    lines.push('SAFETY CHECK');
    lines.push(hr);
    lines.push(`Risk: ${trace.safetyCheck.risk}`);
    lines.push(`Allowed: ${trace.safetyCheck.allowed}`);
    lines.push(`Reasons: ${trace.safetyCheck.reasons.join(', ') || 'none'}`);
    lines.push('');
  }

  if (trace.verification) {
    lines.push(hr);
    lines.push('VERIFICATION');
    lines.push(hr);
    lines.push(`Verified: ${trace.verification.verified}`);
    lines.push(`Issues: ${trace.verification.issues.join(', ') || 'none'}`);
    lines.push('');
  }

  if (trace.permissionState) {
    lines.push(hr);
    lines.push('PERMISSION STATE');
    lines.push(hr);
    lines.push(trace.permissionState);
    lines.push('');
  }

  if (trace.error) {
    lines.push(hr);
    lines.push('ERROR / STACK TRACE');
    lines.push(hr);
    lines.push(trace.error);
    lines.push('');
  }

  if (trace.includedMessages.length > 0) {
    lines.push(hr);
    lines.push(`INCLUDED MESSAGES (${trace.includedMessages.length})`);
    lines.push(hr);
    trace.includedMessages.forEach((msg, i) => {
      lines.push(`[${msg.role.toUpperCase()}] ${msg.content}`);
      lines.push('');
    });
  }

  if (trace.ledgerEvents && trace.ledgerEvents.length > 0) {
    lines.push(hr);
    lines.push('EXECUTION LEDGER EVENTS');
    lines.push(hr);
    trace.ledgerEvents.forEach(e => {
      lines.push(`${ts(e.timestamp)} | ${e.phase} | ${e.capability || '-'} | ${e.success ? 'OK' : 'FAIL'} | ${e.inputSummary} | ${e.outputSummary}`);
    });
    lines.push('');
  }

  lines.push(hr);
  return lines.join('\n');
}

export default function PromptViewer({ visible, trace, onClose }: PromptViewerProps) {
  if (!trace) return null;

  const handleDownload = async () => {
    UltraDevLog.push('CHAIN', { component: 'PromptViewer', action: 'copy_trace', trigger: {}, state: { model: trace.model }, data: { hasSteps: !!(trace.executionSteps?.length) }, outcome: 'copying' });
    try {
      const text = formatTraceToText(trace);
      await Clipboard.setStringAsync(text);
      UltraDevLog.push('CHAIN', { component: 'PromptViewer', action: 'copy_trace', trigger: {}, state: {}, data: { chars: text.length }, outcome: 'copied_ok' });
      Alert.alert('Copied', `Trace copied to clipboard (${text.length} chars).`);
    } catch (err: any) {
      UltraDevLog.push('CHAIN', { component: 'PromptViewer', action: 'copy_trace', trigger: {}, state: {}, data: { error: err?.message }, outcome: 'FAIL:copy_error' });
      Alert.alert('Copy Failed', err.message || 'Unknown error');
    }
  };

  return (
    <Modal visible={visible} animationType="slide" transparent statusBarTranslucent>
      <View style={styles.overlay}>
        <View style={styles.surface} testID="PromptViewer">
          <View style={styles.header}>
            <Text style={styles.title}>Execution Trace</Text>
            <Pressable onPress={() => { UltraDevLog.push('CHAIN', { component: 'PromptViewer', action: 'close', trigger: {}, state: { model: trace.model }, data: {}, outcome: 'dismissed' }); onClose(); }} hitSlop={12}>
              <Ionicons name="close" size={24} color="#fff" />
            </Pressable>
          </View>

          <ScrollView style={styles.scroll} contentContainerStyle={styles.scrollContent} showsVerticalScrollIndicator={false}>
            <View style={styles.infoRow}>
              <View style={styles.infoPill}>
                <Text style={styles.infoPillLabel}>Model</Text>
                <Text style={styles.infoPillValue}>{trace.model}</Text>
              </View>
              {trace.mode && (
                <View style={styles.infoPill}>
                  <Text style={styles.infoPillLabel}>Mode</Text>
                  <Text style={styles.infoPillValue}>{trace.mode}</Text>
                </View>
              )}
              {trace.deterministic !== undefined && (
                <View style={styles.infoPill}>
                  <Text style={styles.infoPillLabel}>Parse</Text>
                  <Text style={styles.infoPillValue}>{trace.deterministic ? 'deterministic' : 'AI'}</Text>
                </View>
              )}
              {trace.durationMs !== undefined && (
                <View style={styles.infoPill}>
                  <Text style={styles.infoPillLabel}>Duration</Text>
                  <Text style={styles.infoPillValue}>{trace.durationMs}ms</Text>
                </View>
              )}
            </View>
            {trace.taskId && (
              <Text style={styles.taskId}>Task ID: {trace.taskId}</Text>
            )}

            <View style={styles.divider} />

            {trace.executionSteps && trace.executionSteps.length > 0 && (
              <>
                <SectionHeader title="Execution Steps" />
                {trace.executionSteps.map((s, i) => (
                  <View key={i} style={styles.stepRow}>
                    <View style={styles.stepHeader}>
                      <StepIndicator success={s.success} />
                      <Text style={styles.stepNumber}>{i + 1}.</Text>
                      <Text style={[styles.stepName, !s.success && styles.stepNameFail]}>{s.step}</Text>
                      <Text style={styles.stepTime}>{new Date(s.timestamp).toLocaleTimeString()}</Text>
                    </View>
                    <Text style={styles.stepDetail}>{s.detail}</Text>
                  </View>
                ))}
                <View style={styles.divider} />
              </>
            )}

            {trace.plan && (
              <>
                <SectionHeader title="Action Plan" />
                <View style={styles.codeBlock}>
                  <Text style={styles.mono}>{JSON.stringify(trace.plan, null, 2)}</Text>
                </View>
                <View style={styles.divider} />
              </>
            )}

            <View style={styles.section}>
              <SectionHeader title="System Prompt" />
              <Text style={styles.mono}>{trace.systemPrompt}</Text>
            </View>

            <View style={styles.divider} />

            <View style={styles.section}>
              <SectionHeader title="Framed User Message" />
              <Text style={styles.mono}>{trace.framedUserMessage}</Text>
            </View>

            <View style={styles.divider} />

            {trace.rawResult && (
              <>
                <SectionHeader title="Raw Result" />
                <ScrollView horizontal style={styles.codeScroll}>
                  <View style={styles.codeBlock}>
                    <Text style={styles.mono}>
                      {(() => {
                        try { return JSON.stringify(JSON.parse(trace.rawResult), null, 2); }
                        catch { return trace.rawResult; }
                      })()}
                    </Text>
                  </View>
                </ScrollView>
                <View style={styles.divider} />
              </>
            )}

            {trace.safetyCheck && (
              <>
                <SectionHeader title="Safety Assessment" />
                <View style={styles.safetyBlock}>
                  <Text style={[styles.riskBadge, trace.safetyCheck.risk === 'safe' ? styles.riskSafe : trace.safetyCheck.risk === 'moderate' ? styles.riskModerate : styles.riskDangerous]}>
                    {trace.safetyCheck.risk.toUpperCase()}
                  </Text>
                  <Text style={styles.mono}>Allowed: {String(trace.safetyCheck.allowed)}</Text>
                  {trace.safetyCheck.reasons.length > 0 && (
                    <Text style={styles.mono}>Reasons: {trace.safetyCheck.reasons.join(', ')}</Text>
                  )}
                </View>
                <View style={styles.divider} />
              </>
            )}

            {trace.verification && (
              <>
                <SectionHeader title="Result Verification" />
                <View style={styles.verifyBlock}>
                  <View style={styles.verifyRow}>
                    <StepIndicator success={trace.verification.verified} />
                    <Text style={styles.mono}> Verified: {String(trace.verification.verified)}</Text>
                  </View>
                  {trace.verification.issues.length > 0 && (
                    <Text style={[styles.mono, { color: '#ff6b6b' }]}>Issues: {trace.verification.issues.join(', ')}</Text>
                  )}
                </View>
                <View style={styles.divider} />
              </>
            )}

            {trace.permissionState && (
              <>
                <SectionHeader title="Permission State" />
                <Text style={styles.mono}>{trace.permissionState}</Text>
                <View style={styles.divider} />
              </>
            )}

            {trace.error && (
              <>
                <SectionHeader title="Error / Stack Trace" />
                <View style={[styles.codeBlock, styles.errorBlock]}>
                  <Text style={[styles.mono, { color: '#ff4444' }]}>{trace.error}</Text>
                </View>
                <View style={styles.divider} />
              </>
            )}

            <View style={styles.section}>
              <SectionHeader title={`Included Messages (${trace.includedMessages.length})`} />
              {trace.includedMessages.map((msg, i) => (
                <View key={i} style={styles.messageBlock}>
                  <RoleLabel role={msg.role} />
                  <Text style={styles.messageContent}>{msg.content}</Text>
                </View>
              ))}
            </View>

            {trace.ledgerEvents && trace.ledgerEvents.length > 0 && (
              <>
                <View style={styles.divider} />
                <SectionHeader title={`Ledger Events (${trace.ledgerEvents.length})`} />
                {trace.ledgerEvents.map((e, i) => (
                  <View key={i} style={styles.ledgerRow}>
                    <View style={styles.ledgerHeader}>
                      <StepIndicator success={e.success} />
                      <Text style={styles.ledgerPhase}>{e.phase}</Text>
                      {e.capability && <Text style={styles.ledgerCap}>{e.capability}</Text>}
                      <Text style={styles.stepTime}>{new Date(e.timestamp).toLocaleTimeString()}</Text>
                    </View>
                    <Text style={styles.ledgerDetail}>{e.inputSummary}</Text>
                    {e.outputSummary ? <Text style={styles.ledgerDetail}>{e.outputSummary}</Text> : null}
                  </View>
                ))}
              </>
            )}

            <View style={styles.section}>
              <Text style={styles.timestamp}>
                {new Date(trace.createdAt).toLocaleString()}
              </Text>
            </View>

            <Pressable onPress={handleDownload} style={styles.downloadBtn}>
              <Ionicons name="copy-outline" size={18} color="#000" />
              <Text style={styles.downloadBtnText}>Copy Trace</Text>
            </Pressable>
          </ScrollView>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: '#000000cc',
    justifyContent: 'flex-end',
  },
  surface: {
    backgroundColor: '#111',
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    maxHeight: '90%',
    paddingBottom: Platform.OS === 'web' ? 34 : 0,
  },
  header: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingHorizontal: 20,
    paddingTop: 16,
    paddingBottom: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#333',
  },
  title: {
    color: '#e5e5e5',
    fontSize: 18,
    fontWeight: '700' as const,
  },
  scroll: {
    flexGrow: 0,
  },
  scrollContent: {
    padding: 20,
    paddingBottom: 40,
  },
  infoRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 8,
  },
  infoPill: {
    backgroundColor: '#1a1a1a',
    borderRadius: 6,
    paddingHorizontal: 10,
    paddingVertical: 5,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: '#333',
  },
  infoPillLabel: {
    color: '#888',
    fontSize: 9,
    fontWeight: '600' as const,
    textTransform: 'uppercase' as const,
    letterSpacing: 0.5,
  },
  infoPillValue: {
    color: '#ddd',
    fontSize: 12,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  },
  taskId: {
    color: '#666',
    fontSize: 11,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    marginBottom: 8,
  },
  section: {
    marginBottom: 16,
  },
  label: {
    color: '#e5e5e5',
    fontSize: 13,
    fontWeight: '600' as const,
    textTransform: 'uppercase' as const,
    letterSpacing: 0.8,
    marginBottom: 8,
  },
  mono: {
    color: '#ddd',
    fontSize: 12,
    lineHeight: 18,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  },
  divider: {
    height: StyleSheet.hairlineWidth,
    backgroundColor: '#333',
    marginVertical: 12,
  },
  stepRow: {
    marginBottom: 10,
    backgroundColor: '#1a1a1a',
    borderRadius: 8,
    padding: 10,
  },
  stepHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    marginBottom: 4,
  },
  stepNumber: {
    color: '#888',
    fontSize: 12,
    fontWeight: '600' as const,
  },
  stepName: {
    color: '#e5e5e5',
    fontSize: 12,
    fontWeight: '700' as const,
    flex: 1,
  },
  stepNameFail: {
    color: '#ff4444',
  },
  stepTime: {
    color: '#555',
    fontSize: 10,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  },
  stepDetail: {
    color: '#bbb',
    fontSize: 11,
    lineHeight: 16,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    marginLeft: 22,
  },
  codeBlock: {
    backgroundColor: '#1a1a1a',
    borderRadius: 8,
    padding: 12,
    marginBottom: 8,
  },
  codeScroll: {
    maxHeight: 200,
    marginBottom: 8,
  },
  errorBlock: {
    borderWidth: 1,
    borderColor: '#ff444444',
  },
  safetyBlock: {
    backgroundColor: '#1a1a1a',
    borderRadius: 8,
    padding: 12,
    gap: 4,
    marginBottom: 8,
  },
  riskBadge: {
    alignSelf: 'flex-start' as const,
    fontSize: 11,
    fontWeight: '700' as const,
    letterSpacing: 1,
    paddingHorizontal: 8,
    paddingVertical: 2,
    borderRadius: 4,
    overflow: 'hidden' as const,
  },
  riskSafe: {
    backgroundColor: '#e5e5e522',
    color: '#e5e5e5',
  },
  riskModerate: {
    backgroundColor: '#ffaa0022',
    color: '#ffaa00',
  },
  riskDangerous: {
    backgroundColor: '#ff444422',
    color: '#ff4444',
  },
  verifyBlock: {
    backgroundColor: '#1a1a1a',
    borderRadius: 8,
    padding: 12,
    gap: 4,
    marginBottom: 8,
  },
  verifyRow: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  messageBlock: {
    backgroundColor: '#1a1a1a',
    borderRadius: 8,
    padding: 12,
    marginBottom: 8,
  },
  roleTag: {
    alignSelf: 'flex-start',
    borderWidth: 1,
    borderRadius: 4,
    paddingHorizontal: 6,
    paddingVertical: 2,
    marginBottom: 6,
  },
  roleText: {
    fontSize: 10,
    fontWeight: '700' as const,
    letterSpacing: 1,
  },
  messageContent: {
    color: '#ccc',
    fontSize: 12,
    lineHeight: 18,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  },
  ledgerRow: {
    backgroundColor: '#1a1a1a',
    borderRadius: 8,
    padding: 10,
    marginBottom: 6,
  },
  ledgerHeader: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 6,
    marginBottom: 4,
  },
  ledgerPhase: {
    color: '#6bc5ff',
    fontSize: 11,
    fontWeight: '700' as const,
  },
  ledgerCap: {
    color: '#ffd166',
    fontSize: 10,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
  },
  ledgerDetail: {
    color: '#999',
    fontSize: 10,
    lineHeight: 14,
    fontFamily: Platform.OS === 'ios' ? 'Menlo' : 'monospace',
    marginLeft: 22,
  },
  timestamp: {
    color: '#666',
    fontSize: 12,
    textAlign: 'center' as const,
  },
  downloadBtn: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: 8,
    backgroundColor: '#e5e5e5',
    borderRadius: 10,
    paddingVertical: 12,
    marginTop: 12,
  },
  downloadBtnText: {
    color: '#000',
    fontSize: 15,
    fontWeight: '700' as const,
  },
});
