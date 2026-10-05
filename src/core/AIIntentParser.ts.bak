import type { ModelRouter } from './ModelRouter';
import type { CapabilityRegistry } from './CapabilityRegistry';
import type { ActionPlan } from '../types/ultra';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';

export class AIIntentParser {
  constructor(private ai: ModelRouter, private caps: CapabilityRegistry) {}

  async parse(userInput: string): Promise<ActionPlan | null> {
    if (!this.ai.hasApiKey()) return null;
    if (!userInput || userInput.trim().length < 2) return null;

    const conversational = /^(hi|hey|hello|thanks|ok|sure|yes|no|maybe|lol|haha|good|nice|cool|wow|damn|wtf|idk|nvm)\b/i;
    if (conversational.test(userInput.trim())) return null;

    const capList = this.caps.getAll()
      .filter(c => c.available)
      .map(c => `${c.id}: ${c.description}`)
      .join('\n');

    const prompt = `You interpret what a phone user wants to do. They might misspell words, use slang, or be vague.

USER SAID: "${userInput}"

AVAILABLE ACTIONS:
${capList}

If the user wants one of these actions, respond with ONLY this JSON (no markdown, no explanation):
{"capability":"the_capability_id","params":{"param_name":"value"},"reason":"what they want"}

Common mappings:
- "opn X" / "oepn X" / "launch X" → app_launch, target: X
- "cll X" / "cal X" / "ring X" → app_launch with call intent
- "txt X" / "msg X" / "mesage X" → sms_send
- "srch X" / "luk up X" / "google X" → web_research, query: X
- "nav to X" / "go to X" / "take me X" → app_launch with navigation
- "pic" / "foto" / "take a photo" → camera_capture
- "set alarm" / "timer" / "wake me" → alarm_set/timer_set
- "whats the weather" / "gonna rain" → open a weather app or web_search depending on capability fit
- "remind me" / "dont forget" → reminder_create
- "turn on/off X" → one of flashlight_toggle, wifi_toggle, bluetooth_toggle, or do_not_disturb

If this is just conversation (greeting, question, opinion, chat) respond with:
{"capability":null}`;

    try {
      const result = await this.ai.complete(prompt, {
        taskId: `intent_${Date.now().toString(36)}`,
        agentId: 'intent_parser',
        maxTokens: 200,
        temperature: 0.1,
      });

      const cleaned = result.content.replace(/```json|```/g, '').trim();
      const parsed = JSON.parse(cleaned);

      if (!parsed.capability || parsed.capability === 'null' || parsed.capability === null) {
        DebugLog.push('PARSE_INPUT' as any, {
          event: 'ai_intent_conversation',
          input: userInput.slice(0, 60),
        });
        return null;
      }

      const cap = this.caps.getAll().find(c => c.id === parsed.capability);
      if (!cap) {
        DebugLog.push('PARSE_INPUT' as any, {
          event: 'ai_intent_unknown_cap',
          input: userInput.slice(0, 60),
          parsed: parsed.capability,
        });
        return null;
      }

      DebugLog.push('PARSE_INPUT' as any, {
        event: 'ai_intent_matched',
        input: userInput.slice(0, 60),
        capability: parsed.capability,
        params: Object.keys(parsed.params || {}),
      });

      return {
        capability: parsed.capability,
        params: parsed.params || {},
        reason: parsed.reason || `AI understood: ${userInput}`,
      };
    } catch (e: any) {
      DebugLog.error('AIIntentParser', `Parse failed: ${e.message}`);
      return null;
    }
  }
}
