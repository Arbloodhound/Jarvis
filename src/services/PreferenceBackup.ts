import { Platform, Alert } from 'react-native';
import * as FileSystem from 'expo-file-system/legacy';
import * as Sharing from 'expo-sharing';
import * as DocumentPicker from 'expo-document-picker';
import { SecureVault } from '../security/SecureVault';
import { LogFolder } from './LogFolder';
import { UltraDevLog } from '../utils/UltraDevLog';

const EXPORT_VERSION = '1.1';
const EXPORT_SOURCE = 'agent-ultra';

const EXPORTABLE_KEYS = [
  'preferred_model',
  'learned_patterns',
  'user_preferences',
  'daily_cost_limit',
  'task_cost_limit',
];

interface SavedApiExport {
  id: string;
  name: string;
  baseUrl: string;
}

interface PreferenceBackupV1 {
  version: string;
  exportedAt: string;
  source: string;
  preferred_model?: string;
  saved_apis?: string;
  learned_patterns?: string;
  user_preferences?: string;
  daily_cost_limit?: string;
  task_cost_limit?: string;
}

function stripApiKeys(savedApisJson: string | null): string | null {
  if (!savedApisJson) return null;
  try {
    const apis = JSON.parse(savedApisJson);
    if (!Array.isArray(apis)) return null;
    const stripped: SavedApiExport[] = apis.map((api: any) => ({
      id: api.id || '',
      name: api.name || '',
      baseUrl: api.baseUrl || '',
    }));
    return JSON.stringify(stripped);
  } catch {
    return null;
  }
}

function migrateBackup(data: any): PreferenceBackupV1 {
  const version = data.version || '1.0';
  if (version === '1.0') {
    return {
      ...data,
      version: '1.1',
    };
  }
  return data as PreferenceBackupV1;
}

function confirmImport(keysToRestore: string[]): Promise<boolean> {
  return new Promise((resolve) => {
    const keyLabels: Record<string, string> = {
      preferred_model: 'Preferred model',
      saved_apis: 'AI providers (names/URLs only, no keys)',
      learned_patterns: 'Learned patterns',
      user_preferences: 'User preferences',
      daily_cost_limit: 'Daily cost limit',
      task_cost_limit: 'Per-task cost limit',
    };
    const listed = keysToRestore.map(k => `• ${keyLabels[k] || k}`).join('\n');
    Alert.alert(
      'Restore Preferences?',
      `The following settings will be overwritten:\n\n${listed}\n\nAPI keys are not included in this backup and must be re-entered manually.`,
      [
        { text: 'Cancel', style: 'cancel', onPress: () => resolve(false) },
        { text: 'Restore', style: 'destructive', onPress: () => resolve(true) },
      ],
      { cancelable: true, onDismiss: () => resolve(false) }
    );
  });
}

export async function exportPreferences(): Promise<{ success: boolean; message: string }> {
  try {
    const vault = await SecureVault.initialize();
    const data: Record<string, any> = {
      version: EXPORT_VERSION,
      exportedAt: new Date().toISOString(),
      source: EXPORT_SOURCE,
    };

    for (const key of EXPORTABLE_KEYS) {
      const val = await vault.get(key).catch(() => null);
      if (val !== null) data[key] = val;
    }

    const savedApisRaw = await vault.get('saved_apis').catch(() => null);
    const strippedApis = stripApiKeys(savedApisRaw);
    if (strippedApis !== null) {
      data.saved_apis = strippedApis;
    }

    const json = JSON.stringify(data, null, 2);
    await LogFolder.writeLog('agent-ultra-preferences-backup.json', json);

    const filePath = Platform.OS !== 'web'
      ? `${FileSystem.documentDirectory}agent-ultra-logs/agent-ultra-preferences-backup.json`
      : null;

    if (filePath) {
      const canShare = await Sharing.isAvailableAsync().catch(() => false);
      if (canShare) {
        await Sharing.shareAsync(filePath, {
          mimeType: 'application/json',
          dialogTitle: 'Save Agent Ultra Preferences Backup',
        });
      }
    }

    const keyCount = Object.keys(data).length - 3;
    UltraDevLog.preferenceBackup('export', true, keyCount);
    return {
      success: true,
      message: 'Preferences exported successfully. API keys are excluded for security — only API names and base URLs are saved.',
    };
  } catch (err: any) {
    UltraDevLog.preferenceBackup('export', false, 0, err.message);
    return { success: false, message: `Export failed: ${err.message}` };
  }
}

export async function importPreferences(): Promise<{ success: boolean; message: string }> {
  try {
    const result = await DocumentPicker.getDocumentAsync({
      type: ['application/json', 'text/plain', '*/*'],
      copyToCacheDirectory: true,
    });
    if (result.canceled) return { success: false, message: 'Import cancelled.' };
    const fileUri = result.assets[0].uri;

    let fileContent: string;
    if (Platform.OS !== 'web') {
      fileContent = await FileSystem.readAsStringAsync(fileUri);
    } else {
      const response = await fetch(fileUri);
      fileContent = await response.text();
    }

    let rawData: any;
    try {
      rawData = JSON.parse(fileContent);
    } catch {
      return { success: false, message: 'Invalid file — could not parse JSON.' };
    }

    if (!rawData.version || !rawData.source || rawData.source !== EXPORT_SOURCE) {
      return { success: false, message: 'Invalid backup file — not an Agent Ultra preferences backup.' };
    }

    const data = migrateBackup(rawData);

    const keysToRestore: string[] = [];
    const allKeys = [...EXPORTABLE_KEYS, 'saved_apis'];
    for (const key of allKeys) {
      if ((data as any)[key] !== undefined) keysToRestore.push(key);
    }

    if (keysToRestore.length === 0) {
      return { success: false, message: 'Backup file contains no restorable preferences.' };
    }

    const confirmed = await confirmImport(keysToRestore);
    if (!confirmed) {
      return { success: false, message: 'Import cancelled.' };
    }

    const vault = await SecureVault.initialize();
    const imported: string[] = [];

    for (const key of allKeys) {
      const val = (data as any)[key];
      if (val !== undefined) {
        const strVal = typeof val === 'string' ? val : JSON.stringify(val);
        await vault.set(key, strVal);
        imported.push(key);
      }
    }

    UltraDevLog.preferenceBackup('import', true, imported.length);
    return {
      success: true,
      message: `Restored ${imported.length} settings.\n\nNote: API keys were not included in the backup — please re-enter them in API Setup. Restart the app to apply all changes.`,
    };
  } catch (err: any) {
    UltraDevLog.preferenceBackup('import', false, 0, err.message);
    return { success: false, message: `Import failed: ${err.message}` };
  }
}
