#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#ifdef HAVE_LLAMA_CPP
#include "llama.h"
#ifdef HAVE_MTMD
#include "mtmd.h"
#include "mtmd-helper.h"
#endif
#endif

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Kode status yang dikembalikan nativeGenerateStream (selain jumlah token >= 0).
namespace status {
constexpr int NOT_LOADED = -1;
constexpr int NO_CALLBACK = -2;
constexpr int CONTEXT_FULL = -3;
constexpr int DECODE_FAILED = -4;
constexpr int TOKENIZE_FAILED = -5;
constexpr int IMAGE_FAILED = -6;
constexpr int ABORTED = -99;
}  // namespace status

namespace {

std::mutex g_mutex;
std::atomic<bool> g_abort_requested{false};
bool g_loaded = false;
bool g_backend_ready = false;
int g_ctx_tokens = 2048;
int g_threads = 4;
int g_batch = 512;

#ifdef HAVE_LLAMA_CPP
llama_model* g_model = nullptr;
llama_context* g_ctx = nullptr;
const llama_vocab* g_vocab = nullptr;
#ifdef HAVE_MTMD
mtmd_context* g_mtmd = nullptr;
#endif

void llama_log_to_android(ggml_log_level level, const char* text, void* /*user*/) {
    if (!text) return;
    switch (level) {
        case GGML_LOG_LEVEL_ERROR: __android_log_print(ANDROID_LOG_ERROR, "llama.cpp", "%s", text); break;
        case GGML_LOG_LEVEL_WARN:  __android_log_print(ANDROID_LOG_WARN, "llama.cpp", "%s", text); break;
        default: break;  // INFO/DEBUG dibuang agar logcat tidak banjir
    }
}
#endif

std::string to_std_string(JNIEnv* env, jstring s) {
    if (s == nullptr) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

/**
 * Token llama.cpp adalah potongan byte, bukan karakter utuh. Satu karakter UTF-8 (emoji,
 * huruf beraksen) bisa terbelah di dua token. Fungsi ini mengubah byte yang sudah lengkap
 * menjadi UTF-16 dan menahan ekor yang belum lengkap sampai byte berikutnya datang.
 * Byte yang rusak diganti U+FFFD sehingga NewString tidak pernah menerima data tidak valid.
 */
void drain_utf8(std::string& buf, std::u16string& out, bool final_flush) {
    size_t i = 0;
    const size_t n = buf.size();
    while (i < n) {
        const unsigned char c = static_cast<unsigned char>(buf[i]);
        uint32_t cp;
        int len;
        if (c < 0x80) { cp = c; len = 1; }
        else if ((c & 0xE0) == 0xC0) { cp = c & 0x1F; len = 2; }
        else if ((c & 0xF0) == 0xE0) { cp = c & 0x0F; len = 3; }
        else if ((c & 0xF8) == 0xF0) { cp = c & 0x07; len = 4; }
        else { out.push_back(0xFFFD); i++; continue; }

        if (i + static_cast<size_t>(len) > n) {
            if (!final_flush) break;  // tunggu byte lanjutan
            out.push_back(0xFFFD);
            i = n;
            break;
        }
        bool ok = true;
        for (int k = 1; k < len; k++) {
            const unsigned char cc = static_cast<unsigned char>(buf[i + k]);
            if ((cc & 0xC0) != 0x80) { ok = false; break; }
            cp = (cp << 6) | (cc & 0x3F);
        }
        if (!ok) { out.push_back(0xFFFD); i++; continue; }

        const bool overlong = (len == 2 && cp < 0x80) || (len == 3 && cp < 0x800) || (len == 4 && cp < 0x10000);
        if (overlong || cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF)) {
            out.push_back(0xFFFD);
            i += len;
            continue;
        }
        if (cp >= 0x10000) {
            cp -= 0x10000;
            out.push_back(static_cast<char16_t>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<char16_t>(0xDC00 + (cp & 0x3FF)));
        } else {
            out.push_back(static_cast<char16_t>(cp));
        }
        i += len;
    }
    buf.erase(0, i);
}

// Kirim teks ke Kotlin. Mengembalikan false bila Kotlin meminta berhenti atau terjadi exception.
bool emit_to_kotlin(JNIEnv* env, jobject callback, jmethodID method, const std::u16string& text) {
    if (text.empty()) return true;
    jstring js = env->NewString(reinterpret_cast<const jchar*>(text.data()), static_cast<jsize>(text.size()));
    if (js == nullptr) return false;
    const jboolean keep_going = env->CallBooleanMethod(callback, method, js);
    env->DeleteLocalRef(js);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        return false;
    }
    return keep_going == JNI_TRUE;
}

#ifdef HAVE_LLAMA_CPP
void free_everything_locked() {
#ifdef HAVE_MTMD
    if (g_mtmd) { mtmd_free(g_mtmd); g_mtmd = nullptr; }
#endif
    if (g_ctx) { llama_free(g_ctx); g_ctx = nullptr; }
    if (g_model) { llama_model_free(g_model); g_model = nullptr; }
    g_vocab = nullptr;
    g_loaded = false;
}

llama_sampler* build_sampler(float temperature) {
    llama_sampler_chain_params sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    llama_sampler* chain = llama_sampler_chain_init(sp);
    // Penalti ringan: model 0.8B-2B mudah masuk loop pengulangan.
    llama_sampler_chain_add(chain, llama_sampler_init_penalties(llama_vocab_n_tokens(g_vocab), 64, 1.08f, 0.0f, 0.0f));
    if (temperature <= 0.01f) {
        llama_sampler_chain_add(chain, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.92f, 1));
        llama_sampler_chain_add(chain, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(chain, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
    }
    return chain;
}

// Prefill token teks per potongan n_batch. Mengembalikan 0 jika sukses, atau kode status negatif.
int prefill_text(const std::vector<llama_token>& tokens) {
    const int total = static_cast<int>(tokens.size());
    for (int i = 0; i < total; i += g_batch) {
        if (g_abort_requested.load()) return status::ABORTED;
        const int count = std::min(g_batch, total - i);
        llama_batch batch = llama_batch_get_one(const_cast<llama_token*>(tokens.data()) + i, count);
        const int rc = llama_decode(g_ctx, batch);
        if (rc == 1) return status::CONTEXT_FULL;
        if (rc != 0) {
            LOGE("llama_decode (prefill) gagal rc=%d", rc);
            return status::DECODE_FAILED;
        }
    }
    return 0;
}
#endif

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_id_localai_inference_llamacpp_LlamaBridge_nativeLoadModel(
        JNIEnv* env, jobject /* this */,
        jstring ggufPath, jstring projectorPath, jint contextTokens, jint cpuThreads) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_abort_requested.store(false);

    const std::string gguf = to_std_string(env, ggufPath);
    const std::string proj = to_std_string(env, projectorPath);
    g_ctx_tokens = contextTokens > 0 ? contextTokens : 2048;
    g_threads = cpuThreads > 0 ? cpuThreads : 4;
    g_batch = std::min(512, g_ctx_tokens);

#ifdef HAVE_LLAMA_CPP
    free_everything_locked();

    if (!g_backend_ready) {
        llama_log_set(llama_log_to_android, nullptr);
        llama_backend_init();
        g_backend_ready = true;
    }

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;  // hemat RAM: bobot dipetakan dari file
    g_model = llama_model_load_from_file(gguf.c_str(), mparams);
    if (!g_model) {
        LOGE("Gagal memuat model GGUF: %s", gguf.c_str());
        return JNI_FALSE;
    }
    g_vocab = llama_model_get_vocab(g_model);

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(g_ctx_tokens);
    cparams.n_batch = static_cast<uint32_t>(g_batch);
    cparams.n_ubatch = static_cast<uint32_t>(g_batch);
    cparams.n_threads = g_threads;
    cparams.n_threads_batch = g_threads;
    cparams.no_perf = true;
    g_ctx = llama_init_from_model(g_model, cparams);
    if (!g_ctx) {
        LOGE("Gagal membuat konteks (mungkin kehabisan memori).");
        free_everything_locked();
        return JNI_FALSE;
    }

#ifdef HAVE_MTMD
    if (!proj.empty()) {
        mtmd_context_params mp = mtmd_context_params_default();
        mp.use_gpu = false;
        mp.print_timings = false;
        mp.n_threads = g_threads;
        mp.warmup = false;
        g_mtmd = mtmd_init_from_file(proj.c_str(), g_model, mp);
        if (!g_mtmd) {
            LOGW("Proyektor visi gagal dimuat, mode teks saja: %s", proj.c_str());
        }
    }
#else
    (void) proj;
#endif

    g_loaded = true;
    LOGI("Model siap: %s (ctx=%d, threads=%d, batch=%d)", gguf.c_str(), g_ctx_tokens, g_threads, g_batch);
    return JNI_TRUE;
#else
    (void) gguf; (void) proj;
    LOGE("Dibangun tanpa llama.cpp (submodule tidak ada), inferensi tidak tersedia.");
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT jboolean JNICALL
Java_id_localai_inference_llamacpp_LlamaBridge_nativeSupportsVision(JNIEnv*, jobject) {
#if defined(HAVE_LLAMA_CPP) && defined(HAVE_MTMD)
    return g_mtmd != nullptr ? JNI_TRUE : JNI_FALSE;
#else
    return JNI_FALSE;
#endif
}

extern "C" JNIEXPORT void JNICALL
Java_id_localai_inference_llamacpp_LlamaBridge_nativeCancel(JNIEnv*, jobject) {
    g_abort_requested.store(true);
}

extern "C" JNIEXPORT void JNICALL
Java_id_localai_inference_llamacpp_LlamaBridge_nativeUnload(JNIEnv*, jobject) {
    g_abort_requested.store(true);
    std::lock_guard<std::mutex> lock(g_mutex);  // menunggu generasi yang sedang berjalan berhenti
#ifdef HAVE_LLAMA_CPP
    free_everything_locked();
#endif
    g_loaded = false;
    g_abort_requested.store(false);
}

extern "C" JNIEXPORT jint JNICALL
Java_id_localai_inference_llamacpp_LlamaBridge_nativeGenerateStream(
        JNIEnv* env, jobject /* this */,
        jstring formattedPrompt, jstring imagePath,
        jint maxOutputTokens, jfloat temperature, jobject callback) {
    std::lock_guard<std::mutex> lock(g_mutex);
    g_abort_requested.store(false);
    if (!g_loaded) return status::NOT_LOADED;

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onPiece = env->GetMethodID(cbClass, "onTokenPiece", "(Ljava/lang/String;)Z");
    if (!onPiece) {
        env->ExceptionClear();
        return status::NO_CALLBACK;
    }

#ifdef HAVE_LLAMA_CPP
    const std::string prompt = to_std_string(env, formattedPrompt);
    const std::string image = to_std_string(env, imagePath);

    llama_memory_clear(llama_get_memory(g_ctx), true);

    int n_past = 0;  // jumlah posisi konteks yang sudah terisi

    if (!image.empty()) {
#ifdef HAVE_MTMD
        if (!g_mtmd) return status::IMAGE_FAILED;

        mtmd_helper_bitmap_wrapper bmp = mtmd_helper_bitmap_init_from_file(g_mtmd, image.c_str(), false, mtmd_helper_init_opt_default());
        if (!bmp.bitmap) {
            LOGE("Gagal membaca gambar: %s", image.c_str());
            return status::IMAGE_FAILED;
        }
        mtmd_input_text text{prompt.c_str(), prompt.size(), /*add_special=*/false, /*parse_special=*/true};
        mtmd_input_chunks* chunks = mtmd_input_chunks_init();
        const mtmd_bitmap* bitmaps[1] = {bmp.bitmap};
        const int32_t trc = mtmd_tokenize(g_mtmd, chunks, &text, bitmaps, 1);
        mtmd_bitmap_free(bmp.bitmap);
        if (trc != 0) {
            LOGE("mtmd_tokenize gagal rc=%d", trc);
            mtmd_input_chunks_free(chunks);
            return status::IMAGE_FAILED;
        }
        if (mtmd_helper_get_n_pos(chunks) + 16 >= g_ctx_tokens) {
            mtmd_input_chunks_free(chunks);
            return status::CONTEXT_FULL;
        }
        llama_pos new_past = 0;
        const int32_t erc = mtmd_helper_eval_chunks(g_mtmd, g_ctx, chunks, 0, 0, g_batch, true, &new_past);
        mtmd_input_chunks_free(chunks);
        if (erc != 0) {
            LOGE("mtmd_helper_eval_chunks gagal rc=%d", erc);
            return status::DECODE_FAILED;
        }
        n_past = static_cast<int>(new_past);
#else
        return status::IMAGE_FAILED;
#endif
    } else {
        const int needed = -llama_tokenize(g_vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, false, true);
        if (needed <= 0) return status::TOKENIZE_FAILED;
        std::vector<llama_token> tokens(static_cast<size_t>(needed));
        if (llama_tokenize(g_vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), tokens.data(), needed, false, true) < 0) {
            return status::TOKENIZE_FAILED;
        }
        if (needed + 16 >= g_ctx_tokens) return status::CONTEXT_FULL;

        const int prc = prefill_text(tokens);
        if (prc != 0) return prc;
        n_past = needed;
    }

    llama_sampler* sampler = build_sampler(temperature);
    const int max_new = std::max(1, std::min(static_cast<int>(maxOutputTokens), g_ctx_tokens - n_past - 1));

    std::string pending_bytes;
    std::u16string ready;
    int generated = 0;
    int result = 0;

    for (int i = 0; i < max_new; i++) {
        if (g_abort_requested.load()) { result = status::ABORTED; break; }

        llama_token tok = llama_sampler_sample(sampler, g_ctx, -1);
        if (llama_vocab_is_eog(g_vocab, tok)) break;

        char buf[256];
        int n = llama_token_to_piece(g_vocab, tok, buf, sizeof(buf), 0, /*special=*/false);
        if (n < 0) {
            std::vector<char> big(static_cast<size_t>(-n));
            n = llama_token_to_piece(g_vocab, tok, big.data(), static_cast<int32_t>(big.size()), 0, false);
            if (n > 0) pending_bytes.append(big.data(), static_cast<size_t>(n));
        } else if (n > 0) {
            pending_bytes.append(buf, static_cast<size_t>(n));
        }
        generated++;

        ready.clear();
        drain_utf8(pending_bytes, ready, false);
        if (!emit_to_kotlin(env, callback, onPiece, ready)) {
            result = status::ABORTED;
            break;
        }

        llama_batch next = llama_batch_get_one(&tok, 1);
        const int rc = llama_decode(g_ctx, next);
        if (rc != 0) {
            LOGE("llama_decode (generate) gagal rc=%d", rc);
            result = (rc == 1) ? status::CONTEXT_FULL : status::DECODE_FAILED;
            break;
        }
    }

    if (result == 0 || result == status::ABORTED) {
        ready.clear();
        drain_utf8(pending_bytes, ready, true);
        if (!ready.empty()) emit_to_kotlin(env, callback, onPiece, ready);
    }

    llama_sampler_free(sampler);
    return result != 0 ? result : generated;
#else
    (void) formattedPrompt; (void) imagePath; (void) maxOutputTokens; (void) temperature;
    return status::NOT_LOADED;
#endif
}
