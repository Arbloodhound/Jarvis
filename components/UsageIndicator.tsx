import React, { useState } from "react";
import {
  View,
  Text,
  Pressable,
  StyleSheet,
  Modal,
  Animated,
  TouchableWithoutFeedback,
  ScrollView,
} from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { Ionicons } from "@expo/vector-icons";
import { UltraDevLog } from "@/src/utils/UltraDevLog";

const ACCENT = "#e5e5e5";
const SURFACE = "#111111";
const SURFACE2 = "#1a1a1a";
const SURFACE3 = "#222222";
const DIM = "#666666";
const TEXT = "#e0e0e0";

export interface ModelUsage {
  modelId: string;
  modelName: string;
  apiName: string;
  calls: number;
  inputTokens: number;
  outputTokens: number;
  cost: number;
}

interface UsageIndicatorProps {
  totalCost: number;
  totalCalls: number;
  modelUsages: ModelUsage[];
  dailyLimit: number;
}

export default function UsageIndicator({
  totalCost,
  totalCalls,
  modelUsages,
  dailyLimit,
}: UsageIndicatorProps) {
  const insets = useSafeAreaInsets();
  const [expanded, setExpanded] = useState(false);
  const fadeAnim = React.useRef(new Animated.Value(0)).current;
  const slideAnim = React.useRef(new Animated.Value(400)).current;

  React.useEffect(() => {
    if (expanded) {
      Animated.parallel([
        Animated.timing(fadeAnim, { toValue: 1, duration: 180, useNativeDriver: true }),
        Animated.spring(slideAnim, { toValue: 0, tension: 65, friction: 11, useNativeDriver: true }),
      ]).start();
    } else {
      Animated.parallel([
        Animated.timing(fadeAnim, { toValue: 0, duration: 120, useNativeDriver: true }),
        Animated.timing(slideAnim, { toValue: 400, duration: 120, useNativeDriver: true }),
      ]).start();
    }
  }, [expanded]);

  const limitLabel = dailyLimit > 0
    ? `$${totalCost.toFixed(4)} / $${dailyLimit.toFixed(2)}`
    : `$${totalCost.toFixed(4)}`;

  const limitPercent = dailyLimit > 0 ? Math.min(100, (totalCost / dailyLimit) * 100) : 0;
  const isOverBudget = dailyLimit > 0 && totalCost >= dailyLimit;

  return (
    <>
      <Pressable onPress={() => { UltraDevLog.push('CHAIN', { component: 'UsageIndicator', action: 'expand', trigger: {}, state: { totalCost, totalCalls }, data: { modelCount: modelUsages.length }, outcome: 'sheet_opened' }); setExpanded(true); }} style={styles.pill}>
        <Ionicons
          name="pulse-outline"
          size={14}
          color={isOverBudget ? "#ef4444" : ACCENT}
        />
        <Text style={[styles.pillText, isOverBudget && { color: "#ef4444" }]}>
          {limitLabel}
        </Text>
        <Text style={styles.pillCalls}>{totalCalls} calls</Text>
      </Pressable>
      <Text style={styles.costNote}>Estimated costs — may be included in your API plan</Text>

      <Modal visible={expanded} transparent animationType="none" onRequestClose={() => setExpanded(false)} statusBarTranslucent>
        <View style={styles.sheetRoot}>
          <TouchableWithoutFeedback onPress={() => { UltraDevLog.push('CHAIN', { component: 'UsageIndicator', action: 'backdrop_close', trigger: {}, state: { totalCost }, data: {}, outcome: 'sheet_dismissed' }); setExpanded(false); }}>
            <Animated.View style={[styles.backdrop, { opacity: fadeAnim }]} />
          </TouchableWithoutFeedback>

          <Animated.View
            style={[
              styles.sheet,
              {
                transform: [{ translateY: slideAnim }],
                paddingBottom: Math.max(insets.bottom, 16),
              },
            ]}
          >
            <View style={styles.handleBar}>
              <View style={styles.handle} />
            </View>

            <View style={styles.sheetHeader}>
              <Text style={styles.sheetTitle}>Usage</Text>
              <Pressable onPress={() => { UltraDevLog.push('CHAIN', { component: 'UsageIndicator', action: 'close_btn', trigger: {}, state: { totalCost }, data: {}, outcome: 'sheet_dismissed' }); setExpanded(false); }} hitSlop={12}>
                <Ionicons name="close" size={20} color={DIM} />
              </Pressable>
            </View>

            <View style={styles.summaryRow}>
              <View style={styles.summaryItem}>
                <Text style={styles.summaryValue}>${totalCost.toFixed(4)}</Text>
                <Text style={styles.summaryLabel}>Total spent</Text>
              </View>
              <View style={styles.summaryItem}>
                <Text style={styles.summaryValue}>{totalCalls}</Text>
                <Text style={styles.summaryLabel}>API calls</Text>
              </View>
              <View style={styles.summaryItem}>
                <Text style={styles.summaryValue}>
                  {dailyLimit > 0 ? `$${dailyLimit.toFixed(2)}` : "None"}
                </Text>
                <Text style={styles.summaryLabel}>Daily limit</Text>
              </View>
            </View>

            {dailyLimit > 0 && (
              <View style={styles.progressWrap}>
                <View style={styles.progressTrack}>
                  <View
                    style={[
                      styles.progressFill,
                      {
                        width: `${Math.min(100, limitPercent)}%`,
                        backgroundColor: isOverBudget ? "#ef4444" : ACCENT,
                      },
                    ]}
                  />
                </View>
                <Text style={styles.progressLabel}>{limitPercent.toFixed(1)}% used</Text>
              </View>
            )}

            <Text style={styles.sectionLabel}>By model</Text>
            <ScrollView style={styles.modelList} showsVerticalScrollIndicator={false}>
              {modelUsages.length === 0 ? (
                <Text style={styles.emptyText}>No usage data yet</Text>
              ) : (
                modelUsages
                  .sort((a, b) => b.cost - a.cost)
                  .map((mu) => (
                    <View key={mu.modelId} style={styles.modelRow}>
                      <View style={styles.modelInfo}>
                        <Text style={styles.modelName} numberOfLines={1}>
                          {mu.modelName}
                        </Text>
                        <Text style={styles.modelApi}>{mu.apiName}</Text>
                      </View>
                      <View style={styles.modelStats}>
                        <Text style={styles.modelCost}>${mu.cost.toFixed(4)}</Text>
                        <Text style={styles.modelCalls}>{mu.calls} calls</Text>
                      </View>
                    </View>
                  ))
              )}
            </ScrollView>
          </Animated.View>
        </View>
      </Modal>
    </>
  );
}

const styles = StyleSheet.create({
  pill: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    backgroundColor: SURFACE2,
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 16,
    borderWidth: 1,
    borderColor: SURFACE3,
  },
  pillText: {
    color: TEXT,
    fontSize: 12,
    fontFamily: "Inter_500Medium",
  },
  pillCalls: {
    color: DIM,
    fontSize: 10,
    fontFamily: "Inter_400Regular",
  },
  costNote: {
    color: "#444",
    fontSize: 10,
    fontFamily: "Inter_400Regular",
    marginTop: 4,
    textAlign: "center" as const,
  },
  sheetRoot: { flex: 1, justifyContent: "flex-end" },
  backdrop: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: "rgba(0,0,0,0.55)",
  },
  sheet: {
    backgroundColor: SURFACE,
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    paddingHorizontal: 20,
    maxHeight: "60%",
  },
  handleBar: { alignItems: "center", paddingTop: 10, paddingBottom: 4 },
  handle: { width: 36, height: 4, borderRadius: 2, backgroundColor: SURFACE3 },
  sheetHeader: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    paddingVertical: 10,
  },
  sheetTitle: { color: TEXT, fontSize: 18, fontFamily: "Inter_700Bold" },
  summaryRow: {
    flexDirection: "row",
    justifyContent: "space-around",
    paddingVertical: 14,
    borderBottomWidth: 1,
    borderBottomColor: SURFACE3,
    marginBottom: 12,
  },
  summaryItem: { alignItems: "center" },
  summaryValue: { color: TEXT, fontSize: 16, fontFamily: "Inter_700Bold" },
  summaryLabel: { color: DIM, fontSize: 11, fontFamily: "Inter_400Regular", marginTop: 2 },
  progressWrap: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    marginBottom: 14,
  },
  progressTrack: {
    flex: 1,
    height: 6,
    borderRadius: 3,
    backgroundColor: SURFACE3,
    overflow: "hidden",
  },
  progressFill: {
    height: 6,
    borderRadius: 3,
  },
  progressLabel: {
    color: DIM,
    fontSize: 11,
    fontFamily: "Inter_500Medium",
    minWidth: 55,
    textAlign: "right",
  },
  sectionLabel: {
    color: DIM,
    fontSize: 11,
    fontFamily: "Inter_600SemiBold",
    textTransform: "uppercase",
    letterSpacing: 0.5,
    marginBottom: 8,
  },
  modelList: { flex: 1 },
  modelRow: {
    flexDirection: "row",
    justifyContent: "space-between",
    alignItems: "center",
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: SURFACE3,
  },
  modelInfo: { flex: 1, marginRight: 12 },
  modelName: { color: TEXT, fontSize: 13, fontFamily: "Inter_500Medium" },
  modelApi: { color: DIM, fontSize: 10, fontFamily: "Inter_400Regular", marginTop: 1 },
  modelStats: { alignItems: "flex-end" },
  modelCost: { color: TEXT, fontSize: 13, fontFamily: "Inter_600SemiBold" },
  modelCalls: { color: DIM, fontSize: 10, fontFamily: "Inter_400Regular", marginTop: 1 },
  emptyText: { color: DIM, fontSize: 13, fontFamily: "Inter_400Regular", textAlign: "center", paddingVertical: 20 },
});
