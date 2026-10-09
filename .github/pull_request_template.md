## What changed
<!-- One or two lines per change, in plain words. -->

## Evidence (label every claim)
<!-- MEASURED = a number from the owner's phone (results file) · PROVEN = JVM tests or the host harness ·
     SOURCE ONLY = written, not yet run on the phone · UNKNOWN = nobody has measured it. -->
- 

## Checklist
- [ ] JVM unit + screen tests green (`./gradlew testDebugUnitTest assembleDebug`), count stated
- [ ] New logic has its own JVM tests (failing before, passing after, where it fixes a bug)
- [ ] Host harness green if native code (JNI / C++) changed
- [ ] No UI change unless the change asks for one
- [ ] No default the owner chose was changed without asking
- [ ] A phone probe added behind Jarvis Tests where only the phone can prove it
- [ ] Nothing committed that the push rules forbid: model files, keys, results files, audit logs, local.properties
- [ ] DEVLOG.md and HANDOFF.md updated
