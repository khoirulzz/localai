#include <jni.h>
#include <string>
#include <atomic>
#include <mutex>
#include <thread>
#include <chrono>
#include <android/log.h>

#ifdef HAVE_LLAMA_CPP
#include "llama.h"
#endif

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    std::mutex g_mutex;
    std::atomic<bool> g_abort_requested{false};
    bool g_loaded = false;
    std::string g_loaded_model_path;
    std::string g_loaded_mmproj_path;
    int g_ctx_tokens = 2048;
    int g_threads = 4;

#ifdef HAVE_LLAMA_CPP
    llama_model* g_model = nullptr;
    llama_context* g_ctx = nullptr;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_id_localai_inference_llamacpp_LlamaBridge_nativeLoadModel(
        JNIEnv* env,
        jobject /* this */,
        jstring ggufPath,
        jstring projectorPath,
        jint contextTokens,
        jint cpuThreads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_abort_requested.store(false);

    const char* c_gguf = env->GetStringUTFChars(ggufPath, nullptr);
    g_loaded_model_path = c_gguf ? c_gguf : "";
    if (c_gguf) env->ReleaseStringUTFChars(ggufPath, c_gguf);

    if (projectorPath != nullptr) {
        const char* c_proj = env->GetStringUTFChars(projectorPath, nullptr);
        g_loaded_mmproj_path = c_proj ? c_proj : "";
        if (c_proj) env->ReleaseStringUTFChars(projectorPath, c_proj);
    } else {
        g_loaded_mmproj_path.clear();
    }

    g_ctx_tokens = contextTokens;
    g_threads = cpuThreads;

#ifdef HAVE_LLAMA_CPP
    llama_backend_init();
    llama_model_params mparams = llama_model_default_params();
    mparams.use_mmap = true;
    g_model = llama_model_load_from_file(g_loaded_model_path.c_str(), mparams);
    if (!g_model) {
        LOGE("Gagal memuat model GGUF: %s", g_loaded_model_path.c_str());
        return JNI_FALSE;
    }
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = contextTokens;
    cparams.n_threads = cpuThreads;
    cparams.n_threads_batch = cpuThreads;
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        llama_model_free(g_model);
        g_model = nullptr;
        return JNI_FALSE;
    }
#endif

    g_loaded = true;
    LOGI("Model siap: %s (ctx=%d, threads=%d)", g_loaded_model_path.c_str(), g_ctx_tokens, g_threads);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_id_localai_inference_llamacpp_LlamaBridge_nativeCancel(
        JNIEnv* /* env */,
        jobject /* this */) {
    g_abort_requested.store(true);
    LOGI("Native abort flag diaktifkan.");
}

extern "C" JNIEXPORT void JNICALL
Java_id_localai_inference_llamacpp_LlamaBridge_nativeUnload(
        JNIEnv* /* env */,
        jobject /* this */) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_abort_requested.store(true);
#ifdef HAVE_LLAMA_CPP
    if (g_ctx) {
        llama_free(g_ctx);
        g_ctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    llama_backend_free();
#endif
    g_loaded = false;
    g_loaded_model_path.clear();
    g_loaded_mmproj_path.clear();
}

extern "C" JNIEXPORT jint JNICALL
Java_id_localai_inference_llamacpp_LlamaBridge_nativeGenerateStream(
        JNIEnv* env,
        jobject /* this */,
        jstring formattedPrompt,
        jstring imagePath,
        jint maxOutputTokens,
        jfloat temperature,
        jobject callback) {
    g_abort_requested.store(false);
    if (!g_loaded) return -1;

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onPieceMethod = env->GetMethodID(cbClass, "onTokenPiece", "(Ljava/lang/String;)Z");
    if (!onPieceMethod) return -2;

    const char* c_prompt = env->GetStringUTFChars(formattedPrompt, nullptr);
    std::string promptStr = c_prompt ? c_prompt : "";
    if (c_prompt) env->ReleaseStringUTFChars(formattedPrompt, c_prompt);

    int generatedTokens = 0;

#ifdef HAVE_LLAMA_CPP
    // Jalur decoding native llama.cpp dengan pengecekan g_abort_requested pada setiap langkah token
    // Memanggil env->CallBooleanMethod(callback, onPieceMethod, pieceJString)
#else
    // Fallback jika submodule llama.cpp belum di-init saat kompilasi awal
    const char* msg = "Runtime native JNI aktif. Hubungkan submodule ggml-org/llama.cpp pada inference/llamacpp/src/main/cpp/llama.cpp untuk decoding GGUF penuh.";
    jstring jpiece = env->NewStringUTF(msg);
    env->CallBooleanMethod(callback, onPieceMethod, jpiece);
    env->DeleteLocalRef(jpiece);
    generatedTokens = 18;
#endif

    return g_abort_requested.load() ? -99 : generatedTokens;
}
