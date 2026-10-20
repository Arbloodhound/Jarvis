package com.agent.jarvis.bench

import com.agent.jarvis.bench.AccelReport.Nnapi
import com.agent.jarvis.bench.AccelReport.Tpu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccelReportTest {
    @Test fun tpuNames() {
        assertTrue(AccelReport.isTpuName("android.hardware.neuralnetworks.IDevice/google-edgetpu"))
        assertTrue(AccelReport.isTpuName("android.hardware.neuralnetworks@1.3::IDevice/darwinn"))
        assertTrue(AccelReport.isTpuName("libedgetpu_util.so"))
        assertTrue(AccelReport.isTpuName("google-tpu"))
        assertTrue(AccelReport.isTpuName("libtpu.so"))
        assertFalse(AccelReport.isTpuName("android.hardware.neuralnetworks.IDevice/mtk-gpu_shim"))
        assertFalse(AccelReport.isTpuName("output_tensor"))
    }

    @Test fun nnDriverNames() {
        assertTrue(AccelReport.isNnDriverName("libneuron_adapter.so"))
        assertTrue(AccelReport.isNnDriverName("libarmnn.so"))
        assertTrue(AccelReport.isNnDriverName("android.hardware.neuralnetworks-shim-service-mtk.xml"))
        assertTrue(AccelReport.isNnDriverName("libnpu.so"))
        assertFalse(AccelReport.isNnDriverName("libinput.so"))
        assertFalse(AccelReport.isNnDriverName("libOpenCL.so"))
    }

    @Test fun halExtra() {
        assertNull(AccelReport.parseHalExtra(null))
        assertEquals(emptyList<String>(), AccelReport.parseHalExtra("none"))
        assertEquals(listOf("a/x", "b"), AccelReport.parseHalExtra(" a/x ,, b,"))
    }

    @Test fun publicLibrariesTxt() = assertEquals(
        listOf("libOpenCL.so", "libedgetpu_client.google.so"),
        AccelReport.publicLibraries("# comment\nlibOpenCL.so\n\n  libedgetpu_client.google.so nopreload # x\n"),
    )

    @Test fun nnapiSpeed() {
        assertEquals(Nnapi.UNTESTED, AccelReport.nnapiVerdict(false, null, null, 0f, 0f))
        assertEquals(Nnapi.FAILED, AccelReport.nnapiVerdict(true, 2.0, null, 0f, 0f))
        assertEquals(Nnapi.FAILED, AccelReport.nnapiVerdict(true, 0.0, 1.0, 0f, 0f))
        assertEquals(Nnapi.FASTER, AccelReport.nnapiVerdict(true, 10.0, 8.0, 1e-5f, 30f))
        assertEquals(Nnapi.NOT_FASTER, AccelReport.nnapiVerdict(true, 10.0, 8.5, 0f, 30f))
        assertEquals(Nnapi.NOT_FASTER, AccelReport.nnapiVerdict(true, 10.0, 30.0, 0f, 30f))
    }

    @Test fun fastButWrongOutputIsNotFaster() =
        // Same rule WakeGate applies: fp16-relaxed output that moves the score is not usable.
        assertEquals(Nnapi.FASTER_WRONG, AccelReport.nnapiVerdict(true, 10.0, 5.0, 0.1f, 1f))

    @Test fun bestAcrossModels() {
        assertEquals(Nnapi.UNTESTED, AccelReport.best(emptyList()))
        assertEquals(Nnapi.FASTER, AccelReport.best(listOf(Nnapi.FAILED, Nnapi.FASTER, Nnapi.NOT_FASTER)))
        assertEquals(Nnapi.FASTER_WRONG, AccelReport.best(listOf(Nnapi.NOT_FASTER, Nnapi.FASTER_WRONG)))
        assertEquals(Nnapi.NOT_FASTER, AccelReport.best(listOf(Nnapi.FAILED, Nnapi.NOT_FASTER)))
        assertEquals(Nnapi.FAILED, AccelReport.best(listOf(Nnapi.UNTESTED, Nnapi.FAILED)))
    }

    private val edgetpu = listOf("android.hardware.neuralnetworks.IDevice/google-edgetpu")

    @Test fun tpuAvailableNeedsServiceAndSpeedUp() {
        val v = AccelReport.tpuVerdict(edgetpu, false, null, Nnapi.FASTER)
        assertEquals(Tpu.AVAILABLE, v.tpu)
        assertTrue(v.line().startsWith("verdict TPU via NNAPI: available ("))
    }

    @Test fun tpuServiceWithoutUsableSpeedUpIsUnknown() {
        assertEquals(Tpu.UNKNOWN, AccelReport.tpuVerdict(listOf("x/darwinn"), true, null, Nnapi.NOT_FASTER).tpu)
        assertEquals(Tpu.UNKNOWN, AccelReport.tpuVerdict(edgetpu, true, null, Nnapi.FASTER_WRONG).tpu)
        assertEquals(Tpu.UNKNOWN, AccelReport.tpuVerdict(edgetpu, true, null, Nnapi.UNTESTED).tpu)
    }

    @Test fun tpuFilesAloneNeverSayAvailable() {
        // Pixels ship edgetpu libraries whether or not NNAPI exposes the TPU; a GPU could be the speed-up.
        assertEquals(Tpu.UNKNOWN, AccelReport.tpuVerdict(null, false, listOf("libedgetpu_util.so"), Nnapi.FASTER).tpu)
        assertEquals(Tpu.UNKNOWN, AccelReport.tpuVerdict(listOf("x/mtk-gpu"), true, listOf("init.edgetpu.rc"), Nnapi.FASTER).tpu)
    }

    @Test fun noServiceListIsUnknown() {
        assertEquals(Tpu.UNKNOWN, AccelReport.tpuVerdict(null, false, null, Nnapi.FASTER).tpu)
        assertEquals(Tpu.UNKNOWN, AccelReport.tpuVerdict(null, false, emptyList(), Nnapi.NOT_FASTER).tpu)
    }

    @Test fun speedUpFromNonTpuServiceIsUnknown() =
        assertEquals(Tpu.UNKNOWN, AccelReport.tpuVerdict(listOf("x/mtk-gpu"), true, null, Nnapi.FASTER).tpu)

    @Test fun notAvailableNeedsBothListsAndATestedNoSpeedUp() {
        assertEquals(Tpu.NOT_AVAILABLE, AccelReport.tpuVerdict(emptyList(), true, null, Nnapi.NOT_FASTER).tpu)
        assertEquals(Tpu.NOT_AVAILABLE, AccelReport.tpuVerdict(listOf("x/mtk-gpu"), true, null, Nnapi.NOT_FASTER).tpu)
        // lshal unread: a HIDL darwinn driver would be invisible.
        assertEquals(Tpu.UNKNOWN, AccelReport.tpuVerdict(emptyList(), false, null, Nnapi.NOT_FASTER).tpu)
        // No model, or NNAPI failed: nothing was measured.
        assertEquals(Tpu.UNKNOWN, AccelReport.tpuVerdict(emptyList(), true, null, Nnapi.UNTESTED).tpu)
        assertEquals(Tpu.UNKNOWN, AccelReport.tpuVerdict(emptyList(), true, null, Nnapi.FAILED).tpu)
    }

    @Test fun openCl() {
        assertEquals("OpenCL: loadable by the debug app (release needs the same uses-native-library)", AccelReport.openClVerdict(true, true))
        assertEquals("OpenCL: present but not loadable by the app", AccelReport.openClVerdict(true, false))
        assertEquals("OpenCL: not found", AccelReport.openClVerdict(false, false))
    }

    @Test fun modelLineFormat() {
        assertEquals("model emb: cpu=4.20ms nnapi=1.05ms xnnpack=-ms nnapiDiff=3.0e-05",
            AccelReport.modelLine("emb", 4.2, 1.05, null, 3e-5f))
        assertEquals("model cls: cpu=-ms nnapi=-ms xnnpack=-ms nnapiDiff=-",
            AccelReport.modelLine("cls", null, null, null, null))
    }

    @Test fun libLineFormat() = assertEquals(
        "lib /vendor/lib64/libOpenCL.so exists=yes load=ok",
        AccelReport.libLine("/vendor/lib64/libOpenCL.so", true, "ok"),
    )

    @Test fun shortErrorIsOneLine() =
        assertEquals("IllegalStateException: a b", AccelReport.shortError(IllegalStateException("a\nb")))
}
