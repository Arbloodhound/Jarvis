package com.agent.jarvis.local

import org.junit.Assert.assertEquals
import org.junit.Test

class CpuVariantTest {
    private val v9 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm " +
        "jscvt fcma lrcpc dcpop sha3 sm3 sm4 asimddp sha512 asimdfhm dit uscat ilrcpc flagm ssbs sb " +
        "paca pacg dcpodp sve2 sveaes svepmull svebitperm svesha3 svesm4 flagm2 frint svei8mm svebf16 " +
        "i8mm bf16 dgh bti"
    private val a76 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 atomics fphp asimdhp cpuid asimdrdm " +
        "lrcpc dcpop asimddp"
    private val a53 = "fp asimd evtstrm aes pmull sha1 sha2 crc32 cpuid"

    private fun cpuinfo(vararg cores: String) = cores.mapIndexed { i, f ->
        "processor\t: $i\nBogoMIPS\t: 49.15\nFeatures\t: $f\nCPU implementer\t: 0x41\n"
    }.joinToString("\n")

    @Test fun tensorG5AllCoresV9GetsI8mm() = assertEquals(
        listOf(CpuVariant.I8MM, CpuVariant.DOTPROD, CpuVariant.BASE),
        CpuVariant.candidates(cpuinfo(v9, v9, v9, v9, v9, v9, v9, v9)),
    )

    @Test fun dotprodWithoutI8mm() = assertEquals(
        listOf(CpuVariant.DOTPROD, CpuVariant.BASE),
        CpuVariant.candidates(cpuinfo(a76, a76, a76, a76)),
    )

    @Test fun oldCoresGetTheBaseBuild() = assertEquals(
        listOf(CpuVariant.BASE),
        CpuVariant.candidates(cpuinfo(a53, a53)),
    )

    /** One core without i8mm is enough to rule it out: a thread can be scheduled there. */
    @Test fun aFeatureMustBeOnEveryCore() = assertEquals(
        listOf(CpuVariant.DOTPROD, CpuVariant.BASE),
        CpuVariant.candidates(cpuinfo(v9, v9, a76, a76)),
    )

    @Test fun noFeaturesLineMeansBase() {
        assertEquals(listOf(CpuVariant.BASE), CpuVariant.candidates(""))
        assertEquals(listOf(CpuVariant.BASE), CpuVariant.candidates("processor\t: 0\nHardware\t: x\n"))
    }

    /** i8mm without fp16 maths: the i8mm build also uses fp16, so neither newer build. */
    @Test fun i8mmAloneIsNotEnough() = assertEquals(
        listOf(CpuVariant.BASE),
        CpuVariant.candidates(cpuinfo("fp asimd asimddp i8mm")),
    )

    @Test fun suffixesBestFirst() {
        assertEquals(listOf("_i8mm", "_dotprod", ""), CpuVariant.suffixes(cpuinfo(v9, v9)))
        assertEquals(listOf("_dotprod", ""), CpuVariant.suffixes(cpuinfo(v9, a76)))
        assertEquals(listOf(""), CpuVariant.suffixes(cpuinfo(a53)))
    }

    /** whisper shares the tier decision; its names come from the same suffixes. */
    @Test fun whisperGetsTheSameTiers() {
        assertEquals(
            listOf("jarvis_whisper_i8mm", "jarvis_whisper_dotprod", "jarvis_whisper"),
            CpuVariant.candidates(cpuinfo(v9, v9, v9), "jarvis_whisper"),
        )
        assertEquals(listOf("jarvis_whisper"), CpuVariant.candidates(cpuinfo(v9, a53), "jarvis_whisper"))
        assertEquals(listOf("jarvis_whisper"), CpuVariant.candidates("", "jarvis_whisper"))
    }

    /** The one-argument form is the llama base, so LlmNative's names are unchanged. */
    @Test fun defaultBaseIsLlama() = assertEquals(
        CpuVariant.candidates(cpuinfo(v9, v9), CpuVariant.BASE),
        CpuVariant.candidates(cpuinfo(v9, v9)),
    )
}
