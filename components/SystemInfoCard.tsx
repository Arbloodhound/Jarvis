import React, { useEffect, useState } from 'react';
import { View, Text, StyleSheet, Platform } from 'react-native';
import { Ionicons, MaterialCommunityIcons } from '@expo/vector-icons';
import * as Battery from 'expo-battery';
import type { SystemInfoData } from '@/src/services/SystemInfoService';

const ACCENT = '#e5e5e5';
const SURFACE2 = '#1a1a1a';
const DIM = '#666666';

function statusColor(percent: number, inverted = false): string {
  const val = inverted ? 100 - percent : percent;
  if (val >= 70) return '#e5e5e5';
  if (val >= 30) return '#fbbf24';
  return '#ef4444';
}

function batteryIconName(level: number, charging: boolean): string {
  if (charging) return 'battery-charging';
  if (level >= 90) return 'battery-full';
  if (level >= 60) return 'battery-three-quarters';
  if (level >= 30) return 'battery-half';
  if (level >= 10) return 'battery-quarter';
  return 'battery-empty';
}

interface BarProps {
  label: string;
  value: number;
  maxValue: number;
  unit: string;
  color: string;
  icon: string;
  iconFamily?: 'ionicons' | 'material';
}

function MetricBar({ label, value, maxValue, unit, color, icon, iconFamily = 'ionicons' }: BarProps) {
  const percent = maxValue > 0 ? Math.min(100, Math.round((value / maxValue) * 100)) : 0;
  return (
    <View style={barStyles.row}>
      <View style={barStyles.labelRow}>
        {iconFamily === 'material' ? (
          <MaterialCommunityIcons name={icon as any} size={14} color={color} />
        ) : (
          <Ionicons name={icon as any} size={14} color={color} />
        )}
        <Text style={barStyles.label}>{label}</Text>
        <Text style={[barStyles.value, { color }]}>{percent}%</Text>
      </View>
      <View style={barStyles.track}>
        <View style={[barStyles.fill, { width: `${percent}%`, backgroundColor: color }]} />
      </View>
      <Text style={barStyles.detail}>
        {formatSize(value)} / {formatSize(maxValue)} {unit}
      </Text>
    </View>
  );
}

function formatSize(mb: number): string {
  if (mb >= 1024) return (mb / 1024).toFixed(1) + ' GB';
  return mb + ' MB';
}

interface Props {
  data: SystemInfoData;
}

export default function SystemInfoCard({ data }: Props) {
  const [batteryLevel, setBatteryLevel] = useState(data.battery.level);
  const [batteryState, setBatteryState] = useState(data.battery.state);

  useEffect(() => {
    if (Platform.OS === 'web') return;
    let levelSub: any;
    let stateSub: any;
    try {
      levelSub = Battery.addBatteryLevelListener(({ batteryLevel: bl }) => {
        setBatteryLevel(Math.round(bl * 100));
      });
      stateSub = Battery.addBatteryStateListener(({ batteryState: bs }) => {
        const stateStr = (['Unknown', 'Unplugged', 'Charging', 'Full'] as const)[bs] ?? 'Unknown';
        setBatteryState(stateStr);
      });
    } catch {}
    return () => {
      try { levelSub?.remove(); } catch {}
      try { stateSub?.remove(); } catch {}
    };
  }, []);

  const isCharging = batteryState === 'Charging';
  const battColor = statusColor(batteryLevel, false);
  const ramColor = statusColor(100 - data.memory.usedPercent, false);
  const storageColor = statusColor(100 - data.storage.usedPercent, false);

  return (
    <View style={styles.card} testID="SystemInfoCard">
      <View style={styles.header}>
        <MaterialCommunityIcons name="monitor-dashboard" size={16} color={ACCENT} />
        <Text style={styles.title}>System Info</Text>
        <Text style={styles.timestamp}>
          {new Date(data.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
        </Text>
      </View>

      <View style={styles.batteryRow}>
        <MaterialCommunityIcons
          name={batteryIconName(batteryLevel, isCharging) as any}
          size={28}
          color={battColor}
        />
        <Text style={[styles.batteryLevel, { color: battColor }]}>{batteryLevel}%</Text>
        <Text style={styles.batteryState}>
          {batteryState}{data.battery.lowPowerMode ? ' · Low Power' : ''}
        </Text>
        {isCharging && (
          <Ionicons name="flash" size={14} color="#fbbf24" style={{ marginLeft: 4 }} />
        )}
      </View>

      <View style={styles.metricsSection}>
        {data.memory.totalMB > 0 && (
          <MetricBar
            label="RAM"
            value={data.memory.usedMB}
            maxValue={data.memory.totalMB}
            unit=""
            color={ramColor}
            icon="memory"
            iconFamily="material"
          />
        )}
        {data.storage.totalMB > 0 && (
          <MetricBar
            label="Storage"
            value={data.storage.totalMB - data.storage.freeMB}
            maxValue={data.storage.totalMB}
            unit=""
            color={storageColor}
            icon="folder"
            iconFamily="material"
          />
        )}
      </View>

      <View style={styles.infoGrid}>
        <InfoItem
          icon="phone-portrait-outline"
          label="Device"
          value={data.device.model}
        />
        <InfoItem
          icon="logo-android"
          label="OS"
          value={`${data.device.os} ${data.device.osVersion}`}
        />
        <InfoItem
          icon="wifi"
          label="Network"
          value={data.network.connected ? data.network.type : 'Disconnected'}
          valueColor={data.network.connected ? '#e5e5e5' : '#ef4444'}
        />
        <InfoItem
          icon="resize"
          label="Screen"
          value={`${data.screen.width} × ${data.screen.height}`}
        />
        {data.cpuTemp !== null && (
          <InfoItem
            icon="thermometer"
            label="CPU Temp"
            value={`${data.cpuTemp}°C`}
            valueColor={data.cpuTemp > 45 ? '#ef4444' : data.cpuTemp > 35 ? '#fbbf24' : '#e5e5e5'}
          />
        )}
        {data.processCount !== null && (
          <InfoItem
            icon="layers"
            label="Processes"
            value={String(data.processCount)}
          />
        )}
      </View>
    </View>
  );
}

function InfoItem({ icon, label, value, valueColor }: { icon: string; label: string; value: string; valueColor?: string }) {
  return (
    <View style={styles.infoItem}>
      <Ionicons name={icon as any} size={12} color={DIM} />
      <Text style={styles.infoLabel}>{label}</Text>
      <Text style={[styles.infoValue, valueColor ? { color: valueColor } : undefined]} numberOfLines={1}>
        {value}
      </Text>
    </View>
  );
}

const barStyles = StyleSheet.create({
  row: { marginBottom: 10 },
  labelRow: { flexDirection: 'row', alignItems: 'center', marginBottom: 4 },
  label: { color: '#ccc', fontSize: 12, marginLeft: 6, flex: 1 },
  value: { fontSize: 12, fontWeight: '600' as const },
  track: { height: 6, backgroundColor: '#222', borderRadius: 3, overflow: 'hidden' },
  fill: { height: '100%', borderRadius: 3 },
  detail: { color: DIM, fontSize: 10, marginTop: 2 },
});

const styles = StyleSheet.create({
  card: {
    backgroundColor: '#0d0d0d',
    borderRadius: 12,
    borderWidth: 1,
    borderColor: '#1f1f1f',
    padding: 14,
    marginTop: 6,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 12,
  },
  title: {
    color: ACCENT,
    fontSize: 13,
    fontWeight: '700' as const,
    marginLeft: 6,
    flex: 1,
    letterSpacing: 0.5,
    textTransform: 'uppercase' as const,
  },
  timestamp: {
    color: DIM,
    fontSize: 10,
  },
  batteryRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 14,
    paddingVertical: 6,
    paddingHorizontal: 10,
    backgroundColor: '#111',
    borderRadius: 8,
  },
  batteryLevel: {
    fontSize: 22,
    fontWeight: '700' as const,
    marginLeft: 8,
  },
  batteryState: {
    color: DIM,
    fontSize: 12,
    marginLeft: 8,
  },
  metricsSection: {
    marginBottom: 8,
  },
  infoGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    borderTopWidth: 1,
    borderTopColor: '#1a1a1a',
    paddingTop: 10,
  },
  infoItem: {
    flexDirection: 'row',
    alignItems: 'center',
    width: '50%',
    paddingVertical: 4,
  },
  infoLabel: {
    color: DIM,
    fontSize: 10,
    marginLeft: 4,
    marginRight: 4,
  },
  infoValue: {
    color: '#ccc',
    fontSize: 11,
    fontWeight: '500' as const,
    flex: 1,
  },
});
