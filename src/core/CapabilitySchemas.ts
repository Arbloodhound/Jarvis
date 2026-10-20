import { CapabilitySchema, ActionPlan, ParamDef } from '../types/ultra';

const schemas: CapabilitySchema[] = [
  {
    capabilityId: 'app_launch',
    version: 2,
    requiredParams: {
      target: { type: 'string', description: 'App name, package name, URL, or action description' },
    },
    optionalParams: {
      action: { type: 'string', description: 'Android intent action (e.g., android.intent.action.VIEW, android.media.action.MEDIA_PLAY_FROM_SEARCH)' },
      data: { type: 'string', description: 'URI data for the intent (e.g., tel:, geo:, https:, spotify:)' },
      extras: { type: 'object', description: 'Key-value extras to pass with the Android intent' },
      packageName: { type: 'string', description: 'Explicit Android package name to target' },
      mimeType: { type: 'string', description: 'MIME type for the intent data' },
    },
  },
  {
    capabilityId: 'sms_send',
    version: 1,
    requiredParams: {
      to: { type: 'string', description: 'Recipient phone number or contact name' },
    },
    optionalParams: {
      message: { type: 'string', description: 'Message content' },
    },
  },
  {
    capabilityId: 'file_read',
    version: 1,
    requiredParams: {},
    optionalParams: {
      path: { type: 'string', description: 'File path to read, defaults to document directory' },
    },
  },
  {
    capabilityId: 'file_write',
    version: 1,
    requiredParams: {
      filename: { type: 'string', description: 'Name of the file to write' },
      content: { type: 'string', description: 'Content to write to the file' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'file_delete',
    version: 1,
    requiredParams: {
      filename: { type: 'string', description: 'Name of the file to delete' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'file_organize',
    version: 1,
    requiredParams: {
      actions: { type: 'array', description: 'Array of {source, destination} move operations' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'contacts_read',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'camera_capture',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'media_access',
    version: 1,
    requiredParams: {},
    optionalParams: {
      action: { type: 'string', description: 'Action to perform: pick (open gallery picker) or list (default, list recent media)' },
    },
  },
  {
    capabilityId: 'app_share',
    version: 1,
    requiredParams: {},
    optionalParams: {
      content: { type: 'string', description: 'Content or message to share' },
      url: { type: 'string', description: 'URL to share' },
      type: { type: 'string', description: 'MIME type of the content' },
    },
  },
  {
    capabilityId: 'device_location',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'code_generate',
    version: 1,
    requiredParams: {
      description: { type: 'string', description: 'Description of code to generate' },
    },
    optionalParams: {
      language: { type: 'string', description: 'Programming language' },
    },
  },
  {
    capabilityId: 'app_build',
    version: 1,
    requiredParams: {
      description: { type: 'string', description: 'Description of the app to build' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'app_install',
    version: 1,
    requiredParams: {},
    optionalParams: {
      apkPath: { type: 'string', description: 'Path to APK file to install' },
    },
  },
  {
    capabilityId: 'network_request',
    version: 1,
    requiredParams: {
      url: { type: 'string', description: 'URL to request' },
    },
    optionalParams: {
      method: { type: 'string', description: 'HTTP method (GET, POST, etc.)' },
      body: { type: 'string', description: 'Request body' },
    },
  },
  {
    capabilityId: 'ai_query',
    version: 1,
    requiredParams: {
      query: { type: 'string', description: 'Query to send to the AI' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'dependency_resolve',
    version: 1,
    requiredParams: {
      coordinates: { type: 'array', description: 'Array of Maven coordinates (group:artifact:version)' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'app_control',
    version: 1,
    requiredParams: {
      targetPackage: { type: 'string', description: 'Package name of app to control' },
      action: { type: 'string', description: 'Action to perform: click, scroll, type, back, home, read' },
    },
    optionalParams: {
      selector: { type: 'string', description: 'Text or content description to find the target element' },
      text: { type: 'string', description: 'Text to input (for type action)' },
    },
  },
  {
    capabilityId: 'app_test',
    version: 1,
    requiredParams: {
      description: { type: 'string', description: 'Description of what the app does (for test plan generation)' },
    },
    optionalParams: {
      packageName: { type: 'string', description: 'Package name of app to test' },
    },
  },
  {
    capabilityId: 'self_modify',
    version: 1,
    requiredParams: {},
    optionalParams: {
      goal: { type: 'string', description: 'Improvement goal for the evolution cycle' },
      maxCycles: { type: 'number', description: 'Maximum evolution cycles (default 3)' },
      challenges: { type: 'array', description: 'Custom task challenges to test offspring against (array of challenge descriptions)' },
    },
  },
  {
    capabilityId: 'self_replicate',
    version: 1,
    requiredParams: {},
    optionalParams: {
      goal: { type: 'string', description: 'Optional goal for the offspring agent' },
    },
  },
  {
    capabilityId: 'image_generate',
    version: 1,
    requiredParams: {
      prompt: { type: 'string', description: 'Text description of the image to generate' },
    },
    optionalParams: {
      model: { type: 'string', description: 'Image model to use' },
      width: { type: 'number', description: 'Image width (default 1024)' },
      height: { type: 'number', description: 'Image height (default 1024)' },
      style: { type: 'string', description: 'Style preset' },
      negative: { type: 'string', description: 'Negative prompt' },
    },
  },
  {
    capabilityId: 'flashlight_toggle',
    version: 1,
    requiredParams: {},
    optionalParams: {
      state: { type: 'string', description: 'Desired state: on, off, or toggle (default)' },
    },
  },
  {
    capabilityId: 'alarm_set',
    version: 1,
    requiredParams: {
      time: { type: 'string', description: 'Time string e.g. "7:30 am", "14:00"' },
    },
    optionalParams: {
      label: { type: 'string', description: 'Alarm label' },
    },
  },
  {
    capabilityId: 'timer_set',
    version: 1,
    requiredParams: {
      duration: { type: 'string', description: 'Duration string e.g. "5 minutes", "1 hour 30 minutes"' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'volume_set',
    version: 1,
    requiredParams: {},
    optionalParams: {
      type: { type: 'string', description: 'Volume type: media, ringer, notification' },
      level: { type: 'number', description: 'Volume level 0-100' },
      direction: { type: 'string', description: 'up or down' },
      state: { type: 'string', description: 'mute or unmute' },
    },
  },
  {
    capabilityId: 'brightness_set',
    version: 1,
    requiredParams: {},
    optionalParams: {
      level: { type: 'number', description: 'Brightness level 0-100' },
      direction: { type: 'string', description: 'dim or brighten' },
    },
  },
  {
    capabilityId: 'wifi_toggle',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'bluetooth_toggle',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'airplane_mode',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'do_not_disturb',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'battery_status',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'clipboard_read',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'clipboard_write',
    version: 1,
    requiredParams: {
      text: { type: 'string', description: 'Text to copy to clipboard' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'media_play',
    version: 1,
    requiredParams: {},
    optionalParams: {
      action: { type: 'string', description: 'play, pause, or resume' },
    },
  },
  {
    capabilityId: 'media_next',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'screenshot',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'screen_record_start',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'open_url',
    version: 1,
    requiredParams: {
      url: { type: 'string', description: 'URL to open' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'web_search',
    version: 1,
    requiredParams: {
      query: { type: 'string', description: 'Search query' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'calendar_create',
    version: 1,
    requiredParams: {},
    optionalParams: {
      title: { type: 'string', description: 'Event title' },
      details: { type: 'string', description: 'Event description or details' },
      startMs: { type: 'number', description: 'Start time in milliseconds' },
      endMs: { type: 'number', description: 'End time in milliseconds' },
    },
  },
  {
    capabilityId: 'reminder_create',
    version: 1,
    requiredParams: {
      text: { type: 'string', description: 'Reminder text' },
    },
    optionalParams: {
      time: { type: 'string', description: 'Time for the reminder' },
    },
  },
  {
    capabilityId: 'note_create',
    version: 1,
    requiredParams: {},
    optionalParams: {
      content: { type: 'string', description: 'Note content' },
    },
  },
  {
    capabilityId: 'file_open',
    version: 1,
    requiredParams: {
      path: { type: 'string', description: 'File path to open' },
    },
    optionalParams: {
      mimeType: { type: 'string', description: 'MIME type of the file' },
    },
  },
  {
    capabilityId: 'share_content',
    version: 1,
    requiredParams: {
      content: { type: 'string', description: 'Content to share' },
    },
    optionalParams: {
      subject: { type: 'string', description: 'Subject line for sharing' },
    },
  },
  {
    capabilityId: 'app_info',
    version: 1,
    requiredParams: {
      target: { type: 'string', description: 'App name or package to get info for' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'notification_read',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'device_info',
    version: 1,
    requiredParams: {},
    optionalParams: {
      focus: { type: 'string', description: 'Focus area: battery, memory, storage, network, or all' },
    },
  },
  {
    capabilityId: 'system_info',
    version: 1,
    requiredParams: {},
    optionalParams: {
      focus: { type: 'string', description: 'battery | memory | storage | temperature | all' },
    },
  },
  {
    capabilityId: 'react_navigate',
    version: 1,
    requiredParams: {
      goal: { type: 'string', description: 'What to accomplish via UI navigation' },
    },
    optionalParams: {
      appHint: { type: 'string', description: 'App name or package to navigate in' },
      packageName: { type: 'string', description: 'Explicit package name of target app' },
    },
  },
  {
    capabilityId: 'multi_step',
    version: 1,
    requiredParams: {
      steps: { type: 'array', description: 'Array of step descriptions or capability objects to execute in sequence' },
    },
    optionalParams: {
      stopOnFirstFailure: { type: 'boolean', description: 'If true, halt the chain on the first failed step and mark remaining steps as skipped. Default: false (continue on failure).' },
    },
  },
  {
    capabilityId: 'event_trigger_set',
    version: 1,
    requiredParams: {
      type: { type: 'string', description: 'Trigger type: sms, battery, notification, schedule' },
      condition: { type: 'string', description: 'Condition to evaluate' },
      action: { type: 'string', description: 'Action to execute when triggered' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'event_trigger_list',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'event_trigger_remove',
    version: 1,
    requiredParams: {
      id: { type: 'string', description: 'Trigger ID to remove' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'memory_recall',
    version: 1,
    requiredParams: {
      query: { type: 'string', description: 'What to recall from memory' },
    },
    optionalParams: {},
  },

  {
    capabilityId: 'knowledge_query',
    version: 1,
    requiredParams: {
      query: { type: 'string', description: 'Entity or topic to query in the knowledge graph' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'set_user_name',
    version: 1,
    requiredParams: {
      name: { type: 'string', description: 'Preferred user name' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'user_correction',
    version: 1,
    requiredParams: {
      correction: { type: 'string', description: 'User correction text' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'proactive_suggestions',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'task_resume',
    version: 1,
    requiredParams: {},
    optionalParams: {
      query: { type: 'string', description: 'Task or goal to resume' },
    },
  },
  {
    capabilityId: 'behavior_patterns',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'web_research',
    version: 1,
    requiredParams: {
      query: { type: 'string', description: 'Topic or question to research' },
    },
    optionalParams: {},
  },
  {
    capabilityId: 'vision_read',
    version: 1,
    requiredParams: {},
    optionalParams: {},
  },
  {
    capabilityId: 'describe_screen',
    version: 1,
    requiredParams: {},
    optionalParams: {
      context: { type: 'string', description: 'Optional context about what the user is trying to do' },
    },
  },
  {
    capabilityId: 'read_text_on_screen',
    version: 1,
    requiredParams: {},
    optionalParams: {
      hint: { type: 'string', description: 'Optional hint about what text to focus on (e.g. "prices", "phone numbers")' },
    },
  },
  {
    capabilityId: 'tts',
    version: 1,
    requiredParams: {
      text: { type: 'string', description: 'Text to convert to speech' },
    },
    optionalParams: {
      voice: { type: 'string', description: 'Voice ID (e.g. af_sky)' },
      speed: { type: 'number', description: 'Speed multiplier (default 1.0)' },
    },
  },
  {
    capabilityId: 'video_generate',
    version: 1,
    requiredParams: {
      prompt: { type: 'string', description: 'Description of the video to generate' },
    },
    optionalParams: {
      model: { type: 'string', description: 'AI video model ID' },
      seconds: { type: 'number', description: 'Duration in seconds (default 5)' },
    },
  },
  {
    capabilityId: 'weather',
    version: 1,
    requiredParams: {},
    optionalParams: {
      location: { type: 'string', description: 'City or location name (defaults to device GPS position)' },
      unit: { type: 'string', description: 'Temperature unit: fahrenheit or celsius (default: fahrenheit)' },
    },
  },
  {
    capabilityId: 'news_headlines',
    version: 1,
    requiredParams: {},
    optionalParams: {
      topic: { type: 'string', description: 'Topic or keyword to filter headlines (e.g. "technology", "sports")' },
      count: { type: 'number', description: 'Number of headlines to return (default 5)' },
    },
  },
  {
    capabilityId: 'sms_read',
    version: 1,
    requiredParams: {},
    optionalParams: {
      limit: { type: 'number', description: 'Max messages to return (default 10)' },
      filter: { type: 'string', description: 'Optional sender filter' },
    },
  },
  {
    capabilityId: 'sms_conversation',
    version: 1,
    requiredParams: {},
    optionalParams: {
      address: { type: 'string', description: 'Phone number or contact name' },
      contact: { type: 'string', description: 'Contact name (resolved to number)' },
      limit: { type: 'number', description: 'Max messages to return (default 15)' },
    },
  },
];

const schemaMap = new Map<string, CapabilitySchema>();
for (const s of schemas) {
  schemaMap.set(s.capabilityId, s);
}

function checkParamType(value: any, def: ParamDef): boolean {
  switch (def.type) {
    case 'string':
      return typeof value === 'string';
    case 'number':
      return typeof value === 'number';
    case 'boolean':
      return typeof value === 'boolean';
    case 'object':
      return typeof value === 'object' && value !== null && !Array.isArray(value);
    case 'array':
      return Array.isArray(value);
    default:
      return true;
  }
}

export function getSchema(capabilityId: string): CapabilitySchema | undefined {
  return schemaMap.get(capabilityId);
}

export function getAllSchemas(): CapabilitySchema[] {
  return schemas;
}

export function validatePlan(plan: ActionPlan): { valid: boolean; errors: string[] } {
  const errors: string[] = [];
  const schema = schemaMap.get(plan.capability);

  if (!schema) {
    errors.push(`Unknown capability: ${plan.capability}`);
    return { valid: false, errors };
  }

  for (const [key, def] of Object.entries(schema.requiredParams)) {
    if (plan.params[key] === undefined || plan.params[key] === null) {
      errors.push(`Missing required param: ${key}`);
    } else if (!checkParamType(plan.params[key], def)) {
      errors.push(`Param '${key}' expected type '${def.type}', got '${typeof plan.params[key]}'`);
    }
  }

  for (const [key, value] of Object.entries(plan.params)) {
    if (schema.requiredParams[key]) continue;
    const optDef = schema.optionalParams[key];
    if (optDef && !checkParamType(value, optDef)) {
      errors.push(`Optional param '${key}' expected type '${optDef.type}', got '${typeof value}'`);
    }
  }

  return { valid: errors.length === 0, errors };
}
