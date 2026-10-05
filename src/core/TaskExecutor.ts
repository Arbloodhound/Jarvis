import { Platform, Share } from 'react-native';
import * as Contacts from 'expo-contacts';
import * as SMS from 'expo-sms';
import * as Sharing from 'expo-sharing';
import * as ImagePicker from 'expo-image-picker';
import * as Location from 'expo-location';
import * as IntentLauncher from 'expo-intent-launcher';
import * as MediaLibrary from 'expo-media-library';
import * as ExpoFileSystem from 'expo-file-system/legacy';
import { BuildSystem } from './BuildSystem';
import { AiService } from './provider/AiService';
import { DebugEngine } from './DebugEngine';
import { CapabilityRegistry } from './CapabilityRegistry';
import { CapabilityProbe } from './CapabilityProbe';
import { PermissionBroker } from './PermissionBroker';
import { ModelRouter } from './ModelRouter';
import { MavenResolver } from './MavenResolver';
import { TestRunner } from './TestRunner';
import { Logger } from '../utils/Logger';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import * as Battery from 'expo-battery';
import DeviceInfo from 'react-native-device-info';
import AppController from '../native/AppController';
import AgentNative from '../native/AgentNative';
import type { ActionPlan } from '../types/ultra';
import type { Genome } from '../genome/types';
import { createDefaultGenome } from '../genome/GenomeFactory';
import { GenomeCompiler } from '../genome/GenomeCompiler';
import { GenomeMutator } from '../genome/GenomeMutator';
import { SelfImprover } from '../genome/SelfImprover';
import { TaskEvaluator } from '../genome/TaskEvaluator';
import { resolveIntent, looksLikeRichIntent } from './IntentResolver';
import { lookupPackage, findBestMatch } from './AppDirectory';
import type { PreferenceLearner } from '../utils/PreferenceLearner';

const FileSystem: any = Platform.OS !== 'web' ? ExpoFileSystem : null;

const isNative = Platform.OS !== 'web';

const GENERIC_SEARCH_PACKAGES = new Set([
  'com.google.android.googlequicksearchbox',
  'com.android.chrome',
  'com.sec.android.app.sbrowser',
]);

function isWeatherLikeTarget(target: string): boolean {
  return /\b(weather|forecast|temperature|temp|rain|snow)\b/i.test(target);
}

function normalizeLaunchQuery(target: string): string {
  const normalized = target.toLowerCase().trim()
    .replace(/^(the|a|an|my)\s+/i, '')
    .replace(/\s+app$/i, '');
  if (isWeatherLikeTarget(normalized)) return 'weather';
  return normalized;
}

async function captureStateDelta(taskId: string, capability: string, toggleFn: () => Promise<boolean>): Promise<{ toggled: boolean; before: any; after: any; changed: Record<string, { from: any; to: any }> }> {
  let before: any = {};
  try {
    const raw = await AppController.getSystemStateSnapshot();
    before = JSON.parse(raw);
  } catch { before = { parseError: true }; }
  DebugLog.push('STATE_BEFORE', { taskId, capability, state: before });

  const toggled = await toggleFn();

  await new Promise(r => setTimeout(r, 1500));

  let after: any = {};
  try {
    const raw = await AppController.getSystemStateSnapshot();
    after = JSON.parse(raw);
  } catch { after = { parseError: true }; }
  DebugLog.push('STATE_AFTER', { taskId, capability, state: after });

  const changed: Record<string, { from: any; to: any }> = {};
  for (const key of Object.keys(after)) {
    if (JSON.stringify(before[key]) !== JSON.stringify(after[key])) {
      changed[key] = { from: before[key], to: after[key] };
    }
  }
  DebugLog.push('STATE_DELTA', { taskId, capability, changed, nothingChanged: Object.keys(changed).length === 0 });

  return { toggled, before, after, changed };
}


async function toAndroidContentUri(pathOrUri: string): Promise<string> {
  if (!pathOrUri) return pathOrUri;
  if (pathOrUri.startsWith('content://')) return pathOrUri;
  const normalized = pathOrUri.startsWith('file://') ? pathOrUri.substring(7) : pathOrUri;
  if (Platform.OS === 'android') {
    try {
      // Use the explicit native FileProvider helper backed by BuildConfig.APPLICATION_ID + ".fileprovider"
      return await AgentNative.getContentUriForFile(normalized);
    } catch {
      return `file://${normalized}`;
    }
  }
  return `file://${normalized}`;
}

async function logUiSnapshot(taskId: string, capability: string): Promise<void> {
  try {
    const pkg = await AppController.getActivePackage();
    const flat = await AppController.getScreenContentFlat();
    const nodes = JSON.parse(flat);
    const summary = {
      package: pkg,
      nodeCount: Array.isArray(nodes) ? nodes.length : 0,
      clickable: Array.isArray(nodes) ? nodes.filter((n: any) => n.k).length : 0,
      topText: Array.isArray(nodes) ? nodes.filter((n: any) => n.t).slice(0, 8).map((n: any) => (n.t || '').slice(0, 40)) : [],
    };
    DebugLog.push('UI_SNAPSHOT', { taskId, capability, ...summary });
  } catch (e: any) { DebugLog.error('UISnapshot', e?.message || 'snapshot failed', e?.stack); }
}

export interface TaskResult {
  success: boolean;
  summary: string;
  data?: any;
}

export class TaskExecutor {
  private _flashlightOn = false;
  private build: BuildSystem;
  private debug: DebugEngine;
  private caps: CapabilityRegistry;
  private probe: CapabilityProbe | null;
  private perms: PermissionBroker;
  private ai: ModelRouter;
  private maven: MavenResolver;
  private testRunner: TestRunner;
  private logger: Logger;
  private docDir: string;
  private currentGenome: Genome | null = null;
  private onGenomeProgress: ((phase: string, message: string) => void) | null = null;
  private learner: PreferenceLearner | null = null;
  private aiServiceProvider: AiService | null = null;

  constructor(build: BuildSystem, debug: DebugEngine, caps: CapabilityRegistry, perms: PermissionBroker, ai: ModelRouter, probe?: CapabilityProbe) {
    this.build = build;
    this.debug = debug;
    this.caps = caps;
    this.probe = probe ?? null;
    this.perms = perms;
    this.ai = ai;
    this.maven = new MavenResolver();
    this.testRunner = new TestRunner(ai);
    this.logger = new Logger('TaskExecutor');
    this.docDir = (isNative && FileSystem?.documentDirectory) || '';
  }

  setPreferenceLearner(learner: PreferenceLearner): void {
    this.learner = learner;
  }

  setAiService(svc: AiService): void {
    this.aiServiceProvider = svc;
  }
  setGenomeProgressCallback(cb: ((phase: string, message: string) => void) | null): void {
    this.onGenomeProgress = cb;
  }

  getCurrentGenome(): Genome | null {
    return this.currentGenome;
  }

  setCurrentGenome(genome: Genome | null): void {
    this.currentGenome = genome;
  }

  private async loadOrCreateGenome(): Promise<Genome> {
    if (this.currentGenome) return this.currentGenome;

    if (isNative) {
      try {
        const destDir = this.docDir + 'genome_sources/';
        const info = await FileSystem.getInfoAsync(destDir);
        if (!info.exists) {
          await FileSystem.makeDirectoryAsync(destDir, { intermediates: true });
          const AgentNativeModule = (await import('../native/AgentNative')).default;
          DebugLog.systemEvent('genome_stage', `Staging genome sources to ${destDir}`);
          try {
            const candidates = [
              FileSystem.bundleDirectory ? `${FileSystem.bundleDirectory.replace(/\/$/, '')}/genome_sources` : null,
              FileSystem.bundleDirectory ? `${FileSystem.bundleDirectory.replace(/\/$/, '')}/assets/genome_sources` : null,
              `file:///android_asset/genome_sources`,
            ].filter(Boolean) as string[];

            let staged = false;
            for (const candidate of candidates) {
              try {
                const info = await FileSystem.getInfoAsync(candidate);
                if (info.exists) {
                  await FileSystem.copyAsync({ from: candidate, to: destDir });
                  DebugLog.systemEvent('genome_stage', `Staged from: ${candidate}`);
                  staged = true;
                  break;
                }
              } catch { }
            }

            if (!staged) {
              const fileNames = ['BinaryManifestWriter.java', 'ApkPackager.java', 'ApkSignerV1.java', 'AgentNativeModule.java', 'AgentAccessibilityService.java'];
              let copiedCount = 0;
              for (const name of fileNames) {
                try {
                  const assetUri = `file:///android_asset/genome_sources/${name}`;
                  const destFile = `${destDir}${name}`;
                  const fileInfo = await FileSystem.getInfoAsync(assetUri);
                  if (fileInfo.exists) {
                    await FileSystem.copyAsync({ from: assetUri, to: destFile });
                    copiedCount++;
                  }
                } catch { }
              }
              if (copiedCount > 0) {
                DebugLog.systemEvent('genome_stage', `Staged ${copiedCount}/${fileNames.length} files individually`);
                staged = true;
              } else {
                DebugLog.error('genome_stage', `genome_sources not found in any candidate path: ${candidates.join(', ')}`);
              }
            }
          } catch (stageErr: any) {
            DebugLog.error('genome_stage', `Genome staging failed: ${stageErr.message}`, stageErr.stack);
          }
        }
      } catch (e: any) { DebugLog.error('GenomeInit', e?.message || 'genome staging failed', e?.stack); }
    }

    if (isNative) {
      try {
        const genomePath = this.docDir + 'genome.json';
        const info = await FileSystem.getInfoAsync(genomePath);
        if (info.exists) {
          const raw = await FileSystem.readAsStringAsync(genomePath);
          this.currentGenome = JSON.parse(raw) as Genome;
          return this.currentGenome;
        }
      } catch (e: any) { DebugLog.error('GenomeLoad', e?.message || 'genome.json parse failed', e?.stack); }
    } else {
      try {
        const raw = typeof localStorage !== 'undefined' ? localStorage.getItem('ultra:genome') : null;
        if (raw) {
          this.currentGenome = JSON.parse(raw) as Genome;
          return this.currentGenome;
        }
      } catch (e: any) { DebugLog.error('GenomeLoad', e?.message || 'localStorage genome parse failed', e?.stack); }
    }
    this.currentGenome = createDefaultGenome();
    await this.persistGenome(this.currentGenome);
    return this.currentGenome;
  }

  private async persistGenome(genome: Genome): Promise<void> {
    if (isNative) {
      try {
        const genomePath = this.docDir + 'genome.json';
        await FileSystem.writeAsStringAsync(genomePath, JSON.stringify(genome, null, 2));
      } catch (e: any) {
        this.logger.error('Failed to persist genome: ' + e.message);
      }
    } else {
      try {
        if (typeof localStorage !== 'undefined') {
          localStorage.setItem('ultra:genome', JSON.stringify(genome));
        }
      } catch (e: any) {
        this.logger.error('Failed to persist genome to localStorage: ' + e.message);
      }
    }
  }

  private createAiClient(timeoutMs?: number) {
    const router = this.ai;
    return {
      chat: async (args: { model: string; messages: Array<{ role: string; content: string }>; max_tokens: number }): Promise<string> => {
        const result = await router.completeWithConversation(args.messages, {
          model: args.model,
          maxTokens: args.max_tokens,
          agentId: 'genome',
        });
        return result.content;
      },
    };
  }

  private createSelfImprover(): SelfImprover {
    const aiClient = this.createAiClient();
    const getModel = async () => this.ai.getDefaultModel();
    const compiler = new GenomeCompiler(aiClient, getModel);
    const mutator = new GenomeMutator(aiClient, getModel);
    const orchestrator = this.build.getOrchestrator();
    const safetyGate = {
      requestApproval: async (_action: string, _details: string): Promise<boolean> => {
        return true;
      },
    };

    const taskEvaluator = new TaskEvaluator(
      isNative && AppController.isAvailable() ? AppController : undefined,
      isNative ? { installApk: (path: string) => this.build.installApk(path) } : undefined
    );

    return new SelfImprover(compiler, mutator, orchestrator, safetyGate, taskEvaluator, aiClient, getModel);
  }

  async initialize(): Promise<void> { this.logger.info('TaskExecutor initialized'); }

  private checkProbe(capId: string): { blocked: boolean; message: string } {
    if (!this.probe) return { blocked: false, message: '' };
    const probeKey = CapabilityProbe.capabilityToProbeKey(capId);
    if (!probeKey) return { blocked: false, message: '' };

    const result = this.probe.get(probeKey);
    if (!result) return { blocked: false, message: '' };

    if (result.unavailable) {
      return {
        blocked: true,
        message: result.note || `${result.label} is not available on this device.`,
      };
    }
    if (!result.granted && !result.canRequest) {
      return {
        blocked: true,
        message: `${result.label} permission was denied. Please enable it in device Settings > Apps > Agent Ultra > Permissions.`,
      };
    }
    return { blocked: false, message: '' };
  }

  async runWithPlan(plan: ActionPlan, taskId?: string): Promise<TaskResult> {
    const id = taskId || Date.now().toString(36);
    const capId = plan.capability;

    if (capId !== 'sms_send') {
      const probeCheck = this.checkProbe(capId);
      if (probeCheck.blocked) {
        return { success: false, summary: probeCheck.message };
      }
    }

    const reqPerms = this.caps.getRequiredPermissions([capId]);
    const missing = this.perms.getMissing(reqPerms);
    if (missing.length > 0) {
      const failed = await this.perms.requestAll(missing);
      if (failed.length > 0) return { success: false, summary: `Missing permissions: ${failed.join(', ')}. Grant in device settings.` };
    }

    try {
      const result = await this.execWithParams(capId, plan.params, plan.reason || '', id);
      const hasError = result && typeof result === 'object' && 'error' in result;
      const explicitFail = result && typeof result === 'object' && result.success === false;
      return {
        success: !hasError && !explicitFail,
        summary: hasError ? result.error : (result.requiresDisambiguation ? result.summary : (result.summary || JSON.stringify(result))),
        data: result,
      };
    } catch (e: any) {
      return { success: false, summary: e.message, data: { error: e.message } };
    }
  }

  async run(capIds: string[], request: string, taskId?: string): Promise<TaskResult> {
    const id = taskId || Date.now().toString(36);

    for (const cid of capIds) {
      const probeCheck = this.checkProbe(cid);
      if (probeCheck.blocked) {
        return { success: false, summary: probeCheck.message };
      }
    }

    const reqPerms = this.caps.getRequiredPermissions(capIds);
    const missing = this.perms.getMissing(reqPerms);
    if (missing.length > 0) {
      const failed = await this.perms.requestAll(missing);
      if (failed.length > 0) return { success: false, summary: `Missing permissions: ${failed.join(', ')}. Grant in device settings.` };
    }
    const results: Array<{ cap: string; result: any }> = [];
    for (const cid of capIds) {
      try {
        const r = await this.exec(cid, request, id);
        results.push({ cap: cid, result: r });
      } catch (e: any) {
        results.push({ cap: cid, result: { error: e.message } });
      }
    }
    const summaryResult = await this.ai.complete(
      `Summarize in 1-2 sentences for user:\nRequest: ${request}\nResults: ${JSON.stringify(results)}`,
      { taskId: id, agentId: 'executor', maxTokens: 200 }
    );
    return { success: results.every((r) => !r.result.error), summary: summaryResult.content, data: results };
  }

  private async completeWithReActLoop(
    goal: string,
    taskId: string,
    appHint?: string,
    maxIterations: number = 6
  ): Promise<{ success: boolean; summary: string; steps: number }> {
    try {
      const serviceEnabled = await AppController.isServiceEnabled().catch(() => false);
      if (!serviceEnabled) {
        return { success: false, summary: 'Accessibility service not enabled — action opened but could not interact', steps: 0 };
      }
      // Pre-allow target package + systemui so first iteration actions aren't rejected
      if (appHint) await AppController.allowPackage(appHint);
      await AppController.allowPackage('com.android.systemui');
      try {
        const currentPkg = await AppController.getActivePackage();
        if (currentPkg) await AppController.allowPackage(currentPkg);
      } catch (e: any) { DebugLog.error('ReActNav', e?.message || 'pre-allow package failed', e?.stack); }
      // Wait for the launched app/settings to render
      await new Promise(resolve => setTimeout(resolve, 2000));

      const { ReActLoop } = await import('./ReActLoop');
      const reactLoop = new ReActLoop(
        async (prompt: string) => {
          const aiResult = await this.ai.complete(prompt, {
            taskId,
            agentId: 'react',
            maxTokens: 600,
            temperature: 0.2,
          });
          return aiResult.content;
        },
        { maxIterations, iterationDelayMs: 1200 }
      );
      const result = await reactLoop.execute(goal, appHint);
      return {
        success: result.goalAchieved,
        summary: result.goalAchieved
          ? `Done: ${goal}`
          : `Opened but could not complete: ${goal} (${result.steps.length} attempts)`,
        steps: result.steps.length,
      };
    } catch (err: any) {
      return { success: false, summary: `UI automation error: ${err.message}`, steps: 0 };
    }
  }

  private getEventMonitor(): import('../services/EventMonitor').EventMonitor | null {
    try {
      const core = require('./AgentCore').getAgentCoreInstance?.();
      return core?.getEventMonitor?.() ?? null;
    } catch {
      return null;
    }
  }

  private inferTriggerType(condition: string): 'schedule' | 'battery_level' | 'notification' | 'sms_content' {
    const normalized = condition.toLowerCase();
    if (/battery|charge/.test(normalized)) return 'battery_level';
    if (/notification|alert|from\s+app|app\s+opens?/.test(normalized)) return 'notification';
    if (/sms|text\s+message|message\s+contains|texts?\s+from/.test(normalized)) return 'sms_content';
    return 'schedule';
  }

  private summarizeTriggers(triggers: Array<{ id: string; type: string; condition: string; action: string; enabled: boolean }>): string {
    if (triggers.length === 0) return 'No active triggers.';
    return triggers
      .map((trigger) => `• ${trigger.id} [${trigger.type}] ${trigger.enabled ? 'enabled' : 'disabled'} — when ${trigger.condition}, do ${trigger.action}`)
      .join('\n');
  }

  private async execWithParams(capId: string, params: Record<string, any>, request: string, taskId: string): Promise<any> {
    switch (capId) {
      case 'file_read': {
        if (!isNative) return { error: 'File operations require Android device' };
        const rawPath = params.path || '';
        // Try 1: explicit absolute path
        // Try 2: internal app directory
        // Try 3: Download directory (requires MANAGE_EXTERNAL_STORAGE on Android 13+)
        const downloadDir = '/storage/emulated/0/Download/';
        const candidates: string[] = [];
        if (rawPath.startsWith('/')) {
          candidates.push(rawPath);
        } else if (rawPath) {
          candidates.push(this.docDir + rawPath);
          candidates.push(downloadDir + rawPath);
        } else {
          candidates.push(this.docDir);
        }
        let targetPath = candidates[0];
        let info: any = { exists: false };
        for (const candidate of candidates) {
          try {
            info = await FileSystem.getInfoAsync(candidate);
            if (info.exists) { targetPath = candidate; break; }
          } catch {}
        }
        if (!info.exists) {
          const triedPaths = candidates.join(', ');
          return { error: `File not found. Tried: ${triedPaths}. If the file is in Downloads, go to Settings and grant "All files access" permission.` };
        }
        try {
          if (info.isDirectory) {
            const files = await FileSystem.readDirectoryAsync(targetPath);
            return { directory: targetPath, files, count: files.length };
          }
          const content = await FileSystem.readAsStringAsync(targetPath);
          return { path: targetPath, content: content.substring(0, 5000), size: content.length };
        } catch (e: any) {
          const files = await FileSystem.readDirectoryAsync(this.docDir);
          return { directory: this.docDir, files, count: files.length };
        }
      }
      case 'file_write': {
        if (!isNative) return { error: 'File operations require Android device' };
        if (params.filename && params.content) {
          const filePath = this.docDir + params.filename;
          await FileSystem.writeAsStringAsync(filePath, params.content);
          return { success: true, path: filePath, size: params.content.length };
        }
        const r = await this.ai.complete(`User wants to write a file: "${request}". Respond JSON: {"filename":"name","content":"data"}`, { taskId, agentId: 'file-write', maxTokens: 4000 });
        const p = JSON.parse(r.content);
        const filePath = this.docDir + p.filename;
        await FileSystem.writeAsStringAsync(filePath, p.content);
        return { success: true, path: filePath, size: p.content.length };
      }
      case 'file_delete': {
        if (!isNative) return { error: 'File operations require Android device' };
        const fn = params.filename;
        if (!fn) return { error: 'No filename provided' };
        const filePath = this.docDir + fn;
        const info = await FileSystem.getInfoAsync(filePath);
        if (info.exists) { await FileSystem.deleteAsync(filePath); return { success: true, deleted: filePath }; }
        return { error: `File not found: ${fn}` };
      }
      case 'file_organize': {
        if (!isNative) return { error: 'File operations require Android device' };
        const actions = params.actions;
        if (!actions || !Array.isArray(actions)) return { error: 'No organize actions provided' };
        const done: string[] = [];
        for (const a of actions) {
          const destDir = a.destination.substring(0, a.destination.lastIndexOf('/'));
          await FileSystem.makeDirectoryAsync(this.docDir + destDir, { intermediates: true });
          await FileSystem.moveAsync({ from: this.docDir + a.source, to: this.docDir + a.destination });
          done.push(`${a.source} -> ${a.destination}`);
        }
        return { success: true, organized: done.length, actions: done };
      }
      case 'contacts_read': {
        const { data } = await Contacts.getContactsAsync({ fields: [Contacts.Fields.Name, Contacts.Fields.PhoneNumbers] });
        return { success: true, contacts: data.length, sample: data.slice(0, 10).map((c) => c.name) };
      }
      case 'sms_read': {
        if (!isNative) return { error: 'SMS reading requires Android device' };
        try {
          const AgentNativeModule = (await import('../native/AgentNative')).default;
          if (!AgentNativeModule?.readSms) return { error: 'SMS reading not available on this device' };
          const limit = params.limit || 10;
          const messages = await AgentNativeModule.readSms(limit, params.filter || '');
          if (!messages || messages.length === 0) {
            return { success: true, summary: 'No messages in inbox', data: { messages: [] } };
          }
          const formatted = messages.map((m: any) => {
            const date = new Date(m.date);
            const timeStr = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            const dateStr = date.toLocaleDateString([], { month: 'short', day: 'numeric' });
            return `[${dateStr} ${timeStr}] ${m.address}: ${m.body}`;
          }).join('\n');
          return {
            success: true,
            summary: `${messages.length} recent messages:\n${formatted}`,
            data: { messages, count: messages.length },
          };
        } catch (err: any) {
          return { success: false, error: `SMS read error: ${err.message}` };
        }
      }
      case 'sms_conversation': {
        if (!isNative) return { error: 'SMS reading requires Android device' };
        try {
          const AgentNativeModule = (await import('../native/AgentNative')).default;
          if (!AgentNativeModule?.readSmsConversation) return { error: 'SMS conversation reading not available' };
          const address = params.address || params.contact || '';
          if (!address) return { error: 'No contact or phone number specified' };
          let resolvedAddress = address;
          if (!/^\+?[\d\s\-\(\)]{7,}$/.test(address)) {
            try {
              const { data } = await Contacts.getContactsAsync({ fields: [Contacts.Fields.PhoneNumbers], name: address });
              const match = data.find((c: any) => c.phoneNumbers && c.phoneNumbers.length > 0);
              if (match?.phoneNumbers?.[0]?.number) resolvedAddress = match.phoneNumbers[0].number;
            } catch {}
          }
          const messages = await AgentNativeModule.readSmsConversation(resolvedAddress, params.limit || 15);
          if (!messages || messages.length === 0) {
            return { success: true, summary: `No messages found with ${address}`, data: { messages: [] } };
          }
          const formatted = messages.map((m: any) => {
            const date = new Date(m.date);
            const timeStr = date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' });
            const dir = m.direction === 'sent' ? 'You' : m.address;
            return `[${timeStr}] ${dir}: ${m.body}`;
          }).join('\n');
          return {
            success: true,
            summary: `Conversation with ${address} (${messages.length} messages):\n${formatted}`,
            data: { messages, count: messages.length, contact: address },
          };
        } catch (err: any) {
          return { success: false, error: `SMS conversation error: ${err.message}` };
        }
      }
      case 'sms_send': {
        console.warn('[TASK] sms_send: to=' + params.to + ' msg_len=' + (params.message || '').length);
        DebugLog.executorEnter(taskId, 'sms_send');
        let to = params.to;
        const message = params.message || '';
        if (!to) { console.warn('[TASK] sms_send: FAIL no_recipient'); DebugLog.executorExit(taskId, 'sms_send', false, 'no_recipient'); return { error: 'No recipient specified' }; }
        const avail = await SMS.isAvailableAsync();
        // FIX 3: Check if user has previously resolved this contact name.
        // If so, use the stored number directly — skip disambiguation.
        if (to && !/^\+?[\d\s\-\(\)]{7,}$/.test(to)) {
          try {
            const core = (await import('./AgentCore')).getAgentCoreInstance();
            if (core) {
              const recalled = await core.recallContact(to);
              if (recalled?.number) {
                DebugLog.smsResolve(taskId, to, recalled.number, 0);
                this.logger.info(`Contact recalled from memory: "${to}" → ${recalled.number}`);
                to = recalled.number;
              }
            }
          } catch (memErr: any) {
            this.logger.warn(`Contact memory recall failed: ${memErr.message}`);
          }
        }
        // Only do contacts lookup if `to` is still a name (not already a phone number)
        if (!/^\+?[\d\s\-\(\)]{7,}$/.test(to)) {
          try {
            const { data } = await Contacts.getContactsAsync({ fields: [Contacts.Fields.Name, Contacts.Fields.PhoneNumbers] });
            const matches = data.filter((c) => c.name?.toLowerCase().includes(to.toLowerCase()));
            if (matches.length > 1) {
              const disambig = matches.filter(m => m.phoneNumbers && m.phoneNumbers.length > 0).map(m => ({
                name: m.name,
                number: m.phoneNumbers![0].number ?? '',
                label: m.phoneNumbers![0].label || 'unknown',
              }));
              if (disambig.length > 1) {
                return {
                  success: false,
                  requiresDisambiguation: true,
                  matches: disambig,
                  summary: `Found ${disambig.length} contacts named "${to}": ${disambig.map(m => `${m.name} (${m.label}: ${m.number})`).join(', ')}. Which one?`,
                };
              }
            }
            const match = matches[0];
            if (match && match.phoneNumbers && match.phoneNumbers.length > 0) {
              const realNumber = match.phoneNumbers.find(
                (p) => p.number && p.number.replace(/\D/g, '').length >= 7
              );
              if (realNumber && realNumber.number) {
                const resolved = realNumber.number;
                DebugLog.smsResolve(taskId, to, resolved, data.length);
                to = resolved;
              } else {
                DebugLog.smsResolve(taskId, to, null, data.length);
              }
            } else {
              DebugLog.smsResolve(taskId, to, null, data.length);
            }
          } catch (e: any) {
            DebugLog.smsResolve(taskId, to, null, 0, e.message);
          }
        }
        DebugLog.smsFire(taskId, to, message);
        let smsSent = false;
        let smsResult: any = 'composed';

        // Try native direct send first (no UI, requires SEND_SMS permission)
        if (message) {
          try {
            const AgentNativeModule = (await import('../native/AgentNative')).default;
            if (AgentNativeModule?.sendSms) {
              const cleanPhone = to.replace(/[\s\-\(\)]/g, '');
              console.warn('[TASK] sms_send: native attempt to=' + cleanPhone);
              const sent = await AgentNativeModule.sendSms(cleanPhone, message);
              console.warn('[TASK] sms_send: native result=' + sent);
              if (sent) {
                smsSent = true;
                smsResult = 'sent_native';
                DebugLog.smsResult(taskId, smsResult, true);
                DebugLog.executorExit(taskId, 'sms_send', true, 'sms_sent_native');
                return {
                  success: true,
                  sent: true,
                  to,
                  summary: `Sent "${message}" to ${to}`,
                };
              }
            }
          } catch (nativeSmsErr: any) {
            this.logger.warn(`Native SMS failed: ${nativeSmsErr.message}, falling back to intent`);
          }
        }

        // Fallback: open SMS composer (user taps Send)
        try {
          const cleanPhone = to.replace(/[\s\-\(\)]/g, '');
          await IntentLauncher.startActivityAsync('android.intent.action.SENDTO', {
            data: `smsto:${cleanPhone}`,
            extra: { sms_body: message },
          });
        } catch (smsIntentErr: any) {
          if (avail) {
            try {
              const { result } = await SMS.sendSMSAsync([to], message);
              smsSent = (result as any)?.data?.sent === true || result === 'sent';
              smsResult = result;
            } catch (expoSmsErr: any) {
              smsResult = expoSmsErr.message;
            }
          }
        }
        DebugLog.smsResult(taskId, smsResult, smsSent);
        DebugLog.executorExit(taskId, 'sms_send', true, smsSent ? 'sms_sent' : 'sms_composed');
        return {
          success: true,
          sent: smsSent,
          to,
          summary: smsSent
            ? `Sent "${message}" to ${to}`
            : `Message to ${to} ready — tap Send`,
        };
      }
      case 'camera_capture': {
        console.warn('[TASK] camera_capture: entering');
        if (!isNative) { console.warn('[TASK] camera_capture: FAIL not_native'); return { error: 'Camera requires a device' }; }
        try {
          const { status } = await ImagePicker.requestCameraPermissionsAsync();
          console.warn('[TASK] camera_capture: permission status=' + status);
          if (status !== 'granted') {
            console.warn('[TASK] camera_capture: FAIL permission_denied');
            return { success: false, error: 'Camera permission not granted. Enable in Settings > Apps > Agent Ultra > Permissions.' };
          }
          const camResult = await ImagePicker.launchCameraAsync({
            mediaTypes: ['images'],
            quality: 0.85,
            allowsEditing: false,
          });
          if (camResult.canceled || !camResult.assets?.length) {
            // Camera may have returned "cancelled" due to the app going to background
            // during the camera session (common on Samsung). Check if a photo was
            // actually taken in the last 30 seconds.
            try {
              const recent = await MediaLibrary.getAssetsAsync({
                first: 1,
                sortBy: [MediaLibrary.SortBy.creationTime],
                mediaType: [MediaLibrary.MediaType.photo],
              });
              if (recent.assets.length > 0) {
                const recovered = recent.assets[0];
                const ageMs = Date.now() - recovered.creationTime;
                if (ageMs < 30000) {
                  DebugLog.push('CAMERA_RECOVERY', { ageMs, uri: recovered.uri.slice(0, 60) });
                  return {
                    success: true,
                    summary: `Photo captured: ${recovered.filename}`,
                    data: { uri: recovered.uri, width: recovered.width, height: recovered.height, filename: recovered.filename },
                  };
                }
              }
            } catch (recoveryErr: any) {
              DebugLog.error('CameraRecovery', recoveryErr?.message || 'recovery failed', recoveryErr?.stack);
            }
            return { success: false, cancelled: true, summary: 'Camera capture cancelled by user' };
          }
          const asset = camResult.assets[0];
          return {
            success: true,
            summary: `Photo captured (${asset.width}×${asset.height})`,
            data: { uri: asset.uri, width: asset.width, height: asset.height, fileSize: asset.fileSize || null },
          };
        } catch (err: any) {
          return { success: false, error: `Camera error: ${err.message}` };
        }
      }
      case 'media_access': {
        if (!isNative) return { error: 'Gallery access requires a device' };
        const perm = await MediaLibrary.requestPermissionsAsync();
        if (perm.status !== 'granted') {
          return { success: false, error: 'Photos permission not granted. Enable in Settings > Apps > Agent Ultra > Permissions.' };
        }
        if (params.action === 'pick') {
          const pickResult = await ImagePicker.launchImageLibraryAsync({
            mediaTypes: ['images'],
            quality: 0.8,
          });
          if (pickResult.canceled || !pickResult.assets || pickResult.assets.length === 0) {
            return { success: false, cancelled: true, summary: 'Image selection cancelled by user' };
          }
          const picked = pickResult.assets[0];
          return { success: true, uri: picked.uri, width: picked.width, height: picked.height, fileSize: picked.fileSize || null };
        }

        if (params.action === 'count') {
          const pageSize = 200;
          let total = 0;
          let after: string | undefined = undefined;
          let hasNextPage = true;
          const recent: Array<{ name: string; type: string }> = [];
          while (hasNextPage) {
            const page = await MediaLibrary.getAssetsAsync({
              first: pageSize,
              after,
              sortBy: [MediaLibrary.SortBy.creationTime],
              mediaType: [MediaLibrary.MediaType.photo],
            });
            total += page.assets.length;
            if (recent.length < 5) {
              for (const asset of page.assets) {
                if (recent.length >= 5) break;
                recent.push({ name: asset.filename, type: asset.mediaType });
              }
            }
            hasNextPage = !!page.hasNextPage;
            after = page.endCursor || undefined;
            if (!page.assets.length) break;
          }
          return {
            success: true,
            count: total,
            recent,
            summary: `You have ${total} image${total === 1 ? '' : 's'} in your gallery.`,
          };
        }

        const { assets } = await MediaLibrary.getAssetsAsync({
          first: 20,
          sortBy: [MediaLibrary.SortBy.creationTime],
          mediaType: [MediaLibrary.MediaType.photo],
        });
        return {
          success: true,
          count: assets.length,
          recent: assets.map((a) => ({ name: a.filename, type: a.mediaType })),
          summary: assets.length > 0 ? `Showing ${assets.length} recent image${assets.length === 1 ? '' : 's'}.` : 'No images found in your gallery.',
        };
      }
      case 'app_launch': {
        console.warn('[TASK] entering: app_launch');
        DebugLog.executorEnter(taskId, 'app_launch');
        const target = params.target;
        if (!target) { DebugLog.executorExit(taskId, 'app_launch', false, 'no_target'); return { error: 'No app or action specified' }; }

        // If target looks like a URL and no explicit action, treat as ACTION_VIEW
        if (target && /^https?:\/\//i.test(target) && !params.action) {
          params.action = 'android.intent.action.VIEW';
          params.data = target;
          params.target = 'browser';
        }

        // ── PATH A: Rich intent (action/data/extras provided by parser) ──
        if (params.action) {
          DebugLog.executorBranch(taskId, 'app_launch', 'rich_intent', { action: params.action });
          this.logger.info(`Rich intent: action=${params.action} data=${params.data || 'none'} pkg=${params.packageName || 'none'}`);

          // Normalize short action aliases to full Android intent strings
          const ACTION_MAP: Record<string, string> = {
            'view': 'android.intent.action.VIEW',
            'call': 'android.intent.action.CALL',
            'dial': 'android.intent.action.DIAL',
            'send': 'android.intent.action.SEND',
            'edit': 'android.intent.action.EDIT',
            'pick': 'android.intent.action.PICK',
          };
          const rawAction = params.action || '';
          const safeAction = ACTION_MAP[rawAction.toLowerCase()] || rawAction || 'android.intent.action.VIEW';

          // Special case: contact name resolution for phone calls
          if (params.extras?._contactName &&
              (params.action === 'android.intent.action.DIAL' ||
               params.action === 'android.intent.action.CALL')) {
            const contactName = params.extras._contactName as string;
            // FIX 3: Check stored contact preference first
            try {
              const core = (await import('./AgentCore')).getAgentCoreInstance();
              if (core) {
                const recalled = await core.recallContact(contactName);
                if (recalled?.number) {
                  params.data = `tel:${recalled.number}`;
                  params.action = 'android.intent.action.CALL';
                  delete params.extras._contactName;
                  this.logger.info(`Contact recalled: "${contactName}" → ${recalled.number}`);
                }
              }
            } catch (callMemErr: any) {
              this.logger.warn(`Call contact memory recall failed: ${callMemErr.message}`);
            }
            if (!params.data) { // Only do full lookup if recall missed
              try {
                const { data: contacts } = await Contacts.getContactsAsync({
                  fields: [Contacts.Fields.PhoneNumbers, Contacts.Fields.Name],
                  name: contactName,
                });
                const withNumbers = contacts.filter(c => c.phoneNumbers && c.phoneNumbers.length > 0);
                if (withNumbers.length > 1) {
                  const disambig = withNumbers.map(m => ({
                    name: m.name,
                    number: m.phoneNumbers![0].number ?? '',
                    label: m.phoneNumbers![0].label || 'unknown',
                  }));
                  return {
                    success: false,
                    requiresDisambiguation: true,
                    matches: disambig,
                    summary: `Found ${disambig.length} contacts named "${contactName}": ${disambig.map(m => `${m.name} (${m.label}: ${m.number})`).join(', ')}. Which one?`,
                  };
                }
                if (withNumbers.length > 0) {
                  const match = withNumbers[0];
                  const realNumber = match.phoneNumbers?.find(
                    (p: any) => p.number && p.number.replace(/\D/g, '').length >= 7
                  );
                  if (realNumber?.number) {
                    params.data = `tel:${realNumber.number}`;
                    params.action = 'android.intent.action.CALL';
                    this.logger.info(`Resolved contact "${contactName}" → ${realNumber.number} (ACTION_CALL)`);
                  } else {
                    return { success: false, error: `Found contact "${contactName}" but no valid phone number` };
                  }
                } else {
                  return { success: false, error: `Contact "${contactName}" not found` };
                }
              } catch (e: any) {
                return { success: false, error: `Contact lookup failed: ${e.message}` };
              }
              delete params.extras._contactName;
            }
          }

          // Resolve package name if not provided
          let pkg = params.packageName;
          if (!pkg && target && target !== 'phone' && target !== 'clock' && target !== 'email' && target !== 'maps' && target !== 'browser') {
            pkg = lookupPackage(target);
            if (!pkg) {
              try {
                const AgentNativeModule = (await import('../native/AgentNative')).default;
                const installed = await AgentNativeModule.getInstalledApps();
                const match = findBestMatch(target, installed);
                if (match) pkg = match.packageName;
              } catch (matchErr: any) {
                this.logger.warn(`App package resolve failed: ${matchErr.message}`);
              }
            }
          }

          try {
            const intentParams: any = {};
            if (params.data) intentParams.data = params.data;
            if (pkg) intentParams.packageName = pkg;
            if (params.mimeType) intentParams.type = params.mimeType;
            if (params.extras) {
              // Filter out internal-only extras (prefixed with _)
              const cleanExtras: Record<string, any> = {};
              for (const [k, v] of Object.entries(params.extras)) {
                if (!k.startsWith('_')) cleanExtras[k] = v;
              }
              if (Object.keys(cleanExtras).length > 0) intentParams.extra = cleanExtras;
            }

            this.logger.info(`startActivityAsync: ${safeAction} → ${JSON.stringify(intentParams)}`);
            const result = await IntentLauncher.startActivityAsync(safeAction, intentParams);

            DebugLog.executorExit(taskId, 'app_launch', true, 'rich_intent_success');
            // Log what's in foreground after launching
            try {
              const { DeviceDiagnostics } = await import('../services/DeviceDiagnostics');
              await DeviceDiagnostics.logActivityStack();
              await DeviceDiagnostics.logWindowState();
            } catch {}
            return {
              success: true,
              launched: target,
              action: params.action,
              data: params.data,
              packageName: pkg,
              resultCode: result.resultCode,
            };
          } catch (err: any) {
            DebugLog.executorBranch(taskId, 'app_launch', 'rich_intent_failed', { error: err.message });
            this.logger.warn(`Rich intent failed: ${err.message}, falling back to openApplication`);

            // FIX 2: If ACTION_CALL was blocked by missing CALL_PHONE
            // permission, retry with ACTION_DIAL — opens dialer with
            // number pre-filled, no permission required.
            if (
              params.action === 'android.intent.action.CALL' &&
              (err.message?.includes('SecurityException') ||
               err.message?.includes('Permission Denial') ||
               err.message?.includes('revoked') ||
               err.message?.includes('CALL_PHONE'))
            ) {
              try {
                const dialParams: any = {};
                if (params.data) dialParams.data = params.data;
                const dialResult = await IntentLauncher.startActivityAsync(
                  'android.intent.action.DIAL',
                  dialParams
                );
                DebugLog.executorExit(taskId, 'app_launch', true, 'dial_fallback');
                this.logger.info('CALL_PHONE denied — opened dialer via ACTION_DIAL');
                return {
                  success: true,
                  launched: target,
                  action: 'android.intent.action.DIAL',
                  data: params.data,
                  packageName: pkg,
                  resultCode: dialResult.resultCode,
                  note: 'CALL_PHONE permission denied — dialer opened, tap Call to complete',
                };
              } catch (dialErr: any) {
                this.logger.warn(`DIAL fallback failed: ${dialErr.message}`);
                // Fall through to existing openApplication fallback
              }
            }

            // Fallback: try simple app launch if we have a package
            if (pkg) {
              try {
                await IntentLauncher.openApplication(pkg);
                return { success: true, launched: target, packageName: pkg, fallback: true };
              } catch (err2: any) {
                // Browser fallback — if the target looks like it could be a website
                const webTarget = target.toLowerCase().replace(/\s+/g, '');
                const commonSites = ['reddit','youtube','twitter','instagram','facebook','spotify',
                  'amazon','netflix','tiktok','pinterest','linkedin','github','stackoverflow',
                  'wikipedia','google','yahoo','bing','twitch','discord','slack','whatsapp','telegram'];
                if (commonSites.includes(webTarget) || webTarget.includes('.')) {
                  try {
                    const { Linking } = require('react-native');
                    const url = webTarget.includes('.') ? 'https://' + webTarget : 'https://www.' + webTarget + '.com';
                    await Linking.openURL(url);
                    DebugLog.executorExit(taskId, 'app_launch', true, 'browser_fallback');
                    return { success: true, summary: `App not installed — opened ${url} in browser instead.`, fallback: 'browser' };
                  } catch (browserErr: any) {
                    DebugLog.error('app_launch_browser_fallback', browserErr.message, browserErr.stack);
                  }
                }
                return { success: false, error: `Failed to launch ${target}: ${err.message} (fallback also failed: ${err2.message})` };
              }
            }

            return { success: false, error: `Intent failed for ${target}: ${err.message}` };
          }
        }

        // ── PATH B: Simple app launch (no action — just open the app) ──
        DebugLog.executorBranch(taskId, 'app_launch', 'simple_launch');

        // ── Layer 2: Settings intents (direct lookup before package resolution) ──
        const { resolveSettingsIntent } = await import('./SettingsDirectory');
        const settingsMatch = resolveSettingsIntent(target);
        DebugLog.settingsIntent(target, !!settingsMatch, settingsMatch?.action, settingsMatch?.label);
        if (settingsMatch) {
          try {
            await IntentLauncher.startActivityAsync(settingsMatch.action, {});
            DebugLog.executorExit(taskId, 'app_launch', true, 'settings_intent');
            return { success: true, launched: settingsMatch.label, action: settingsMatch.action };
          } catch (err: any) {
            DebugLog.executorExit(taskId, 'app_launch', false, 'settings_intent_failed');
            return { success: false, error: `Could not open ${settingsMatch.label}: ${err.message}` };
          }
        }

        // ── Layer 3: Deep links (before package resolution) ──
        const { resolveDeepLink } = await import('./DeepLinkDirectory');
        const deepLinkMatch = resolveDeepLink(target);
        DebugLog.deepLink(target, !!deepLinkMatch, deepLinkMatch?.uri, deepLinkMatch?.label);
        if (deepLinkMatch) {
          try {
            await IntentLauncher.startActivityAsync(
              'android.intent.action.VIEW' as any,
              { data: deepLinkMatch.uri, packageName: deepLinkMatch.packageHint }
            );
            DebugLog.executorExit(taskId, 'app_launch', true, 'deep_link');
            return { success: true, launched: deepLinkMatch.label, uri: deepLinkMatch.uri };
          } catch (err: any) {
            DebugLog.executorExit(taskId, 'app_launch', false, 'deep_link_failed');
            return { success: false, error: `Could not open ${deepLinkMatch.label}: ${err.message}` };
          }
        }

        // ── Layer 4: System actions (toggle/direct device actions) ──
        const { resolveSystemAction } = await import('./SystemActions');
        const systemAction = resolveSystemAction(target);
        if (systemAction) {
          const sysResult = await systemAction.handler();
          DebugLog.executorExit(taskId, 'app_launch', sysResult.success, 'system_action');
          return sysResult;
        }

        const targetLower = normalizeLaunchQuery(target);
        const weatherLikeTarget = isWeatherLikeTarget(target);

        DebugLog.appLaunchBegin(taskId, target, targetLower);

        if (['contacts', 'people', 'address book', 'phonebook'].includes(targetLower)) {
          try {
            await IntentLauncher.startActivityAsync('android.intent.action.VIEW', {
              data: 'content://com.android.contacts/contacts',
            });
            DebugLog.executorExit(taskId, 'app_launch', true, 'contacts_intent');
            return { success: true, launched: 'Contacts' };
          } catch (err: any) {
            this.logger.warn(`Contacts intent failed: ${err.message}, trying package`);
          }
        }

        if (['messages', 'messaging', 'text messages', 'sms app', 'sms'].includes(targetLower)) {
          try {
            await IntentLauncher.startActivityAsync('android.intent.action.MAIN', {
              category: 'android.intent.category.APP_MESSAGING',
            });
            DebugLog.executorExit(taskId, 'app_launch', true, 'messages_intent');
            return { success: true, launched: 'Messages' };
          } catch (e: any) {
            DebugLog.error('AppLaunch', `Messages intent failed: ${e?.message}`, e?.stack);
            try {
              await IntentLauncher.startActivityAsync('android.intent.action.VIEW', {
                data: 'sms:',
              });
              DebugLog.executorExit(taskId, 'app_launch', true, 'messages_sms_fallback');
              return { success: true, launched: 'Messages' };
            } catch (err2: any) {
              this.logger.warn(`Messages intent failed: ${err2.message}, trying package`);
            }
          }
        }

        let pkg: string | undefined;
        let resolvedViaAlias = false;

        // Step 0a: Use pre-supplied packageName if caller already resolved it (e.g., from disambiguation)
        if (params.packageName && params.packageName.includes('.')) {
          pkg = params.packageName;
          resolvedViaAlias = true; // Treat as pre-confirmed — skip alias write
          this.logger.info(`Pre-supplied package: "${target}" → ${pkg}`);
          DebugLog.appLaunchMatch(taskId, targetLower, 'exact', target, pkg);
        }

        // Step 0b: Check permanently learned aliases (user-confirmed mappings — never re-query)
        if (!pkg && this.learner) {
          const aliasedPkg = this.learner.getAppAlias(targetLower);
          if (aliasedPkg) {
            const poisonedWeatherAlias = weatherLikeTarget && GENERIC_SEARCH_PACKAGES.has(aliasedPkg);
            if (poisonedWeatherAlias) {
              try { await this.learner.forgetAppAlias(targetLower); } catch {}
              DebugLog.systemEvent('TaskExecutor', `Ignored poisoned weather alias: "${targetLower}" → ${aliasedPkg}`);
            } else {
              pkg = aliasedPkg;
              resolvedViaAlias = true;
              this.logger.info(`Alias hit: "${targetLower}" → ${aliasedPkg}`);
              DebugLog.appLaunchMatch(taskId, targetLower, 'exact', targetLower, aliasedPkg);
            }
          }
        }

        // Step 2: Query installed apps with fuzzy matching
        let fuzzyMatch: { packageName: string; appName: string; score: number; matchType: string } | null = null;
        let fuzzyAlternatives: Array<{ packageName: string; appName: string; score: number }> = [];
        if (!pkg) {
          try {
            const _qStart = Date.now();
            const AgentNativeModule = (await import('../native/AgentNative')).default;
            const installed = await AgentNativeModule.getInstalledApps();
            DebugLog.appLaunchDeviceQuery(taskId, installed?.length ?? 0, Date.now() - _qStart);
            if (installed && installed.length > 0) {
              let match = findBestMatch(targetLower, installed);
              if (!match && weatherLikeTarget) {
                const weatherCandidates = installed.filter((app: any) => /weather|accuweather|forecast/i.test(`${app.appName} ${app.packageName}`));
                match = weatherCandidates.length > 0 ? findBestMatch('weather', weatherCandidates, 25) : null;
              }
              if (match) {
                fuzzyMatch = match;
                pkg = match.packageName;
                DebugLog.appLaunchMatch(taskId, targetLower, match.matchType === 'exact' ? 'exact' : 'partial', match.appName, pkg);
                this.logger.info(`Fuzzy match: "${targetLower}" → "${match.appName}" (${match.packageName}) score=${match.score} type=${match.matchType}`);

                // When confidence is low (score 35–70), also find runner-up candidates
                // so the user can confirm the right one
                if (match.score < 70 && match.matchType !== 'exact' && match.matchType !== 'directory') {
                  for (const app of installed) {
                    if (app.packageName === match.packageName) continue;
                    const altMatch = findBestMatch(targetLower, [app], 25);
                    if (altMatch && altMatch.score >= 35 && altMatch.score >= match.score - 20) {
                      fuzzyAlternatives.push(altMatch);
                    }
                  }
                  fuzzyAlternatives = fuzzyAlternatives
                    .sort((a, b) => b.score - a.score)
                    .slice(0, 3);
                }
              } else {
                DebugLog.appLaunchMatch(taskId, targetLower, 'none');
              }
            }
          } catch (e: any) {
            this.logger.warn('getInstalledApps failed, using AI fallback', { error: e.message });
            DebugLog.appLaunchDeviceQuery(taskId, 0, 0, e.message);
          }

          // When fuzzy match confidence is low and alternatives exist, ask user to confirm
          if (fuzzyMatch && fuzzyMatch.score < 70 && fuzzyMatch.matchType !== 'exact' && fuzzyMatch.matchType !== 'directory') {
            const candidates = [fuzzyMatch, ...fuzzyAlternatives];
            if (candidates.length > 1 || fuzzyMatch.score < 55) {
              DebugLog.executorExit(taskId, 'app_launch', false, 'fuzzy_confirm_needed');
              return {
                success: false,
                requiresFuzzyConfirmation: true,
                query: target,
                candidates: candidates.map(c => ({ appName: c.appName, packageName: c.packageName, score: c.score })),
                summary: candidates.length > 1
                  ? `Found ${candidates.length} possible matches for "${target}": ${candidates.map(c => c.appName).join(', ')}. Which one did you mean?`
                  : `Found "${fuzzyMatch.appName}" — did you mean to open that?`,
              };
            }
          }
        }

        // Step 3: AI fallback only if both directory and device query found nothing
        if (!pkg) {
          if (weatherLikeTarget) {
            // No weather app found on device — redirect to built-in weather capability
            DebugLog.systemEvent('TaskExecutor', `Weather target "${target}" unresolved locally; redirecting to weather capability`);
            return this.execWithParams('weather', params, request, taskId);
          } else if (!this.ai.hasApiKey()) {
            DebugLog.appLaunchFail(taskId, target, undefined, 'No API key configured', 'ai_fallback');
            return { success: false, error: `Could not find "${target}" on this device. Configure an API key to enable AI-assisted app lookup.` };
          }
          const _aiPrompt = `What is the exact Android package name for the app "${target}"? Reply with ONLY the package name, nothing else. If you're not sure, reply "unknown". IMPORTANT: Never suggest com.android.weather — it does not exist. For weather apps use com.google.android.apps.weather, com.accuweather.android, or com.weather.Weather.`;
          if (!weatherLikeTarget) {
            const _aiStart = Date.now();
            const r = await this.ai.complete(
              _aiPrompt,
              { taskId, agentId: 'launch', maxTokens: 100, temperature: 0.1 }
            );
            const aiPkg = r.content.trim().replace(/[^a-zA-Z0-9._]/g, '');
            if (aiPkg && aiPkg.includes('.') && aiPkg !== 'unknown') {
              // Validate AI-suggested package is actually installed before trusting it
              try {
                const AgentNativeModuleValidate = (await import('../native/AgentNative')).default;
                const allApps = await AgentNativeModuleValidate.getInstalledApps();
                const aiPkgExists = allApps.some((a: { packageName: string }) => a.packageName === aiPkg);
                if (aiPkgExists) {
                  pkg = aiPkg;
                } else {
                  this.logger.warn(`AI suggested "${aiPkg}" but it is not installed on this device`);
                  // Try fuzzy matching the AI's suggestion as an app name against installed list
                  const aiNameMatch = findBestMatch(aiPkg.split('.').pop() || '', allApps, 60);
                  if (aiNameMatch) {
                    pkg = aiNameMatch.packageName;
                    this.logger.info(`AI fallback fuzzy recovered: "${aiPkg}" → "${aiNameMatch.appName}" (${aiNameMatch.packageName})`);
                  }
                }
              } catch (validateErr: any) {
                // If validation fails, still use AI suggestion as last resort
                pkg = aiPkg;
                this.logger.warn(`Could not validate AI package suggestion: ${validateErr.message}`);
              }
            }
            DebugLog.appLaunchAiFallback(taskId, target, _aiPrompt, pkg || 'NONE', Date.now() - _aiStart);
          }
        }

        if (!pkg || !pkg.includes('.')) {
          DebugLog.appLaunchFail(taskId, target, pkg, 'No valid package found', 'no_package');
          try {
            const { AppFallback } = await import('./AppFallback');
            const fallbackResult = AppFallback.resolve(target);
            if (fallbackResult && fallbackResult.type !== 'none') return { success: false, ...fallbackResult };
          } catch {}
          return { success: false, error: `Could not find "${target}" on this device` };
        }

        const launchResult = { success: true, launched: target, packageName: pkg };
        DebugLog.appLaunchFire(taskId, pkg, target);
        try {
          // Use native launchApp which verifies package exists via getLaunchIntentForPackage
          const AgentNativeModuleLaunch = (await import('../native/AgentNative')).default;
          const nativeLaunchResult = await AgentNativeModuleLaunch.launchApp(pkg);
          if (!nativeLaunchResult.success) {
            // Native verification failed — package does not exist or is not launchable
            DebugLog.appLaunchFail(taskId, target, pkg, nativeLaunchResult.error || 'Not launchable', 'intent_launch');
            DebugLog.executorExit(taskId, 'app_launch', false, 'native_launch_not_found');
            try {
              const { AppFallback } = await import('./AppFallback');
              const fallbackResult = AppFallback.resolve(target);
              if (fallbackResult && fallbackResult.type !== 'none') return { success: false, ...fallbackResult };
            } catch {}
            return { success: false, error: `"${target}" is not installed on this device. (tried package: ${pkg})` };
          }
        } catch (err: any) {
          // Fallback to IntentLauncher.openApplication if native launchApp is unavailable
          try {
            await IntentLauncher.openApplication(pkg);
          } catch (fallbackErr: any) {
            DebugLog.appLaunchFail(taskId, target, pkg, fallbackErr.message, 'intent_launch');
            DebugLog.executorExit(taskId, 'app_launch', false, 'intent_launch_error');
            return { success: false, error: `Failed to launch ${target} (${pkg}): ${fallbackErr.message}` };
          }
        }

        // Permanently learn the mapping so future launches are instant (skip if already from alias)
        if (!resolvedViaAlias && this.learner && pkg && pkg.includes('.')) {
          const suppressGenericAlias = weatherLikeTarget && GENERIC_SEARCH_PACKAGES.has(pkg);
          if (!suppressGenericAlias) {
            try {
              await this.learner.learnAppAlias(targetLower, pkg);
              this.logger.info(`Learned alias permanently: "${targetLower}" → ${pkg}`);
            } catch (e: any) {
              this.logger.warn(`Failed to persist alias: ${e.message}`);
            }
          } else {
            DebugLog.systemEvent('TaskExecutor', `Skipped generic alias learn: "${targetLower}" → ${pkg}`);
          }
        }

        await logUiSnapshot(taskId, 'app_launch');
        DebugLog.executorExit(taskId, 'app_launch', true, 'simple_launch_success');
        return launchResult;
      }
      case 'app_share': {
        const shareContent = params.content || params.message || params.url;
        if (shareContent) {
          const isFilePath = shareContent.startsWith?.('file://') || shareContent.startsWith?.('/');
          if (isFilePath) {
            const shareAvail = await Sharing.isAvailableAsync();
            if (!shareAvail) return { error: 'Sharing is not available on this device' };
            const contentUri = await toAndroidContentUri(shareContent);
            await Sharing.shareAsync(contentUri);
            return { success: true, shared: contentUri };
          }
          try {
            const shareResult = await Share.share({ message: shareContent });
            return { success: true, action: shareResult.action };
          } catch (e: any) {
            return { error: 'Share failed: ' + e.message };
          }
        }
        try {
          const shareResult = await Share.share({ message: 'Shared from Agent Ultra' });
          return { success: true, action: shareResult.action };
        } catch (e: any) {
          return { error: 'Share failed: ' + e.message };
        }
      }
      case 'code_generate': {
        const desc = params.description || request;
        const lang = params.language || 'Java';
        const r = await this.ai.complete(`Generate complete production ${lang} code for: "${desc}". All imports, error handling.`, { taskId, agentId: 'codegen', maxTokens: 8000, temperature: 0.5 });
        if (isNative) {
          const ext = lang.toLowerCase() === 'python' ? 'py' : lang.toLowerCase() === 'javascript' ? 'js' : 'java';
          const fn = `generated_${Date.now()}.${ext}`;
          const filePath = this.docDir + 'projects/' + fn;
          await FileSystem.writeAsStringAsync(filePath, r.content);
          return { success: true, path: filePath, lines: r.content.split('\n').length, cost: r.cost };
        }
        return { success: true, lines: r.content.split('\n').length, cost: r.cost, note: 'File save requires Android device' };
      }
      case 'app_build':
        return this.build.buildApp(params.description || request, taskId);
      case 'app_install': {
        if (!isNative) return { error: 'APK install requires Android device' };
        if (params.apkPath) {
          await this.build.installApk(params.apkPath);
          return { success: true, installing: params.apkPath };
        }
        const files = await FileSystem.readDirectoryAsync(this.docDir + 'projects/');
        const apks = files.filter((f: string) => f.endsWith('-signed.apk'));
        if (apks.length === 0) return { error: 'No APK found. Build first.' };
        await this.build.installApk(this.docDir + 'projects/' + apks[apks.length - 1]);
        return { success: true, installing: apks[apks.length - 1] };
      }
      case 'network_request': {
        const url = params.url;
        if (!url) return { error: 'No URL specified' };
        const method = params.method || 'GET';
        const body = params.body;
        const fetchOpts: any = { method };
        if (body) fetchOpts.body = body;
        const resp = await fetch(url, fetchOpts);
        const text = await resp.text();
        return { success: true, status: resp.status, length: text.length, body: text.substring(0, 1000) };
      }
      case 'ai_query': {
        const query = params.query || request;
        const r = await this.ai.complete(query, { taskId, agentId: 'query' });
        return { success: true, response: r.content, cost: r.cost };
      }
      case 'dependency_resolve': {
        if (!isNative) return { error: 'Dependency resolution requires Android device' };
        const coords = params.coordinates;
        if (!coords || !Array.isArray(coords)) return { error: 'coordinates array is required' };
        const paths = await this.maven.resolveAll(coords);
        return { success: true, resolved: paths.length, total: coords.length, paths };
      }
      case 'app_test': {
        const desc = params.description || request;
        const spec = params.spec || this.build.getLastBuiltSpec();
        if (!spec) {
          return { error: 'No app spec available. Build an app first, then test it.' };
        }
        const testPlan = await this.testRunner.generateTestPlan(desc, spec);
        const result = await this.testRunner.executeTestPlan(testPlan);
        return { success: result.passed, summary: result.summary, steps: result.steps };
      }
      case 'self_modify': {
        DebugLog.executorEnter(taskId, 'self_modify');
        const genome = await this.loadOrCreateGenome();
        DebugLog.executorBranch(taskId, 'self_modify', 'genome_loaded', { generation: genome.generation, id: genome.id });
        const improver = this.createSelfImprover();
        const goal = params.goal;
        const maxCycles = params.maxCycles || 3;
        const customChallenges = params.challenges;
        const result = await improver.evolve(genome, maxCycles, goal, (phase, msg) => {
          DebugLog.executorBranch(taskId, 'self_modify', `evolve_${phase}`, { message: msg });
          this.onGenomeProgress?.(phase, msg);
        }, customChallenges);
        this.currentGenome = result.genome;
        await this.persistGenome(result.genome);
        DebugLog.executorBranch(taskId, 'self_modify', 'genome_persisted', { generation: result.genome.generation, improved: result.totalImprovements > 0 });

        const taskPerf = result.genome.fitness?.taskPerformance;
        const taskSummary = taskPerf
          ? `Task performance: ${taskPerf.challengesPassed}/${taskPerf.challengesTotal} challenges passed (weighted: ${(taskPerf.weightedScore * 100).toFixed(1)}%)${taskPerf.failedChallenges.length > 0 ? `. Failed: ${taskPerf.failedChallenges.join(', ')}` : ''}`
          : 'No task evaluation performed';

        const lineage = improver.getLineage();
        const best = lineage.getBestGenome();
        const failures = lineage.getAllNodes();

        DebugLog.executorExit(taskId, 'self_modify', result.totalImprovements > 0, `cycles=${result.totalCycles}_improvements=${result.totalImprovements}`);
        return {
          success: true,
          type: 'evolution',
          totalCycles: result.totalCycles,
          totalImprovements: result.totalImprovements,
          generation: result.genome.generation,
          fitness: result.genome.fitness?.overallScore ?? null,
          taskSummary,
          bestGeneration: best ? { generation: best.generation, score: best.fitness?.overallScore ?? 0 } : null,
          persistentFailures: failures.filter((f: any) => f.failureRate > 0.5).map((f: any) => f.challengeId),
          report: result.report,
        };
      }
      case 'self_replicate': {
        DebugLog.executorEnter(taskId, 'self_replicate');
        const genome = await this.loadOrCreateGenome();
        DebugLog.executorBranch(taskId, 'self_replicate', 'genome_loaded', { generation: genome.generation, id: genome.id, packageName: genome.identity.packageName });
        const aiClient = this.createAiClient(180000);
        const getModel = async () => this.ai.getDefaultModel();
        const compiler = new GenomeCompiler(aiClient, getModel);
        const improver = this.createSelfImprover();
        DebugLog.executorBranch(taskId, 'self_replicate', 'compiling_genome');
        this.onGenomeProgress?.('compiling', 'Compiling genome for offspring...');
        const buildOutput = await compiler.compile(genome);
        DebugLog.executorBranch(taskId, 'self_replicate', 'genome_compiled', { sourceFiles: buildOutput.sourceFiles.length, dependencies: buildOutput.dependencies.length });
        this.onGenomeProgress?.('building', 'Building offspring APK...');
        DebugLog.executorBranch(taskId, 'self_replicate', 'building_apk');
        const spec = improver.genomeBuildToAppSpec(genome, buildOutput);
        const buildResult = await this.build.buildFromSpec(spec, (progress) => {
          DebugLog.executorBranch(taskId, 'self_replicate', `build_${progress.phase}`, { message: progress.message });
          this.onGenomeProgress?.(progress.phase, progress.message);
        });
        if (!buildResult.success) {
          DebugLog.executorExit(taskId, 'self_replicate', false, `build_failed: ${buildResult.error}`);
          return { error: `Offspring build failed: ${buildResult.error}` };
        }
        DebugLog.executorExit(taskId, 'self_replicate', true, `offspring_gen${genome.generation + 1}_built`);
        return {
          success: true,
          type: 'replication',
          offspringGeneration: genome.generation + 1,
          parentId: genome.id,
          apkPath: buildResult.apkPath,
          packageName: genome.identity.packageName,
        };
      }
      case 'device_location': {
        if (Platform.OS === 'web') {
          try {
            const pos = await new Promise<GeolocationPosition>((resolve, reject) => {
              navigator.geolocation.getCurrentPosition(resolve, reject, { timeout: 15000 });
            });
            return { success: true, latitude: pos.coords.latitude, longitude: pos.coords.longitude, accuracy: pos.coords.accuracy };
          } catch (e: any) {
            return { error: 'Location unavailable on web: ' + e.message };
          }
        }
        const { coords } = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Balanced });
        let cityName: string | null = null;
        try {
          const [addr] = await Location.reverseGeocodeAsync({ latitude: coords.latitude, longitude: coords.longitude });
          cityName = addr?.city || addr?.subregion || addr?.region || null;
        } catch {}
        return {
          success: true,
          latitude: coords.latitude,
          longitude: coords.longitude,
          accuracy: coords.accuracy,
          altitude: coords.altitude,
          city: cityName,
          locationSummary: cityName
            ? `${cityName} (${coords.latitude.toFixed(4)}, ${coords.longitude.toFixed(4)})`
            : `${coords.latitude.toFixed(4)}, ${coords.longitude.toFixed(4)}`,
        };
      }
      case 'app_control': {
        if (!isNative || !AppController.isAvailable()) return { error: 'App control requires Android device with accessibility service enabled' };
        const enabled = await AppController.isServiceEnabled();
        if (!enabled) {
          await AppController.openAccessibilitySettings();
          return { error: 'Accessibility service not enabled. Opening settings — please enable Agent Ultra accessibility service.' };
        }
        const targetPackage = params.targetPackage;
        const action = params.action;
        if (!targetPackage || !action) return { error: 'targetPackage and action are required' };
        await AppController.allowPackage(targetPackage);
        const activePackage = await AppController.getActivePackage();
        if (activePackage !== targetPackage) return { error: `Target app ${targetPackage} is not in foreground. Current: ${activePackage}` };
        switch (action) {
          case 'read': {
            const tree = await AppController.getScreenContent();
            return { success: true, screenContent: tree };
          }
          case 'click': {
            if (!params.selector) return { error: 'selector is required for click action' };
            const clicked = await AppController.performClick(params.selector);
            return { success: clicked, action: 'click', selector: params.selector };
          }
          case 'scroll': {
            const dir = params.selector as 'up' | 'down' | 'left' | 'right' || 'down';
            const scrolled = await AppController.performScroll(dir);
            return { success: scrolled, action: 'scroll', direction: dir };
          }
          case 'type': {
            if (!params.selector || !params.text) return { error: 'selector and text are required for type action' };
            const typed = await AppController.performText(params.selector, params.text);
            return { success: typed, action: 'type', selector: params.selector };
          }
          case 'back': {
            const backed = await AppController.performBack();
            return { success: backed, action: 'back' };
          }
          case 'home': {
            const homed = await AppController.performHome();
            return { success: homed, action: 'home' };
          }
          default:
            return { error: `Unknown app_control action: ${action}` };
        }
      }
      case 'react_navigate': {
        console.warn('[TASK] entering: react_navigate');
        DebugLog.executorEnter(taskId, 'react_navigate');
        const goal = params.goal || params.target || '';
        const appHint = params.appHint || params.packageName || '';
        if (!goal) {
          return { success: false, summary: 'No navigation goal specified' };
        }
        // Block navigation to accessibility or settings screens that could disable the service
        const dangerousTarget = /\b(accessibility|quick settings|notification settings)\b/i;
        if (dangerousTarget.test(goal) || dangerousTarget.test(appHint)) {
          console.warn(`[TASK] react_navigate: BLOCKED dangerous target goal="${goal}" appHint="${appHint}"`);
          return { success: false, summary: 'Cannot navigate to accessibility or system settings — use the dedicated toggle tools instead.' };
        }
        if (!isNative || !AppController.isAvailable()) {
          return { success: false, summary: 'UI navigation requires Android with accessibility service enabled' };
        }
        const serviceEnabled = await AppController.isServiceEnabled().catch(() => false);
        if (!serviceEnabled) {
          await AppController.openAccessibilitySettings();
          return { success: false, summary: 'Enable Agent Ultra in Accessibility Settings first' };
        }

        if (appHint) {
          const launchTarget = appHint.toLowerCase().trim();
          try {
            const AgentNativeModuleNav = (await import('../native/AgentNative')).default;
            const installed = await AgentNativeModuleNav.getInstalledApps();
            const match = findBestMatch(launchTarget, installed, 55);
            if (match && (match.matchType === 'exact' || match.matchType === 'directory' || match.score >= 55)) {
              console.warn('[TASK] react_navigate: launching', match.appName, match.packageName, 'score=', match.score);
              const launchResult = await AgentNativeModuleNav.launchApp(match.packageName);
              console.warn('[TASK] launch_result:', launchResult.success);
            } else {
              if (match) {
                console.warn('[TASK] react_navigate: rejected low-confidence match:', match.appName, 'score=', match.score);
              }
              const knownPkg = lookupPackage(launchTarget);
              if (knownPkg) {
                const launchResult = await AgentNativeModuleNav.launchApp(knownPkg);
                console.warn('[TASK] launch_result:', launchResult.success);
              } else if (/^https?:\/\/|[\w-]+\.(com|org|net|io|co|app|dev|ai|gov|edu)(\/|$)/i.test(launchTarget)) {
                // URL-like appHint — only open if it's a well-known domain, not an LLM hallucination
                const KNOWN_DOMAINS = new Set([
                  'google.com', 'youtube.com', 'reddit.com', 'twitter.com', 'x.com',
                  'instagram.com', 'facebook.com', 'amazon.com', 'netflix.com', 'spotify.com',
                  'tiktok.com', 'pinterest.com', 'linkedin.com', 'github.com', 'stackoverflow.com',
                  'wikipedia.org', 'yahoo.com', 'bing.com', 'twitch.tv', 'discord.com',
                  'slack.com', 'whatsapp.com', 'telegram.org', 'ebay.com', 'walmart.com',
                  'target.com', 'bestbuy.com', 'craigslist.org', 'yelp.com', 'imdb.com',
                ]);
                const domainMatch = launchTarget.replace(/^https?:\/\//, '').replace(/^www\./, '').match(/^([\w.-]+)/);
                const domain = domainMatch ? domainMatch[1].toLowerCase() : '';
                if (KNOWN_DOMAINS.has(domain)) {
                  const url = /^https?:\/\//i.test(launchTarget) ? launchTarget : `https://${launchTarget}`;
                  DebugLog.systemEvent('ReActNav', `Known URL appHint "${launchTarget}" — opening via ACTION_VIEW`);
                  try {
                    await IntentLauncher.startActivityAsync('android.intent.action.VIEW', { data: url });
                  } catch (urlErr: any) {
                    this.logger.warn(`react_navigate URL open failed: ${urlErr.message}`);
                  }
                } else {
                  // Likely hallucinated URL — open browser instead and let ReActLoop search
                  console.warn(`[TASK] react_navigate: rejecting unverified URL "${launchTarget}" — opening browser`);
                  DebugLog.systemEvent('ReActNav', `Rejected unverified URL "${launchTarget}" — falling back to browser`);
                  const browserPkg = lookupPackage('chrome') || 'com.android.chrome';
                  try {
                    await AgentNativeModuleNav.launchApp(browserPkg);
                  } catch {
                    // Browser launch failed — ReActLoop will handle whatever is on screen
                  }
                }
              } else {
                console.warn('[TASK] react_navigate: no match for appHint:', launchTarget);
                DebugLog.systemEvent('ReActNav', `No app match for "${launchTarget}" — skipping launch`);
              }
            }
          } catch (launchErr: any) {
            this.logger.warn(`react_navigate app launch failed: ${launchErr.message}`);
          }
        }

        // Delegate ReActLoop to HeadlessJS task — block until it completes
        // so BrainExecutor's tool loop doesn't issue the next tool prematurely
        const HEADLESS_TIMEOUT_MS = 300000; // 5 minutes, matches HeadlessJS config
        try {
          const AgentNativeModuleHeadless = (await import('../native/AgentNative')).default;
          const { DeviceEventEmitter } = require('react-native');

          const headlessResult = await new Promise<{
            success: boolean; steps: number; goal: string;
            finalObservation?: string; error?: string;
          }>((resolve) => {
            let settled = false;
            const timer = setTimeout(() => {
              if (!settled) {
                settled = true;
                sub.remove();
                console.warn('[TASK] react_navigate: HeadlessJS timed out after', HEADLESS_TIMEOUT_MS, 'ms');
                resolve({ success: false, steps: 0, goal, error: 'headless_timeout' });
              }
            }, HEADLESS_TIMEOUT_MS);

            const sub = DeviceEventEmitter.addListener(
              'headlessReActComplete',
              (data: any) => {
                if (data?.taskId === taskId && !settled) {
                  settled = true;
                  clearTimeout(timer);
                  sub.remove();
                  console.warn('[TASK] react_navigate: HeadlessJS completed', data);
                  resolve(data);
                }
              },
            );

            // Start the HeadlessJS task (which also calls moveTaskToBack natively)
            AgentNativeModuleHeadless.startReActTask(goal, appHint || '', taskId).catch((err: any) => {
              if (!settled) {
                settled = true;
                clearTimeout(timer);
                sub.remove();
                console.warn('[TASK] react_navigate: startReActTask failed:', err.message);
                resolve({ success: false, steps: 0, goal, error: err.message });
              }
            });
          });

          DebugLog.executorExit(taskId, 'react_navigate', headlessResult.success, `steps=${headlessResult.steps} headless=true`);
          const screenInfo = (headlessResult as any).screenContent ? `\nScreen now shows: ${(headlessResult as any).screenContent}` : '';
          return {
            success: headlessResult.success,
            summary: headlessResult.success
              ? `Completed: ${goal} in ${headlessResult.steps} steps.${screenInfo}`
              : `Could not complete: ${goal}${headlessResult.error ? ' (' + headlessResult.error + ')' : ''} after ${headlessResult.steps} steps.${screenInfo}`,
            data: {
              steps: headlessResult.steps,
              goalAchieved: headlessResult.success,
              finalObservation: headlessResult.finalObservation?.slice(0, 300) || '',
              screenContent: (headlessResult as any).screenContent || '',
              headless: true,
            },
          };
        } catch (headlessErr: any) {
          console.warn('[TASK] react_navigate: HeadlessJS failed:', headlessErr.message);
          DebugLog.error('ReActNav', `HeadlessJS failed: ${headlessErr.message}`, headlessErr.stack);
          DebugLog.executorExit(taskId, 'react_navigate', false, 'headless_error');
          return {
            success: false,
            summary: `Navigation failed: ${headlessErr.message}`,
            data: { headless: true, error: headlessErr.message },
          };
        }
      }

      case 'multi_step': {
        const rawSteps = Array.isArray(params.steps) ? params.steps : [];
        if (rawSteps.length === 0) return { success: false, summary: 'No steps provided for multi-step task.' };
        const { CommandParser } = await import('./CommandParser');
        const { AIIntentParser } = await import('./AIIntentParser');
        const parser = new CommandParser();
        const aiIntent = new AIIntentParser(this.ai, this.caps);
        const summaries: string[] = [];
        let allSucceeded = true;

        for (let idx = 0; idx < rawSteps.length; idx++) {
          const rawStep = rawSteps[idx];
          let stepPlan: ActionPlan | null = null;
          let stepLabel = '';

          if (typeof rawStep === 'string') {
            stepLabel = rawStep.trim();
            stepPlan = parser.parse(stepLabel);
            if (!stepPlan && this.ai.hasApiKey()) {
              try { stepPlan = await aiIntent.parse(stepLabel); } catch {}
            }
          } else if (rawStep && typeof rawStep === 'object') {
            const candidate = rawStep as Record<string, any>;
            if (typeof candidate.capability === 'string') {
              stepPlan = {
                capability: candidate.capability,
                params: (candidate.params && typeof candidate.params === 'object') ? candidate.params : {},
                reason: candidate.reason || 'Nested multi-step capability',
              };
              stepLabel = candidate.reason || candidate.capability;
            } else if (typeof candidate.input === 'string' || typeof candidate.request === 'string' || typeof candidate.query === 'string') {
              stepLabel = String(candidate.input || candidate.request || candidate.query).trim();
              stepPlan = parser.parse(stepLabel);
              if (!stepPlan && this.ai.hasApiKey()) {
                try { stepPlan = await aiIntent.parse(stepLabel); } catch {}
              }
            }
          }

          const stopOnFail = params.stopOnFirstFailure === true;
          const skipRemaining = (fromIdx: number, reason: string) => {
            for (let j = fromIdx; j < rawSteps.length; j++) {
              summaries.push(`⊘ step ${j + 1}: skipped (${reason})`);
            }
          };

          if (!stepPlan) {
            summaries.push(`✗ Step ${idx + 1}: could not parse`);
            allSucceeded = false;
            if (stopOnFail) { skipRemaining(idx + 1, 'prior step failed'); break; }
            continue;
          }
          if (stepPlan.capability === 'multi_step') {
            summaries.push(`✗ Step ${idx + 1}: nested multi_step is not allowed`);
            allSucceeded = false;
            if (stopOnFail) { skipRemaining(idx + 1, 'prior step failed'); break; }
            continue;
          }

          const STEP_TIMEOUT_MS = 20000;
          const stepWatchdogId = `${taskId}_ms${idx + 1}`;
          DebugLog.watchdogArm(stepWatchdogId, 'multistep_exec', STEP_TIMEOUT_MS);
          const timeoutResult = new Promise<{ success: boolean; summary: string }>((resolve) =>
            setTimeout(
              () => resolve({ success: false, summary: `${stepLabel || stepPlan!.capability}: timed out after ${STEP_TIMEOUT_MS / 1000}s` }),
              STEP_TIMEOUT_MS
            )
          );
          let stepResult: Awaited<ReturnType<typeof this.runWithPlan>>;
          try {
            stepResult = await Promise.race([
              this.runWithPlan(stepPlan, stepWatchdogId),
              timeoutResult,
            ]) as Awaited<ReturnType<typeof this.runWithPlan>>;
          } finally {
            DebugLog.watchdogDisarm(stepWatchdogId, 'multistep_exec');
          }

          // ── Mid-chain disambiguation detection ──
          // If this step needs the user to resolve a contact or app name, pause the chain
          // and store the remaining steps so AgentCore can resume after the user answers.
          const stepData = stepResult.data as Record<string, any> | undefined;
          const needsDisambig = stepData?.requiresDisambiguation || stepData?.requiresFuzzyConfirmation;
          if (needsDisambig) {
            const remainingSteps = rawSteps.slice(idx + 1).map((s: any) => {
              if (typeof s === 'string') return { capability: '__raw__', params: { __raw__: s }, reason: s };
              return { capability: s.capability || '__raw__', params: s.params || {}, reason: s.reason || '' };
            });
            DebugLog.systemEvent('MultiStep', `Disambiguation at step ${idx + 1}/${rawSteps.length} — pausing chain (${remainingSteps.length} steps remaining)`);
            return {
              success: false,
              summary: stepResult.summary || 'Disambiguation required',
              data: {
                requiresDisambiguation: true,
                summary: stepResult.summary,
                pendingChain: {
                  steps: remainingSteps,
                  idx,
                  collectedSummaries: [...summaries],
                  disambigCapability: stepPlan.capability,
                  disambigParams: stepPlan.params as Record<string, unknown>,
                  createdAt: Date.now(),
                },
                ...(stepData || {}),
              },
            };
          }

          const stepTag = stepResult.success ? '✓' : '✗';
          const stepSummary = stepResult.summary || `${stepLabel || stepPlan.capability}: ${stepResult.success ? 'ok' : 'failed'}`;
          summaries.push(`${stepTag} ${stepSummary}`);
          if (!stepResult.success) {
            allSucceeded = false;
            if (stopOnFail) { skipRemaining(idx + 1, 'prior step failed'); break; }
          }
        }

        return {
          success: allSucceeded,
          summary: summaries.join(' → '),
          stepsRun: rawSteps.length,
        };
      }

      case 'event_trigger_set': {
        const monitor = this.getEventMonitor();
        if (!monitor) {
          return { success: false, summary: 'Event monitor is not running yet.' };
        }
        const condition = typeof params.condition === 'string' ? params.condition.trim() : '';
        const action = typeof params.action === 'string' ? params.action.trim() : '';
        if (!condition || !action) {
          return { success: false, summary: 'Trigger requires both a condition and an action.' };
        }
        const triggerType = typeof params.type === 'string' && params.type.trim()
          ? params.type.trim()
          : this.inferTriggerType(condition);
        const triggerId = await monitor.addTrigger({
          type: triggerType as any,
          condition,
          action,
          enabled: true,
        });
        const triggers = monitor.getTriggers();
        DebugLog.systemEvent('event_trigger_set', `Trigger stored: ${triggerId} type=${triggerType} condition="${condition.slice(0, 80)}"`);
        return {
          success: true,
          summary: `Trigger set: when ${condition}, will ${action}. ID: ${triggerId}`,
          data: { id: triggerId, type: triggerType, totalTriggers: triggers.length },
        };
      }

      case 'event_trigger_list': {
        const monitor = this.getEventMonitor();
        if (!monitor) {
          return { success: false, summary: 'Event monitor is not running yet.' };
        }
        const triggers = monitor.getTriggers();
        return {
          success: true,
          summary: this.summarizeTriggers(triggers),
          data: { triggers, count: triggers.length },
        };
      }

      case 'event_trigger_remove': {
        const monitor = this.getEventMonitor();
        if (!monitor) {
          return { success: false, summary: 'Event monitor is not running yet.' };
        }
        const id = typeof params.id === 'string' ? params.id.trim() : '';
        if (!id) {
          return { success: false, summary: 'Which trigger ID should I remove?' };
        }
        const existing = monitor.getTriggers().find((trigger) => trigger.id === id);
        if (!existing) {
          return { success: false, summary: `No active trigger found with ID ${id}` };
        }
        await monitor.removeTrigger(id);
        return {
          success: true,
          summary: `Removed trigger ${id}`,
          data: { id },
        };
      }

      case 'memory_recall': {
        const query = params.query || '';
        try {
          const core = (await import('./AgentCore')).getAgentCoreInstance();
          const graph = core?.getCortex()?.getKnowledgeGraph();
          if (graph) {
            const contextStr = graph.getContextFor(query);
            if (contextStr && contextStr.length > 10) {
              return { success: true, summary: contextStr, data: { query, source: 'knowledge_graph' } };
            }
          }
          // Check vault for user profile info
          const vault = core?.getVault?.();
          if (vault) {
            const keys = ['user_preferred_name', 'user_email', 'user_phone', 'user_address'];
            const matches: string[] = [];
            for (const key of keys) {
              try {
                const val = await vault.get(key);
                if (val && val.toLowerCase().includes(query.toLowerCase())) {
                  matches.push(`${key.replace('user_', '')}: ${val}`);
                }
              } catch {}
            }
            if (matches.length > 0) {
              return { success: true, summary: matches.join(', '), data: { query, source: 'vault' } };
            }
          }
        } catch {}
        return { success: true, summary: `I don't have specific memories about "${query}" yet.`, data: { query } };
      }

      case 'flashlight_toggle': {
        const AgentNativeModule = (await import('../native/AgentNative')).default;
        // Determine desired state from param, or infer from user request
        let stateParam = params.state?.toLowerCase();
        if (!stateParam) {
          const reqLower = request.toLowerCase();
          if (/\b(off|disable|stop|kill)\b/.test(reqLower)) stateParam = 'off';
          else if (/\b(on|enable|start|shine)\b/.test(reqLower)) stateParam = 'on';
        }
        if (stateParam === 'off') {
          await AgentNativeModule.setFlashlight(false);
          this._flashlightOn = false;
          return { success: true, summary: 'Flashlight turned off' };
        } else if (stateParam === 'on') {
          await AgentNativeModule.setFlashlight(true);
          this._flashlightOn = true;
          return { success: true, summary: 'Flashlight turned on' };
        } else {
          const newState = !this._flashlightOn;
          await AgentNativeModule.setFlashlight(newState);
          this._flashlightOn = newState;
          return { success: true, summary: `Flashlight turned ${newState ? 'on' : 'off'}` };
        }
      }
      case 'alarm_set': {
        const timeStr = params.time || '';
        const parsed = parseTimeString(timeStr);
        await IntentLauncher.startActivityAsync('android.intent.action.SET_ALARM', {
          extra: {
            'android.intent.extra.alarm.HOUR': parsed.hour,
            'android.intent.extra.alarm.MINUTES': parsed.minute,
            'android.intent.extra.alarm.MESSAGE': params.label || 'Ultra alarm',
            'android.intent.extra.alarm.SKIP_UI': false,
          },
        });
        return { success: true, summary: `Alarm set for ${parsed.display}` };
      }
      case 'timer_set': {
        const seconds = parseDurationToSeconds(params.duration || '');
        await IntentLauncher.startActivityAsync('android.intent.action.SET_TIMER', {
          extra: {
            'android.intent.extra.alarm.LENGTH': seconds,
            'android.intent.extra.alarm.MESSAGE': 'Ultra timer',
            'android.intent.extra.alarm.SKIP_UI': true,
          },
        });
        return { success: true, summary: `Timer set for ${params.duration}` };
      }
      case 'volume_set': {
        const { DeviceDiagnostics: DD } = await import('../services/DeviceDiagnostics');
        await DD.logAudioBeforeAfter('before_volume');
        let volumeResult: { success: boolean; summary: string };
        try {
          // Direction-based stepping uses adjustVolume (ADJUST_RAISE/LOWER)
          if (params.direction === 'up' || params.direction === 'down') {
            const actualPct = await AppController.adjustVolume(params.direction);
            volumeResult = { success: true, summary: `Media volume ${params.direction === 'up' ? 'raised' : 'lowered'} to ${actualPct}%` };
          } else {
            // Absolute level set
            const level = typeof params.level === 'number' ? params.level : parseInt(String(params.level ?? params.percent ?? params.value ?? 50), 10);
            const stream = (params.stream || params.type || 'media').toLowerCase().replace('music', 'media');
            const streamType = stream === 'ring' || stream === 'ringer' ? 'ring'
              : stream === 'alarm' ? 'alarm'
              : stream === 'notification' ? 'notification'
              : 'media';
            const actualPct = await AppController.setVolume(streamType, isNaN(level) ? 50 : Math.max(0, Math.min(100, level)));
            volumeResult = { success: true, summary: `${streamType.charAt(0).toUpperCase() + streamType.slice(1)} volume set to ${actualPct}%` };
          }
        } catch (volErr: any) {
          this.logger.warn(`Native volume failed: ${volErr.message} — opening settings`);
          await IntentLauncher.startActivityAsync('android.settings.SOUND_SETTINGS', {});
          volumeResult = { success: true, summary: 'Opened sound settings — adjust volume there' };
        }
        await DD.logAudioBeforeAfter('after_volume');
        return volumeResult;
      }
      case 'brightness_set': {
        await IntentLauncher.startActivityAsync('android.settings.DISPLAY_SETTINGS', {});
        return { success: true, summary: 'Opened display settings for brightness' };
      }
      case 'wifi_toggle': {
        // P8: Try direct API first (WRITE_SECURE_SETTINGS), fallback to QS automation
        try {
          const current = await AppController.getSecureSetting('global', 'wifi_on');
          if (current >= 0) {
            const newState = current === 0 ? 1 : 0;
            await AppController.setSecureSetting('global', 'wifi_on', newState);
            console.warn(`[TASK] wifi_toggle: direct API ${current} → ${newState}`);
            return { success: true, summary: `Done — Wi-Fi ${newState ? 'enabled' : 'disabled'}.` };
          }
        } catch (e: any) {
          console.warn(`[TASK] wifi_toggle: direct API failed (${e.message}), falling back to QS`);
        }
        const wifiDelta = await captureStateDelta(taskId, 'wifi_toggle', () => AppController.toggleQuickSetting('Wi-Fi'));
        await logUiSnapshot(taskId, 'wifi_toggle');
        if (wifiDelta.toggled) return { success: true, summary: 'Done — Wi-Fi toggled.', data: { delta: wifiDelta.changed } };
        await IntentLauncher.startActivityAsync('android.settings.WIFI_SETTINGS', {});
        return { success: true, summary: 'Couldn\'t toggle directly — opened Wi-Fi settings for you.', data: { partial: true } };
      }
      case 'bluetooth_toggle': {
        try {
          const current = await AppController.getSecureSetting('global', 'bluetooth_on');
          if (current >= 0) {
            const newState = current === 0 ? 1 : 0;
            await AppController.setSecureSetting('global', 'bluetooth_on', newState);
            console.warn(`[TASK] bluetooth_toggle: direct API ${current} → ${newState}`);
            return { success: true, summary: `Done — Bluetooth ${newState ? 'enabled' : 'disabled'}.` };
          }
        } catch (e: any) {
          console.warn(`[TASK] bluetooth_toggle: direct API failed (${e.message}), falling back to QS`);
        }
        const btDelta = await captureStateDelta(taskId, 'bluetooth_toggle', () => AppController.toggleQuickSetting('Bluetooth'));
        await logUiSnapshot(taskId, 'bluetooth_toggle');
        if (btDelta.toggled) return { success: true, summary: 'Done — Bluetooth toggled.', data: { delta: btDelta.changed } };
        await IntentLauncher.startActivityAsync('android.settings.BLUETOOTH_SETTINGS', {});
        return { success: true, summary: 'Couldn\'t toggle directly — opened Bluetooth settings for you.', data: { partial: true } };
      }
      case 'airplane_mode': {
        try {
          const current = await AppController.getSecureSetting('global', 'airplane_mode_on');
          if (current >= 0) {
            const newState = current === 0 ? 1 : 0;
            await AppController.setSecureSetting('global', 'airplane_mode_on', newState);
            console.warn(`[TASK] airplane_mode: direct API ${current} → ${newState}`);
            return { success: true, summary: `Done — Airplane Mode ${newState ? 'enabled' : 'disabled'}.` };
          }
        } catch (e: any) {
          console.warn(`[TASK] airplane_mode: direct API failed (${e.message}), falling back to QS`);
        }
        const apDelta = await captureStateDelta(taskId, 'airplane_mode', async () => {
          let r = await AppController.toggleQuickSetting('Airplane');
          if (!r) r = await AppController.toggleQuickSetting('Flight');
          return r;
        });
        await logUiSnapshot(taskId, 'airplane_mode');
        if (apDelta.toggled) return { success: true, summary: 'Done — Airplane Mode toggled.', data: { delta: apDelta.changed } };
        await IntentLauncher.startActivityAsync('android.settings.AIRPLANE_MODE_SETTINGS', {});
        return { success: true, summary: 'Couldn\'t toggle directly — opened Airplane Mode settings for you.', data: { partial: true } };
      }
      case 'do_not_disturb': {
        const dndDelta = await captureStateDelta(taskId, 'do_not_disturb', async () => {
          let r = await AppController.toggleQuickSetting('Do not disturb');
          if (!r) r = await AppController.toggleQuickSetting('DND');
          return r;
        });
        await logUiSnapshot(taskId, 'do_not_disturb');
        if (dndDelta.toggled) return { success: true, summary: 'Done — Do Not Disturb toggled.', data: { delta: dndDelta.changed } };
        await IntentLauncher.startActivityAsync('android.settings.ZEN_MODE_SETTINGS', {});
        return { success: true, summary: 'Couldn\'t toggle directly — opened DND settings for you.', data: { partial: true } };
      }
      case 'battery_status': {
        const [level, state] = await Promise.all([
          Battery.getBatteryLevelAsync(),
          Battery.getBatteryStateAsync(),
        ]);
        const pct = Math.round(level * 100);
        const stateStr = ['Unknown', 'Unplugged', 'Charging', 'Full'][state] ?? 'Unknown';
        return { success: true, summary: `Battery: ${pct}% (${stateStr})`, data: { level: pct, state: stateStr } };
      }
      case 'clipboard_write': {
        try {
          const ExpoClipboard = await import('expo-clipboard');
          await ExpoClipboard.setStringAsync(params.text || '');
          return { success: true, summary: `Copied to clipboard: "${params.text}"` };
        } catch (clipErr: any) {
          return { success: false, summary: `Clipboard write failed: ${clipErr.message}` };
        }
      }
      case 'clipboard_read': {
        try {
          const ExpoClipboard = await import('expo-clipboard');
          const clipText = await ExpoClipboard.getStringAsync();
          return { success: true, summary: `Clipboard contains: "${clipText}"`, data: { text: clipText } };
        } catch (clipErr: any) {
          return { success: false, summary: `Clipboard read failed: ${clipErr.message}` };
        }
      }
      case 'media_play': {
        const action = params.action?.toLowerCase();
        const keyCode = action === 'pause' ? 127 : 85;
        const { DeviceDiagnostics: DDMedia } = await import('../services/DeviceDiagnostics');
        await DDMedia.logAudioBeforeAfter('before_media');
        let mediaPlayResult: { success: boolean; summary: string };
        try {
          const AgentNativeModule = (await import('../native/AgentNative')).default;
          if (AgentNativeModule?.sendMediaKey) {
            await AgentNativeModule.sendMediaKey(keyCode);
            mediaPlayResult = { success: true, summary: `Media ${action || 'play/pause'} triggered` };
          } else {
            await IntentLauncher.startActivityAsync('android.settings.SOUND_SETTINGS', {});
            mediaPlayResult = { success: true, summary: 'Opened media controls (native key injection unavailable)' };
          }
        } catch (mediaErr: any) {
          this.logger.warn(`Media play key injection failed: ${mediaErr.message}`);
          await IntentLauncher.startActivityAsync('android.settings.SOUND_SETTINGS', {});
          mediaPlayResult = { success: true, summary: 'Opened media controls (native key injection unavailable)' };
        }
        await DDMedia.logAudioBeforeAfter('after_media');
        return mediaPlayResult;
      }
      case 'media_next': {
        const { DeviceDiagnostics: DDNext } = await import('../services/DeviceDiagnostics');
        await DDNext.logAudioBeforeAfter('before_media');
        let mediaNextResult: { success: boolean; summary: string };
        try {
          const AgentNativeModule = (await import('../native/AgentNative')).default;
          if (AgentNativeModule?.sendMediaKey) {
            await AgentNativeModule.sendMediaKey(87);
            mediaNextResult = { success: true, summary: 'Skipped to next track' };
          } else {
            mediaNextResult = { success: false, summary: 'Media next requires native module (sendMediaKey)' };
          }
        } catch (nextErr: any) {
          this.logger.warn(`Media next key injection failed: ${nextErr.message}`);
          mediaNextResult = { success: false, summary: 'Media next requires native module (sendMediaKey)' };
        }
        await DDNext.logAudioBeforeAfter('after_media');
        return mediaNextResult;
      }
      case 'screenshot': {
        if (!isNative) return { success: false, summary: 'Screenshot requires Android device' };
        await new Promise<void>(resolve => setTimeout(resolve, 1000));
        const screenshotTaken = await AppController.takeScreenshot().catch(() => false);
        if (screenshotTaken) {
          // Dismiss Samsung Smart Capture overlay that appears after every screenshot
          await new Promise<void>(resolve => setTimeout(resolve, 600));
          await AppController.performBack().catch(() => {});
          await new Promise<void>(resolve => setTimeout(resolve, 500));
          return { success: true, summary: 'Screenshot taken — saved to your Screenshots folder' };
        }
        try {
          const tree = await AppController.getScreenContent();
          return { success: true, summary: 'Could not capture visual screenshot. Screen content returned as text.', data: { screen: tree } };
        } catch (err: any) {
          return { success: false, summary: `Screenshot failed: ${err.message}` };
        }
      }
      case 'screen_record_start': {
        try {
          await IntentLauncher.startActivityAsync('android.intent.action.MAIN', {
            packageName: 'com.android.systemui',
            className: 'com.android.systemui.screenrecord.ScreenRecordDialog',
          });
        } catch (e: any) {
          DebugLog.error('ScreenRecord', `Direct dialog failed: ${e?.message}`, e?.stack);
          await IntentLauncher.startActivityAsync(IntentLauncher.ActivityAction.SETTINGS, {});
        }
        return { success: true, summary: 'Screen recording initiated' };
      }
      case 'open_url': {
        const url = params.url;
        if (!url) return { success: false, error: 'No URL specified' };
        await IntentLauncher.startActivityAsync('android.intent.action.VIEW', {
          data: url,
        });
        return { success: true, summary: `Opened ${url}` };
      }
      case 'web_search': {
        const searchQuery = params.query || '';
        const encodedQuery = encodeURIComponent(searchQuery);
        console.warn(`[TASK] web_search: query="${searchQuery}"`);
        // Fetch actual search results — do NOT open browser
        try {
          const searchUrl = `https://html.duckduckgo.com/html/?q=${encodedQuery}`;
          const resp = await fetch(searchUrl, {
            headers: { 'User-Agent': 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36' },
          });
          if (resp.ok) {
            const html = await resp.text();
            const results: string[] = [];
            let match;
            // Pattern 1: DuckDuckGo result__a + result__snippet
            const p1 = /<a class="result__a"[^>]*>([\s\S]*?)<\/a>[\s\S]*?<a class="result__snippet"[^>]*>([\s\S]*?)<\/a>/gi;
            while ((match = p1.exec(html)) !== null && results.length < 6) {
              const t = match[1].replace(/<[^>]*>/g, '').trim();
              const s = match[2].replace(/<[^>]*>/g, '').trim();
              if (t && s) results.push(`• ${t}: ${s}`);
            }
            // Pattern 2: result__a titles only
            if (results.length === 0) {
              const p2 = /<a class="result__a"[^>]*>([\s\S]*?)<\/a>/gi;
              while ((match = p2.exec(html)) !== null && results.length < 6) {
                const t = match[1].replace(/<[^>]*>/g, '').trim();
                if (t) results.push(`• ${t}`);
              }
            }
            // Pattern 3: generic link extraction from result divs
            if (results.length === 0) {
              const p3 = /<div class="result[^"]*"[\s\S]*?<a[^>]*href="([^"]*)"[^>]*>([\s\S]*?)<\/a>/gi;
              while ((match = p3.exec(html)) !== null && results.length < 6) {
                const t = match[2].replace(/<[^>]*>/g, '').trim();
                if (t && t.length > 5 && !t.startsWith('http')) results.push(`• ${t}`);
              }
            }
            // Pattern 4: brute force — find all <a> with substantial text in result sections
            if (results.length === 0) {
              const p4 = /<a[^>]*class="[^"]*result[^"]*"[^>]*>([\s\S]*?)<\/a>/gi;
              while ((match = p4.exec(html)) !== null && results.length < 6) {
                const t = match[1].replace(/<[^>]*>/g, '').trim();
                if (t && t.length > 10) results.push(`• ${t}`);
              }
            }
            console.warn(`[TASK] web_search: extracted ${results.length} results from ${html.length} bytes`);
            if (results.length > 0) {
              return { success: true, summary: `Search results for "${searchQuery}":\n${results.join('\n')}` };
            }
            // Even with 0 regex matches, extract raw text as last resort
            const textOnly = html.replace(/<script[\s\S]*?<\/script>/gi, '').replace(/<style[\s\S]*?<\/style>/gi, '').replace(/<[^>]*>/g, ' ').replace(/\s+/g, ' ').trim();
            const snippet = textOnly.slice(0, 800);
            if (snippet.length > 50) {
              return { success: true, summary: `Search results for "${searchQuery}" (raw):\n${snippet}` };
            }
          }
        } catch (fetchErr: any) {
          console.warn(`[TASK] web_search: fetch failed: ${fetchErr.message}`);
        }
        // Last resort: use the LLM's own knowledge
        try {
          const aiResult = await this.ai.complete(`Answer this search query concisely: "${searchQuery}"`, {
            taskId, agentId: 'search', maxTokens: 500, temperature: 0.3,
          });
          if (aiResult.content) {
            return { success: true, summary: aiResult.content };
          }
        } catch {}
        return { success: false, summary: `Could not search for "${searchQuery}". No internet or search service available.` };
      }
      case 'calendar_create': {
        const now = Date.now();
        await IntentLauncher.startActivityAsync('android.intent.action.INSERT', {
          data: 'content://com.android.calendar/events',
          extra: {
            'title': params.title || params.details || 'New Event',
            'description': params.details || '',
            'beginTime': params.startMs || now,
            'endTime': params.endMs || (now + 3600000),
            'allDay': false,
          },
        });
        return { success: true, summary: `Calendar event created: ${params.title || params.details || 'New Event'}` };
      }
      case 'reminder_create': {
        try {
          await IntentLauncher.startActivityAsync('android.intent.action.INSERT', {
            data: 'content://com.android.calendar/events',
            extra: {
              'title': params.text || 'Reminder',
              'description': params.text || '',
            },
          });
        } catch {
          await IntentLauncher.startActivityAsync('android.intent.action.VIEW', {
            data: `com.google.android.keep://createnewnote?text=${encodeURIComponent(params.text || '')}`,
          });
        }
        return { success: true, summary: `Reminder created: "${params.text}"` };
      }
      case 'note_create': {
        try {
          await IntentLauncher.startActivityAsync('android.intent.action.INSERT', {
            packageName: 'com.samsung.android.app.notes',
            extra: { 'android.intent.extra.TEXT': params.content || '' },
          });
        } catch {
          await IntentLauncher.startActivityAsync('android.intent.action.VIEW', {
            data: `com.google.android.keep://createnewnote?text=${encodeURIComponent(params.content || '')}`,
          });
        }
        return { success: true, summary: `Note created: "${params.content}"` };
      }
      case 'file_open': {
        const filePath = params.path;
        if (!filePath) return { success: false, error: 'No file path specified' };
        const mimeType = params.mimeType || '*/*';
        const contentUri = await toAndroidContentUri(filePath);
        await IntentLauncher.startActivityAsync('android.intent.action.VIEW', {
          data: contentUri,
          type: mimeType,
          flags: 1, // FLAG_GRANT_READ_URI_PERMISSION
        });
        return { success: true, summary: `Opened file: ${filePath}` };
      }
      case 'share_content': {
        await IntentLauncher.startActivityAsync('android.intent.action.SEND', {
          extra: {
            'android.intent.extra.TEXT': params.content || '',
            'android.intent.extra.SUBJECT': params.subject || '',
          },
          type: 'text/plain',
        });
        return { success: true, summary: 'Share dialog opened' };
      }
      case 'app_info': {
        const appTarget = String(params.target || '').trim();
        let appPkg = lookupPackage(appTarget);
        const wantsForegroundApp = /^(foreground_app|foreground app|current app|current application|active app)$/i.test(appTarget);
        if (!appPkg && wantsForegroundApp) {
          try {
            const foregroundPkg = await AppController.getActivePackage();
            if (foregroundPkg) appPkg = foregroundPkg;
          } catch (fgErr: any) {
            this.logger.warn(`Foreground package resolve failed: ${fgErr.message}`);
          }
        }
        if (!appPkg) {
          try {
            const AgentNativeModule = (await import('../native/AgentNative')).default;
            const installed = await AgentNativeModule.getInstalledApps();
            const match = findBestMatch(appTarget, installed);
            if (match) appPkg = match.packageName;
          } catch (appInfoErr: any) {
            this.logger.warn(`App info package resolve failed: ${appInfoErr.message}`);
          }
        }
        if (appPkg) {
          await IntentLauncher.startActivityAsync('android.settings.APPLICATION_DETAILS_SETTINGS', {
            data: `package:${appPkg}`,
          });
          return { success: true, summary: `Opened app info for ${wantsForegroundApp ? appPkg : appTarget}` };
        }
        return { success: false, summary: `Could not find app: ${appTarget}` };
      }
      case 'notification_read': {
        if (!isNative || !AppController.isAvailable()) {
          return { success: false, summary: 'Notification reading requires Android device with accessibility service' };
        }
        try {
          const tree = await AppController.getScreenContent();
          return { success: true, summary: 'Notifications retrieved', data: { notifications: tree } };
        } catch (err: any) {
          return { success: false, summary: `Notification read failed: ${err.message}` };
        }
      }
      case 'device_info': {
        try {
          const { SystemInfoService } = await import('../services/SystemInfoService');
          const sysData = await SystemInfoService.gather();
          const focus = params.focus as string | undefined;
          let summary: string;
          if (focus === 'battery') {
            summary = `Battery: ${sysData.battery.level}%${sysData.battery.state === 'Charging' ? ' (charging)' : ''}`;
          } else if (focus === 'memory') {
            summary = `RAM: ${sysData.memory.usedMB} MB / ${sysData.memory.totalMB} MB (${sysData.memory.usedPercent}%)`;
          } else if (focus === 'storage') {
            summary = `Storage: ${(sysData.storage.freeMB / 1024).toFixed(1)} GB free / ${(sysData.storage.totalMB / 1024).toFixed(1)} GB total`;
          } else if (focus === 'network') {
            summary = `Network: ${sysData.network.connected ? sysData.network.type : 'Disconnected'}`;
          } else {
            summary = SystemInfoService.toSummaryString(sysData);
          }
          return { success: true, summary, data: { systemInfoData: sysData } };
        } catch (err: any) {
          return { success: false, error: `Device info failed: ${err.message}` };
        }
      }

      case 'image_generate': {
        const prompt = params.prompt || request;
        if (!prompt) return { error: 'No image prompt specified' };
        try {
          const explicitImageModel = (params as any).model as string | undefined;
          let result: { images: string[]; model: string };
          if (this.aiServiceProvider) {
            const aiResult = await this.aiServiceProvider.generateImage({ prompt, model: explicitImageModel, taskId });
            result = { images: aiResult.images, model: aiResult.model };
          } else {
            const imageModel = explicitImageModel ||
              (this.ai.getDefaultModelId ? this.ai.getDefaultModelId() ?? undefined : undefined) ||
              'fluently-xl';
            result = await this.ai.generateImage(prompt, { taskId, model: imageModel });
          }
          if (result.images.length === 0) return { error: 'No images generated' };
          const docDirSlash = this.docDir.endsWith('/') ? this.docDir : this.docDir + '/';
          const imagePath = `${docDirSlash}generated_${Date.now()}.png`;
          if (isNative && FileSystem) {
            const raw = result.images[0].replace(/^data:image\/\w+;base64,/, '');
            await FileSystem.writeAsStringAsync(imagePath, raw, {
              encoding: FileSystem.EncodingType.Base64,
            });
          }
          return {
            success: true,
            summary: `Image generated and saved to ${imagePath}`,
            imageCount: result.images.length,
            path: isNative ? imagePath : undefined,
            model: result.model,
          };
        } catch (err: any) {
          return { error: `Image generation failed: ${err.message}` };
        }
      }

      case 'tts': {
        const text = (params as any).text || request;
        if (!text) return { error: 'No text specified for speech' };
        if (!this.aiServiceProvider) return { error: 'No AI service configured. Open Settings and add a provider with audio generation support.' };
        try {
          const voice = (params as any).voice as string | undefined;
          const result = await this.aiServiceProvider.generateSpeech({ text, voice, taskId });
          const docDirSlash = this.docDir.endsWith('/') ? this.docDir : this.docDir + '/';
          const audioPath = `${docDirSlash}tts_${Date.now()}.mp3`;
          if (isNative && FileSystem) {
            await FileSystem.writeAsStringAsync(audioPath, result.audioBase64, {
              encoding: FileSystem.EncodingType.Base64,
            });
          }
          return {
            success: true,
            path: isNative ? audioPath : undefined,
            latencyMs: result.latencyMs,
            summary: `Audio generated (${result.latencyMs}ms)`,
          };
        } catch (err: any) {
          return { error: `TTS failed: ${err.message}` };
        }
      }

      case 'video_generate': {
        const prompt = (params as any).prompt || request;
        if (!prompt) return { error: 'No prompt specified for video generation' };
        if (!this.aiServiceProvider) return { error: 'No AI service configured. Open Settings and add a provider with video generation support.' };
        try {
          const model = (params as any).model as string | undefined;
          const seconds = (params as any).seconds as number | undefined;
          const duration = (params as any).duration as number | undefined;
          const resolvedDuration = duration ?? seconds;
          const videoResult = await this.aiServiceProvider.generateVideo({ prompt, model, duration: resolvedDuration, taskId });
          const videoBase64 = videoResult.base64 ?? '';
          const docDirSlash = this.docDir.endsWith('/') ? this.docDir : this.docDir + '/';
          const videoPath = `${docDirSlash}video_${Date.now()}.mp4`;
          if (isNative && FileSystem && videoBase64) {
            await FileSystem.writeAsStringAsync(videoPath, videoBase64, {
              encoding: FileSystem.EncodingType.Base64,
            });
          }
          return {
            success: true,
            model: videoResult.model,
            path: isNative && videoBase64 ? videoPath : undefined,
            url: videoResult.url,
            summary: `Video generated with model ${videoResult.model}`,
          };
        } catch (err: any) {
          return { error: `Video generation failed: ${err.message}` };
        }
      }
      case 'system_info': {
        DebugLog.executorEnter(taskId, 'system_info');
        try {
          const { SystemInfoService } = await import('../services/SystemInfoService');
          const sysData = await SystemInfoService.gather();
          const summary = SystemInfoService.toSummaryString(sysData);
          DebugLog.executorExit(taskId, 'system_info', true, 'success');
          return { success: true, summary, data: { systemInfoData: sysData } };
        } catch (err: any) {
          DebugLog.executorExit(taskId, 'system_info', false, 'error');
          return { success: false, error: `System info failed: ${err.message}` };
        }
      }

      case 'knowledge_query': {
        try {
          const core = (await import('./AgentCore')).getAgentCoreInstance();
          const graph = core?.getCortex()?.getKnowledgeGraph();
          if (!graph) return { success: false, summary: 'Knowledge graph not available' };
          const query = params.query || '';
          const allResults = graph.resolveAll(query);
          if (allResults.length === 0) {
            return { success: true, summary: `I don't have specific knowledge about "${query}" yet.` };
          }
          if (allResults.length === 1) {
            return { success: true, summary: `${graph.getContextFor(query)}\n\n${graph.getSummary()}`, data: { entity: allResults[0].entity } };
          }
          const list = allResults.map((r, i) => {
            const e = r.entity!;
            const relSummary = r.relations.slice(0, 3).map(rel => `${rel.relation.type} → ${rel.targetEntity.name}`).join(', ');
            return `${i + 1}. ${e.name} (${e.type})${relSummary ? `: ${relSummary}` : ''}`;
          }).join('\n');
          return { success: true, summary: `I know ${allResults.length} entities matching "${query}":\n\n${list}`, data: { matches: allResults.length } };
        } catch (e: any) { return { success: false, summary: `Knowledge query failed: ${e.message}` }; }
      }
      case 'set_user_name': {
        try {
          const rawName = (params.name || '').trim();
          if (!rawName) return { success: false, summary: 'No name provided.' };
          const core = (await import('./AgentCore')).getAgentCoreInstance();
          const graph = core?.getCortex()?.getKnowledgeGraph();
          const vault = core?.getVault?.();
          DebugLog.push('KG_ENTITY' as any, { event: 'set_user_name_start', name: rawName });
          if (graph) {
            const existing = graph.findEntityByName('self') || graph.findEntityByName('me') || graph.findEntityByName('user');
            if (existing) {
              existing.name = rawName;
              existing.aliases = [...new Set([...(existing.aliases || []), rawName.toLowerCase(), 'me', 'self', 'user'])];
              existing.updatedAt = Date.now();
              existing.confidence = 1.0;
            } else {
              graph.addEntity({ type: 'person' as any, name: rawName, confidence: 1.0, source: 'user_correction' });
            }
            graph.persist().catch(() => {});
          }
          if (vault) await vault.set('user_preferred_name', rawName).catch(() => {});
          DebugLog.push('KG_ENTITY' as any, { event: 'set_user_name_done', name: rawName, graphUpdated: !!graph, vaultUpdated: !!vault });
          return { success: true, summary: `Got it — I'll call you ${rawName}.` };
        } catch (e: any) { return { success: false, summary: `Couldn't save name: ${e.message}` }; }
      }
      case 'user_correction': {
        try {
          const core = (await import('./AgentCore')).getAgentCoreInstance();
          const graph = core?.getCortex()?.getKnowledgeGraph();
          const ai = core?.getModelRouter();
          if (!graph) return { success: false, summary: 'Knowledge graph not available' };
          const { UserCorrection } = await import('./UserCorrection');
          const corrector = new UserCorrection(graph, ai!);
          const result = await corrector.process(params.correction || '');
          return { success: result.applied, summary: result.description };
        } catch (e: any) { return { success: false, summary: `Correction failed: ${e.message}` }; }
      }
      case 'proactive_suggestions': {
        try {
          const core = (await import('./AgentCore')).getAgentCoreInstance();
          const engine = core?.getProactiveEngine();
          if (!engine) return { success: true, summary: 'Proactive engine not active yet.' };
          const active = engine.getActive();
          if (active.length === 0) return { success: true, summary: 'No suggestions right now.' };
          return { success: true, summary: `${active.length} suggestions:\n\n${active.map(s => `${s.urgency === 'high' ? '\ud83d\udd34' : s.urgency === 'medium' ? '\ud83d\udfe1' : '\ud83d\udfe2'} ${s.title}\n   ${s.body}${s.suggestedCommand ? `\n   \u2192 Say: "${s.suggestedCommand}"` : ''}`).join('\n\n')}` };
        } catch (e: any) { return { success: false, summary: `Suggestions failed: ${e.message}` }; }
      }
      case 'task_resume': {
        try {
          const core = (await import('./AgentCore')).getAgentCoreInstance();
          const cortex = core?.getCortex();
          if (!cortex) return { success: false, summary: 'Cortex not available' };
          const resumable = await cortex.getResumableTasks();
          if (resumable.length === 0) return { success: true, summary: 'No tasks to resume.' };
          const query = params.query?.toLowerCase() || '';
          const match = query ? resumable.find(t => t.goal.toLowerCase().includes(query)) : resumable[0];
          if (!match) return { success: true, summary: `${resumable.length} resumable tasks:\n${resumable.map(t => `\u2022 "${t.goal.slice(0, 50)}" [${t.status}]`).join('\n')}` };
          const result = await cortex.resume(match.id);
          return { success: result.success, summary: result.summary };
        } catch (e: any) { return { success: false, summary: `Resume failed: ${e.message}` }; }
      }
      case 'behavior_patterns': {
        try {
          const core = (await import('./AgentCore')).getAgentCoreInstance();
          const signals = core?.getDeviceSignals();
          if (!signals) return { success: true, summary: 'Pattern detection not active yet.' };
          const patterns = signals.getPatterns();
          if (patterns.length === 0) return { success: true, summary: 'Not enough data yet. Keep using the app.' };
          return { success: true, summary: `Detected patterns:\n${patterns.sort((a, b) => b.confidence - a.confidence).slice(0, 10).map(p => `\u2022 ${p.description} (${Math.round(p.confidence * 100)}%)`).join('\n')}` };
        } catch (e: any) { return { success: false, summary: `Pattern query failed: ${e.message}` }; }
      }
      case 'web_research': {
        try {
          const core = (await import('./AgentCore')).getAgentCoreInstance();
          const appIntel = core?.getCortex()?.getAppIntelligence();
          if (!appIntel) return { success: false, summary: 'App intelligence not available' };
          if (!params.query) return { success: false, summary: 'What should I research?' };
          const result = await appIntel.search(params.query, { app: 'google' });
          return { success: result.success, summary: result.aiSummary, data: { structuredData: result.structuredData, app: result.app, steps: result.steps } };
        } catch (e: any) { return { success: false, summary: `Research failed: ${e.message}` }; }
      }
      case 'describe_screen': {
        try {
          const core = (await import('./AgentCore')).getAgentCoreInstance();
          const vision = core?.getCortex()?.getVisionPipeline();
          if (!vision) return { success: false, summary: 'Vision pipeline not available' };
          const context: string | undefined = params.context || undefined;
          const u = await vision.understand(context);
          let summary = u.description;
          if (u.textContent.length > 0) summary += '\n\nVisible text: ' + u.textContent.slice(0, 5).join('; ');
          if (u.interactableElements.length > 0) summary += '\n\nInteractive: ' + u.interactableElements.slice(0, 5).map(e => `${e.label} [${e.type}]`).join(', ');
          DebugLog.push('VISION_ANALYZE' as any, { event: 'describe_screen', screenType: u.screenType, confidence: u.confidence, textItems: u.textContent.length, interactive: u.interactableElements.length });
          return { success: true, summary, data: u };
        } catch (e: any) { return { success: false, summary: `Screen description failed: ${e.message}` }; }
      }
      case 'read_text_on_screen': {
        // Use accessibility service directly — proven reliable
        try {
          if (isNative && AppController.isAvailable()) {
            const flat = await AppController.getScreenContentFlat();
            const nodes = JSON.parse(flat);
            if (Array.isArray(nodes) && nodes.length > 0) {
              const pkg = await AppController.getActivePackage().catch(() => 'unknown');
              const texts = nodes
                .filter((n: any) => (n.t || n.d || '').trim())
                .map((n: any) => (n.t || n.d || '').trim());
              if (texts.length > 0) {
                return { success: true, summary: `[${pkg}] ${texts.slice(0, 30).join(' | ')}`, data: { app: pkg, items: texts.length } };
              }
            }
            return { success: true, summary: 'Screen appears empty or has no readable text.' };
          }
        } catch {}
        // Fallback to vision pipeline
        try {
          const core = (await import('./AgentCore')).getAgentCoreInstance();
          const vision = core?.getCortex()?.getVisionPipeline();
          if (!vision) return { success: false, summary: 'Screen reading not available' };
          const result = await vision.readText(params.hint);
          return { success: result.success, summary: result.text || 'No text found.' };
        } catch (e: any) { return { success: false, summary: `Screen reading failed: ${e.message}` }; }
      }
      case 'vision_read': {
        try {
          const core = (await import('./AgentCore')).getAgentCoreInstance();
          const vision = core?.getCortex()?.getVisionPipeline();
          if (!vision) return { success: false, summary: 'Vision pipeline not available' };
          const u = await vision.understand();
          let summary = u.description;
          if (u.interactableElements.length > 0) summary += '\n\nInteractive:\n' + u.interactableElements.map(e => `\u2022 ${e.label} [${e.type}] \u2014 ${e.suggestedAction}`).join('\n');
          return { success: true, summary, data: u };
        } catch (e: any) { return { success: false, summary: `Vision failed: ${e.message}` }; }
      }
      case 'weather': {
        try {
          let lat: number | null = null;
          let lon: number | null = null;
          let locationName: string | null = null;
          const unit = (params.unit === 'celsius') ? 'celsius' : 'fahrenheit';
          const unitSymbol = unit === 'celsius' ? '°C' : '°F';

          const weatherLocation = params.location || params.city || '';
          if (weatherLocation) {
            const geoUrl = `https://geocoding-api.open-meteo.com/v1/search?name=${encodeURIComponent(weatherLocation)}&count=1&language=en&format=json`;
            const geoRes = await fetch(geoUrl);
            if (geoRes.ok) {
              const geoData = await geoRes.json();
              if (geoData.results && geoData.results.length > 0) {
                lat = geoData.results[0].latitude;
                lon = geoData.results[0].longitude;
                locationName = geoData.results[0].name;
              }
            }
          } else if (isNative) {
            try {
              const { status } = await Location.requestForegroundPermissionsAsync();
              if (status === 'granted') {
                const loc = await Location.getCurrentPositionAsync({ accuracy: Location.Accuracy.Low });
                lat = loc.coords.latitude;
                lon = loc.coords.longitude;
                try {
                  const [addr] = await Location.reverseGeocodeAsync({ latitude: lat, longitude: lon });
                  locationName = addr.city || addr.subregion || addr.region || addr.country || null;
                } catch {}
              }
            } catch {}
          }

          if (lat === null || lon === null) {
            return { success: false, summary: 'Could not determine your location. Try "weather in New York".' };
          }

          const weatherUrl = `https://api.open-meteo.com/v1/forecast?latitude=${lat.toFixed(4)}&longitude=${lon.toFixed(4)}&current=temperature_2m,relative_humidity_2m,weather_code,wind_speed_10m,precipitation_probability&hourly=temperature_2m,precipitation_probability,weather_code&forecast_days=1&timezone=auto&temperature_unit=${unit}`;
          const res = await fetch(weatherUrl);
          if (!res.ok) return { success: false, summary: `Weather service unavailable (${res.status}). Try again later.` };
          const data = await res.json();

          const cur = data.current;
          const temp = Math.round(cur.temperature_2m ?? cur.temperature ?? 0);
          const humidity = Math.round(cur.relative_humidity_2m ?? 0);
          const windspeed = Math.round(cur.wind_speed_10m ?? cur.windspeed ?? 0);
          const precipChance = Math.round(cur.precipitation_probability ?? 0);
          const weatherCode = cur.weather_code ?? cur.weathercode ?? 0;
          const condition = wmoCodeToCondition(weatherCode);

          // Build hourly forecast for the next 6 hours
          const forecast: Array<{ time: string; temp: number; precipChance: number; condition: string }> = [];
          const nowIso = cur.time as string | undefined;
          const hourlyTimes: string[] = data.hourly?.time || [];
          const hourlyTemps: number[] = data.hourly?.temperature_2m || [];
          const hourlyPrecip: number[] = data.hourly?.precipitation_probability || [];
          const hourlyCodes: number[] = data.hourly?.weather_code || [];
          const startIdx = nowIso ? hourlyTimes.findIndex((t: string) => t >= nowIso) : 0;
          const from = startIdx >= 0 ? startIdx : 0;
          for (let i = from + 1; i < Math.min(from + 7, hourlyTimes.length); i++) {
            forecast.push({
              time: hourlyTimes[i].slice(11, 16),
              temp: Math.round(hourlyTemps[i] ?? temp),
              precipChance: Math.round(hourlyPrecip[i] ?? 0),
              condition: wmoCodeToCondition(hourlyCodes[i] ?? weatherCode),
            });
          }

          const locStr = locationName ? ` in ${locationName}` : '';
          let summary = `Weather${locStr}: ${temp}${unitSymbol}, ${condition}. Humidity: ${humidity}%. Wind: ${windspeed} km/h.`;
          if (precipChance > 20) summary += ` Current precipitation chance: ${precipChance}%.`;
          if (forecast.length > 0) {
            const forecastStr = forecast.map(f => `${f.time}: ${f.temp}${unitSymbol} ${f.condition}${f.precipChance > 20 ? ` (${f.precipChance}% rain)` : ''}`).join(', ');
            summary += `\nForecast: ${forecastStr}`;
          }

          DebugLog.push('WEATHER', { lat, lon, location: locationName, temp, humidity, windspeed, weatherCode, condition, precipChance, forecastHours: forecast.length });
          return {
            success: true,
            summary,
            data: { temperature: temp, unit: unitSymbol, condition, windspeed, humidity, location: locationName, precipChance, forecast },
          };
        } catch (e: any) {
          return { success: false, summary: `Weather error: ${e.message}` };
        }
      }
      case 'news_headlines': {
        try {
          const topic: string = params.topic || '';
          const count: number = typeof params.count === 'number' ? Math.min(params.count, 10) : 5;
          const feeds = topic
            ? [`https://news.google.com/rss/search?q=${encodeURIComponent(topic)}&hl=en&gl=US&ceid=US:en`]
            : [
                'https://news.google.com/rss?hl=en-US&gl=US&ceid=US:en',
                'https://feeds.bbci.co.uk/news/rss.xml',
                'https://feeds.reuters.com/reuters/topNews',
              ];

          for (const feedUrl of feeds) {
            try {
              const res = await fetch(feedUrl);
              if (!res.ok) continue;
              const xml = await res.text();
              const headlines = parseRssHeadlines(xml, count);
              if (headlines.length > 0) {
                const topicStr = topic ? ` about "${topic}"` : '';
                const summary = `Top headlines${topicStr}:\n${headlines.map((h, i) => `${i + 1}. ${h.title}${h.link ? `\n   ${h.link}` : ''}`).join('\n')}`;
                DebugLog.push('NEWS_FETCH', { feedUrl, count: headlines.length, topic });
                return { success: true, summary, data: { headlines, source: feedUrl } };
              }
            } catch {}
          }
          return { success: false, summary: 'Could not fetch news headlines. Check your internet connection.' };
        } catch (e: any) {
          return { success: false, summary: `News error: ${e.message}` };
        }
      }
      case 'set_user_info': {
        try {
          const core = (await import('./AgentCore')).getAgentCoreInstance();
          const vault = core?.getVault?.();
          if (!vault) return { success: false, summary: 'Secure storage not available' };
          const saved: string[] = [];
          if (params.email) { await vault.set('user_email', params.email); saved.push(`email: ${params.email}`); }
          if (params.phone) { await vault.set('user_phone', params.phone); saved.push(`phone: ${params.phone}`); }
          if (params.address) { await vault.set('user_address', params.address); saved.push(`address: ${params.address}`); }
          if (params.name) { await vault.set('user_preferred_name', params.name); saved.push(`name: ${params.name}`); }
          if (saved.length === 0) return { success: false, summary: 'No info provided. Send email, phone, or address.' };
          console.warn(`[TASK] set_user_info: saved ${saved.join(', ')}`);
          return { success: true, summary: `Got it — saved ${saved.join(', ')}. I'll use this to fill forms and personalize your experience.` };
        } catch (e: any) { return { success: false, summary: `Couldn't save info: ${e.message}` }; }
      }
      case 'install_app': {
        const appName = params.appName || params.target || '';
        if (!appName) return { success: false, summary: 'No app name specified' };
        console.warn(`[TASK] install_app: searching Play Store for "${appName}"`);
        try {
          // Open Play Store search directly
          await IntentLauncher.startActivityAsync('android.intent.action.VIEW', {
            data: `market://search?q=${encodeURIComponent(appName)}`,
          });
          // Wait for Play Store to load, then use react_navigate to tap Install
          await new Promise(resolve => setTimeout(resolve, 3000));
          return {
            success: true,
            summary: `Opened Play Store search for "${appName}". The app page should be showing — tap Install to add it to this phone.`,
            data: { appName, playStoreOpened: true },
          };
        } catch (playErr: any) {
          // Fallback: open Play Store in browser
          try {
            await IntentLauncher.startActivityAsync('android.intent.action.VIEW', {
              data: `https://play.google.com/store/search?q=${encodeURIComponent(appName)}`,
            });
            return { success: true, summary: `Opened Play Store web for "${appName}".` };
          } catch {
            return { success: false, summary: `Could not open Play Store for "${appName}": ${playErr.message}` };
          }
        }
      }
      default:
        throw new Error(`No executor for: ${capId}`);
    }
  }

  private async exec(capId: string, request: string, taskId: string): Promise<any> {
    // Delegate to the canonical execWithParams() to avoid duplicate logic
    return this.execWithParams(capId, {}, request, taskId);
  }
}

async function gatherSystemInfo(): Promise<string> {
  const lines: string[] = [];
  const sensorData: Parameters<typeof DebugLog.systemInfo>[0] = {
    batteryPct: undefined, batteryState: undefined, lowPower: undefined,
    ramUsedMB: undefined, ramTotalMB: undefined,
    storageFreeGB: undefined, storageTotalGB: undefined,
    cpuTempC: undefined, failedReads: [],
  };

  try {
    const [level, state, lowPower] = await Promise.all([
      Battery.getBatteryLevelAsync(),
      Battery.getBatteryStateAsync(),
      Battery.isLowPowerModeEnabledAsync(),
    ]);
    const pct = Math.round(level * 100);
    const stateStr = ['Unknown', 'Unplugged', 'Charging', 'Full'][state] ?? 'Unknown';
    sensorData.batteryPct = pct;
    sensorData.batteryState = stateStr;
    sensorData.lowPower = lowPower;
    lines.push(`Battery: ${pct}% (${stateStr})${lowPower ? ' — Low Power Mode ON' : ''}`);
  } catch { lines.push('Battery: unavailable'); sensorData.failedReads.push('battery'); }
  try {
    const [used, total] = await Promise.all([
      DeviceInfo.getUsedMemory(),
      DeviceInfo.getTotalMemory(),
    ]);
    const usedMB = Math.round(used/1024/1024);
    const totalMB = Math.round(total/1024/1024);
    sensorData.ramUsedMB = usedMB;
    sensorData.ramTotalMB = totalMB;
    lines.push(`RAM: ${usedMB} MB used of ${totalMB} MB`);
  } catch { lines.push('RAM: unavailable'); sensorData.failedReads.push('ram'); }
  try {
    const [free, total] = await Promise.all([
      DeviceInfo.getFreeDiskStorage(),
      DeviceInfo.getTotalDiskCapacity(),
    ]);
    const freeGB = parseFloat((free/1024/1024/1024).toFixed(1));
    const totalGB = parseFloat((total/1024/1024/1024).toFixed(1));
    sensorData.storageFreeGB = freeGB;
    sensorData.storageTotalGB = totalGB;
    lines.push(`Storage: ${Math.round(free/1024/1024)} MB free of ${Math.round(total/1024/1024)} MB`);
  } catch { lines.push('Storage: unavailable'); sensorData.failedReads.push('storage'); }
  try {
    const AppControllerModule = AppController;
    const temp = await AppControllerModule.exec?.('cat /sys/class/thermal/thermal_zone0/temp');
    if (temp && !isNaN(parseInt(temp))) {
      const tempC = parseFloat((parseInt(temp)/1000).toFixed(1));
      sensorData.cpuTempC = tempC;
      lines.push(`CPU Temp: ${tempC}°C`);
    }
  } catch { sensorData.failedReads.push('temp'); }
  lines.push(`Device: ${DeviceInfo.getModel()} (Android ${DeviceInfo.getSystemVersion()})`);

  DebugLog.systemInfo(sensorData);
  return lines.join('\n');
}

function parseTimeString(input: string): { hour: number; minute: number; display: string } {
  const normalized = input.toLowerCase().trim();
  const match = normalized.match(/(\d{1,2}):?(\d{2})?\s*(am|pm)?/i);
  if (!match) return { hour: 8, minute: 0, display: input };
  let hour = parseInt(match[1]);
  const minute = match[2] ? parseInt(match[2]) : 0;
  const meridiem = match[3]?.toLowerCase();
  if (meridiem === 'pm' && hour < 12) hour += 12;
  if (meridiem === 'am' && hour === 12) hour = 0;
  const display = `${hour % 12 || 12}:${minute.toString().padStart(2, '0')} ${hour < 12 ? 'AM' : 'PM'}`;
  return { hour, minute, display };
}

function parseDurationToSeconds(input: string): number {
  const normalized = input.toLowerCase().trim();
  const hours = normalized.match(/(\d+)\s*h(our)?s?/)?.[1];
  const minutes = normalized.match(/(\d+)\s*m(in(ute)?)?s?/)?.[1];
  const seconds = normalized.match(/(\d+)\s*s(ec(ond)?)?s?/)?.[1];
  return (parseInt(hours || '0') * 3600) + (parseInt(minutes || '0') * 60) + parseInt(seconds || '0');
}

function wmoCodeToCondition(code: number): string {
  if (code === 0) return 'Clear sky';
  if (code === 1) return 'Mainly clear';
  if (code === 2) return 'Partly cloudy';
  if (code === 3) return 'Overcast';
  if (code === 45 || code === 48) return 'Foggy';
  if (code >= 51 && code <= 55) return 'Drizzle';
  if (code >= 56 && code <= 57) return 'Freezing drizzle';
  if (code >= 61 && code <= 65) return 'Rain';
  if (code >= 66 && code <= 67) return 'Freezing rain';
  if (code >= 71 && code <= 77) return 'Snow';
  if (code >= 80 && code <= 82) return 'Rain showers';
  if (code === 85 || code === 86) return 'Snow showers';
  if (code === 95) return 'Thunderstorm';
  if (code === 96 || code === 99) return 'Thunderstorm with hail';
  return 'Unknown';
}

function parseRssHeadlines(xml: string, limit: number): Array<{ title: string; link?: string }> {
  const headlines: Array<{ title: string; link?: string }> = [];
  const itemRe = /<item[\s>]([\s\S]*?)<\/item>/gi;
  const titleRe = /<title>(?:<!\[CDATA\[)?\s*([\s\S]*?)\s*(?:\]\]>)?<\/title>/i;
  const linkRe = /<link>([^<]+)<\/link>/i;
  let m: RegExpExecArray | null;
  while ((m = itemRe.exec(xml)) !== null && headlines.length < limit) {
    const block = m[1];
    const titleMatch = block.match(titleRe);
    if (!titleMatch) continue;
    const title = titleMatch[1]
      .replace(/&amp;/g, '&')
      .replace(/&lt;/g, '<')
      .replace(/&gt;/g, '>')
      .replace(/&quot;/g, '"')
      .replace(/&#39;/g, "'")
      .replace(/\s+/g, ' ')
      .trim();
    if (!title || title.length < 5) continue;
    const linkMatch = block.match(linkRe);
    headlines.push({ title, link: linkMatch?.[1]?.trim() });
  }
  return headlines;
}
