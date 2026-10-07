package com.agent.ultra.listen

/**
 * The voices Jarvis can speak with (owner, 2026-10-07: "the voice really sucks … let me choose a
 * voice"). Each own voice is a sherpa-onnx archive from its tts-models release, pinned by sha256 and
 * size (both read from the published files on 2026-10-07), unpacked once and then used offline.
 * [PHONE] is Android's text-to-speech instead (Google's voices on a Pixel). Pure, JVM-tested.
 *
 * Kokoro (82M parameters, int8) is the most natural of them and the default, in the British male
 * voice "George". Its speaker numbers are sherpa-onnx's for kokoro-en-v0_19
 * (scripts/kokoro/v0.19/generate_voices_bin.py). The Piper voices are smaller and faster.
 */
object VoiceCatalog {

    enum class Kind { PIPER, KOKORO }

    data class Model(
        val id: String,
        val label: String,
        val blurb: String,
        val kind: Kind,
        val bytes: Long,
        val sha256: String,
        /** The .onnx file inside the archive's folder. */
        val modelFile: String,
        /** Named speakers (index = speaker id), or empty. */
        val speakers: List<String> = emptyList(),
        /** Numbered speakers when there are no names (0 = a single voice). */
        val speakerCount: Int = 0,
        val defaultSpeaker: Int = 0,
    ) {
        val url get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$id.tar.bz2"
        val sizeMb get() = ((bytes + 524_288) / 1_048_576).toInt()
        /** More than one voice inside: named, or by number. */
        val multiSpeaker get() = speakers.size > 1 || speakerCount > 1
        val maxSpeaker get() = if (speakers.isNotEmpty()) speakers.size - 1 else maxOf(0, speakerCount - 1)
    }

    /** Android's own text-to-speech engine. */
    const val PHONE = "phone"

    const val KOKORO = "kokoro-int8-en-v0_19"
    /** The voice before 2026-10-07; the first one, and what a phone already has downloaded. */
    const val NORMAN = "vits-piper-en_US-norman-medium-int8"

    const val DEFAULT = KOKORO
    const val DEFAULT_SPEAKER = 9   // George (British male)

    val all: List<Model> = listOf(
        Model(KOKORO, "Kokoro", "The most natural voice. 11 voices, American and British.", Kind.KOKORO,
            103_248_205L, "c9f0dd393615805b0bab050c340834d5e684e732aec91c0e860cd30e982c08bd", "model.int8.onnx",
            speakers = listOf(
                "American female (mix)", "Bella (American female)", "Nicole (American female)",
                "Sarah (American female)", "Sky (American female)", "Adam (American male)",
                "Michael (American male)", "Emma (British female)", "Isabella (British female)",
                "George (British male)", "Lewis (British male)",
            ), defaultSpeaker = DEFAULT_SPEAKER),
        Model("vits-piper-en_US-ryan-high-int8", "Ryan", "American male, clear (high quality).", Kind.PIPER,
            34_473_341L, "df13fc140db80fd586e98834cad703a8988c1004748daa068dd07a59e502013e", "en_US-ryan-high.onnx"),
        Model("vits-piper-en_US-lessac-high-int8", "Lessac", "American female, clear (high quality).", Kind.PIPER,
            35_022_847L, "f06e85ae07ca11adae6d3b8cedff4b600fa1181a354f1f86779866b86b2fdb33", "en_US-lessac-high.onnx"),
        Model("vits-piper-en_GB-alan-medium-int8", "Alan", "British male.", Kind.PIPER,
            21_103_831L, "f0b2526c9600657141ccbbe412f6159dceedab5bab4ba4e8857d9443acb50de4", "en_GB-alan-medium.onnx"),
        Model("vits-piper-en_GB-northern_english_male-medium-int8", "Northern English", "Male, northern English accent.", Kind.PIPER,
            21_100_291L, "9b2afdc8f35426f6b2739f255e67841679a52b56d19ece874a1eae208cb88ad1", "en_GB-northern_english_male-medium.onnx"),
        Model("vits-piper-en_GB-cori-high-int8", "Cori", "British female (high quality).", Kind.PIPER,
            35_038_210L, "115adb9000159c3a90076fff8ba75ae4d26ccbbc749a444a24dc277b0dad872b", "en_GB-cori-high.onnx"),
        Model("vits-piper-en_GB-jenny_dioco-medium-int8", "Jenny", "British female.", Kind.PIPER,
            20_950_036L, "db7ed4edd3c1e28b1afdecd9a77f5cb44db326f687632ffd364be513ec6a11e6", "en_GB-jenny_dioco-medium.onnx"),
        Model("vits-piper-en_US-amy-medium-int8", "Amy", "American female.", Kind.PIPER,
            21_028_122L, "bd23c0aa629eb3719448582f45ede49e8fa6a679061fed5eab16a6a6fd8e7e82", "en_US-amy-medium.onnx"),
        Model("vits-piper-en_US-joe-medium-int8", "Joe", "American male.", Kind.PIPER,
            21_230_019L, "644527f29eca0ada5595d7b6e4daf6388f5ac0c4870b4d42996a6687fcefeb37", "en_US-joe-medium.onnx"),
        Model("vits-piper-en_US-libritts_r-medium-int8", "LibriTTS", "904 American voices: pick one by number.", Kind.PIPER,
            23_398_348L, "7e4552e239988f4896872822b56e99e0e9e00958164e3f6bdf5ee14391fbe829", "en_US-libritts_r-medium.onnx",
            speakerCount = 904),
        Model("vits-piper-en_GB-vctk-medium-int8", "VCTK", "109 British voices: pick one by number.", Kind.PIPER,
            23_366_127L, "a506a3798c69bd9116f07d6ee6d4a502dd9e35742349fd56bafa192acba93f95", "en_GB-vctk-medium.onnx",
            speakerCount = 109),
        Model(NORMAN, "Norman", "American male. The first voice Jarvis had.", Kind.PIPER,
            20_987_233L, "cb481a514bc213ccf3899391c0f27fdcc4e4b814ec30496f28089a027b5aa01b", "en_US-norman-medium.onnx"),
    )

    fun byId(id: String?): Model? = all.firstOrNull { it.id == id }

    /** What to call speaker [sid] of [m]: its name, "Voice N" for a numbered one, "" for a single voice. */
    fun speakerName(m: Model, sid: Int): String = when {
        m.speakers.isNotEmpty() -> m.speakers.getOrElse(sid) { m.speakers[m.defaultSpeaker] }
        m.speakerCount > 1 -> "Voice ${sid.coerceIn(0, m.maxSpeaker)}"
        else -> ""
    }

    /** [sid] kept inside [m]'s speakers. */
    fun clampSpeaker(m: Model, sid: Int): Int = sid.coerceIn(0, m.maxSpeaker)

    /**
     * The own voice to speak with now: [chosen] if downloaded, else the first downloaded of the
     * default and then every other own voice (so speech never stops while a new voice downloads),
     * else null (the phone's voice speaks). [present] says whether a voice is unpacked.
     */
    fun speaking(chosen: String, present: (Model) -> Boolean): Model? {
        if (chosen == PHONE) return null
        byId(chosen)?.let { if (present(it)) return it }
        return (listOfNotNull(byId(DEFAULT), byId(NORMAN)) + all).firstOrNull { present(it) }
    }
}
