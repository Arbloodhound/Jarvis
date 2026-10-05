import React, { useCallback, useRef, useEffect, useState } from "react";
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
  Alert,
  Platform,
  TextInput,
} from "react-native";
import { useSafeAreaInsets } from "react-native-safe-area-context";
import { Ionicons, MaterialCommunityIcons } from "@expo/vector-icons";
import type { ConversationMeta } from "@/src/types/ultra";
import { AppStorage } from "@/src/utils/AppStorage";
import { UltraDevLog } from "@/src/utils/UltraDevLog";
import { loadZoneConfig, lookupAction } from "@/src/data/ZoneConfig";
import type { GridAction } from "@/src/types/actionGrid";

const ACCENT = "#e5e5e5";
const BG = "#000000";
const SURFACE = "#0a0a0a";
const SURFACE2 = "#141414";
const SURFACE3 = "#1e1e1e";
const DIM = "#555555";
const TEXT = "#e0e0e0";
const TEXT_DIM = "#888888";
const DRAWER_WIDTH = Dimensions.get("window").width * 0.80;

// ── Types ──────────────────────────────────────────────
interface Folder {
  id: string;
  name: string;
  isSystem: boolean; // "Logs" is system, user folders are not
}

interface ConversationListProps {
  visible: boolean;
  conversations: ConversationMeta[];
  currentConversationId: string | null;
  onSelect: (id: string) => void;
  onDelete: (id: string) => void;
  onNewChat: () => void;
  onClose: () => void;
  onOpenSettings: () => void;
  onOpenLogs: () => void;
  onQuickCommand?: (command: string) => void;
  onExecuteToggle?: (capability: string, params: Record<string, any>) => void;
  onOpenZoneEditor?: () => void;
}

const QUICK_COMMANDS = [
  { id: "status", label: "System Status", icon: "pulse-outline" as const, command: "/status" },
  { id: "cost", label: "Usage & Costs", icon: "wallet-outline" as const, command: "/cost" },
];

interface ToggleItem {
  id: string;
  label: string;
  icon: string;
  capability: string;
  params: Record<string, any>;
}

const TOGGLE_TREE: ToggleItem[] = [
  { id: 'toggle_dnd', label: 'Do Not Disturb', icon: 'moon-outline', capability: 'do_not_disturb', params: {} },
  { id: 'toggle_wifi', label: 'WiFi', icon: 'wifi', capability: 'wifi_toggle', params: {} },
  { id: 'toggle_bt', label: 'Bluetooth', icon: 'bluetooth', capability: 'bluetooth_toggle', params: {} },
  { id: 'toggle_flash', label: 'Flashlight', icon: 'flashlight-outline', capability: 'flashlight_toggle', params: {} },
  { id: 'toggle_airplane', label: 'Airplane', icon: 'airplane-outline', capability: 'airplane_mode', params: {} },
  { id: 'toggle_hotspot', label: 'Hotspot', icon: 'cellular-outline', capability: 'open_settings', params: { target: 'tethering settings' } },
  { id: 'toggle_location', label: 'Location', icon: 'location-outline', capability: 'device_location', params: {} },
  { id: 'toggle_rotate', label: 'Auto-Rotate', icon: 'phone-landscape-outline', capability: 'screen_rotate', params: {} },
  { id: 'toggle_vol_up', label: 'Vol +', icon: 'volume-high-outline', capability: 'volume_set', params: { direction: 'up' } },
  { id: 'toggle_vol_down', label: 'Vol -', icon: 'volume-low-outline', capability: 'volume_set', params: { direction: 'down' } },
  { id: 'toggle_mute', label: 'Mute', icon: 'volume-mute-outline', capability: 'volume_set', params: { level: 0 } },
  { id: 'toggle_screenshot', label: 'Screenshot', icon: 'camera-outline', capability: 'screenshot', params: {} },
];

// ── Helpers ────────────────────────────────────────────
function formatDate(ts: number): string {
  const d = new Date(ts);
  const now = new Date();
  const diff = now.getTime() - ts;
  if (diff < 60000) return "Just now";
  if (diff < 3600000) return `${Math.floor(diff / 60000)}m`;
  if (diff < 86400000) return `${Math.floor(diff / 3600000)}h`;
  if (d.getFullYear() === now.getFullYear()) {
    return d.toLocaleDateString(undefined, { month: "short", day: "numeric" });
  }
  return d.toLocaleDateString(undefined, { month: "short", day: "numeric", year: "2-digit" });
}

// ── Conversation Item ──────────────────────────────────
function ConversationItem({
  item,
  isCurrent,
  onSelect,
  onDelete,
}: {
  item: ConversationMeta;
  isCurrent: boolean;
  onSelect: (id: string) => void;
  onDelete: (id: string) => void;
}) {
  const handleLongPress = useCallback(() => {
    if (Platform.OS === "web") {
      const confirmed = window.confirm(`Delete "${item.title}"?`);
      if (confirmed) onDelete(item.id);
    } else {
      Alert.alert("Delete Conversation", `Delete "${item.title}"?`, [
        { text: "Cancel", style: "cancel" },
        { text: "Delete", style: "destructive", onPress: () => onDelete(item.id) },
      ]);
    }
  }, [item.id, item.title, onDelete]);

  const preview = item.preview
    ? item.preview.length > 50 ? item.preview.slice(0, 50) + "…" : item.preview
    : "No messages yet";

  return (
    <Pressable
      onPress={() => onSelect(item.id)}
      onLongPress={handleLongPress}
      style={({ pressed }) => [
        styles.convItem,
        isCurrent && styles.convItemActive,
        pressed && styles.convItemPressed,
      ]}
    >
      <Ionicons
        name={item.starred ? "star" : "chatbubble-outline"}
        size={16}
        color={item.starred ? "#f59e0b" : isCurrent ? ACCENT : "#333"}
        style={styles.convIcon}
      />
      <View style={styles.convContent}>
        <Text style={[styles.convTitle, isCurrent && styles.convTitleActive]} numberOfLines={1}>
          {item.title}
        </Text>
        <Text style={styles.convPreview} numberOfLines={1}>{preview}</Text>
      </View>
      <Text style={styles.convDate}>{formatDate(item.updatedAt)}</Text>
    </Pressable>
  );
}

// ── Main Component ─────────────────────────────────────
export default function ConversationList({
  visible,
  conversations,
  currentConversationId,
  onSelect,
  onDelete,
  onNewChat,
  onClose,
  onOpenSettings,
  onOpenLogs,
  onQuickCommand,
  onExecuteToggle,
  onOpenZoneEditor,
}: ConversationListProps) {
  const insets = useSafeAreaInsets();
  const slideAnim = useRef(new Animated.Value(-DRAWER_WIDTH)).current;
  const fadeAnim = useRef(new Animated.Value(0)).current;
  const webTopInset = Platform.OS === "web" ? 67 : 0;
  const webBottomInset = Platform.OS === "web" ? 34 : 0;

  // Folder state (UI-only for now — persistence comes later)
  const [folders, setFolders] = useState<Folder[]>([
    { id: "folder_logs", name: "Logs", isSystem: true },
  ]);

  useEffect(() => {
    AppStorage.get('user_folders').then(raw => {
      if (raw) {
        try {
          const saved: Folder[] = JSON.parse(raw);
          setFolders(prev => [...prev.filter(f => f.isSystem), ...saved]);
        } catch {}
      }
    }).catch(() => {});
  }, []);
  const [showNewFolder, setShowNewFolder] = useState(false);
  const [newFolderName, setNewFolderName] = useState("");
  const [toggleTreeOpen, setToggleTreeOpen] = useState(false);
  const [sidebarItems, setSidebarItems] = useState<Array<{ id: string; label: string; icon: string; capability: string; params: Record<string, any> }> | null>(null);

  // Load sidebar items from zone config
  useEffect(() => {
    loadZoneConfig().then(zc => {
      if (zc.sidebar && zc.sidebar.length > 0) {
        const resolved = zc.sidebar
          .map(id => {
            const action = lookupAction(id);
            if (!action) return null;
            return { id: `toggle_${action.id}`, label: action.label, icon: action.icon, capability: action.capability, params: action.params };
          })
          .filter(Boolean) as Array<{ id: string; label: string; icon: string; capability: string; params: Record<string, any> }>;
        if (resolved.length > 0) {
          setSidebarItems(resolved);
          UltraDevLog.push('EFFECT', {
            component: 'ConversationList', action: 'sidebar_zone_loaded',
            itemCount: resolved.length, source: 'zone_config', success: true,
          });
        } else {
          UltraDevLog.push('EFFECT', {
            component: 'ConversationList', action: 'sidebar_zone_loaded',
            itemCount: 0, source: 'fallback_toggle_tree', success: true,
            note: 'Zone sidebar empty or all IDs orphaned — using TOGGLE_TREE',
          });
        }
      }
    }).catch(() => {
      UltraDevLog.push('EFFECT', {
        component: 'ConversationList', action: 'sidebar_zone_loaded',
        source: 'fallback_toggle_tree', success: false,
        note: 'Zone config load failed — using TOGGLE_TREE',
      });
    });
  }, []);

  useEffect(() => {
    if (visible) {
      Animated.parallel([
        Animated.spring(slideAnim, { toValue: 0, tension: 65, friction: 11, useNativeDriver: true }),
        Animated.timing(fadeAnim, { toValue: 1, duration: 200, useNativeDriver: true }),
      ]).start();
      UltraDevLog.push('SIDEBAR_OPEN', { convCount: conversations.length, folderCount: folders.length });
    } else {
      Animated.parallel([
        Animated.timing(slideAnim, { toValue: -DRAWER_WIDTH, duration: 200, useNativeDriver: true }),
        Animated.timing(fadeAnim, { toValue: 0, duration: 180, useNativeDriver: true }),
      ]).start();
    }
  }, [visible]);

  const handleCreateFolder = useCallback(async () => {
    const name = newFolderName.trim();
    if (!name) {
      UltraDevLog.push('CHAIN', { component: 'ConversationList', action: 'create_folder', trigger: {}, state: { folderCount: folders.length }, data: { name: '' }, outcome: 'EMPTY:no_name_cancelled' });
      return;
    }
    const id = `folder_${Date.now()}_${Math.random().toString(36).slice(2, 6)}`;
    const newFolder: Folder = { id, name, isSystem: false };
    const updated = [...folders, newFolder];
    setFolders(updated);
    setNewFolderName("");
    setShowNewFolder(false);
    UltraDevLog.push('CHAIN', { component: 'ConversationList', action: 'create_folder', trigger: {}, state: { folderCount: folders.length }, data: { name, id }, outcome: 'folder_created' });
    try {
      await AppStorage.set('user_folders', JSON.stringify(updated.filter(f => !f.isSystem)));
    } catch {}
  }, [newFolderName, folders]);

  const handleFolderTap = useCallback((folder: Folder) => {
    if (folder.id === "folder_logs") {
      UltraDevLog.push('CHAIN', { component: 'ConversationList', action: 'folder_tap', trigger: { folderId: folder.id }, state: {}, data: { name: folder.name }, outcome: 'open_logs' });
      onClose();
      onOpenLogs();
      return;
    }
    UltraDevLog.push('CHAIN', { component: 'ConversationList', action: 'folder_tap', trigger: { folderId: folder.id }, state: {}, data: { name: folder.name }, outcome: 'folder_preview_alert' });
    Alert.alert(folder.name, 'Folder view coming in the next update. You can create and organize folders now.');
  }, [onClose, onOpenLogs]);

  const handleConvSelect = useCallback((id: string) => {
    UltraDevLog.push('SIDEBAR_CONV_SELECT', { convId: id });
    UltraDevLog.push('CHAIN', { component: 'ConversationList', action: 'conv_select', trigger: { convId: id }, state: { total: conversations.length }, data: {}, outcome: 'conversation_switched' });
    onSelect(id);
  }, [onSelect, conversations.length]);

  const renderConversation = useCallback(
    ({ item }: { item: ConversationMeta }) => (
      <ConversationItem
        item={item}
        isCurrent={item.id === currentConversationId}
        onSelect={handleConvSelect}
        onDelete={onDelete}
      />
    ),
    [currentConversationId, handleConvSelect, onDelete]
  );

  return (
    <Modal visible={visible} transparent animationType="none" onRequestClose={onClose} statusBarTranslucent>
      <View style={styles.root}>
        <TouchableWithoutFeedback onPress={onClose}>
          <Animated.View style={[styles.backdrop, { opacity: fadeAnim }]} />
        </TouchableWithoutFeedback>

        <Animated.View
          testID="ConversationList"
          style={[
            styles.drawer,
            {
              transform: [{ translateX: slideAnim }],
              paddingTop: insets.top + webTopInset,
              paddingBottom: Math.max(insets.bottom, webBottomInset),
            },
          ]}
        >
          {/* ── Header ─────────────────────────────────── */}
          <View style={styles.drawerHeader}>
            <View style={styles.drawerLogoRow}>
              <View style={styles.drawerLogo}>
                <MaterialCommunityIcons name="robot" size={18} color={ACCENT} />
              </View>
              <Text style={styles.drawerBrand}>Agent Ultra</Text>
            </View>
            <Pressable onPress={() => { UltraDevLog.push('CHAIN', { component: 'ConversationList', action: 'close', trigger: {}, state: { convCount: conversations.length }, data: {}, outcome: 'drawer_closed' }); onClose(); }} style={styles.closeBtn}>
              <Ionicons name="close" size={20} color={DIM} />
            </Pressable>
          </View>

          {/* ── New Chat (plain row, no green bg) ──────── */}
          <Pressable
            testID="sidebar_new_chat"
            onPress={() => { UltraDevLog.push('CHAIN', { component: 'ConversationList', action: 'new_chat', trigger: {}, state: { convCount: conversations.length }, data: {}, outcome: 'new_conversation_started' }); onNewChat(); onClose(); }}
            style={({ pressed }) => [styles.menuRow, pressed && styles.menuRowPressed]}
          >
            <Ionicons name="add-outline" size={20} color={TEXT} />
            <Text style={styles.menuRowText}>New Chat</Text>
          </Pressable>

          {/* ── Settings (gear moved here) ─────────────── */}
          <Pressable
            testID="sidebar_settings"
            onPress={() => { UltraDevLog.push('CHAIN', { component: 'ConversationList', action: 'open_settings', trigger: {}, state: {}, data: {}, outcome: 'settings_opened' }); onClose(); onOpenSettings(); }}
            style={({ pressed }) => [styles.menuRow, pressed && styles.menuRowPressed]}
          >
            <Ionicons name="settings-outline" size={20} color={TEXT} />
            <Text style={styles.menuRowText}>Settings</Text>
          </Pressable>

          {/* ── Toggle Tree ──────────────────────────────── */}
          <Pressable
            onPress={() => { const next = !toggleTreeOpen; UltraDevLog.push('CHAIN', { component: 'ConversationList', action: 'toggle_tree', trigger: {}, state: { wasOpen: toggleTreeOpen }, data: {}, outcome: next ? 'toggles_expanded' : 'toggles_collapsed' }); setToggleTreeOpen(next); }}
            style={({ pressed }) => [styles.menuRow, pressed && styles.menuRowPressed]}
            testID="toggle_tree_root"
          >
            <Ionicons name="git-branch-outline" size={20} color={toggleTreeOpen ? ACCENT : TEXT} />
            <Text style={[styles.menuRowText, toggleTreeOpen && { color: ACCENT }]}>Quick Toggles</Text>
            {onOpenZoneEditor && (
              <Pressable
                onPress={() => {
                  UltraDevLog.push('CHAIN', { component: 'ConversationList', action: 'zone_edit_tap', trigger: { source: 'sidebar_toggles' }, state: {}, data: {}, outcome: 'opening_zone_editor' });
                  onClose();
                  setTimeout(() => onOpenZoneEditor(), 300);
                }}
                hitSlop={{ top: 8, bottom: 8, left: 8, right: 8 }}
                style={{ marginLeft: 'auto', marginRight: 8 }}
              >
                <Ionicons name="pencil-outline" size={14} color={DIM} />
              </Pressable>
            )}
            <Ionicons name={toggleTreeOpen ? 'chevron-up' : 'chevron-down'} size={14} color={DIM} style={onOpenZoneEditor ? {} : { marginLeft: 'auto' }} />
          </Pressable>

          {toggleTreeOpen && (
            <View style={styles.toggleGrid}>
              {(sidebarItems || TOGGLE_TREE).map(toggle => (
                <Pressable
                  key={toggle.id}
                  testID={toggle.id}
                  onPress={() => {
                    UltraDevLog.push('TOGGLE_TAP', { id: toggle.id, capability: toggle.capability });
                    UltraDevLog.push('CHAIN', { component: 'ConversationList', action: 'execute_toggle', trigger: { id: toggle.id }, state: {}, data: { capability: toggle.capability }, outcome: onExecuteToggle ? 'toggle_executed' : 'FAIL:no_handler' });
                    onExecuteToggle?.(toggle.capability, toggle.params);
                  }}
                  style={({ pressed }) => [styles.toggleBtn, pressed && styles.toggleBtnPressed]}
                >
                  <Ionicons name={toggle.icon as any} size={18} color={TEXT_DIM} />
                  <Text style={styles.toggleLabel} numberOfLines={1}>{toggle.label}</Text>
                </Pressable>
              ))}
            </View>
          )}

          {/* ── Divider ─────────────────────────────────── */}
          <View style={styles.divider} />

          {/* ── QUICK ACTIONS ────────────────────────────── */}
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionLabel}>QUICK ACTIONS</Text>
          </View>
          <View style={styles.quickActionsGrid}>
            {QUICK_COMMANDS.map((cmd) => (
              <Pressable
                key={cmd.id}
                testID={`quick_${cmd.id}`}
                onPress={() => { UltraDevLog.push('SIDEBAR_QUICK', { command: cmd.command }); onClose(); onQuickCommand?.(cmd.command); }}
                style={({ pressed }) => [styles.quickActionBtn, pressed && styles.quickActionBtnPressed]}
              >
                <Ionicons name={cmd.icon} size={16} color={TEXT_DIM} />
                <Text style={styles.quickActionText} numberOfLines={1}>{cmd.label}</Text>
              </Pressable>
            ))}
          </View>

          {/* ── Divider ─────────────────────────────────── */}
          <View style={styles.divider} />

          {/* ── FOLDERS section ─────────────────────────── */}
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionLabel}>FOLDERS</Text>
            <Pressable
              onPress={() => setShowNewFolder(true)}
              hitSlop={12}
              style={styles.sectionAction}
            >
              <Ionicons name="add" size={18} color={DIM} />
            </Pressable>
          </View>

          {folders.map((folder) => (
            <Pressable
              key={folder.id}
              onPress={() => handleFolderTap(folder)}
              style={({ pressed }) => [styles.folderRow, pressed && styles.menuRowPressed]}
            >
              <Ionicons
                name={folder.id === "folder_logs" ? "document-text-outline" : "folder-outline"}
                size={18}
                color={folder.id === "folder_logs" ? "#f59e0b" : TEXT_DIM}
              />
              <Text style={styles.folderName}>{folder.name}</Text>
              {folder.isSystem && (
                <View style={styles.systemBadge}>
                  <Text style={styles.systemBadgeText}>Dev</Text>
                </View>
              )}
            </Pressable>
          ))}

          {/* New folder inline input */}
          {showNewFolder && (
            <View style={styles.newFolderRow}>
              <TextInput
                value={newFolderName}
                onChangeText={setNewFolderName}
                placeholder="Folder name..."
                placeholderTextColor="#444"
                style={styles.newFolderInput}
                autoFocus
                onSubmitEditing={handleCreateFolder}
                returnKeyType="done"
              />
              <Pressable onPress={handleCreateFolder} style={styles.newFolderSave}>
                <Ionicons name="checkmark" size={16} color={ACCENT} />
              </Pressable>
              <Pressable onPress={() => { UltraDevLog.push('CHAIN', { component: 'ConversationList', action: 'cancel_new_folder', trigger: {}, state: {}, data: {}, outcome: 'cancelled' }); setShowNewFolder(false); setNewFolderName(""); }} style={styles.newFolderCancel}>
                <Ionicons name="close" size={16} color={DIM} />
              </Pressable>
            </View>
          )}

          {/* ── Divider ─────────────────────────────────── */}
          <View style={styles.divider} />

          {/* ── CHATS section ──────────────────────────── */}
          <View style={styles.sectionHeader}>
            <Text style={styles.sectionLabel}>CHATS</Text>
            {conversations.length > 0 && (
              <Text style={styles.sectionCount}>{conversations.length}</Text>
            )}
          </View>

          <FlatList
            data={conversations}
            renderItem={renderConversation}
            keyExtractor={(item) => item.id}
            style={styles.chatList}
            contentContainerStyle={styles.chatListContent}
            showsVerticalScrollIndicator={false}
            ListEmptyComponent={
              <View style={styles.emptyState}>
                <Text style={styles.emptyText}>Start your first conversation</Text>
              </View>
            }
          />
        </Animated.View>
      </View>
    </Modal>
  );
}

// ── Styles ─────────────────────────────────────────────
const styles = StyleSheet.create({
  root: { flex: 1, flexDirection: "row" },
  backdrop: {
    ...StyleSheet.absoluteFillObject,
    backgroundColor: "rgba(0,0,0,0.55)",
  },
  drawer: {
    width: DRAWER_WIDTH,
    backgroundColor: SURFACE,
    borderRightWidth: 1,
    borderRightColor: "#1a1a1a",
  },

  // Header
  drawerHeader: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingHorizontal: 16,
    paddingVertical: 14,
  },
  drawerLogoRow: { flexDirection: "row", alignItems: "center", gap: 10 },
  drawerLogo: {
    width: 32,
    height: 32,
    borderRadius: 8,
    backgroundColor: SURFACE3,
    justifyContent: "center",
    alignItems: "center",
  },
  drawerBrand: {
    color: TEXT,
    fontSize: 16,
    fontFamily: "Inter_700Bold",
  },
  closeBtn: { padding: 4 },

  // Menu rows (New Chat, Settings)
  menuRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 12,
    paddingVertical: 12,
    paddingHorizontal: 16,
    marginHorizontal: 8,
    borderRadius: 8,
  },
  menuRowPressed: { backgroundColor: SURFACE2 },
  menuRowText: {
    color: TEXT,
    fontSize: 14,
    fontFamily: "Inter_500Medium",
  },

  // Quick Actions
  quickActionsGrid: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 6,
    paddingHorizontal: 12,
    marginBottom: 4,
  },
  quickActionBtn: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    paddingHorizontal: 10,
    paddingVertical: 7,
    borderRadius: 8,
    backgroundColor: SURFACE2,
    borderWidth: 1,
    borderColor: "#1a1a1a",
  },
  quickActionBtnPressed: { backgroundColor: SURFACE3 },
  quickActionText: {
    color: TEXT_DIM,
    fontSize: 12,
    fontFamily: "Inter_400Regular",
  },

  // Divider
  divider: {
    height: 1,
    backgroundColor: "#1a1a1a",
    marginHorizontal: 16,
    marginVertical: 8,
  },

  // Section headers
  sectionHeader: {
    flexDirection: "row",
    alignItems: "center",
    justifyContent: "space-between",
    paddingHorizontal: 16,
    paddingVertical: 8,
  },
  sectionLabel: {
    color: DIM,
    fontSize: 11,
    fontFamily: "Inter_600SemiBold",
    textTransform: "uppercase",
    letterSpacing: 0.8,
  },
  sectionCount: {
    color: "#444",
    fontSize: 11,
    fontFamily: "Inter_400Regular",
  },
  sectionAction: { padding: 2 },

  // Folder rows
  folderRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 10,
    paddingVertical: 10,
    paddingHorizontal: 20,
    marginHorizontal: 8,
    borderRadius: 8,
  },
  folderName: {
    color: TEXT_DIM,
    fontSize: 13,
    fontFamily: "Inter_400Regular",
    flex: 1,
  },
  systemBadge: {
    backgroundColor: "#332200",
    paddingHorizontal: 5,
    paddingVertical: 1,
    borderRadius: 4,
  },
  systemBadgeText: {
    color: "#f59e0b",
    fontSize: 9,
    fontFamily: "Inter_500Medium",
  },

  // New folder input
  newFolderRow: {
    flexDirection: "row",
    alignItems: "center",
    gap: 6,
    paddingHorizontal: 20,
    marginHorizontal: 8,
    paddingVertical: 6,
  },
  newFolderInput: {
    flex: 1,
    backgroundColor: SURFACE2,
    borderRadius: 6,
    paddingHorizontal: 10,
    paddingVertical: 6,
    color: TEXT,
    fontSize: 13,
    fontFamily: "Inter_400Regular",
    borderWidth: 1,
    borderColor: "#2a2a2a",
  },
  newFolderSave: { padding: 4 },
  newFolderCancel: { padding: 4 },

  // Chat list
  chatList: { flex: 1 },
  chatListContent: { paddingHorizontal: 4, paddingBottom: 16 },
  convItem: {
    flexDirection: "row",
    alignItems: "center",
    paddingVertical: 10,
    paddingHorizontal: 12,
    marginHorizontal: 4,
    borderRadius: 8,
    gap: 10,
  },
  convItemActive: {
    backgroundColor: "rgba(74, 222, 128, 0.06)",
  },
  convItemPressed: {
    backgroundColor: SURFACE2,
  },
  convIcon: { marginTop: 1 },
  convContent: { flex: 1 },
  convTitle: {
    color: TEXT_DIM,
    fontSize: 13,
    fontFamily: "Inter_500Medium",
    marginBottom: 2,
  },
  convTitleActive: { color: ACCENT },
  convPreview: {
    color: "#444",
    fontSize: 11,
    fontFamily: "Inter_400Regular",
  },
  convDate: {
    color: "#3a3a3a",
    fontSize: 10,
    fontFamily: "Inter_400Regular",
  },

  // Toggle tree
  toggleGrid: {
    flexDirection: 'row', flexWrap: 'wrap',
    paddingHorizontal: 8, paddingBottom: 8, gap: 6,
  },
  toggleBtn: {
    width: '30%', flexDirection: 'row', alignItems: 'center', gap: 6,
    paddingVertical: 8, paddingHorizontal: 8,
    backgroundColor: SURFACE2, borderRadius: 8,
  },
  toggleBtnPressed: { backgroundColor: SURFACE3 },
  toggleLabel: { color: TEXT_DIM, fontSize: 11, flex: 1 },

  // Empty
  emptyState: {
    paddingVertical: 30,
    alignItems: "center",
  },
  emptyText: {
    color: DIM,
    fontSize: 13,
    fontFamily: "Inter_400Regular",
  },
});
