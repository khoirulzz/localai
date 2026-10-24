# Local AI — Native Android (v0.3)

Implementasi proyek Android native sesuai spesifikasi:
- **DESIGN.md (`local_ai_design_v0_2.md`)**: *The conversation is the interface.* Tanpa bubble asisten, tanpa badge dekoratif, tipografi sistem proporsional (`16sp`/`24sp`), token warna Light (`#FFFFFF`) & Dark (`#101010`), dan composer yang menyatu dengan `WindowInsets.ime`.
- **PRD (`local_ai_prd_v0_3.md`)**: Tiga fitur inti (Chat lokal streaming dengan Qwen3.5-2B / 0.8B / Gemma 4 E2B, Pencarian Web opt-in via Tavily BYOK, dan Input Gambar VLM + PDF teks lokal).
- **ARCHITECTURE (`local_ai_architecture_v0_3.md`)**: Kontrak `InferenceEngine`, bridge JNI `llama.cpp` (`arm64-v8a`), penyimpanan Room + FTS4 untuk chunk PDF, enkripsi API key Tavily dengan Android Keystore AES-GCM, serta `ModelDownloadManager` dengan dukungan HTTP Range resume dan verifikasi SHA-256.

## Struktur Modul
- `:app` — UI Jetpack Compose, Room + FTS4, `TavilySearchProvider`, `TavilyKeyStore`, `AttachmentProcessor` (PdfBox-Android & image downsampling), `ModelDownloadManager`, dan `ContextBuilder`.
- `:inference:api` — Kontrak `InferenceEngine`, `ModelConfig`, `InferenceRequest`, `InferenceEvent`, dan `RunStats`.
- `:inference:llamacpp` — Implementasi `LlamaCppEngine` dengan coalescing window 40ms, native abort flag (`cancel()`), dan bridge C++ JNI (`llama_jni.cpp` + `CMakeLists.txt`).

## Cara Membangun APK di Mesin Lokal / Android Studio
1. Ekstrak arsip ini dan buka folder proyek di **Android Studio Ladybug (2024.2.1+)** atau gunakan JDK 17 + Android SDK 35 & NDK r27.
2. Untuk mengaktifkan decoding GGUF native penuh, inisialisasi submodule `ggml-org/llama.cpp` di dalam `inference/llamacpp/src/main/cpp/llama.cpp`.
3. Jalankan `./gradlew assembleDebug` untuk menghasilkan APK `arm64-v8a` siap pasang pada perangkat target (Infinix Hot 60 Pro / X6885).
