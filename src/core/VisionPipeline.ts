import { Platform } from 'react-native';
import * as ExpoFileSystem from 'expo-file-system/legacy';
import AppController from '../native/AppController';
import type { ModelRouter } from './ModelRouter';
import { UltraDevLog as DebugLog } from '../utils/UltraDevLog';
import { logAICall } from '../utils/AICallLogger';

const FileSystem: any = Platform.OS !== 'web' ? ExpoFileSystem : null;
const isNative = Platform.OS !== 'web';

export interface ScreenUnderstanding {
  description: string; appName: string; screenType: string;
  interactableElements: Array<{ label: string; type: 'button' | 'input' | 'link' | 'toggle' | 'menu' | 'tab' | 'other'; suggestedAction: string }>;
  textContent: string[]; structuredData: any; confidence: number; timestamp: number;
}

export class VisionPipeline {
  private lastScreenshot: string | null = null;
  private lastScreenshotTime = 0;

  constructor(private ai: ModelRouter) {}

  async understand(context?: string, options?: { extractData?: boolean; identifyActions?: boolean }): Promise<ScreenUnderstanding> {
    if (!isNative || !AppController.isAvailable()) return this.emptyResult('Vision requires Android device');
    const opts = { extractData: true, identifyActions: true, ...options };

    let accessibilityText = ''; let activePackage = '';
    try {
      activePackage = await AppController.getActivePackage();
      const flat = await AppController.getScreenContentFlat();
      const nodes = JSON.parse(flat) as Array<{ i: number; t: string; d: string; c: boolean; e: boolean; s: boolean; x: number; y: number }>;
      DebugLog.push('VISION_CAPTURE' as any, { event: 'a11y_read', nodeCount: nodes.length, clickableCount: nodes.filter(n => n.c).length });
      accessibilityText = nodes.map(n => { const label = (n.t || n.d || '').slice(0, 60); const flags: string[] = []; if (n.c) flags.push('tappable'); if (n.e) flags.push('editable'); if (n.s) flags.push('scrollable'); return `[${n.i}] "${label}" [${flags.join(',')}]`; }).join('\n');
    } catch (e: any) { DebugLog.error('VisionPipeline', `A11y read failed: ${e.message}`); }

    let screenshotBase64: string | null = null;
    try {
      const taken = await AppController.takeScreenshot();
      if (taken && FileSystem) {
        const screenshotDir = FileSystem.documentDirectory + 'screenshots/';
        try {
          const files = await FileSystem.readDirectoryAsync(screenshotDir);
          const pngFiles = files.filter((f: string) => f.endsWith('.png')).sort().reverse();
          if (pngFiles.length > 0) {
            screenshotBase64 = await FileSystem.readAsStringAsync(screenshotDir + pngFiles[0], { encoding: FileSystem.EncodingType.Base64 });
            const imageSizeBytes = Math.round(screenshotBase64!.length * 0.75);
            DebugLog.push('VISION_CAPTURE' as any, { event: 'screenshot_captured', sizeBytes: imageSizeBytes, exceedsModelLimit: imageSizeBytes > 5_000_000 });
            if (imageSizeBytes > 5_000_000) DebugLog.push('VISION_CAPTURE' as any, { event: 'screenshot_too_large', sizeBytes: imageSizeBytes });
            this.lastScreenshot = screenshotBase64; this.lastScreenshotTime = Date.now();
          }
        } catch {}
      }
    } catch (e: any) { DebugLog.error('VisionPipeline', `Screenshot failed: ${e.message}`); }

    if (!this.ai.hasApiKey()) {
      return { description: `Screen showing ${activePackage}. ${accessibilityText.slice(0, 500)}`, appName: activePackage, screenType: 'unknown', interactableElements: [],
        textContent: accessibilityText.split('\n').filter(l => l.includes('"')).map(l => { const m = l.match(/"([^"]+)"/); return m ? m[1] : ''; }).filter(Boolean),
        structuredData: null, confidence: 0.3, timestamp: Date.now() };
    }

    try {
      const contextLine = context ? `\nCONTEXT: The user is trying to: ${context}` : '';
      const userContent: any[] = [];
      // Only include screenshot if a vision-capable model is available.
      // completeVision() in ModelRouter selects a vision model if one exists.
      // For completeWithConversation(), we check by attempting to find any vision model
      // from the bridge; if none, sending image bytes causes HTTP 400.
      const canUseVision = screenshotBase64 && typeof (this.ai as any).completeVision === 'function';
      if (canUseVision) userContent.push({ type: 'image', source: { type: 'base64', media_type: 'image/png', data: screenshotBase64!.slice(0, 1_000_000) } });
      userContent.push({ type: 'text', text: `Describe this Android screen.${contextLine}\n\nACCESSIBILITY TREE:\n${accessibilityText.slice(0, 2000)}\n\nAPP: ${activePackage}\n\nRespond:\nAPP: name\nSCREEN_TYPE: home|search_results|settings|chat|form|list|media|map|login|error|other\nDESCRIPTION: 2-3 sentences\nTEXT: key visible text (one per line, max 10)\n${opts.identifyActions ? 'ACTIONS: "label" [type] - what it does (max 8)' : ''}\n${opts.extractData ? 'DATA: JSON object of structured data visible' : ''}\nCONFIDENCE: 0-1` });

      const messages = [{ role: 'system', content: 'You analyze Android screenshots and accessibility data.' }, { role: 'user', content: userContent }];
      const aiLog = logAICall({ agentId: 'vision', prompt: `[vision] ${activePackage} ${context || ''}`, maxTokens: 800, temperature: 0.2 });
      const aiResult = await this.ai.completeWithConversation(messages as any, { taskId: `vision_${Date.now().toString(36)}`, agentId: 'vision', maxTokens: 800, temperature: 0.2 });
      aiLog.logResponse(aiResult.content, aiResult.cost);

      const rLC = aiResult.content.toLowerCase();
      const usedVision = ['screenshot', 'image', 'i can see', 'the screen shows', 'visual', 'color', 'icon', 'logo'].some(w => rLC.includes(w));
      const usedA11yOnly = ['accessibility', 'node', 'element index', 'from the text'].some(w => rLC.includes(w));
      DebugLog.push('VISION_ANALYZE' as any, { event: 'model_vision_detection', likelyUsedImage: usedVision && !usedA11yOnly, hadScreenshot: !!screenshotBase64 });

      return this.parseVisionResponse(aiResult.content, activePackage);
    } catch (err: any) {
      DebugLog.error('VisionPipeline', `AI vision failed: ${err.message}`);
      // Build a meaningful tree-based fallback narrative from the accessibility data
      // already collected above, so callers get useful content instead of a blank error.
      const treeItems = accessibilityText
        .split('\n')
        .filter(l => l.includes('"'))
        .slice(0, 15)
        .map(l => { const m = l.match(/"([^"]+)"/); return m ? m[1].trim() : ''; })
        .filter(Boolean);
      const clickable = accessibilityText
        .split('\n')
        .filter(l => l.includes('tappable'))
        .slice(0, 6)
        .map(l => { const m = l.match(/"([^"]+)"/); return m ? m[1].trim() : ''; })
        .filter(Boolean);
      const fallbackDesc = treeItems.length > 0
        ? `${activePackage || 'App'} screen contains: ${treeItems.slice(0, 6).join(', ')}${treeItems.length > 6 ? ` and ${treeItems.length - 6} more elements` : ''}. (AI vision model unavailable — showing accessibility tree only.)`
        : `App: ${activePackage || 'unknown'}. No accessibility data available. (AI vision model unavailable.)`;
      return {
        description: fallbackDesc,
        appName: activePackage,
        screenType: 'unknown',
        interactableElements: clickable.map(label => ({ label, type: 'button' as const, suggestedAction: `tap "${label}"` })),
        textContent: treeItems,
        structuredData: null,
        confidence: 0.2,
        timestamp: Date.now(),
      };
    }
  }

  private parseVisionResponse(text: string, fallbackApp: string): ScreenUnderstanding {
    const appMatch = text.match(/APP:\s*(.+)/im); const typeMatch = text.match(/SCREEN_TYPE:\s*(\w+)/im);
    const descMatch = text.match(/DESCRIPTION:\s*(.+?)(?=\nTEXT:|\nACTIONS:|\nDATA:|\nCONFIDENCE:|$)/is);
    const textMatch = text.match(/TEXT:\s*([\s\S]+?)(?=\nACTIONS:|\nDATA:|\nCONFIDENCE:|$)/i);
    const actionsMatch = text.match(/ACTIONS:\s*([\s\S]+?)(?=\nDATA:|\nCONFIDENCE:|$)/i);
    const dataMatch = text.match(/DATA:\s*(\{[\s\S]*?\})(?=\s*\nCONFIDENCE:|$)/i);
    const confMatch = text.match(/CONFIDENCE:\s*([\d.]+)/i);
    let structuredData: any = null; if (dataMatch) { try { structuredData = JSON.parse(dataMatch[1]); } catch {} }
    const interactableElements: ScreenUnderstanding['interactableElements'] = [];
    if (actionsMatch) { for (const line of actionsMatch[1].trim().split('\n').filter(Boolean).slice(0, 8)) { const m = line.match(/"?([^"]+)"?\s*\[(\w+)\]\s*[-\u2013\u2014]\s*(.+)/); if (m) interactableElements.push({ label: m[1].trim(), type: m[2].toLowerCase() as any, suggestedAction: m[3].trim() }); } }
    const textContent = textMatch ? textMatch[1].trim().split('\n').map(l => l.replace(/^[-\u2022*]\s*/, '').trim()).filter(Boolean).slice(0, 10) : [];
    return { description: descMatch ? descMatch[1].trim() : text.slice(0, 300), appName: appMatch ? appMatch[1].trim() : fallbackApp, screenType: typeMatch ? typeMatch[1].trim().toLowerCase() : 'unknown', interactableElements, textContent, structuredData, confidence: confMatch ? parseFloat(confMatch[1]) : 0.5, timestamp: Date.now() };
  }

  async readText(hint?: string): Promise<{ success: boolean; text: string }> {
    if (!isNative || !AppController.isAvailable()) return { success: false, text: 'Vision requires Android device' };

    let screenshotBase64: string | null = null;
    try {
      const taken = await AppController.takeScreenshot();
      if (taken && FileSystem) {
        const screenshotDir = FileSystem.documentDirectory + 'screenshots/';
        try {
          const files = await FileSystem.readDirectoryAsync(screenshotDir);
          const pngFiles = files.filter((f: string) => f.endsWith('.png')).sort().reverse();
          if (pngFiles.length > 0) {
            screenshotBase64 = await FileSystem.readAsStringAsync(screenshotDir + pngFiles[0], { encoding: FileSystem.EncodingType.Base64 });
            this.lastScreenshot = screenshotBase64;
            this.lastScreenshotTime = Date.now();
          }
        } catch {}
      }
    } catch (e: any) { DebugLog.error('VisionPipeline', `readText screenshot failed: ${e.message}`); }

    if (!screenshotBase64) return { success: false, text: 'Could not capture screen for text reading' };
    if (!this.ai.hasApiKey()) return { success: false, text: 'AI provider not configured' };

    try {
      const hintLine = hint ? ` Focus on: ${hint}.` : '';
      const userContent: any[] = [
        { type: 'image', source: { type: 'base64', media_type: 'image/png', data: screenshotBase64.slice(0, 1_000_000) } },
        { type: 'text', text: `Read all visible text from this screenshot exactly as it appears.${hintLine} Return only the raw text you can see, preserving line breaks. Do not describe the image — just transcribe the text.` },
      ];
      const messages = [{ role: 'system', content: 'You are an OCR assistant. Extract all visible text from images exactly as it appears.' }, { role: 'user', content: userContent }];
      const aiResult = await this.ai.completeWithConversation(messages as any, { taskId: `vision_ocr_${Date.now().toString(36)}`, agentId: 'vision', maxTokens: 600, temperature: 0.1 });
      DebugLog.push('VISION_ANALYZE' as any, { event: 'read_text_done', chars: aiResult.content.length });
      return { success: true, text: aiResult.content.trim() };
    } catch (err: any) {
      DebugLog.error('VisionPipeline', `readText AI failed: ${err.message}`);
      const isModelCap = /vision|multimodal|image|not support/i.test(err.message || '');
      const userMsg = isModelCap
        ? 'The current AI model does not support image OCR. Switch to a vision-capable model (e.g. GPT-4o, Claude 3, Gemini Pro Vision) to read text from screen.'
        : `Screen text reading failed: ${err.message}`;
      return { success: false, text: userMsg };
    }
  }

  getLastScreenshot(): { base64: string | null; timestamp: number } { return { base64: this.lastScreenshot, timestamp: this.lastScreenshotTime }; }
  private emptyResult(reason: string): ScreenUnderstanding { return { description: reason, appName: '', screenType: 'unknown', interactableElements: [], textContent: [], structuredData: null, confidence: 0, timestamp: Date.now() }; }
}
