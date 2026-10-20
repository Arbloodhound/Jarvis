import type { AppSpec } from '../types/appspec';
import { ModelRouter } from './ModelRouter';
import { Logger } from '../utils/Logger';
import { UltraDevLog } from '../utils/UltraDevLog';

export class AppArchitect {
  private modelRouter: ModelRouter;
  private logger: Logger;

  constructor(modelRouter: ModelRouter) {
    this.modelRouter = modelRouter;
    this.logger = new Logger('AppArchitect');
  }

  async designApp(userDescription: string, conversationContext?: string): Promise<AppSpec> {
    this.logger.info(`Designing app from description: ${userDescription.substring(0, 100)}...`);
    UltraDevLog.push('SYSTEM', { event: 'app_architect_design_start', descriptionLen: userDescription.length, hasContext: !!conversationContext });

    const systemPrompt = `You are an expert Android application architect. The user will describe an app they want built.
You must produce a COMPLETE AppSpec as JSON. This spec will be used by an automated build system to generate, compile, and package a real APK.

Rules:
- Package name format: com.ultra.generated.<shortname> (lowercase, no spaces)
- Min SDK: 26, Target SDK: 33
- Use ONLY standard Android SDK classes unless you specify Maven dependencies
- CRITICAL: ALL UI must be built PROGRAMMATICALLY in Java code. Do NOT reference any XML layouts, do NOT use setContentView(R.layout.xxx), do NOT create layout XML files. There is no aapt2 or R.java generator available. All views must be created via new LinearLayout(), new TextView(), etc. in code.
- The main Activity must be the launcher
- Think about what files are actually needed: Activities, adapters, models, utilities
- For each file, specify its PURPOSE clearly (this will be used to prompt code generation)
- Specify file DEPENDENCIES (which other files must exist before this one can be generated)
- Order matters: generate base classes and models before Activities that use them
- Be thorough: include error handling, loading states, edge cases in your architecture notes
- For network calls: use HttpURLConnection (no OkHttp unless you add the Maven dep)
- For JSON parsing: use org.json (built into Android)
- Theme colors as hex strings (#RRGGBB)

Respond with ONLY valid JSON matching this exact schema (no markdown, no explanation):
{
  "appName": "string",
  "packageName": "string",
  "versionCode": 1,
  "versionName": "1.0",
  "minSdk": 26,
  "targetSdk": 33,
  "permissions": ["android.permission.INTERNET"],
  "dependencies": [],
  "theme": {
    "primaryColor": "#hex",
    "backgroundColor": "#hex",
    "accentColor": "#hex",
    "textColor": "#hex",
    "isDark": true
  },
  "files": [
    {
      "path": "src/com/ultra/generated/example/MainActivity.java",
      "purpose": "Main screen with programmatic UI that shows X and handles Y",
      "dependsOn": ["src/com/ultra/generated/example/DataModel.java"],
      "status": "pending"
    }
  ],
  "activities": [
    {
      "className": "com.ultra.generated.example.MainActivity",
      "isLauncher": true,
      "exported": true
    }
  ],
  "strings": {
    "app_name": "Example App"
  },
  "architectureNotes": "Brief description of how components interact. ALL UI is programmatic, no XML layouts."
}`;

    const userPrompt = conversationContext
      ? `Previous conversation context:\n${conversationContext}\n\nApp request:\n${userDescription}`
      : userDescription;

    const result = await this.modelRouter.complete(userPrompt, {
      systemPrompt,
      model: this.modelRouter.selectModel('architect'),
      temperature: 0.4,
      maxTokens: 4000,
      taskId: 'app-architect',
      agentId: 'app-architect',
    });

    return this.parseSpec(result.content, userDescription);
  }

  async refineApp(currentSpec: AppSpec, userFeedback: string): Promise<AppSpec> {
    this.logger.info(`Refining app spec based on feedback: ${userFeedback.substring(0, 100)}...`);

    const systemPrompt = `You are an Android architect. You have an existing AppSpec (JSON). The user wants changes.
Produce the COMPLETE updated AppSpec as JSON. Keep everything that isn't changing. Add/modify/remove only what the user asked for.
CRITICAL: ALL UI must be built PROGRAMMATICALLY in Java code. Do NOT reference any XML layouts. There is no aapt2 or R.java generator available.
Respond with ONLY valid JSON, no markdown, no explanation.`;

    const userPrompt = `Current spec:\n${JSON.stringify(currentSpec, null, 2)}\n\nRequested changes:\n${userFeedback}`;

    const result = await this.modelRouter.complete(userPrompt, {
      systemPrompt,
      model: this.modelRouter.selectModel('architect'),
      temperature: 0.4,
      maxTokens: 4000,
      taskId: 'app-architect',
      agentId: 'app-architect',
    });

    return this.parseSpec(result.content, userFeedback);
  }

  private parseSpec(raw: string, fallbackName: string): AppSpec {
    let cleaned = raw.trim();
    const fenced = cleaned.match(/```(?:json)?\s*([\s\S]*?)```/);
    if (fenced) cleaned = fenced[1].trim();

    try {
      const spec = JSON.parse(cleaned) as AppSpec;

      if (!spec.packageName) throw new Error('Missing packageName');
      if (!spec.files || spec.files.length === 0) throw new Error('No files specified');
      if (!spec.activities || spec.activities.length === 0) throw new Error('No activities specified');

      for (const f of spec.files) {
        if (!f.status) f.status = 'pending';
        if (!f.dependsOn) f.dependsOn = [];
      }

      if (!spec.strings) spec.strings = {};
      if (!spec.strings['app_name']) spec.strings['app_name'] = spec.appName || fallbackName;
      if (!spec.permissions) spec.permissions = [];
      if (!spec.dependencies) spec.dependencies = [];
      if (!spec.architectureNotes) spec.architectureNotes = '';

      this.logger.info(`Parsed spec: ${spec.appName} with ${spec.files.length} files, ${spec.activities.length} activities`);
      return spec;
    } catch (e) {
      throw new Error(`AppArchitect failed to parse spec: ${(e as Error).message}\nRaw response:\n${raw.slice(0, 500)}`);
    }
  }
}
