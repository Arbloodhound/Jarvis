# Jarvis architecture

The skeleton every change is checked against: what each part does and what it talks to. Generated from two read-throughs of the code on 2026-10-07 (every edge names the function it goes through). Diagrams are Mermaid; GitHub renders them.

## Components

### A · Brain

| Part | File | Role |
|---|---|---|
| Brain | `agent/Brain.kt` | Routes each request: shortcuts, on-device answer, cloud tool loop, navigation |
| Interrupt | `agent/Interrupt.kt` | New words mid-run: Stop, After, Replace or Run |
| IntentRouter · WebNeed | `agent/IntentRouter.kt, WebNeed.kt` | Request part, think budget, whether the web is needed |
| LocalPrompt · LocalToolCall | `agent/LocalPrompt.kt, LocalToolCall.kt` | On-device prompts and the local tool catalog |
| OfflineNavigator · OfflineNav | `agent/OfflineNavigator.kt, OfflineNav.kt` | On-device perceive, think, act, verify loop |
| ReActNavigator | `agent/ReActNavigator.kt` | Cloud in-app navigator; owns the shared executeAction |
| WebResearch · WebResearchRun | `agent/WebResearch.kt, WebResearchRun.kt` | Multi-query search, ranking, page reading, brief |
| Experience · Recipes | `agent/Experience.kt, Recipes.kt, RoutePlayer.kt` | Lessons from runs; saved routines |
| Budgets | `agent/ThinkBudget.kt, WaitBudget.kt, ThermalGuard.kt` | Think tokens, wait time, heat scaling |

### B · Models

| Part | File | Role |
|---|---|---|
| LocalModelEngine | `local/LocalModelEngine.kt` | llama.cpp wrapper: thinker and librarian, download, load, generate |
| LlmNative · CpuVariant · CpuTopology | `local/*.kt` | JNI and picking the fastest CPU build and cores |
| VisionModel · VisionNative | `local/VisionModel.kt, VisionNative.kt` | Qwen2.5-VL 3B: describe a screenshot |
| ParallelDownload · Downloads | `local/ParallelDownload.kt, Downloads.kt` | 4-connection resumable downloads |
| OpenAiClient | `provider/OpenAiClient.kt` | Optional cloud model |

### C · Memory

| Part | File | Role |
|---|---|---|
| AmbientMemory | `listen/AmbientMemory.kt` | ambient.db: heard lines, vectors, tags, voices |
| Librarian | `listen/Librarian.kt, LibrarianText.kt` | Turns heard lines into notes when idle |
| MemoryNotes | `listen/MemoryNotes.kt` | memory_notes.db: notes with full-text search |
| MemoryRecall · MemoryIndexer | `listen/MemoryRecall.kt, MemoryIndexer.kt` | Keyword, vector and time recall |
| ConversationInsights · CueTaxonomy | `listen/ConversationInsights.kt, CueTaxonomy.kt` | Talk statistics and literature-based patterns |
| ChatStore · Room | `ui/ChatStore.kt, data/*` | Chat state and ultra.db |

### D · Actions

| Part | File | Role |
|---|---|---|
| Tools | `agent/Tools.kt` | Tool dispatcher with personal-data and secret checks |
| AgentController | `agent/AgentController.kt` | Device facade: screen, taps, settings, secret tracking |
| AgentAccessibilityService | `AgentAccessibilityService.java` | Reads and drives the screen; takeover overlay; screenshots |
| ScreenVision · OcrLayout | `agent/ScreenVision.kt, OcrLayout.kt` | Screenshot, ML Kit OCR, vision look |

### E · Safety

| Part | File | Role |
|---|---|---|
| Gate | `gate/Gate.kt + Manifest, Contracts, RiskScorer` | Deterministic per-call policy |
| ActionGate · Disagreement | `agent/ActionGate.kt, Disagreement.kt` | Confirm card for commitments; stop on contradiction |
| ScreenSecrets · ScamWatch | `gate/ScreenSecrets.kt, ScamWatch.kt` | Codes and cards stay put; scam follow-ups blocked |

### Voice in and out

| Part | File | Role |
|---|---|---|
| AmbientListenService | `listen/AmbientListenService.kt` | Mic pipeline: gate, wake, whisper, dispatch |
| Segmenter · WakeGate · WakeFilter | `listen/*.kt` | Utterance cutting, wake word, addressed speech |
| WhisperNative · LocalSpeech | `listen/WhisperNative.kt, LocalSpeech.kt` | whisper.cpp speech to text |
| SpeechChunker · SpeechQueue · Speaker | `agent/SpeechChunker.kt, ui/SpeechQueue.kt, ui/Speaker.kt` | Speak as it writes; echo guard |
| LocalVoice · VoiceCatalog | `listen/LocalVoice.kt, VoiceCatalog.kt` | Own voices via sherpa-onnx |

## The whole app

Who starts what. Arrows point from caller to callee. Grey boxes are third-party engines inside the APK.

```mermaid
flowchart LR
  subgraph IN["Ways in"]
    CHAT["ChatScreen<br/>typed + mic"]
    VOICE["VoiceActivity<br/>assistant button"]
    AMB["AmbientListenService<br/>Hey Jarvis"]
    NOTIF["UltraNotificationService<br/>scam triggers"]
  end
  subgraph BRAIN["A · Brain"]
    INT["Interrupt<br/>stop / after / replace"]
    BR["Brain.run"]
    LOC["emitLocal<br/>on-device answer"]
    LOOP["runLoop<br/>cloud tool loop"]
    ONAV["OfflineNavigator"]
    RNAV["ReActNavigator"]
    WEB["WebResearchRun"]
  end
  subgraph MODELS["B · Models"]
    LME["LocalModelEngine<br/>thinker + librarian"]
    VM["VisionModel"]
    OAI["OpenAiClient<br/>optional cloud"]
  end
  subgraph ACT["D · Actions"]
    TOOLS["Tools.execute"]
    CTRL["AgentController"]
    A11Y["AgentAccessibilityService<br/>read, tap, takeover"]
    SV["ScreenVision<br/>screenshot, OCR, look"]
  end
  subgraph SAFE["E · Safety"]
    GATE["Gate.enforceCall<br/>manifest, taint, risk"]
    AG["ActionGate<br/>confirm card"]
    SEC["ScreenSecrets"]
  end
  subgraph MEM["C · Memory"]
    AM["AmbientMemory<br/>ambient.db"]
    LIB["Librarian"]
    MN["MemoryNotes<br/>memory_notes.db"]
    REC["MemoryRecall"]
  end
  subgraph OUT["Voice out"]
    SQ["SpeechQueue"]
    SPK["Speaker"]
    LV["LocalVoice"]
  end
  subgraph NAT["Native engines"]
    LLAMA[("libultra_llm<br/>llama.cpp + mtmd")]
    WHIS[("libultra_whisper<br/>whisper.cpp")]
    SHERPA[("sherpa-onnx")]
    ORT[("onnxruntime")]
    MLK[("ML Kit text")]
  end
  CHAT --> INT --> BR
  VOICE --> INT
  AMB --> INT
  BR --> LOC & LOOP & ONAV
  LOC --> WEB
  LOC --> LME
  LOOP --> OAI
  LOOP --> GATE --> TOOLS
  TOOLS --> CTRL --> A11Y
  TOOLS --> RNAV --> AG
  ONAV --> RNAV
  ONAV --> LME
  TOOLS --> SV --> A11Y
  SV --> MLK
  SV --> VM --> LLAMA
  LME --> LLAMA
  CTRL --> SEC
  AMB --> WHIS
  AMB --> ORT
  AMB --> AM --> LIB --> MN
  LIB --> LME
  LOC --> REC --> AM
  REC --> MN
  BR --> SQ --> SPK --> LV --> SHERPA
  NOTIF --> GATE
```

## Hey Jarvis to a spoken answer

Flow (a)+(b): microphone to command to speech, including the echo guard that stops Jarvis hearing itself.

```mermaid
sequenceDiagram
  autonumber
  participant Mic as AudioRecord 16 kHz
  participant ALS as AmbientListenService
  participant WG as WakeGate (onnxruntime)
  participant SEG as Segmenter
  participant WH as whisper.cpp
  participant MEM as AmbientMemory
  participant B as Brain
  participant SQ as SpeechQueue + Speaker
  Mic->>ALS: 512-sample frame
  ALS->>ALS: muted? (mutedUntil or Speaker.mutesListener) then drop frame
  ALS->>WG: accept(frame)
  WG-->>ALS: "hey jarvis" fired: open 8 s command window, chime
  ALS->>SEG: push(frame)
  SEG-->>ALS: utterance (window kept from its start)
  ALS->>WH: transcribe (AmbientBacklog waits while thinker busy)
  WH-->>ALS: text + confidence
  ALS->>MEM: record line (every kept line)
  ALS->>ALS: Interrupt.decide: Stop / After / Replace / Run
  ALS->>B: dispatchNow, fallbackBrain.run(request, spoken)
  B->>B: shortcuts, else emitLocal (web brief, memory brief)
  B-->>SQ: SpeechChunker pieces as tokens stream
  SQ->>ALS: onStart: mutedUntil = now + EchoGuard
  SQ-->>Mic: audio out (own voice via sherpa-onnx)
```

## A tool call, gate by gate

Flow (c): every safety check a tool call passes before anything happens on the phone.

```mermaid
flowchart TD
  M["Model asks for a tool<br/>(cloud runLoop or local runLocalCall)"] --> G{"Gate.enforceCall<br/>declared in ultra.manifest.json?<br/>tainted by a secret? scam follow-up?<br/>risk score"}
  G -- "blocked, confirmable (cloud)" --> C["Confirm card<br/>Brain.resolvePending"]
  G -- "blocked" --> X["Refused, reason back to the model"]
  C -- yes --> T
  G -- allowed --> T{"Tools.execute pre-checks<br/>personal data allowed?<br/>outbound secret in any argument?"}
  T -- refused --> X
  T --> R{"Which tool?"}
  R -- "wifi_toggle etc." --> S["AgentController.setWifi"] --> V["Brain.verifyAction<br/>VERIFIED / UNVERIFIED"]
  R -- "read_text_on_screen" --> F["AgentController.screenFlat"]
  F -- "PROTECTED" --> P["Protected app: refused"]
  F --> O{"OcrLayout.sparse?"}
  O -- yes --> SV["ScreenVision.readScreen<br/>screenshot + ML Kit<br/>noteScreenSecrets"]
  O -- no --> RT["Tree text"]
  R -- "react_navigate" --> N["ReActNavigator<br/>approveTap: ActionGate"]
  V --> FB["Result back to the model"]
  SV --> FB
  RT --> FB
  N --> FB
```

## On-device app driving

Flow (d): perceive, think, act, verify, with the takeover overlay holding off your touches.

```mermaid
flowchart TD
  S["OfflineNavigator.run"] --> TK["showTakeover<br/>full-screen overlay + Stop"]
  TK --> L["launchApp (app policy)"]
  L --> P["Perceive: ReActNavigator.perceive<br/>screenFlat + noteScreenSecrets<br/>OfflineNav.compact"]
  P --> TH{"fewer than 3 elements<br/>and not PROTECTED?"}
  TH -- yes --> PIC["ScreenVision OCR<br/>else vision model look<br/>OfflineNav.withPicture"]
  TH -- no --> TN
  PIC --> TN["Think: OfflineNav.prompt<br/>LocalModelEngine.generate 64 tokens<br/>OfflineNav.parse, one re-ask"]
  TN --> D{"Done / Fail / same action<br/>on same screen / budget?"}
  D -- yes --> E["Outcome"]
  D -- no --> A["Act: ReActNavigator.act<br/>approveTap: ActionGate + Disagreement<br/>refuseTyping"]
  A --> W["Verify: wait 900 ms, perceive,<br/>compare signature"]
  W --> P
  E --> H["finally: hideTakeover"]
  STOP["Stop pill on overlay"] -.-> K["stopFromTakeover:<br/>Brain.stopAll, stopActive, SpeechQueue.stopAll"] -.-> H
```

## Searching the web

Flow (e): several searches, ranked by agreement and site trust, pages read, numbered sources.

```mermaid
flowchart TD
  Q["Brain.emitLocal<br/>WebNeed.query: needs the web?"] --> PL["WebResearch.plan<br/>2-4 queries with operators<br/>product / how-to / fact"]
  PL --> DDG["DuckDuckGo HTML, in parallel<br/>(20 s budget)"]
  DDG --> PA["WebResearch.parseDdg<br/>real links via uddg"]
  PA --> RK["WebResearch.rank<br/>found-by-N + trust sets"]
  RK --> PG["Read top 3 pages<br/>WebResearch.pageText, prices"]
  PG --> EMP{"No page text<br/>(JavaScript sites)?"}
  EMP -- "yes, screen on + unlocked" --> OS["Tools.readPageOnScreen<br/>open in browser under takeover<br/>tree + OCR, then Back"]
  EMP -- no --> BF
  OS --> BF["WebResearch.brief<br/>numbered sources + rules"]
  BF --> PR["LocalPrompt.build<br/>3000-char research slot"]
  PR --> GEN["LocalModelEngine.generate"]
  RK -- "nothing found" --> OLD["Old path: instant answer,<br/>Wikipedia, DDG snippets"] --> PR
```

## Memory and notes

Flow (c, memory side): what is heard becomes searchable lines, then notes, links and insights.

```mermaid
flowchart LR
  H["Heard line"] --> AM[("ambient.db<br/>heard FTS4")]
  H --> CW["TranscriptCueWorker<br/>speaker, pause, pace tags"]
  CW --> VP["VoicePrints<br/>TitaNet via sherpa-onnx"] --> AM
  AM --> IX["MemoryIndexer<br/>MiniLM vectors (onnxruntime)"] --> AM
  AM --> LB["Librarian<br/>when idle / charging"]
  LB --> SM["LocalModelEngine LIBRARIAN<br/>small model"]
  SM --> NP["NoteParser + TimeResolver<br/>NoteMerge dedupe"]
  NP --> MN[("memory_notes.db<br/>notes + FTS")]
  LB --> LK["LibrarianLinks"] --> MN
  LB --> INS["Insights.dailyNote<br/>ConversationInsights"] --> MN
  Q["Question about the past"] --> RC["MemoryRecall<br/>keyword + vector + time"]
  RC --> AM
  RC --> MN
  RC --> BRF["MemoryBrief into the prompt"]
```

## Native boundary

Every Kotlin external function and the C++ it lands in. Each .so is built in three CPU flavours (base, dotprod, i8mm); the fastest the phone supports is loaded.

```mermaid
flowchart LR
  subgraph K["Kotlin"]
    LN["LlmNative<br/>nativeLoad, nativeGenerate,<br/>nativeAttachDraft, nativeDetachDraft,<br/>nativeSpecStats, nativeFree, nativeContextSize"]
    VN["VisionNative<br/>nativeVlmLoad, nativeVlmDescribe,<br/>nativeVlmFree"]
    WN["WhisperNative<br/>nativeInit, nativeTranscribe, nativeFree"]
    LVK["LocalVoice / VoicePrints<br/>vendored sherpa-onnx Kotlin"]
    WGK["WakeGate / SentenceEmbedder"]
    SVK["ScreenVision"]
  end
  subgraph C["C++ in the APK"]
    ULLM["ultra_llm.cpp"]
    UVLM["ultra_vlm.cpp"]
    UWH["ultra_whisper.cpp"]
  end
  subgraph L["Libraries"]
    LL[("llama.cpp 8345f33<br/>+ libmtmd")]
    WC[("whisper.cpp")]
    SO[("libsherpa-onnx-jni.so")]
    OR[("onnxruntime-android 1.30.0")]
    MK[("ML Kit text-recognition 16.0.1")]
  end
  LN --> ULLM --> LL
  VN --> UVLM --> LL
  WN --> UWH --> WC
  LVK --> SO
  WGK --> OR
  SVK --> MK
```

## Where things are stored

Databases, preference files and model files on the phone.

```mermaid
flowchart LR
  subgraph ROOM["Room ultra.db v7"]
    R1["conversations, messages<br/>ChatStore"]
    R2["task_shortcuts, tool_reliability,<br/>recipes, screen_memory, lessons<br/>Brain, Tools, ReActNavigator"]
  end
  subgraph SQL["SQLite"]
    S1["ambient.db: heard, heard_vec,<br/>heard_tag, voices, meta"]
    S2["memory_notes.db: notes + FTS"]
  end
  subgraph PREF["SharedPreferences"]
    P1["ambient: AmbientConfig"]
    P2["ultra_settings: UltraPrefs, VoicePrefs"]
    P3["ultra_local_model(_librarian),<br/>ultra_provider, ultra_a11y,<br/>ultra_protected_apps"]
  end
  subgraph FILES["filesDir"]
    F1["models/: thinker, librarian, draft,<br/>vision model + mmproj (.part resumable)"]
    F2["whisper, VAD, wake, MiniLM,<br/>TitaNet model files"]
    F3["voice folders (Kokoro, Piper)"]
  end
```
