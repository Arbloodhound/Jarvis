import React, { useState, useEffect, useCallback, useMemo } from 'react';
import {
  View, Text, TextInput, FlatList, Pressable, StyleSheet,
  ActivityIndicator, Switch, ListRenderItemInfo,
} from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import AppController from '@/src/native/AppController';
import AgentNativeModule from '@/src/native/AgentNative';
import { UltraDevLog } from '@/src/utils/UltraDevLog';

const ACCENT = '#e5e5e5';
const BG = '#000000';
const SURFACE = '#111111';
const SURFACE2 = '#1a1a1a';
const DIM = '#666666';
const DANGER = '#ef4444';
const TEXT = '#e0e0e0';

interface InstalledApp {
  packageName: string;
  appName: string;
}

interface BlockedAppsTabProps {
  isNative: boolean;
}

export default function BlockedAppsTab({ isNative }: BlockedAppsTabProps) {
  const [installedApps, setInstalledApps] = useState<InstalledApp[]>([]);
  const [blockedPackages, setBlockedPackages] = useState<Set<string>>(new Set());
  const [searchQuery, setSearchQuery] = useState('');
  const [loading, setLoading] = useState(false);
  const [serviceAvailable, setServiceAvailable] = useState(false);
  const [toggling, setToggling] = useState<string | null>(null);

  const loadData = useCallback(async () => {
    if (!isNative) return;
    setLoading(true);
    try {
      const isEnabled = await AppController.isServiceEnabled();
      setServiceAvailable(isEnabled);

      const [apps, blocked] = await Promise.all([
        AgentNativeModule.getInstalledApps(),
        AppController.getBlockedPackages(),
      ]);

      const sorted = [...apps].sort((a, b) =>
        a.appName.localeCompare(b.appName)
      );
      setInstalledApps(sorted);
      setBlockedPackages(new Set(blocked));
    } catch {
      setInstalledApps([]);
      setBlockedPackages(new Set());
    } finally {
      setLoading(false);
    }
  }, [isNative]);

  useEffect(() => { loadData(); }, [loadData]);

  const filteredApps = useMemo(() => {
    const q = searchQuery.trim().toLowerCase();
    if (!q) return installedApps;
    return installedApps.filter(
      a => a.appName.toLowerCase().includes(q) || a.packageName.toLowerCase().includes(q)
    );
  }, [installedApps, searchQuery]);

  const toggleBlock = useCallback(async (pkg: string, isBlocked: boolean) => {
    UltraDevLog.push('CHAIN', { component: 'BlockedAppsTab', action: 'toggle_block', trigger: { pkg }, state: { wasBlocked: isBlocked }, data: {}, outcome: isBlocked ? 'unblocking' : 'blocking' });
    setToggling(pkg);
    try {
      if (isBlocked) {
        await AppController.unblockPackage(pkg);
        setBlockedPackages(prev => {
          const next = new Set(prev);
          next.delete(pkg);
          return next;
        });
      } else {
        await AppController.blockPackage(pkg);
        setBlockedPackages(prev => new Set(prev).add(pkg));
      }
    } catch {}
    setToggling(null);
  }, []);

  const blockedCount = blockedPackages.size;

  if (!isNative) {
    return (
      <View style={s.empty}>
        <Text style={s.emptyText}>Blocked apps requires Android device</Text>
      </View>
    );
  }

  if (!serviceAvailable) {
    return (
      <View style={s.empty}>
        <Ionicons name="lock-closed-outline" size={36} color={DIM} />
        <Text style={s.emptyText}>Accessibility service must be enabled to manage blocked apps</Text>
        <Pressable style={s.settingsBtn} onPress={() => { UltraDevLog.push('CHAIN', { component: 'BlockedAppsTab', action: 'open_accessibility', trigger: {}, state: {}, data: {}, outcome: 'opening_settings' }); AppController.openAccessibilitySettings(); }}>
          <Text style={s.settingsBtnText}>Open Accessibility Settings</Text>
        </Pressable>
      </View>
    );
  }

  const renderApp = ({ item }: ListRenderItemInfo<InstalledApp>) => {
    const isBlocked = blockedPackages.has(item.packageName);
    const isToggling = toggling === item.packageName;
    return (
      <View style={s.row}>
        <View style={s.appInfo}>
          <Text style={s.appName} numberOfLines={1}>{item.appName}</Text>
          <Text style={s.appPkg} numberOfLines={1}>{item.packageName}</Text>
        </View>
        {isToggling ? (
          <ActivityIndicator size="small" color={ACCENT} />
        ) : (
          <Switch
            value={isBlocked}
            onValueChange={() => toggleBlock(item.packageName, isBlocked)}
            trackColor={{ false: SURFACE2, true: DANGER + '99' }}
            thumbColor={isBlocked ? DANGER : DIM}
          />
        )}
      </View>
    );
  };

  return (
    <View style={s.container} testID="BlockedAppsTab">
      <View style={s.noticeBox}>
        <Ionicons name="warning-outline" size={14} color="#fbbf24" />
        <Text style={s.noticeText}>
          Agent Ultra will refuse to interact with blocked apps via accessibility.
          This does NOT prevent you from using those apps normally.
        </Text>
      </View>

      <View style={s.headerRow}>
        <Text style={s.headerTitle}>Installed Apps</Text>
        {blockedCount > 0 && (
          <View style={s.blockedBadge}>
            <Text style={s.blockedBadgeText}>{blockedCount} blocked</Text>
          </View>
        )}
      </View>

      <View style={s.searchRow}>
        <Ionicons name="search" size={16} color={DIM} />
        <TextInput
          style={s.searchInput}
          value={searchQuery}
          onChangeText={setSearchQuery}
          placeholder="Filter apps..."
          placeholderTextColor={DIM}
          autoCapitalize="none"
          autoCorrect={false}
          clearButtonMode="while-editing"
        />
      </View>

      {loading ? (
        <ActivityIndicator color={ACCENT} style={{ marginTop: 32 }} />
      ) : (
        <FlatList
          data={filteredApps}
          keyExtractor={item => item.packageName}
          renderItem={renderApp}
          style={s.list}
          keyboardShouldPersistTaps="handled"
          initialNumToRender={30}
          maxToRenderPerBatch={30}
          windowSize={10}
          ListEmptyComponent={
            <View style={s.empty}>
              <Text style={s.emptyText}>
                {searchQuery ? 'No apps match your search' : 'No apps found'}
              </Text>
            </View>
          }
        />
      )}
    </View>
  );
}

const s = StyleSheet.create({
  container: { flex: 1, backgroundColor: BG },
  noticeBox: {
    flexDirection: 'row', alignItems: 'flex-start', gap: 8,
    backgroundColor: '#1a1500', borderRadius: 10, padding: 12,
    marginHorizontal: 16, marginTop: 12, marginBottom: 8,
    borderWidth: 1, borderColor: '#3a3000',
  },
  noticeText: { color: '#fbbf24', fontSize: 12, flex: 1, lineHeight: 17 },
  headerRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 16, paddingBottom: 8,
  },
  headerTitle: { color: TEXT, fontSize: 15, fontWeight: '600' },
  blockedBadge: {
    backgroundColor: DANGER + '22', borderRadius: 10, paddingHorizontal: 10, paddingVertical: 3,
    borderWidth: 1, borderColor: DANGER + '55',
  },
  blockedBadgeText: { color: DANGER, fontSize: 12, fontWeight: '600' },
  searchRow: {
    flexDirection: 'row', alignItems: 'center', gap: 8,
    backgroundColor: SURFACE, borderRadius: 10, marginHorizontal: 16, marginBottom: 8,
    paddingHorizontal: 12, paddingVertical: 8, borderWidth: 1, borderColor: SURFACE2,
  },
  searchInput: { flex: 1, color: TEXT, fontSize: 14 },
  list: { flex: 1 },
  row: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    paddingHorizontal: 16, paddingVertical: 10,
    borderBottomWidth: 1, borderBottomColor: SURFACE,
  },
  appInfo: { flex: 1, marginRight: 10 },
  appName: { color: TEXT, fontSize: 14 },
  appPkg: { color: DIM, fontSize: 11, marginTop: 1 },
  empty: { alignItems: 'center', justifyContent: 'center', paddingTop: 48, gap: 12 },
  emptyText: { color: DIM, fontSize: 14, textAlign: 'center', maxWidth: 260 },
  settingsBtn: {
    backgroundColor: ACCENT, borderRadius: 10,
    paddingHorizontal: 18, paddingVertical: 10, marginTop: 8,
  },
  settingsBtnText: { color: BG, fontWeight: '700', fontSize: 14 },
});
