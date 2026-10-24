package id.localai.inference.llamacpp

interface NativeStreamCallback {
    fun onTokenPiece(piece: String): Boolean
}

object LlamaBridge {
    init {
        try {
            System.loadLibrary("localai_llama_jni")
        } catch (_: UnsatisfiedLinkError) {
            // Dibiarkan aman pada unit test JVM host
        }
    }

    external fun nativeLoadModel(
        ggufPath: String,
        projectorPath: String?,
        contextTokens: Int,
        cpuThreads: Int
    ): Boolean

    external fun nativeGenerateStream(
        formattedPrompt: String,
        imagePath: String?,
        maxOutputTokens: Int,
        temperature: Float,
        callback: NativeStreamCallback
    ): Int

    external fun nativeCancel()

    external fun nativeUnload()
}
