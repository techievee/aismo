// A small JNI wrapper around llama.cpp's C API for the on-device writing assistant: load a GGUF model, then turn a chat
// (system, user and assistant messages) into a reply. Only the plain C API is used, not llama.cpp's "common" helpers, so
// nothing here can reach the network.
#include <android/log.h>
#include <jni.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "ggml-backend.h"
#include "llama.h"

#define TAG "aismo_llm"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

constexpr int kBatch = 512;

struct Engine {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    const llama_vocab *vocab = nullptr;
    std::string chat_template;           // the model's own template, empty if it has none
    int n_ctx = 0;
    std::vector<llama_token> cache;      // tokens whose results are in the context's memory, for prefix reuse
    std::atomic<bool> cancel{false};
    std::mutex run;                      // one generation at a time
    std::string stats;                   // of the last generation
};

std::mutex g_error_mutex;
std::string g_last_error;

void set_error(const std::string &message) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    g_last_error = message;
    LOGE("%s", message.c_str());
}

void log_callback(ggml_log_level level, const char *text, void *) {
    if (level == GGML_LOG_LEVEL_ERROR) LOGE("%s", text);
    else if (level == GGML_LOG_LEVEL_WARN) LOGW("%s", text);
}

std::string to_std(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out(chars ? chars : "");
    if (chars) env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Java strings are modified UTF-8; getBytes(UTF_8) on the Kotlin side hands over standard UTF-8, so messages arrive as bytes.
std::string bytes_to_std(JNIEnv *env, jbyteArray a) {
    const jsize n = env->GetArrayLength(a);
    std::string out((size_t) n, '\0');
    env->GetByteArrayRegion(a, 0, n, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

// The prompt text for a chat: the model's template when llama.cpp understands it, plain ChatML otherwise.
std::string format_chat(Engine *e, const std::vector<std::string> &roles, const std::vector<std::string> &contents) {
    std::vector<llama_chat_message> messages(roles.size());
    for (size_t i = 0; i < roles.size(); i++) messages[i] = {roles[i].c_str(), contents[i].c_str()};
    if (!e->chat_template.empty()) {
        std::vector<char> buffer(4096);
        int n = llama_chat_apply_template(e->chat_template.c_str(), messages.data(), messages.size(), true, buffer.data(), (int) buffer.size());
        if (n > (int) buffer.size()) {
            buffer.resize((size_t) n);
            n = llama_chat_apply_template(e->chat_template.c_str(), messages.data(), messages.size(), true, buffer.data(), (int) buffer.size());
        }
        if (n >= 0) return std::string(buffer.data(), (size_t) n);
    }
    std::string text;
    for (size_t i = 0; i < roles.size(); i++) text += "<|im_start|>" + roles[i] + "\n" + contents[i] + "<|im_end|>\n";
    return text + "<|im_start|>assistant\n";
}

long now_ms() {
    return (long) std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now().time_since_epoch()).count();
}

}  // namespace

extern "C" {

// Loads the CPU variants shipped next to this library and starts llama.cpp.
JNIEXPORT jboolean JNICALL Java_aismo_sms_data_llm_LlamaNative_init(JNIEnv *env, jobject, jstring native_lib_dir) {
    llama_log_set(log_callback, nullptr);
    const std::string dir = to_std(env, native_lib_dir);
    ggml_backend_load_all_from_path(dir.c_str());
    llama_backend_init();
    return JNI_TRUE;
}

// Returns a handle, or 0 with the reason in lastError().
JNIEXPORT jlong JNICALL Java_aismo_sms_data_llm_LlamaNative_load(JNIEnv *env, jobject, jstring model_path, jint context_size, jint threads) {
    const std::string path = to_std(env, model_path);
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    llama_model *model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) { set_error("could not load the model file"); return 0; }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = (uint32_t) context_size;
    cp.n_batch = kBatch;
    cp.n_ubatch = kBatch;
    cp.n_threads = threads;
    cp.n_threads_batch = threads;
    llama_context *ctx = llama_init_from_model(model, cp);
    if (!ctx) { llama_model_free(model); set_error("could not create the model context (out of memory?)"); return 0; }

    auto *e = new Engine();
    e->model = model;
    e->ctx = ctx;
    e->vocab = llama_model_get_vocab(model);
    e->n_ctx = (int) llama_n_ctx(ctx);
    if (const char *tmpl = llama_model_chat_template(model, nullptr)) e->chat_template = tmpl;
    return reinterpret_cast<jlong>(e);
}

JNIEXPORT jstring JNICALL Java_aismo_sms_data_llm_LlamaNative_lastError(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> lock(g_error_mutex);
    return env->NewStringUTF(g_last_error.c_str());
}

JNIEXPORT jstring JNICALL Java_aismo_sms_data_llm_LlamaNative_systemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jstring JNICALL Java_aismo_sms_data_llm_LlamaNative_lastStats(JNIEnv *env, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    return env->NewStringUTF(e ? e->stats.c_str() : "");
}

JNIEXPORT void JNICALL Java_aismo_sms_data_llm_LlamaNative_cancel(JNIEnv *, jobject, jlong handle) {
    if (auto *e = reinterpret_cast<Engine *>(handle)) e->cancel.store(true);
}

JNIEXPORT void JNICALL Java_aismo_sms_data_llm_LlamaNative_free(JNIEnv *, jobject, jlong handle) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) return;
    { std::lock_guard<std::mutex> lock(e->run); }   // wait for a generation that is still finishing
    llama_free(e->ctx);
    llama_model_free(e->model);
    delete e;
}

// Runs one chat and returns the reply as UTF-8 bytes, or null with the reason in lastError() ("cancelled" if cancelled).
// The start of the prompt is reused from the previous call when it is the same, so fixed instructions cost nothing twice.
JNIEXPORT jbyteArray JNICALL Java_aismo_sms_data_llm_LlamaNative_generate(
        JNIEnv *env, jobject, jlong handle, jobjectArray roles, jobjectArray contents, jint max_tokens, jfloat temperature, jint seed) {
    auto *e = reinterpret_cast<Engine *>(handle);
    if (!e) { set_error("the model is not loaded"); return nullptr; }
    std::lock_guard<std::mutex> lock(e->run);
    e->cancel.store(false);

    std::vector<std::string> role_list, content_list;
    const jsize count = env->GetArrayLength(roles);
    for (jsize i = 0; i < count; i++) {
        auto *r = (jbyteArray) env->GetObjectArrayElement(roles, i);
        auto *c = (jbyteArray) env->GetObjectArrayElement(contents, i);
        role_list.push_back(bytes_to_std(env, r));
        content_list.push_back(bytes_to_std(env, c));
        env->DeleteLocalRef(r);
        env->DeleteLocalRef(c);
    }

    const std::string prompt = format_chat(e, role_list, content_list);
    std::vector<llama_token> tokens((size_t) prompt.size() + 8);
    int n = llama_tokenize(e->vocab, prompt.c_str(), (int) prompt.size(), tokens.data(), (int) tokens.size(), llama_vocab_get_add_bos(e->vocab), true);
    if (n < 0) {
        tokens.resize((size_t) -n);
        n = llama_tokenize(e->vocab, prompt.c_str(), (int) prompt.size(), tokens.data(), (int) tokens.size(), llama_vocab_get_add_bos(e->vocab), true);
    }
    if (n <= 0) { set_error("could not read the prompt"); return nullptr; }
    tokens.resize((size_t) n);
    if (n + max_tokens > e->n_ctx) { set_error("the text is too long for the model"); return nullptr; }

    const long t0 = now_ms();
    // Reuse what the context already holds for the shared start of the prompt.
    size_t common = 0;
    while (common < e->cache.size() && common < tokens.size() && e->cache[common] == tokens[common]) common++;
    if (common == tokens.size()) common--;   // the last prompt token must be decoded to get its logits
    llama_memory_t memory = llama_get_memory(e->ctx);
    if (!llama_memory_seq_rm(memory, 0, (llama_pos) common, -1)) {   // some models cannot drop a tail: start over
        llama_memory_clear(memory, true);
        common = 0;
    }
    e->cache.assign(tokens.begin(), tokens.begin() + (long) common);

    for (size_t i = common; i < tokens.size(); i += kBatch) {
        if (e->cancel.load()) { e->cache.clear(); llama_memory_clear(memory, true); set_error("cancelled"); return nullptr; }
        const int chunk = (int) std::min((size_t) kBatch, tokens.size() - i);
        if (llama_decode(e->ctx, llama_batch_get_one(tokens.data() + i, chunk)) != 0) {
            e->cache.clear(); llama_memory_clear(memory, true);
            set_error("the model failed while reading the prompt");
            return nullptr;
        }
        e->cache.insert(e->cache.end(), tokens.begin() + (long) i, tokens.begin() + (long) i + chunk);
    }
    const long t1 = now_ms();

    llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(sampler, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.95f, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist((uint32_t) seed));
    }

    std::string reply;
    int generated = 0;
    bool cancelled = false;
    for (; generated < max_tokens; generated++) {
        if (e->cancel.load()) { cancelled = true; break; }
        llama_token token = llama_sampler_sample(sampler, e->ctx, -1);
        if (llama_vocab_is_eog(e->vocab, token)) break;
        char piece[256];
        const int len = llama_token_to_piece(e->vocab, token, piece, sizeof(piece), 0, false);
        if (len > 0) reply.append(piece, (size_t) len);
        e->cache.push_back(token);
        if (llama_decode(e->ctx, llama_batch_get_one(&token, 1)) != 0) { e->cache.pop_back(); break; }
    }
    llama_sampler_free(sampler);
    const long t2 = now_ms();

    e->stats = "prompt_tokens=" + std::to_string(tokens.size()) + " reused=" + std::to_string(common) +
               " prompt_ms=" + std::to_string(t1 - t0) + " generated=" + std::to_string(generated) + " generate_ms=" + std::to_string(t2 - t1);
    if (cancelled) { e->cache.clear(); llama_memory_clear(memory, true); set_error("cancelled"); return nullptr; }

    jbyteArray out = env->NewByteArray((jsize) reply.size());
    env->SetByteArrayRegion(out, 0, (jsize) reply.size(), reinterpret_cast<const jbyte *>(reply.data()));
    return out;
}

}  // extern "C"
