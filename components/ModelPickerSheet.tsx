import React, { useCallback, useEffect } from "react";
import {
  View,
  Text,
  FlatList,
  Pressable,
  StyleSheet,
  Modal,
  Animated,
  TouchableWithoutFeedback,
  Dimensions,
} from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { Ionicons } from "@expo/vector-icons";
import { UltraDevLog } from "@/src/utils/UltraDevLog";

const ACCENT = "#e5e5e5";
const BG = "#000000";
const SURFACE = "#111111";
const SURFACE3 = "#222222";
const DIM = "#666666";
const TEXT = "#e0e0e0";
const SHEET_MAX_HEIGHT = Dimensions.get("window").height * 0.55;

export interface SavedApi {
  id: string;
  name: string;
  baseUrl: string;
  apiKey?: string;
  password?: string;
}

export interface PickerModel {
  id: string;
  name: string;
  type: "text" | "image" | "code" | "video" | "embedding" | "reasoning";
  apiName: string;
  costIndicator?: string;
  isSelected: boolean;
}

interface ModelPickerSheetProps {
  visible: boolean;
  models: PickerModel[];
  currentModelId: string;
  onSelect: (modelId: string) => void;
  onClose: () => void;
  logContext?: {
    currentMode: string;
    defaultModel: string | null;
    selectedModel: string | null;
    routerSelectedModel?: string | null;
    uiSelectedModel?: string | null;
    source: "provider_bridge" | "legacy_cache" | "mixed" | "empty";
  };
}

export default function ModelPickerSheet({
  visible,
  models,
  currentModelId,
  onSelect,
  onClose,
  logContext,
}: ModelPickerSheetProps) {
  const insets = useSafeAreaInsets();
  const fadeAnim = React.useRef(new Animated.Value(0)).current;
  const slideAnim = React.useRef(new Animated.Value(SHEET_MAX_HEIGHT)).current;

  const ctx = logContext;
  const ctxCurrentMode = ctx?.currentMode ?? "unknown";
  const ctxDefaultModel = ctx?.defaultModel ?? null;
  const ctxSelectedModel = ctx?.selectedModel ?? currentModelId ?? null;
  const ctxUiSelectedModel = ctx?.uiSelectedModel ?? ctxSelectedModel;
  const ctxRouterSelectedModel = ctx?.routerSelectedModel ?? null;
  const ctxSource = ctx?.source ?? (models.length === 0 ? "empty" : "provider_bridge");

  const mountedAtRef = React.useRef(Date.now());

  React.useEffect(() => {
    if (visible) {
      const slideVal = (slideAnim as any)._value ?? SHEET_MAX_HEIGHT;
      UltraDevLog.pickerOpenDetailed({
        requestedFilter: "all",
        effectiveFilter: "all",
        currentMode: ctxCurrentMode,
        totalModels: models.length,
        rawFilteredCount: models.length,
        displayedCount: models.length,
        fallbackUsed: false,
        source: ctxSource,
        selectedModel: ctxSelectedModel,
        uiSelectedModel: ctxUiSelectedModel,
        routerSelectedModel: ctxRouterSelectedModel,
        defaultModel: ctxDefaultModel,
        slideAnimCurrentValue: slideVal,
        note: slideVal > 0 && slideVal < 100 ? "WARN: slideAnim partially open before reset" : "ok",
      });
      slideAnim.setValue(SHEET_MAX_HEIGHT);
      fadeAnim.setValue(0);
      const currentSlide = (slideAnim as any)._value ?? -1;
      UltraDevLog.pickerAnimate("open", currentSlide, 0, "spring");
      Animated.parallel([
        Animated.timing(fadeAnim, { toValue: 1, duration: 200, useNativeDriver: true }),
        Animated.spring(slideAnim, { toValue: 0, tension: 65, friction: 11, useNativeDriver: true }),
      ]).start((result) => {
        if (!result.finished) {
          UltraDevLog.error(
            "ModelPickerSheet",
            "Open animation did not finish",
            `started=${currentSlide} visible=${visible}`
          );
        }
      });
    } else {
      const currentSlide = (slideAnim as any)._value ?? -1;
      UltraDevLog.pickerAnimate(
        "close",
        currentSlide,
        SHEET_MAX_HEIGHT,
        "timing",
        mountedAtRef.current
      );
      Animated.parallel([
        Animated.timing(fadeAnim, { toValue: 0, duration: 150, useNativeDriver: true }),
        Animated.timing(slideAnim, { toValue: SHEET_MAX_HEIGHT, duration: 150, useNativeDriver: true }),
      ]).start();
    }
  }, [visible]);

  // Sort so the currently-selected model appears first.
  const displayedModels = [...models].sort((a, b) => {
    const aActive = a.id === currentModelId ? 1 : 0;
    const bActive = b.id === currentModelId ? 1 : 0;
    return bActive - aActive;
  });

  // Log picker content state once when the picker becomes visible or model list changes.
  useEffect(() => {
    if (!visible) return;
    UltraDevLog.pickerContentDetailed({
      requestedFilter: "all",
      effectiveFilter: "all",
      currentMode: ctxCurrentMode,
      rawFilteredCount: models.length,
      displayedCount: displayedModels.length,
      totalModels: models.length,
      fallbackUsed: false,
      source: ctxSource,
      selectedModel: ctxSelectedModel,
      uiSelectedModel: ctxUiSelectedModel,
      routerSelectedModel: ctxRouterSelectedModel,
      defaultModel: ctxDefaultModel,
      note: `ok — ${displayedModels.length} models`,
    });
    UltraDevLog.push("PICKER_RENDER" as any, {
      event: "picker_content_rendered",
      rawCount: models.length,
      displayedCount: displayedModels.length,
      currentModelId,
      currentModelInList: displayedModels.some((m) => m.id === currentModelId),
    });
  }, [visible, models.length, currentModelId]);

  const renderModel = useCallback(
    ({ item }: { item: PickerModel }) => {
      const isActive = item.id === currentModelId;
      return (
        <Pressable
          onPress={() => {
            UltraDevLog.push("CHAIN", {
              component: "ModelPickerSheet",
              action: "model_select",
              trigger: { modelId: item.id },
              state: { prev: currentModelId },
              data: { modelName: item.name, type: item.type },
              outcome: item.id === currentModelId ? "same_model_reselected" : "model_changed",
            });
            try {
              onSelect(item.id);
              UltraDevLog.push("EFFECT", {
                component: "ModelPickerSheet",
                action: "model_select_dispatched",
                modelId: item.id,
                dispatched: true,
              });
            } catch (selErr: any) {
              UltraDevLog.push("EFFECT", {
                component: "ModelPickerSheet",
                action: "model_select_dispatched",
                modelId: item.id,
                dispatched: false,
                error: selErr?.message,
              });
            }
            onClose();
          }}
          style={({ pressed }) => [
            styles.modelRow,
            isActive && styles.modelRowActive,
            pressed && styles.modelRowPressed,
          ]}
        >
          <View style={styles.modelInfo}>
            <Text style={[styles.modelName, isActive && styles.modelNameActive]} numberOfLines={1}>
              {item.name}
            </Text>
            <View style={styles.modelBadges}>
              <View style={styles.typeBadge}>
                <Text style={styles.typeBadgeText}>{item.type}</Text>
              </View>
              <Text style={styles.apiLabel}>{item.apiName}</Text>
              {item.costIndicator && item.costIndicator !== "Free" && (
                <View style={styles.costBadge}>
                  <Text style={styles.costBadgeText}>{item.costIndicator}</Text>
                </View>
              )}
            </View>
          </View>
          <View style={[styles.radioOuter, isActive && styles.radioOuterActive]}>
            {isActive && <View style={styles.radioInner} />}
          </View>
        </Pressable>
      );
    },
    [currentModelId, onSelect, onClose]
  );

  return (
    <Modal
      visible={visible}
      transparent
      animationType="none"
      onRequestClose={onClose}
      statusBarTranslucent
    >
      <View style={styles.root} testID="ModelPickerSheet">
        <TouchableWithoutFeedback onPress={onClose}>
          <Animated.View style={[styles.backdrop, { opacity: fadeAnim }]} />
        </TouchableWithoutFeedback>

        <Animated.View
          style={[
            styles.sheet,
            {
              transform: [{ translateY: slideAnim }],
              paddingBottom: Math.max(insets.bottom, 16),
              maxHeight: SHEET_MAX_HEIGHT,
              height: SHEET_MAX_HEIGHT,
            },
          ]}
        >
          <View style={styles.handleBar}>
            <View style={styles.handle} />
          </View>

          <View style={styles.sheetHeader}>
            <Text style={styles.sheetTitle}>Models</Text>
            <Pressable onPress={onClose} hitSlop={12}>
              <Ionicons name="close" size={20} color={DIM} />
            </Pressable>
          </View>

          <FlatList
            data={displayedModels}
            renderItem={renderModel}
            keyExtractor={(item) => item.id}
            style={styles.list}
            showsVerticalScrollIndicator={false}
            ListEmptyComponent={
              <View style={styles.emptyState}>
                <Text style={styles.emptyText}>No models available</Text>
              </View>
            }
          />
        </Animated.View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  root: { flex: 1, justifyContent: "flex-end" },
  backdrop: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: "rgba(0,0,0,0.6)",
  },
  sheet: {
    backgroundColor: SURFACE,
    borderTopLeftRadius: 20,
    borderTopRightRadius: 20,
    overflow: "hidden",
  },
  handleBar: { alignItems: "center", paddingTop: 10, paddingBottom: 4 },
  handle: { width: 36, height: 4, borderRadius: 2, backgroundColor: SURFACE3 },
  sheetHeader: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingHorizontal: 20,
    paddingVertical: 10,
  },
  sheetTitle: {
    color: TEXT,
    fontSize: 18,
    fontFamily: "Inter_700Bold",
  },
  list: { flex: 1, paddingHorizontal: 12 },
  modelRow: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingVertical: 12,
    paddingHorizontal: 12,
    borderRadius: 10,
    marginBottom: 2,
  },
  modelRowActive: {
    backgroundColor: "rgba(74, 222, 128, 0.08)",
    borderWidth: 1,
    borderColor: "rgba(74, 222, 128, 0.25)",
  },
  modelRowPressed: { opacity: 0.7 },
  modelInfo: { flex: 1, marginRight: 12 },
  modelName: {
    color: TEXT,
    fontSize: 14,
    fontFamily: "Inter_500Medium",
    marginBottom: 4,
  },
  modelNameActive: { color: ACCENT },
  modelBadges: { flexDirection: "row", alignItems: "center", gap: 6 },
  typeBadge: {
    backgroundColor: SURFACE3,
    paddingHorizontal: 6,
    paddingVertical: 2,
    borderRadius: 4,
  },
  typeBadgeText: {
    color: DIM,
    fontSize: 10,
    fontFamily: "Inter_500Medium",
    textTransform: "capitalize",
  },
  apiLabel: {
    color: "#555",
    fontSize: 10,
    fontFamily: "Inter_400Regular",
  },
  costBadge: {
    backgroundColor: "#332200",
    paddingHorizontal: 5,
    paddingVertical: 1,
    borderRadius: 4,
  },
  costBadgeText: {
    color: "#ffaa44",
    fontSize: 9,
    fontFamily: "Inter_500Medium",
  },
  radioOuter: {
    width: 20,
    height: 20,
    borderRadius: 10,
    borderWidth: 2,
    borderColor: "#333",
    justifyContent: "center",
    alignItems: "center",
  },
  radioOuterActive: { borderColor: ACCENT },
  radioInner: {
    width: 10,
    height: 10,
    borderRadius: 5,
    backgroundColor: ACCENT,
  },
  emptyState: { paddingVertical: 30, alignItems: "center" },
  emptyText: { color: DIM, fontSize: 13, fontFamily: "Inter_400Regular" },
});
