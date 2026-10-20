import React, { useCallback } from "react";
import {
  View,
  Text,
  Pressable,
  StyleSheet,
  Modal,
  TouchableWithoutFeedback,
  Platform,
} from "react-native";
import { Ionicons, MaterialCommunityIcons } from "@expo/vector-icons";
import { UltraDevLog } from "@/src/utils/UltraDevLog";

const BG = "#000000";
const SURFACE = "#161616";
const SURFACE2 = "#222222";
const DIM = "#666666";
const TEXT = "#e0e0e0";
const DANGER = "#ef4444";

export interface ActionMenuItem {
  id: string;
  label: string;
  icon: string;
  iconFamily?: "ionicons" | "material";
  color?: string;
  destructive?: boolean;
  disabled?: boolean;
}

interface ActionMenuProps {
  visible: boolean;
  items: ActionMenuItem[];
  onSelect: (id: string) => void;
  onClose: () => void;
  anchorRight?: number;
  anchorTop?: number;
}

export default function ActionMenu({
  visible,
  items,
  onSelect,
  onClose,
  anchorRight = 12,
  anchorTop = 54,
}: ActionMenuProps) {
  const handleSelect = useCallback(
    (id: string) => {
      UltraDevLog.push('CHAIN', { component: 'ActionMenu', action: 'item_select', trigger: { itemId: id }, state: {}, data: {}, outcome: 'selected' });
      onClose();
      setTimeout(() => onSelect(id), 100);
    },
    [onSelect, onClose]
  );

  if (!visible) return null;

  return (
    <Modal visible={visible} transparent animationType="fade" onRequestClose={onClose} statusBarTranslucent>
      <TouchableWithoutFeedback onPress={() => { UltraDevLog.push('CHAIN', { component: 'ActionMenu', action: 'backdrop_close', trigger: {}, state: {}, data: {}, outcome: 'dismissed' }); onClose(); }}>
        <View style={styles.backdrop} testID="ActionMenu">
          <TouchableWithoutFeedback>
            <View style={[styles.menu, { right: anchorRight, top: anchorTop }]}>
              {items.map((item, index) => {
                const color = item.destructive ? DANGER : item.color || TEXT;
                const isLast = index === items.length - 1;

                return (
                  <Pressable
                    key={item.id}
                    onPress={() => handleSelect(item.id)}
                    disabled={item.disabled}
                    style={({ pressed }) => [
                      styles.menuItem,
                      !isLast && styles.menuItemBorder,
                      pressed && styles.menuItemPressed,
                      item.disabled && styles.menuItemDisabled,
                    ]}
                  >
                    <Text
                      style={[
                        styles.menuItemText,
                        { color },
                        item.disabled && styles.menuItemTextDisabled,
                      ]}
                    >
                      {item.label}
                    </Text>
                    {item.iconFamily === "material" ? (
                      <MaterialCommunityIcons
                        name={item.icon as any}
                        size={18}
                        color={item.disabled ? "#333" : color}
                      />
                    ) : (
                      <Ionicons
                        name={item.icon as any}
                        size={18}
                        color={item.disabled ? "#333" : color}
                      />
                    )}
                  </Pressable>
                );
              })}
            </View>
          </TouchableWithoutFeedback>
        </View>
      </TouchableWithoutFeedback>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: {
    flex: 1,
  },
  menu: {
    position: "absolute",
    backgroundColor: SURFACE,
    borderRadius: 12,
    minWidth: 200,
    paddingVertical: 4,
    ...Platform.select({
      ios: {
        shadowColor: "#000",
        shadowOffset: { width: 0, height: 8 },
        shadowOpacity: 0.4,
        shadowRadius: 16,
      },
      android: { elevation: 12 },
      web: { boxShadow: "0 8px 32px rgba(0,0,0,0.5)" },
    }),
    borderWidth: 1,
    borderColor: "#222",
  },
  menuItem: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingVertical: 13,
    paddingHorizontal: 16,
  },
  menuItemBorder: {
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: "#222",
  },
  menuItemPressed: {
    backgroundColor: "rgba(255,255,255,0.04)",
  },
  menuItemDisabled: {
    opacity: 0.35,
  },
  menuItemText: {
    fontSize: 15,
    fontFamily: "Inter_400Regular",
    marginRight: 16,
  },
  menuItemTextDisabled: {
    color: "#444",
  },
});
