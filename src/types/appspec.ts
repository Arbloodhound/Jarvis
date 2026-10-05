export interface AppSpec {
  appName: string;
  packageName: string;
  versionCode: number;
  versionName: string;
  minSdk: number;
  targetSdk: number;
  permissions: string[];
  dependencies: string[];
  theme: {
    primaryColor: string;
    backgroundColor: string;
    accentColor: string;
    textColor: string;
    isDark: boolean;
  };
  files: FileSpec[];
  activities: ActivitySpec[];
  strings: Record<string, string>;
  architectureNotes: string;
}

export interface FileSpec {
  path: string;
  purpose: string;
  dependsOn: string[];
  content?: string;
  status: 'pending' | 'generated' | 'compiled' | 'error';
  lastError?: string;
}

export interface ActivitySpec {
  className: string;
  isLauncher: boolean;
  exported: boolean;
  layoutName?: string;
}

export type BuildPhase =
  | 'idle'
  | 'specifying'
  | 'planning'
  | 'resolving_deps'
  | 'generating'
  | 'scaffolding'
  | 'compiling'
  | 'debugging'
  | 'dexing'
  | 'packaging'
  | 'signing'
  | 'installing'
  | 'complete'
  | 'failed';

export interface BuildProgress {
  phase: BuildPhase;
  message: string;
  filesGenerated: number;
  filesTotal: number;
  compileAttempt: number;
  maxCompileAttempts: number;
  errors: string[];
}
