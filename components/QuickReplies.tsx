import React from "react";
import { View, Text, Pressable, StyleSheet } from "react-native";
import { Ionicons, MaterialCommunityIcons } from "@expo/vector-icons";
import type { ChatMessage } from "@/src/types/ultra";
import { UltraDevLog } from "@/src/utils/UltraDevLog";

const ACCENT = "#e5e5e5";
const SURFACE2 = "#1a1a1a";
const SURFACE3 = "#222222";
const DIM = "#666666";
const TEXT = "#c0c0c0";

export interface QuickReply {
  id: string;
  label: string;
  icon?: string;
  iconFamily?: "ionicons" | "material";
  prompt: string;
}

export function getQuickReplies(msg: ChatMessage): QuickReply[] {
  const replies: QuickReply[] = [];
  const cap = msg.meta?.capability;
  const source = msg.source;
  const risk = msg.meta?.risk;
  const content = msg.content || "";
  const isBuildLog = msg.meta?.isBuildLog;
  const isError = content.toLowerCase().includes("error") || content.toLowerCase().includes("failed");
  const hasCode = content.includes("```") || content.includes("function ") || content.includes("class ");

  if (isBuildLog) {
    if (isError) {
      replies.push({ id: "fix_rebuild", label: "Fix & rebuild", icon: "hammer-outline", iconFamily: "ionicons", prompt: "Fix the errors and rebuild" });
      replies.push({ id: "show_errors", label: "Show errors", icon: "bug-outline", iconFamily: "ionicons", prompt: "Show me the compilation errors in detail" });
    } else {
      replies.push({ id: "view_result", label: "View result", icon: "eye-outline", iconFamily: "ionicons", prompt: "Show me the build result details" });
    }
    return replies;
  }

  if (risk === "blocked" || risk === "dangerous") {
    replies.push({ id: "explain_block", label: "Why blocked?", icon: "help-circle-outline", iconFamily: "ionicons", prompt: "Explain why this action was blocked" });
    replies.push({ id: "alternative", label: "Suggest alternative", icon: "bulb-outline", iconFamily: "ionicons", prompt: "Suggest a safer alternative approach" });
    return replies;
  }

  if (cap === "app_build") {
    if (isError) {
      replies.push({ id: "fix_rebuild", label: "Fix & rebuild", icon: "hammer-outline", iconFamily: "ionicons", prompt: "Fix the build errors and try again" });
    } else {
      replies.push({ id: "test_app", label: "Test it", icon: "flask-outline", iconFamily: "ionicons", prompt: "Test the app I just built" });
      replies.push({ id: "improve_app", label: "Improve it", icon: "trending-up", iconFamily: "ionicons", prompt: "Suggest improvements for this app" });
    }
    return replies;
  }

  if (cap === "self_modify" || cap === "self_replicate") {
    replies.push({ id: "fitness_detail", label: "Fitness details", icon: "analytics-outline", iconFamily: "ionicons", prompt: "Show me detailed fitness metrics" });
    replies.push({ id: "evolve_more", label: "Evolve more", icon: "rocket-outline", iconFamily: "ionicons", prompt: "Run another evolution cycle" });
    return replies;
  }

  if (cap === "image_generate") {
    replies.push({ id: "variations", label: "Variations", icon: "copy-outline", iconFamily: "ionicons", prompt: "Generate variations of this image" });
    replies.push({ id: "diff_style", label: "Different style", icon: "color-palette-outline", iconFamily: "ionicons", prompt: "Regenerate this image in a different style" });
    replies.push({ id: "save_img", label: "Save", icon: "download-outline", iconFamily: "ionicons", prompt: "Save this image to device" });
    return replies;
  }

  if (cap === "code_generate") {
    replies.push({ id: "explain_code", label: "Explain", icon: "book-outline", iconFamily: "ionicons", prompt: "Explain this code step by step" });
    replies.push({ id: "build_it", label: "Build it", icon: "construct-outline", iconFamily: "ionicons", prompt: "Build this into an app" });
    replies.push({ id: "simplify", label: "Simplify", icon: "remove-circle-outline", iconFamily: "ionicons", prompt: "Simplify this code" });
    return replies;
  }

  if (cap === "sms_send" || cap === "contacts_read" || cap === "camera_capture" || cap === "media_access") {
    replies.push({ id: "do_again", label: "Do again", icon: "refresh-outline", iconFamily: "ionicons", prompt: content.includes("sent") ? "Send another text message" : "Do that again" });
    return replies;
  }

  if (cap === "device_location") {
    replies.push({ id: "nearby", label: "What's nearby?", icon: "location-outline", iconFamily: "ionicons", prompt: "What's interesting near my location?" });
    return replies;
  }

  if (cap === "file_read" || cap === "file_write" || cap === "file_delete") {
    replies.push({ id: "list_files", label: "Show files", icon: "folder-outline", iconFamily: "ionicons", prompt: "Show my files" });
    return replies;
  }

  if (isError && source !== "system") {
    replies.push({ id: "try_again", label: "Try again", icon: "refresh-outline", iconFamily: "ionicons", prompt: "Try that again" });
    replies.push({ id: "diff_model", label: "Try different model", icon: "swap-horizontal-outline", iconFamily: "ionicons", prompt: "Try this with a different model" });
    replies.push({ id: "report_bug", label: "Copy error", icon: "clipboard-outline", iconFamily: "ionicons", prompt: "__COPY_ERROR__" });
    return replies;
  }

  if (hasCode) {
    replies.push({ id: "explain_code", label: "Explain code", icon: "book-outline", iconFamily: "ionicons", prompt: "Explain this code" });
    replies.push({ id: "run_code", label: "Run this", icon: "play-outline", iconFamily: "ionicons", prompt: "Run this code" });
    replies.push({ id: "improve_code", label: "Improve", icon: "trending-up", iconFamily: "ionicons", prompt: "Improve this code for production use" });
    return replies;
  }

  if (source === "model" || source === "ultra" || !source) {
    if (content.length > 400) {
      replies.push({ id: "summarize", label: "Summarize", icon: "contract-outline", iconFamily: "ionicons", prompt: "Summarize that more concisely" });
    }
    if (content.length > 100) {
      replies.push({ id: "go_deeper", label: "Go deeper", icon: "expand-outline", iconFamily: "ionicons", prompt: "Tell me more about that" });
    }
    replies.push({ id: "actionable", label: "Make actionable", icon: "checkmark-done-outline", iconFamily: "ionicons", prompt: "Turn that into actionable steps" });

    return replies.slice(0, 3);
  }

  return replies;
}

interface QuickRepliesProps {
  message: ChatMessage;
  onSelect: (prompt: string, messageContent: string) => void;
}

export default function QuickReplies({ message, onSelect }: QuickRepliesProps) {
  const replies = getQuickReplies(message);

  if (replies.length === 0) return null;

  return (
    <View style={styles.container} testID="QuickReplies">
      {replies.map((reply) => (
        <Pressable
          key={reply.id}
          onPress={() => {
            UltraDevLog.push('CHAIN', { component: 'QuickReplies', action: 'chip_select', trigger: { replyId: reply.id }, state: { prompt: reply.prompt.slice(0, 40) }, data: { label: reply.label }, outcome: reply.prompt === '__COPY_ERROR__' ? 'copy_error' : 'send_prompt' });
            onSelect(reply.prompt, message.content);
          }}
          style={({ pressed }) => [styles.chip, pressed && styles.chipPressed]}
        >
          {reply.iconFamily === "material" ? (
            <MaterialCommunityIcons name={reply.icon as any} size={13} color={DIM} />
          ) : reply.icon ? (
            <Ionicons name={reply.icon as any} size={13} color={DIM} />
          ) : null}
          <Text style={styles.chipText}>{reply.label}</Text>
        </Pressable>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: "row",
    flexWrap: "wrap",
    gap: 6,
    marginTop: 8,
    marginBottom: 2,
  },
  chip: {
    flexDirection: "row",
    alignItems: "center",
    gap: 4,
    paddingHorizontal: 10,
    paddingVertical: 6,
    borderRadius: 16,
    backgroundColor: SURFACE3,
    borderWidth: 1,
    borderColor: "#2a2a2a",
  },
  chipPressed: {
    backgroundColor: "#2a2a2a",
    borderColor: "#3a3a3a",
  },
  chipText: {
    color: TEXT,
    fontSize: 12,
    fontFamily: "Inter_400Regular",
  },
});
