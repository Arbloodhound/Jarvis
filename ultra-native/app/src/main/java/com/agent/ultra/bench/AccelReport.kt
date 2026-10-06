package com.agent.ultra.bench

import com.agent.ultra.listen.WakeGate
import java.util.Locale

/**
 * Verdicts and formatting for the debug-only accelerator probe (src/debug/.../AccelProbeReceiver,
 * driven by tools/accel-probe.sh). Lives in main only so the JVM tests can reach it; nothing in a
 * release code path references it, so R8 drops it. Pure, JVM-tested, no Android types.
 *
 * What the probe can and cannot prove: an NNAPI session created with CPU_DISABLED always
 * "succeeds" — ops no accelerator takes simply stay on ONNX Runtime's CPU kernels. So creation
 * alone proves nothing; a clear speed-up over plain CPU is the in-app evidence that some
 * accelerator ran the model, and the registered NNAPI service's name (from `dumpsys -l` / lshal,
 * passed in by the script) is the evidence of WHICH one. A TPU verdict needs both. Vendor file
 * names are only a hint: Pixels ship edgetpu libraries and init files whether or not NNAPI
 * exposes the TPU.
 */
object AccelReport {
    /**
     * FASTER: WakeGate itself would put this model on NNAPI (same 20% margin and same output
     * check, by calling WakeGate.useAccelerator). FASTER_WRONG: as fast, but the numbers differ
     * from the CPU's (fp16-relaxed or buggy driver): an accelerator ran it, the app won't use it.
     */
    enum class Nnapi { FASTER, FASTER_WRONG, NOT_FASTER, FAILED, UNTESTED }

    enum class Tpu(val label: String) { AVAILABLE("available"), NOT_AVAILABLE("not available"), UNKNOWN("unknown") }

    data class Verdict(val tpu: Tpu, val reason: String) {
        /** The line tools/accel-probe.sh greps for. */
        fun line(): String = "verdict TPU via NNAPI: ${tpu.label} ($reason)"
    }

    /** Driver / service names that mean the Google TPU ("darwinn" is its NNAPI driver name on
     *  Tensor G1–G4, "edgetpu" its kernel/HAL name). Word-bounded "tpu" so "output" can't match. */
    private val TPU_NAME = Regex("darwinn|edge[-_]?tpu|(^|[^a-z]|lib)tpu([^a-z]|$)", RegexOption.IGNORE_CASE)

    /** Broader: any vendor file that looks like an NN accelerator driver (TPU, MediaTek APU /
     *  NeuroPilot, Arm NN, Samsung/other NPU, the NNAPI HAL itself). Logged, not judged; "npu"
     *  word-bounded like "tpu" ("input" contains it). */
    private val NN_DRIVER_NAME = Regex(
        "darwinn|edge[-_]?tpu|(^|[^a-z]|lib)tpu([^a-z]|$)|neuralnetworks|nnapi|neuron|apusys|armnn|(^|[^a-z]|lib)npu|gxp",
        RegexOption.IGNORE_CASE,
    )

    fun isTpuName(name: String): Boolean = TPU_NAME.containsMatchIn(name)

    fun isNnDriverName(name: String): Boolean = NN_DRIVER_NAME.containsMatchIn(name)

    /**
     * The script's `--es hal` extra: comma-separated NNAPI service names from dumpsys/lshal.
     * null = the script could not look (or the broadcast was sent by hand), "none" = it looked
     * and found nothing (an empty extra cannot be sent through `adb shell am`).
     */
    fun parseHalExtra(extra: String?): List<String>? = when {
        extra == null -> null
        extra.trim() == "none" -> emptyList()
        else -> extra.split(',').map { it.trim() }.filter { it.isNotEmpty() && it != "none" }
    }

    /** Library names from /vendor/etc/public.libraries.txt (what apps may load from /vendor). */
    fun publicLibraries(text: String): List<String> =
        text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { it.split(Regex("\\s+")).first() }
            .toList()

    /** null timings = the NNAPI session could not be created / run; no model = not tested.
     *  [maxDiff]: largest |cpu - nnapi| output difference; [scale]: largest |cpu| output. */
    fun nnapiVerdict(hasModel: Boolean, cpuMs: Double?, nnapiMs: Double?, maxDiff: Float, scale: Float): Nnapi = when {
        !hasModel -> Nnapi.UNTESTED
        cpuMs == null || nnapiMs == null || cpuMs <= 0 || nnapiMs <= 0 -> Nnapi.FAILED
        WakeGate.useAccelerator(cpuMs, nnapiMs, maxDiff, scale) -> Nnapi.FASTER
        WakeGate.useAccelerator(cpuMs, nnapiMs, 0f, 0f) -> Nnapi.FASTER_WRONG
        else -> Nnapi.NOT_FASTER
    }

    /**
     * [halNames]: NNAPI services the script saw (null = not looked); [halComplete]: both the AIDL
     * (dumpsys) and HIDL (lshal) lists were read; the Tensor G1-G3 TPU driver is HIDL, and
     * non-root lshal often fails. [fileHints]: NN-driver-looking vendor file names (null = not
     * listable). [nnapi]: the best result over the models tested.
     * "available" only when a TPU-named service is registered AND WakeGate would use NNAPI;
     * "not available" only when both lists were read, hold no TPU service, and NNAPI was tested
     * and gave no speed-up; everything else is "unknown" with the reason.
     */
    fun tpuVerdict(halNames: List<String>?, halComplete: Boolean, fileHints: List<String>?, nnapi: Nnapi): Verdict {
        val tpuHal = halNames.orEmpty().filter { isTpuName(it) }.distinct()
        val tpuFiles = fileHints.orEmpty().filter { isTpuName(it) }.distinct()
        val speed = when (nnapi) {
            Nnapi.FASTER -> "NNAPI ran the wake model faster than CPU with matching output"
            Nnapi.FASTER_WRONG -> "NNAPI ran the wake model faster than CPU but its output differs"
            Nnapi.NOT_FASTER -> "NNAPI gave no speed-up over CPU on the wake model"
            Nnapi.FAILED -> "NNAPI session failed"
            Nnapi.UNTESTED -> "no wake model to test NNAPI with"
        }
        val files = if (tpuFiles.isEmpty()) "" else "; TPU vendor files ${tpuFiles.joinToString(",")}"
        return when {
            tpuHal.isNotEmpty() && nnapi == Nnapi.FASTER ->
                Verdict(Tpu.AVAILABLE, "TPU service ${tpuHal.joinToString(",")}; $speed")
            tpuHal.isNotEmpty() ->
                Verdict(Tpu.UNKNOWN, "TPU service ${tpuHal.joinToString(",")} registered, but $speed")
            halNames == null ->
                Verdict(Tpu.UNKNOWN, "NNAPI service list not read (run tools/accel-probe.sh); $speed$files")
            nnapi == Nnapi.FASTER || nnapi == Nnapi.FASTER_WRONG ->
                Verdict(Tpu.UNKNOWN, "$speed, but no TPU-named service (" +
                    halNames.ifEmpty { listOf("no NNAPI services") }.joinToString(",") + "): GPU/APU?$files")
            !halComplete ->
                Verdict(Tpu.UNKNOWN, "no TPU-named AIDL service, HIDL list (lshal) not read; $speed$files")
            nnapi != Nnapi.NOT_FASTER ->
                Verdict(Tpu.UNKNOWN, "no TPU-named NNAPI service, but $speed$files")
            else ->
                Verdict(Tpu.NOT_AVAILABLE, "no TPU-named NNAPI service" +
                    (if (halNames.isEmpty()) "" else " (${halNames.joinToString(",")})") + "; $speed$files")
        }
    }

    /** GPU compute reachable from the app: OpenCL loads (usable by a GPU backend) / is on the
     *  phone but the linker refuses it / is absent. Vulkan is reported separately as files. */
    fun openClVerdict(anyExists: Boolean, anyLoaded: Boolean): String = when {
        // The debug manifest declares uses-native-library for it; release would need the same.
        anyLoaded -> "OpenCL: loadable by the debug app (release needs the same uses-native-library)"
        anyExists -> "OpenCL: present but not loadable by the app"
        else -> "OpenCL: not found"
    }

    fun ms(v: Double?): String = if (v == null) "-" else "%.2f".format(Locale.ROOT, v)

    /** One model's timing line. tools/accel-probe.sh prints these as-is. */
    fun modelLine(tag: String, cpuMs: Double?, nnapiMs: Double?, xnnpackMs: Double?, nnapiDiff: Float?): String =
        "model $tag: cpu=${ms(cpuMs)}ms nnapi=${ms(nnapiMs)}ms xnnpack=${ms(xnnpackMs)}ms " +
            "nnapiDiff=${nnapiDiff?.let { "%.1e".format(Locale.ROOT, it) } ?: "-"}"

    /** One library probe line: where it was looked for, whether the file is there, load result. */
    fun libLine(path: String, exists: Boolean, load: String): String =
        "lib $path exists=${if (exists) "yes" else "no"} load=$load"

    /** A load error message, one line and short enough for logcat. */
    fun shortError(t: Throwable): String =
        "${t.javaClass.simpleName}: ${t.message.orEmpty()}".replace('\n', ' ').take(160)

    /** Best NNAPI result across models: FASTER, FASTER_WRONG, NOT_FASTER, FAILED, in that order. */
    fun best(results: List<Nnapi>): Nnapi = when {
        results.isEmpty() -> Nnapi.UNTESTED
        Nnapi.FASTER in results -> Nnapi.FASTER
        Nnapi.FASTER_WRONG in results -> Nnapi.FASTER_WRONG
        Nnapi.NOT_FASTER in results -> Nnapi.NOT_FASTER
        Nnapi.FAILED in results -> Nnapi.FAILED
        else -> Nnapi.UNTESTED
    }
}
