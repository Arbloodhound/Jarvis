// Vendored unmodified from sherpa-onnx v1.13.8 (Apache License 2.0):
//   https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.8/sherpa-onnx/kotlin-api/SpeakerEmbeddingExtractorConfig.kt
// It must stay in step with libsherpa-onnx-jni.so (jarvis-native/ondevice-brain/fetch-sherpa.sh pins
// the same tag): the library binds these classes' natives by name and reads config fields by name.
// Used by listen/VoicePrints.kt (on-device speaker embeddings for the transcript's speaker tags).
// Licensed under the Apache License, Version 2.0: http://www.apache.org/licenses/LICENSE-2.0
package com.k2fsa.sherpa.onnx

data class SpeakerEmbeddingExtractorConfig(
    val model: String = "",
    var numThreads: Int = 1,
    var debug: Boolean = false,
    var provider: String = "cpu",
)
