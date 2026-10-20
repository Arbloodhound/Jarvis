# Jarvis ANR reports (raw extracts)

Two "App isn't responding" reports from the Pixel 10 Pro, 2026-10-06. Copied from the phone's error reports without changes, except:

- Only the app's own process is included; the system_server dump that follows it is cut.
- Only these threads are kept: `main`, the `DefaultDispatcher-worker-*` threads, and the app's `Thread-N` threads.
- Frames inside `libart.so` (the Android runtime's interpreter) are removed for readability. Every other line is verbatim.

Full originals: `Error_in_Jarvis_588218b6fa36.txt` and `Error_in_Jarvis_86bceeafe0d2.txt`.

No fix is included here.

## What the stacks show (observations only, no fix)

- **Phone and build:** `google/mustang`, Android 17 (`CP3A.260905.009`); app `com.agent.ultra` versionCode 17 (2.6.0); targetSdk 35. The build is debuggable: CheckJNI frames appear in the stack.
- **Type:** both are `Input dispatching timed out ... MainActivity is not responding. Waited 5000ms for MotionEvent`.
- **The `main` thread is inside the on-device model load in both reports.** The Java part of the stack is the same in both:
  `Handler.handleCallback → Brain$runUnlocked$1.invokeSuspend → Brain.runUnlocked → LocalModelEngine.ensureLoaded → LlmNative.nativeLoad`
  - ANR 1: deepest frame is `llama_model_load_from_file → load_tensors → init_mappings → llama_mmap → mmap`.
  - ANR 2: `nativeLoad+204` in `ReleaseStringUTFChars`, just after `llama_model_load_from_file` returned.
- **CPU time on `main`:** `utm=9199` (about 92 s) in ANR 1 and `utm=9010` (about 90 s) in ANR 2.
- **Process memory:** ANR 1 `RssKb 8588792` (`RssAnonKb 178616`, `VmSwapKb 209960`); ANR 2 `RssKb 8573736` (`RssAnonKb 360096`).


## ANR 1: `Error_in_Jarvis_588218b6fa36.txt`

**Subject: Input dispatching timed out (111ab58 com.agent.ultra/com.agent.ultra.MainActivity is not responding. Waited 5000ms for MotionEvent).**

### Header

```
type: ANR
osVersion: google/mustang/mustang:17/CP3A.260905.009/2026100201:user/release-keys
package: com.agent.ultra:17, targetSdk 35
process: com.agent.ultra
installer: com.android.packageinstaller

Subject: Input dispatching timed out (111ab58 com.agent.ultra/com.agent.ultra.MainActivity is not responding. Waited 5000ms for MotionEvent).
Timeout: 5000
TimeoutStart: 13401479
RssHwmKb: 10094908
RssKb: 8588792
RssAnonKb: 178616
RssShmemKb: 1192
VmSwapKb: 209960
BaseFsVerity: false


--- CriticalEventLog ---
capacity: 20
timestamp_ms: 13406497
window_ms: 300000

----- dumping pid: 25899 at 13406494

----- pid 25899 at 2026-10-06 01:31:26.544644308-0700 -----
Cmd line: com.agent.ultra
Build fingerprint: 'google/mustang/mustang:17/CP3A.260905.009/2026100201:user/release-keys'
ABI: 'arm64'
Build type: optimized
Debug Store: (omitted, ~1 KB of broadcast bookkeeping)
suspend all histogram:	Sum: 26.387ms 99% C.I. 0.676us-5200.639us Avg: 141.865us Max: 6243us
DALVIK THREADS (49):
```

### Thread main

```
"main" prio=5 tid=1 Native
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x72d2c478 self=0xd00dcb3b0a1c000
  | sysTid=25899 nice=-10 cgrp=default sched=1073741824/0 handle=0xddfd903f9000
  | state=R schedstat=( 103206499634 4278286300 42723 ) utm=9199 stm=1121 core=7 HZ=100
  | stack=0xe291ce22f000-0xe291ce233000 stackSize=8188KB
  | held mutexes=
  native: #00 pc 000ef28c  /apex/com.android.runtime/lib64/bionic/libc.so (mmap+12) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  native: #01 pc 00295c20  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 4e4c000) (llama_mmap::impl::impl+232) (BuildId: 55bebca889cac3e479e6c3998673243b34214095)
  native: #02 pc 00294a70  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 4e4c000) (llama_mmap::llama_mmap+68) (BuildId: 55bebca889cac3e479e6c3998673243b34214095)
  native: #03 pc 002a1950  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 4e4c000) (llama_model_loader::init_mappings+496) (BuildId: 55bebca889cac3e479e6c3998673243b34214095)
  native: #04 pc 002c9590  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 4e4c000) (llama_model_base::load_tensors+11368) (BuildId: 55bebca889cac3e479e6c3998673243b34214095)
  native: #05 pc 0020be10  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 4e4c000) (???) (BuildId: 55bebca889cac3e479e6c3998673243b34214095)
  native: #06 pc 0020c734  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 4e4c000) (llama_model_load_from_file+204) (BuildId: 55bebca889cac3e479e6c3998673243b34214095)
  native: #07 pc 00200d5c  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 4e4c000) (Java_com_agent_ultra_local_LlmNative_nativeLoad+184) (BuildId: 55bebca889cac3e479e6c3998673243b34214095)
  native: #13 pc 00005264  [anon:dalvik-classes5.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.local.LocalModelEngine.ensureLoaded)
  native: #17 pc 000205e8  [anon:dalvik-classes3.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.agent.Brain.runUnlocked)
  native: #21 pc 00019c40  [anon:dalvik-classes3.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.agent.Brain.access$runUnlocked)
  native: #25 pc 0001954c  [anon:dalvik-classes3.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.agent.Brain$runUnlocked$1.invokeSuspend)
  native: #29 pc 002cc758  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #33 pc 0031851c  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #37 pc 0029c060  /system/framework/framework.jar (offset 126b000) (android.os.Handler.handleCallback)
  native: #41 pc 0029be9c  /system/framework/framework.jar (offset 126b000) (android.os.Handler.dispatchMessageImpl)
  native: #45 pc 0029be84  /system/framework/framework.jar (offset 126b000) (android.os.Handler.dispatchMessage)
  native: #49 pc 002c4ef0  /system/framework/framework.jar (offset 126b000) (android.os.Looper.loopOnce)
  native: #53 pc 002c5aa4  /system/framework/framework.jar (offset 126b000) (android.os.Looper.loop)
  native: #57 pc 001e0688  /system/framework/framework.jar (offset 6000) (android.app.ActivityThread.main)
  native: #69 pc 00365124  /system/framework/framework.jar (offset 23bc000) (com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run)
  at com.agent.ultra.local.LlmNative.nativeLoad(Native method)
  at com.agent.ultra.local.LocalModelEngine.ensureLoaded(LocalModelEngine.kt:190)
  - locked <@addr=0x22fd1f0> (a java.lang.Object)
  at com.agent.ultra.agent.Brain.runUnlocked(Brain.kt:188)
  at com.agent.ultra.agent.Brain.access$runUnlocked(Brain.kt:28)
  at com.agent.ultra.agent.Brain$runUnlocked$1.invokeSuspend(unavailable:15)
  at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
  at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:104)
  at android.os.Handler.handleCallback(Handler.java:1095)
  at android.os.Handler.dispatchMessageImpl(Handler.java:135)
  at android.os.Handler.dispatchMessage(Handler.java:125)
  at android.os.Looper.loopOnce(Looper.java:296)
  at android.os.Looper.loop(Looper.java:397)
  at android.app.ActivityThread.main(ActivityThread.java:9569)
  at java.lang.reflect.Method.invoke(Native method)
  at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:575)
  at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:975)
DumpLatencyMs: 6.88786
```

### Thread DefaultDispatcher-worker-5

```
"DefaultDispatcher-worker-5" daemon prio=5 tid=14 TimedWaiting
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x2493300 self=0xdcb3b0a60400
  | sysTid=26090 nice=0 cgrp=default sched=0/0 handle=0xdac362bfa000
  | state=S schedstat=( 4536850 6989272 27 ) utm=0 stm=0 core=0 HZ=100
  | stack=0xdac362aad000-0xdac362ab1000 stackSize=1051KB
  | held mutexes=
  at jdk.internal.misc.Unsafe.park(Native method)
  - waiting on an unknown object
  at java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:417)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.park(CoroutineScheduler.kt:856)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.tryPark(CoroutineScheduler.kt:801)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:749)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:702)
DumpLatencyMs: 674.807
```

### Thread DefaultDispatcher-worker-1

```
"DefaultDispatcher-worker-1" daemon prio=5 tid=19 Native
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x2246c18 self=0xdcb3b0ae7000
  | sysTid=25933 nice=0 cgrp=default sched=0/0 handle=0xdac30927d000
  | state=S schedstat=( 125849812169 69812225843 106225 ) utm=11766 stm=818 core=4 HZ=100
  | stack=0xdac30912a000-0xdac30912e000 stackSize=1051KB
  | held mutexes=
  native: #00 pc 000aef20  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+32) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  native: #01 pc 00072560  /system/lib64/libaudiobase.so (android::ClientProxy::obtainBuffer+832) (BuildId: 57b40f68bbd55af85b4e5bc8cd07be45)
  native: #02 pc 00049510  /system/lib64/libaudioclient.so (android::AudioRecord::obtainBuffer+368) (BuildId: e67855e62792718b12a8743c48c40dba)
  native: #03 pc 0004c4c8  /system/lib64/libaudioclient.so (android::AudioRecord::read+312) (BuildId: e67855e62792718b12a8743c48c40dba)
  native: #04 pc 00237e78  /system/lib64/libandroid_runtime.so (int android_media_AudioRecord_readInArray<_jshortArray*> +216) (BuildId: 833fc3fcd4a6e4edf4abcc96ce0fc3fd)
  native: #07 pc 00475f1e  /system/framework/framework.jar (offset 968000) (android.media.AudioRecord.read+106)
  native: #09 pc 00475e9a  /system/framework/framework.jar (offset 968000) (android.media.AudioRecord.read+2)
  native: #14 pc 00006314  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.AmbientListenService.captureOnce)
  native: #18 pc 0000613c  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.AmbientListenService.capture)
  native: #22 pc 000060cc  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.AmbientListenService.access$capture)
  native: #26 pc 00005be4  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.AmbientListenService$runPipeline$2.invokeSuspend)
  native: #30 pc 00005bb8  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.AmbientListenService$runPipeline$2.invoke)
  native: #34 pc 00005b94  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.AmbientListenService$runPipeline$2.invoke)
  native: #38 pc 00357ee8  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #42 pc 00316a04  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #46 pc 00006830  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.AmbientListenService.runPipeline)
  native: #50 pc 00006120  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.AmbientListenService.access$runPipeline)
  native: #54 pc 00005904  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.AmbientListenService$onStartCommand$1.invokeSuspend)
  native: #58 pc 002cc758  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #62 pc 0031851c  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #66 pc 0035a320  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #70 pc 00358dc8  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #74 pc 00358ee0  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #78 pc 00358ec8  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #86 pc 00096e24  /apex/com.android.runtime/lib64/bionic/libc.so (__pthread_start +180) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  native: #87 pc 00085e14  /apex/com.android.runtime/lib64/bionic/libc.so (__start_thread+68) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  at android.media.AudioRecord.native_read_in_short_array(Native method)
  at android.media.AudioRecord.read(AudioRecord.java:1750)
  at android.media.AudioRecord.read(AudioRecord.java:1702)
  at com.agent.ultra.listen.AmbientListenService.captureOnce(AmbientListenService.kt:195)
  at com.agent.ultra.listen.AmbientListenService.capture(AmbientListenService.kt:168)
  at com.agent.ultra.listen.AmbientListenService.access$capture(AmbientListenService.kt:44)
  at com.agent.ultra.listen.AmbientListenService$runPipeline$2.invokeSuspend(AmbientListenService.kt:110)
  at com.agent.ultra.listen.AmbientListenService$runPipeline$2.invoke(unavailable:8)
  at com.agent.ultra.listen.AmbientListenService$runPipeline$2.invoke(unavailable:4)
  at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndispatchedOrReturn(Undispatched.kt:61)
  at kotlinx.coroutines.CoroutineScopeKt.coroutineScope(CoroutineScope.kt:261)
  at com.agent.ultra.listen.AmbientListenService.runPipeline(AmbientListenService.kt:106)
  at com.agent.ultra.listen.AmbientListenService.access$runPipeline(AmbientListenService.kt:44)
  at com.agent.ultra.listen.AmbientListenService$onStartCommand$1.invokeSuspend(AmbientListenService.kt:95)
  at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
  at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:104)
  at kotlinx.coroutines.scheduling.CoroutineScheduler.runSafely(CoroutineScheduler.kt:584)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.executeTask(CoroutineScheduler.kt:811)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:715)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:702)
DumpLatencyMs: 631.055
```

### Thread DefaultDispatcher-worker-2

```
"DefaultDispatcher-worker-2" daemon prio=5 tid=20 TimedWaiting
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x2247308 self=0xdcb3b0ae3800
  | sysTid=25934 nice=0 cgrp=default sched=0/0 handle=0xdac300584000
  | state=S schedstat=( 90589451196 35674478553 26729 ) utm=8727 stm=331 core=4 HZ=100
  | stack=0xdac30040a000-0xdac30040e000 stackSize=1051KB
  | held mutexes=
  at jdk.internal.misc.Unsafe.park(Native method)
  - waiting on an unknown object
  at java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:417)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.park(CoroutineScheduler.kt:856)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.tryPark(CoroutineScheduler.kt:801)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:749)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:702)
DumpLatencyMs: 653.112
```

### Thread DefaultDispatcher-worker-3

```
"DefaultDispatcher-worker-3" daemon prio=5 tid=23 Native
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x2411278 self=0xdcb3b0ae1c00
  | sysTid=25939 nice=0 cgrp=default sched=0/0 handle=0xdac22d416000
  | state=R schedstat=( 495474994629 193740320390 106978 ) utm=49032 stm=515 core=1 HZ=100
  | stack=0xdac22d2fc000-0xdac22d300000 stackSize=1051KB
  | held mutexes=
  native: #00 pc 00190594  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #01 pc 0018ff5c  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #02 pc 00157ed0  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #03 pc 00109e64  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #04 pc 00109454  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #05 pc 000b4b84  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #06 pc 000b4410  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #07 pc 000c2954  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #08 pc 000c272c  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #09 pc 000a30bc  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (Java_com_agent_ultra_listen_WhisperNative_nativeTranscribe+732) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #16 pc 0000d02c  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.WhisperNative.transcribe)
  native: #21 pc 00007180  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.AmbientListenService.onUtterance)
  native: #26 pc 00006fa8  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.AmbientListenService.access$onUtterance)
  native: #31 pc 00005a60  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.AmbientListenService$runPipeline$2$1.invokeSuspend)
  native: #35 pc 002cc758  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #39 pc 0031851c  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #43 pc 0035a320  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #47 pc 00358dc8  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #51 pc 00358ee0  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #55 pc 00358ec8  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #63 pc 00096e24  /apex/com.android.runtime/lib64/bionic/libc.so (__pthread_start +180) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  native: #64 pc 00085e14  /apex/com.android.runtime/lib64/bionic/libc.so (__start_thread+68) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  at com.agent.ultra.listen.WhisperNative.nativeTranscribe(Native method)
  at com.agent.ultra.listen.WhisperNative.transcribe(WhisperNative.kt:48)
  at com.agent.ultra.listen.AmbientListenService.onUtterance(AmbientListenService.kt:239)
  at com.agent.ultra.listen.AmbientListenService.access$onUtterance(AmbientListenService.kt:44)
  at com.agent.ultra.listen.AmbientListenService$runPipeline$2$1.invokeSuspend(AmbientListenService.kt:109)
  at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
  at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:104)
  at kotlinx.coroutines.scheduling.CoroutineScheduler.runSafely(CoroutineScheduler.kt:584)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.executeTask(CoroutineScheduler.kt:811)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:715)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:702)
DumpLatencyMs: 654.679
```

### Thread DefaultDispatcher-worker-4

```
"DefaultDispatcher-worker-4" daemon prio=5 tid=24 TimedWaiting
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x242dcd0 self=0xdcb3b0a21400
  | sysTid=26055 nice=0 cgrp=default sched=0/0 handle=0xdabfaa9bb000
  | state=S schedstat=( 6830708 21081094 46 ) utm=0 stm=0 core=0 HZ=100
  | stack=0xdabfaa899000-0xdabfaa89d000 stackSize=1051KB
  | held mutexes=
  at jdk.internal.misc.Unsafe.park(Native method)
  - waiting on an unknown object
  at java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:417)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.park(CoroutineScheduler.kt:856)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.tryPark(CoroutineScheduler.kt:801)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:749)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:702)
DumpLatencyMs: 674.658
```

### Thread Thread-8

```
"Thread-8" prio=5 tid=28 Native
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x242e5b8 self=0xdcb3b0b78400
  | sysTid=25944 nice=0 cgrp=default sched=0/0 handle=0xdac35d945000
  | state=S schedstat=( 17301039 2338279 8 ) utm=1 stm=0 core=4 HZ=100
  | stack=0xdac35cf4a000-0xdac35cf4e000 stackSize=8155KB
  | held mutexes=
  native: #00 pc 000aef24  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+36) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  native: #01 pc 000849e4  /apex/com.android.runtime/lib64/bionic/libc.so (__futex_wait_ex+148) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  native: #02 pc 00095e78  /apex/com.android.runtime/lib64/bionic/libc.so (pthread_cond_timedwait+136) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  native: #03 pc 00b12c3c  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset bb8000) (???) (BuildId: cde4ee2ccabaee8514cda78e3674e97ada02438e)
  native: #04 pc 01afda30  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset bb8000) (???) (BuildId: cde4ee2ccabaee8514cda78e3674e97ada02438e)
  native: #05 pc 01afe2c0  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset bb8000) (???) (BuildId: cde4ee2ccabaee8514cda78e3674e97ada02438e)
  native: #06 pc 00096e24  /apex/com.android.runtime/lib64/bionic/libc.so (__pthread_start +180) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  native: #07 pc 00085e14  /apex/com.android.runtime/lib64/bionic/libc.so (__start_thread+68) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  (no managed stack frames)
DumpLatencyMs: 667.681
```

### Thread DefaultDispatcher-worker-6

```
"DefaultDispatcher-worker-6" daemon prio=5 tid=34 TimedWaiting
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x2493768 self=0xdcb3b0aff800
  | sysTid=26091 nice=0 cgrp=default sched=0/0 handle=0xdac30d342000
  | state=S schedstat=( 149789073206 22583717739 25336 ) utm=14865 stm=113 core=3 HZ=100
  | stack=0xdac30d1f5000-0xdac30d1f9000 stackSize=1051KB
  | held mutexes=
  at jdk.internal.misc.Unsafe.park(Native method)
  - waiting on an unknown object
  at java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:417)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.park(CoroutineScheduler.kt:856)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.tryPark(CoroutineScheduler.kt:801)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:749)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:702)
DumpLatencyMs: 674.944
```

### Thread DefaultDispatcher-worker-7

```
"DefaultDispatcher-worker-7" daemon prio=5 tid=48 TimedWaiting
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x250be98 self=0xdcb3b0c15c00
  | sysTid=27325 nice=0 cgrp=default sched=0/0 handle=0xdac22daae000
  | state=S schedstat=( 270956235560 76107315977 648566 ) utm=20381 stm=6714 core=5 HZ=100
  | stack=0xdac22d997000-0xdac22d99b000 stackSize=1051KB
  | held mutexes=
  at jdk.internal.misc.Unsafe.park(Native method)
  - waiting on an unknown object
  at java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:417)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.park(CoroutineScheduler.kt:856)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.tryPark(CoroutineScheduler.kt:801)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:749)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:702)
DumpLatencyMs: 678.423
```

## ANR 2: `Error_in_Jarvis_86bceeafe0d2.txt`

**Subject: Input dispatching timed out (8ff0f55 com.agent.ultra/com.agent.ultra.MainActivity is not responding. Waited 5000ms for MotionEvent).**

### Header

```
type: ANR
osVersion: google/mustang/mustang:17/CP3A.260905.009/2026100201:user/release-keys
package: com.agent.ultra:17, targetSdk 35
process: com.agent.ultra
installer: com.android.packageinstaller

Subject: Input dispatching timed out (8ff0f55 com.agent.ultra/com.agent.ultra.MainActivity is not responding. Waited 5000ms for MotionEvent).
Timeout: 5000
TimeoutStart: 12140137
RssHwmKb: 8710708
RssKb: 8573736
RssAnonKb: 360096
RssShmemKb: 1188
VmSwapKb: 149652
BaseFsVerity: false


--- CriticalEventLog ---
capacity: 20
timestamp_ms: 12145168
window_ms: 300000

----- dumping pid: 20775 at 12145168

----- pid 20775 at 2026-10-06 01:10:25.204829048-0700 -----
Cmd line: com.agent.ultra
Build fingerprint: 'google/mustang/mustang:17/CP3A.260905.009/2026100201:user/release-keys'
ABI: 'arm64'
Build type: optimized
Debug Store: (omitted, ~1 KB of broadcast bookkeeping)
suspend all histogram:	Sum: 18.303ms 99% C.I. 0.273us-767.039us Avg: 67.043us Max: 1048us
DALVIK THREADS (41):
```

### Thread main

```
"main" prio=5 tid=1 Native
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x73274478 self=0xa00c83e7bad8000
  | sysTid=20775 nice=-10 cgrp=default sched=1073741824/0 handle=0xc994345bf000
  | state=R schedstat=( 99274524529 2457473414 43840 ) utm=9010 stm=916 core=7 HZ=100
  | stack=0xf7bb35a66000-0xf7bb35a6a000 stackSize=8188KB
  | held mutexes=
  native: #00 pc 000aef20  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+32) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  native: #03 pc 00201358  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 4e4c000) (_JNIEnv::ReleaseStringUTFChars+44) (BuildId: 55bebca889cac3e479e6c3998673243b34214095)
  native: #04 pc 00200d70  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 4e4c000) (Java_com_agent_ultra_local_LlmNative_nativeLoad+204) (BuildId: 55bebca889cac3e479e6c3998673243b34214095)
  native: #10 pc 00005264  [anon:dalvik-classes5.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.local.LocalModelEngine.ensureLoaded)
  native: #14 pc 000205e8  [anon:dalvik-classes3.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.agent.Brain.runUnlocked)
  native: #18 pc 00019c40  [anon:dalvik-classes3.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.agent.Brain.access$runUnlocked)
  native: #22 pc 0001954c  [anon:dalvik-classes3.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.agent.Brain$runUnlocked$1.invokeSuspend)
  native: #26 pc 002cc758  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #30 pc 0031851c  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #34 pc 0029c060  /system/framework/framework.jar (offset 126b000) (android.os.Handler.handleCallback)
  native: #38 pc 0029be9c  /system/framework/framework.jar (offset 126b000) (android.os.Handler.dispatchMessageImpl)
  native: #42 pc 0029be84  /system/framework/framework.jar (offset 126b000) (android.os.Handler.dispatchMessage)
  native: #46 pc 002c4ef0  /system/framework/framework.jar (offset 126b000) (android.os.Looper.loopOnce)
  native: #50 pc 002c5aa4  /system/framework/framework.jar (offset 126b000) (android.os.Looper.loop)
  native: #54 pc 001e0688  /system/framework/framework.jar (offset 6000) (android.app.ActivityThread.main)
  native: #66 pc 00365124  /system/framework/framework.jar (offset 23bc000) (com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run)
  at com.agent.ultra.local.LlmNative.nativeLoad(Native method)
  at com.agent.ultra.local.LocalModelEngine.ensureLoaded(LocalModelEngine.kt:190)
  - locked <@addr=0x22f49b8> (a java.lang.Object)
  at com.agent.ultra.agent.Brain.runUnlocked(Brain.kt:188)
  at com.agent.ultra.agent.Brain.access$runUnlocked(Brain.kt:28)
  at com.agent.ultra.agent.Brain$runUnlocked$1.invokeSuspend(unavailable:15)
  at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
  at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:104)
  at android.os.Handler.handleCallback(Handler.java:1095)
  at android.os.Handler.dispatchMessageImpl(Handler.java:135)
  at android.os.Handler.dispatchMessage(Handler.java:125)
  at android.os.Looper.loopOnce(Looper.java:296)
  at android.os.Looper.loop(Looper.java:397)
  at android.app.ActivityThread.main(ActivityThread.java:9569)
  at java.lang.reflect.Method.invoke(Native method)
  at com.android.internal.os.RuntimeInit$MethodAndArgsCaller.run(RuntimeInit.java:575)
  at com.android.internal.os.ZygoteInit.main(ZygoteInit.java:975)
DumpLatencyMs: 1.95133
```

### Thread DefaultDispatcher-worker-1

```
"DefaultDispatcher-worker-1" daemon prio=5 tid=21 Native
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x23fc750 self=0xc83e7bc14c00
  | sysTid=20813 nice=0 cgrp=default sched=0/0 handle=0xc64baf1d6000
  | state=R schedstat=( 1269713820 1680710839 998 ) utm=123 stm=3 core=0 HZ=100
  | stack=0xc64baf068000-0xc64baf06c000 stackSize=1051KB
  | held mutexes=
  native: #00 pc 00190598  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #01 pc 0018ff5c  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #02 pc 00157ed0  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #03 pc 00109e64  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #04 pc 00109454  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #05 pc 000b4b84  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #06 pc 000b4410  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #07 pc 000c2954  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #08 pc 000c272c  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (???) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #09 pc 000a30bc  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset 595c000) (Java_com_agent_ultra_listen_WhisperNative_nativeTranscribe+732) (BuildId: 958af99fb2220380212cae9752dbc999e89dad9e)
  native: #16 pc 0000d02c  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.WhisperNative.transcribe)
  native: #21 pc 00007180  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.AmbientListenService.onUtterance)
  native: #26 pc 00006fa8  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.AmbientListenService.access$onUtterance)
  native: #31 pc 00005a60  [anon:dalvik-classes8.dex extract...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (com.agent.ultra.listen.AmbientListenService$runPipeline$2$1.invokeSuspend)
  native: #35 pc 002cc758  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #39 pc 0031851c  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #43 pc 0035a320  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #47 pc 00358dc8  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #51 pc 00358ee0  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #55 pc 00358ec8  [anon:dalvik-classes12.dex extrac...com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk] (???)
  native: #63 pc 00096e24  /apex/com.android.runtime/lib64/bionic/libc.so (__pthread_start +180) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  native: #64 pc 00085e14  /apex/com.android.runtime/lib64/bionic/libc.so (__start_thread+68) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  at com.agent.ultra.listen.WhisperNative.nativeTranscribe(Native method)
  at com.agent.ultra.listen.WhisperNative.transcribe(WhisperNative.kt:48)
  at com.agent.ultra.listen.AmbientListenService.onUtterance(AmbientListenService.kt:239)
  at com.agent.ultra.listen.AmbientListenService.access$onUtterance(AmbientListenService.kt:44)
  at com.agent.ultra.listen.AmbientListenService$runPipeline$2$1.invokeSuspend(AmbientListenService.kt:109)
  at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
  at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:104)
  at kotlinx.coroutines.scheduling.CoroutineScheduler.runSafely(CoroutineScheduler.kt:584)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.executeTask(CoroutineScheduler.kt:811)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:715)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:702)
DumpLatencyMs: 163.817
```

### Thread DefaultDispatcher-worker-2

```
"DefaultDispatcher-worker-2" daemon prio=5 tid=22 Runnable
  | group="main" sCount=0 ucsCount=0 flags=0 obj=0x23fcd70 self=0xc83e7bc13000
  | sysTid=20814 nice=0 cgrp=default sched=0/0 handle=0xc64baf05a000
  | state=R schedstat=( 254391644395 13511627610 791815 ) utm=15315 stm=10123 core=4 HZ=100
  | stack=0xc64baeefa000-0xc64baeefe000 stackSize=1051KB
  | held mutexes= "mutator lock"(shared held)
  at com.agent.ultra.listen.Segmenter$Companion.dbfs(Segmenter.kt:82)
  at com.agent.ultra.listen.Segmenter.push(Segmenter.kt:44)
  at com.agent.ultra.listen.AmbientListenService.captureOnce(AmbientListenService.kt:209)
  at com.agent.ultra.listen.AmbientListenService.capture(AmbientListenService.kt:168)
  at com.agent.ultra.listen.AmbientListenService.access$capture(AmbientListenService.kt:44)
  at com.agent.ultra.listen.AmbientListenService$runPipeline$2.invokeSuspend(AmbientListenService.kt:110)
  at com.agent.ultra.listen.AmbientListenService$runPipeline$2.invoke(unavailable:8)
  at com.agent.ultra.listen.AmbientListenService$runPipeline$2.invoke(unavailable:4)
  at kotlinx.coroutines.intrinsics.UndispatchedKt.startUndispatchedOrReturn(Undispatched.kt:61)
  at kotlinx.coroutines.CoroutineScopeKt.coroutineScope(CoroutineScope.kt:261)
  at com.agent.ultra.listen.AmbientListenService.runPipeline(AmbientListenService.kt:106)
  at com.agent.ultra.listen.AmbientListenService.access$runPipeline(AmbientListenService.kt:44)
  at com.agent.ultra.listen.AmbientListenService$onStartCommand$1.invokeSuspend(AmbientListenService.kt:95)
  at kotlin.coroutines.jvm.internal.BaseContinuationImpl.resumeWith(ContinuationImpl.kt:33)
  at kotlinx.coroutines.DispatchedTask.run(DispatchedTask.kt:104)
  at kotlinx.coroutines.scheduling.CoroutineScheduler.runSafely(CoroutineScheduler.kt:584)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.executeTask(CoroutineScheduler.kt:811)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:715)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:702)
DumpLatencyMs: 1.93578
```

### Thread DefaultDispatcher-worker-3

```
"DefaultDispatcher-worker-3" daemon prio=5 tid=23 TimedWaiting
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x23ff290 self=0xc83e7bc0c000
  | sysTid=20815 nice=0 cgrp=default sched=0/0 handle=0xc64baceec000
  | state=S schedstat=( 35754503 4735676 67 ) utm=3 stm=0 core=6 HZ=100
  | stack=0xc64bacdd7000-0xc64bacddb000 stackSize=1051KB
  | held mutexes=
  at jdk.internal.misc.Unsafe.park(Native method)
  - waiting on an unknown object
  at java.util.concurrent.locks.LockSupport.parkNanos(LockSupport.java:417)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.park(CoroutineScheduler.kt:856)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.tryPark(CoroutineScheduler.kt:801)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.runWorker(CoroutineScheduler.kt:749)
  at kotlinx.coroutines.scheduling.CoroutineScheduler$Worker.run(CoroutineScheduler.kt:702)
DumpLatencyMs: 169.91
```

### Thread Thread-10

```
"Thread-10" prio=5 tid=44 Native
  | group="main" sCount=1 ucsCount=0 flags=1 obj=0x27504a0 self=0xc83e7bbbb800
  | sysTid=21622 nice=0 cgrp=default sched=0/0 handle=0xc64b58e5e000
  | state=S schedstat=( 6634763 911771 8 ) utm=0 stm=0 core=3 HZ=100
  | stack=0xc64b582c5000-0xc64b582c9000 stackSize=8155KB
  | held mutexes=
  native: #00 pc 000aef24  /apex/com.android.runtime/lib64/bionic/libc.so (syscall+36) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  native: #01 pc 000849e4  /apex/com.android.runtime/lib64/bionic/libc.so (__futex_wait_ex+148) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  native: #02 pc 00095e78  /apex/com.android.runtime/lib64/bionic/libc.so (pthread_cond_timedwait+136) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  native: #03 pc 00b12c3c  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset bb8000) (???) (BuildId: cde4ee2ccabaee8514cda78e3674e97ada02438e)
  native: #04 pc 01afda30  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset bb8000) (???) (BuildId: cde4ee2ccabaee8514cda78e3674e97ada02438e)
  native: #05 pc 01afe2c0  /data/app/~~T9FhSAupXd0VywhMlPJWyg==/com.agent.ultra-h9YUIdo7UqHkwqkIe2xQ_w==/base.apk (offset bb8000) (???) (BuildId: cde4ee2ccabaee8514cda78e3674e97ada02438e)
  native: #06 pc 00096e24  /apex/com.android.runtime/lib64/bionic/libc.so (__pthread_start +180) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  native: #07 pc 00085e14  /apex/com.android.runtime/lib64/bionic/libc.so (__start_thread+68) (BuildId: b0bf9d275220e0cd7dc779902337c7e8)
  (no managed stack frames)
DumpLatencyMs: 180.18
```
