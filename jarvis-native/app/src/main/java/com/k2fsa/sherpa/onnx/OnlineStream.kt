// Vendored unmodified from sherpa-onnx v1.13.8 (Apache License 2.0):
//   https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/OnlineStream.kt
// It must stay in step with libsherpa-onnx-jni.so (jarvis-native/ondevice-brain/fetch-sherpa.sh pins
// the same tag): the library binds these classes' natives by name and reads config fields by name.
// Used by listen/VoicePrints.kt (on-device speaker embeddings for the transcript's speaker tags).
// Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
package com.k2fsa.sherpa.onnx

class OnlineStream(var ptr: Long = 0) {
    init {
        require(ptr != 0L) { "Failed to create native OnlineStream" }
    }

    fun acceptWaveform(samples: FloatArray, sampleRate: Int) =
        acceptWaveform(ptr, samples, sampleRate)

    fun inputFinished() = inputFinished(ptr)

    fun setOption(key: String, value: String) = setOption(ptr, key, value)

    fun getOption(key: String): String = getOption(ptr, key)

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    fun release() = finalize()

    fun use(block: (OnlineStream) -> Unit) {
        try {
            block(this)
        } finally {
            release()
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun inputFinished(ptr: Long)
    private external fun setOption(ptr: Long, key: String, value: String)
    private external fun getOption(ptr: Long, key: String): String
    private external fun delete(ptr: Long)


    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}
