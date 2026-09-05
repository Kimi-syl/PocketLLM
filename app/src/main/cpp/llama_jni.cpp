#include <jni.h>
#include <algorithm>
#include <string>
#include <vector>
#include <atomic>
#include <mutex>
#include <unordered_map>

#include "llama.h"

namespace {

struct Session {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    std::atomic<bool> stopGen{false};
    std::mutex busy;
};

std::mutex gMutex;
std::unordered_map<long long, Session*> gSessions;
long long gNextId = 1;

static std::mutex gLogMutex;
static std::string gLogBuffer;

static void ggml_log_forward(ggml_log_level, const char * text, void *) {
    if (text == nullptr) return;
    std::lock_guard<std::mutex> lock(gLogMutex);
    if (gLogBuffer.size() < 16384) gLogBuffer += text;
}

// Install the ggml log hook as early as possible so backend registration
// messages (Vulkan device enumeration etc.) are captured for diagnostics.
__attribute__((constructor)) static void llama_jni_init_log() {
    ggml_log_set(ggml_log_forward, nullptr);
}

std::string toStdString(JNIEnv* env, jstring js) {
    if (js == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(js, nullptr);
    std::string out(chars != nullptr ? chars : "");
    if (chars != nullptr) env->ReleaseStringUTFChars(js, chars);
    return out;
}

Session* findSession(jlong id) {
    std::lock_guard<std::mutex> lock(gMutex);
    auto it = gSessions.find(static_cast<long long>(id));
    return it == gSessions.end() ? nullptr : it->second;
}

void dropSession(jlong id) {
    Session* s = nullptr;
    {
        std::lock_guard<std::mutex> lock(gMutex);
        auto it = gSessions.find(static_cast<long long>(id));
        if (it == gSessions.end()) return;
        s = it->second;
        gSessions.erase(it);
    }
    if (s->ctx != nullptr) llama_free(s->ctx);
    if (s->model != nullptr) llama_model_free(s->model);
    delete s;
}

size_t utf8SequenceLength(unsigned char lead) {
    if ((lead & 0x80u) == 0) return 1;
    if ((lead & 0xE0u) == 0xC0) return 2;
    if ((lead & 0xF0u) == 0xE0) return 3;
    if ((lead & 0xF8u) == 0xF0) return 4;
    return 1;
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_pocketllm_llm_LlamaBridge_backendInit(JNIEnv*, jobject) {
    llama_backend_init();
}

extern "C" const char *opencl_shim_debug(void);
extern "C" const char *vulkan_shim_debug(void);
extern "C" const char *vulkan_shim_probe(void);

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketllm_llm_LlamaBridge_backendInfo(JNIEnv* env, jobject) {
    std::string out;
    size_t n = ggml_backend_dev_count();
    out += "registered devices: " + std::to_string(n) + "\n";
    for (size_t i = 0; i < n; i++) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        out += "- ";
        out += ggml_backend_dev_name(dev);
        out += " | ";
        out += ggml_backend_dev_description(dev);
        switch (ggml_backend_dev_type(dev)) {
            case GGML_BACKEND_DEVICE_TYPE_CPU:  out += " [cpu]"; break;
            case GGML_BACKEND_DEVICE_TYPE_GPU:  out += " [gpu]"; break;
            case GGML_BACKEND_DEVICE_TYPE_IGPU: out += " [igpu]"; break;
            default:                            out += " [other]"; break;
        }
        out += "\n";
    }
    {
        std::lock_guard<std::mutex> lock(gLogMutex);
        out += "--- ggml log ---\n";
        out += gLogBuffer;
        gLogBuffer.clear();
    }
    out += "--- opencl shim ---\n";
    out += opencl_shim_debug();
    out += "--- vulkan shim ---\n";
    out += vulkan_shim_debug();
    out += "--- vulkan probe (fork-isolated) ---\n";
    out += vulkan_shim_probe();
    return env->NewStringUTF(out.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketllm_llm_LlamaBridge_supportsGpuOffload(JNIEnv*, jobject) {
    return llama_supports_gpu_offload() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pocketllm_llm_LlamaBridge_loadModel(JNIEnv* env, jobject, jstring jPath,
                                             jint contextSize, jint batchSize, jint threads,
                                             jint gpuLayers) {
    std::string path = toStdString(env, jPath);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = static_cast<int>(gpuLayers);
    llama_model* model = llama_model_load_from_file(path.c_str(), mparams);
    if (model == nullptr) return -1;

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = static_cast<uint32_t>(contextSize);
    // Use the same value for n_batch and n_ubatch. llama.cpp asserts
    // n_tokens <= n_ubatch inside llama_decode, so both must be large
    // enough to hold the longest single decode call (typically the
    // prompt-eval batch, which is prompt_len / ~4 tokens for a chat
    // template with tool blocks — easily 1000+ tokens for agent mode).
    cparams.n_batch = static_cast<uint32_t>(batchSize);
    cparams.n_ubatch = static_cast<uint32_t>(batchSize);
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;

    llama_context* ctx = llama_init_from_model(model, cparams);
    if (ctx == nullptr) {
        llama_model_free(model);
        return -1;
    }

    Session* session = new Session();
    session->model = model;
    session->ctx = ctx;

    std::lock_guard<std::mutex> lock(gMutex);
    long long id = gNextId++;
    gSessions[id] = session;
    return static_cast<jlong>(id);
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketllm_llm_LlamaBridge_freeModel(JNIEnv*, jobject, jlong id) {
    dropSession(id);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_pocketllm_llm_LlamaBridge_contextLength(JNIEnv*, jobject, jlong id) {
    Session* session = findSession(id);
    if (session == nullptr) return 0;
    return static_cast<jint>(llama_n_ctx(session->ctx));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketllm_llm_LlamaBridge_applyChatTemplate(JNIEnv* env, jobject, jlong id,
                                                     jobjectArray roles, jobjectArray contents) {
    Session* session = findSession(id);
    if (session == nullptr) return env->NewStringUTF("");

    jsize count = env->GetArrayLength(roles);
    if (count <= 0) return env->NewStringUTF("");

    std::vector<std::string> roleStrings(static_cast<size_t>(count));
    std::vector<std::string> contentStrings(static_cast<size_t>(count));
    std::vector<llama_chat_message> messages;
    messages.reserve(static_cast<size_t>(count));

    for (jsize i = 0; i < count; i++) {
        jstring jRole = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        jstring jContent = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        roleStrings[static_cast<size_t>(i)] = toStdString(env, jRole);
        contentStrings[static_cast<size_t>(i)] = toStdString(env, jContent);
        if (jRole != nullptr) env->DeleteLocalRef(jRole);
        if (jContent != nullptr) env->DeleteLocalRef(jContent);
        messages.push_back(llama_chat_message{
            roleStrings[static_cast<size_t>(i)].c_str(),
            contentStrings[static_cast<size_t>(i)].c_str()});
    }

    std::vector<char> buffer(8192);
    int32_t written = -1;
    while (buffer.size() <= (1u << 22)) {
        written = llama_chat_apply_template(nullptr, messages.data(), messages.size(), true,
                                            buffer.data(), static_cast<int32_t>(buffer.size()));
        bool fits = written >= 0 && static_cast<size_t>(written) < buffer.size() - 1;
        if (fits) break;
        buffer.resize(buffer.size() * 2);
    }
    if (written < 0 || static_cast<size_t>(written) >= buffer.size()) return env->NewStringUTF("");

    buffer[static_cast<size_t>(written)] = '\0';
    return env->NewStringUTF(buffer.data());
}

extern "C" JNIEXPORT void JNICALL
Java_com_pocketllm_llm_LlamaBridge_stopGeneration(JNIEnv*, jobject, jlong id) {
    Session* session = findSession(id);
    if (session != nullptr) session->stopGen.store(true);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_pocketllm_llm_LlamaBridge_generate(JNIEnv* env, jobject, jlong id, jstring jPrompt,
                                            jint maxNewTokens, jfloat temperature, jfloat topP,
                                            jint topK, jlong seed, jstring jGrammar, jobject sink) {
    Session* session = findSession(id);
    if (session == nullptr) return nullptr;
    if (!session->busy.try_lock()) return nullptr;
    struct BusyUnlock {
        std::mutex& m;
        ~BusyUnlock() { m.unlock(); }
    } busyUnlock{session->busy};

    session->stopGen.store(false);

    llama_context* ctx = session->ctx;
    const llama_vocab* vocab = llama_model_get_vocab(session->model);
    const int nCtx = static_cast<int>(llama_n_ctx(ctx));

    std::string prompt = toStdString(env, jPrompt);

    std::string grammarStr;
    const char* grammarC = nullptr;
    if (jGrammar != nullptr) {
        grammarStr = toStdString(env, jGrammar);
        if (!grammarStr.empty()) grammarC = grammarStr.c_str();
    }

    std::vector<llama_token> tokens(std::max(prompt.size() / 2u + static_cast<size_t>(256), static_cast<size_t>(256)));
    int nPrompt = -1;
    while (true) {
        nPrompt = llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()),
                                 tokens.data(), static_cast<int32_t>(tokens.size()), true, true);
        if (nPrompt >= 0) break;
        tokens.resize(static_cast<size_t>(-nPrompt));
    }
    tokens.resize(static_cast<size_t>(nPrompt));

    if (nPrompt >= nCtx) {
        size_t overflow = tokens.size() - static_cast<size_t>(nCtx - 16);
        tokens.erase(tokens.begin(), tokens.begin() + static_cast<long>(overflow));
        nPrompt = static_cast<int>(tokens.size());
    }

    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID onTokenMethod = env->GetMethodID(sinkClass, "onToken", "([B)Z");
    if (onTokenMethod == nullptr) return nullptr;

    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (grammarC != nullptr) {
        llama_sampler_chain_add(sampler, llama_sampler_init_grammar(vocab, grammarC, "root"));
    }
    if (temperature <= 0.0f) {
        llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
    } else {
        if (topK > 0) llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK));
        if (topP < 1.0f) llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
        llama_sampler_chain_add(sampler, llama_sampler_init_temp_ext(temperature, 0.0f, 1.0f));
        llama_sampler_chain_add(sampler, llama_sampler_init_dist(seed < 0 ? 0xFFFFFFFFu : static_cast<uint32_t>(seed)));
    }

    if (nPrompt <= 0 || llama_decode(ctx, llama_batch_get_one(tokens.data(), nPrompt)) != 0) {
        llama_sampler_free(sampler);
        return nullptr;
    }

    jintArray result = nullptr;
    std::string pending;
    bool cancelled = false;
    int nGenerated = 0;

    auto invokeSink = [&](const char* data, size_t len) -> bool {
        jbyteArray chunk = env->NewByteArray(static_cast<jsize>(len));
        env->SetByteArrayRegion(chunk, 0, static_cast<jsize>(len),
                                reinterpret_cast<const jbyte*>(data));
        jboolean keepGoing = env->CallBooleanMethod(sink, onTokenMethod, chunk);
        env->DeleteLocalRef(chunk);
        if (env->ExceptionCheck()) {
            env->ExceptionDescribe();
            env->ExceptionClear();
            return false;
        }
        return keepGoing;
    };

    if (env->PushLocalFrame(128) == 0) {
        // Hard safety cap. Regardless of what the caller asked for, never
        // generate more than this many tokens in a single call. This is a
        // last-resort guard against runaway loops (e.g. sampler degeneration).
        const int HARD_CAP = 2048;
        int effectiveMax = maxNewTokens < 0 ? 0 : (maxNewTokens > HARD_CAP ? HARD_CAP : maxNewTokens);
        for (int i = 0; i < effectiveMax && nPrompt + nGenerated < nCtx; i++) {
            if (session->stopGen.load()) { cancelled = true; break; }

            llama_token tok = llama_sampler_sample(sampler, ctx, -1);
            if (llama_vocab_is_eog(vocab, tok)) break;

            char piece[512];
            int nPiece = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
            if (nPiece > 0) {
                pending.append(piece, static_cast<size_t>(nPiece));
                size_t offset = 0;
                while (offset < pending.size()) {
                    size_t seq = utf8SequenceLength(static_cast<unsigned char>(pending[offset]));
                    if (offset + seq > pending.size()) break;
                    if (!invokeSink(pending.data() + offset, seq)) { cancelled = true; break; }
                    offset += seq;
                }
                pending.erase(0, offset);
                if (cancelled) { nGenerated++; break; }
            }

            nGenerated++;

            if (session->stopGen.load()) { cancelled = true; break; }
            llama_batch batch = llama_batch_get_one(&tok, 1);
            if (llama_decode(ctx, batch) != 0) break;
        }

        if (!pending.empty() && !cancelled) {
            invokeSink(pending.data(), pending.size());
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
            }
        }
        env->PopLocalFrame(nullptr);
    }

    llama_sampler_free(sampler);

    jint counts[2] = {static_cast<jint>(nPrompt), static_cast<jint>(nGenerated)};
    result = env->NewIntArray(2);
    env->SetIntArrayRegion(result, 0, 2, counts);
    return result;
}
