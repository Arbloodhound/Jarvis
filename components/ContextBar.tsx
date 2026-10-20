import React from 'react';
import { View, Text, Pressable, StyleSheet, ScrollView } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import type { ChatMessage } from '@/src/types/ultra';
import { getContextActions } from '@/src/data/contextRules';
import { UltraDevLog } from '@/src/utils/UltraDevLog';

interface ContextBarProps {
  message: ChatMessage;
  onExecutePlan: (capability: string, params: Record<string, any>) => void;
  onSendPrompt: (text: string) => void;
}

export default function ContextBar({ message, onExecutePlan, onSendPrompt }: ContextBarProps) {
  const capability = message.meta?.capability as string | undefined;
  const content = message.content || '';
  const actions = getContextActions(capability, content, "chat");

  if (actions.length === 0) return null;

  return (
    <ScrollView
      horizontal
      showsHorizontalScrollIndicator={false}
      style={styles.container}
      contentContainerStyle={styles.content}
      testID="ContextBar"
    >
      {actions.map(action => (
        <Pressable
          key={action.id}
          onPress={() => {
            if (action.execute.type === 'plan') {
              UltraDevLog.push('CHAIN', { component: 'ContextBar', action: 'chip_tap', trigger: { actionId: action.id }, state: { type: 'plan' }, data: { capability: action.execute.capability }, outcome: 'execute_plan' });
              onExecutePlan(action.execute.capability, action.execute.params);
            } else {
              UltraDevLog.push('CHAIN', { component: 'ContextBar', action: 'chip_tap', trigger: { actionId: action.id }, state: { type: 'prompt' }, data: { text: action.execute.text.slice(0, 40) }, outcome: 'send_prompt' });
              onSendPrompt(action.execute.text);
            }
          }}
          style={({ pressed }) => [styles.chip, pressed && styles.chipPressed]}
        >
          <Ionicons name={action.icon as any} size={13} color="#666" />
          <Text style={styles.chipText}>{action.label}</Text>
        </Pressable>
      ))}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { marginTop: 8, marginBottom: 2 },
  content: { flexDirection: 'row', gap: 6, paddingHorizontal: 4 },
  chip: {
    flexDirection: 'row', alignItems: 'center', gap: 4,
    paddingHorizontal: 10, paddingVertical: 6,
    borderRadius: 16, backgroundColor: '#222',
    borderWidth: 1, borderColor: '#2a2a2a',
  },
  chipPressed: { backgroundColor: '#2a2a2a', borderColor: '#3a3a3a' },
  chipText: { color: '#c0c0c0', fontSize: 12 },
});
