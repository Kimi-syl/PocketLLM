#include <jni.h>
#include <algorithm>
#include <cstdlib>
#include <string>
#include <vector>
#include <atomic>
#include <memory>
#include <mutex>
#include <unordered_map>

#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

namespace {

struct Session {
    llama_model* model = nullptr;
    llama_context* ctx = nullptr;
    // mmproj side of multimodal inference. Null unless loadVision() succeeded.
    mtmd_context* vision = nullptr;
    // LoRA adapters applied to ctx. The model owns them and frees them when it
    // is deleted, so this only has to be cleared, never freed here.
    std::vector<llama_adapter_lora*> loras;
    std::vector<float> lora_scales;
    std::atomic<bool> stopGen{false};
    std::mutex busy;
};

std::mutex gMutex;
std::unordered_map<long long, std::shared_ptr<Session>> gSessions;
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

std::shared_ptr<Session> findSession(jlong id) {
    std::lock_guard<std::mutex> lock(gMutex);
    auto it = gSessions.find(static_cast<long long>(id));
    return it == gSessions.end() ? nullptr : it->second;
}

// The session owns the llama resources via a shared_ptr custom deleter, so a
// session can be dropped while a generation still holds a reference: the
// context/model are freed only when the last user is done.
void dropSession(jlong id) {
    std::lock_guard<std::mutex> lock(gMutex);
    gSessions.erase(static_cast<long long>(id));
}

size_t utf8SequenceLength(unsigned char lead) {
    if ((lead & 0x80u) == 0) return 1;
    if ((lead & 0xE0u) == 0xC0) return 2;
    if ((lead & 0xF0u) == 0xE0) return 3;
    if ((lead & 0xF8u) == 0xF0) return 4;
    return 1;
}

} // namespace

extern "C" const char *opencl_shim_debug(void);
extern "C" const char *vulkan_shim_debug(void);
extern "C" const char *vulkan_shim_probe(void);
extern "C" int vulkan_shim_driver_broken(void);
extern "C" const char *vulkan_shim_gpu_usable(void);

extern "C" JNIEXPORT void JNICALL
Java_com_pocketllm_llm_LlamaBridge_backendInit(JNIEnv*, jobject) {
    /* A driver that cannot answer feature queries kills the load path
     * regardless of gpuLayers: even CPU layers route host buffers through
     * the vk host allocator once the backend registers. Deregistering
     * before llama_backend_init keeps every load on the plain CPU path. */
    if (vulkan_shim_driver_broken()) {
        setenv("GGML_DISABLE_VULKAN", "1", 1);
    }
    llama_backend_init();
}

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
    /* llama_supports_gpu_offload() only says a GPU backend registered. The
     * Mali driver registers and passes instance enumeration but dies in
     * vkCreateDevice/createBuffer - so the fork-isolated probe now runs the
     * FULL device + buffer path and we trust only that. */
    if (llama_supports_gpu_offload() != JNI_TRUE) return JNI_FALSE;
    return vulkan_shim_gpu_usable() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_pocketllm_llm_LlamaBridge_loadModel(JNIEnv* env, jobject, jstring jPath,
                                             jint contextSize, jint batchSize, jint threads,
                                             jint gpuLayers) {
    std::string path = toStdString(env, jPath);

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = static_cast<int>(gpuLayers);
    /* CPU-only load: keep tensors out of the Vulkan host buffer type. The
     * CPU buft list prefers the vk host buft (pinned memory) when the vk
     * backend is registered, and allocating it calls vkCreateBuffer - which
     * segfaults on drivers that register but cannot actually serve buffers
     * (Mali-G68). no_host makes select_weight_buft fall through to plain
     * CPU buffers, so a gpuLayers=0 load never touches the driver. */
    mparams.no_host = gpuLayers == 0;
    {
        char plog[128];
        snprintf(plog, sizeof(plog), "mparams: n_gpu_layers=%d no_host=%d\n",
                 mparams.n_gpu_layers, (int)mparams.no_host);
        ggml_log_forward(GGML_LOG_LEVEL_INFO, plog, nullptr);
    }
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

    auto session = std::shared_ptr<Session>(new Session(), [](Session* s) {
        if (s->ctx != nullptr) llama_free(s->ctx);
        // Drop the adapter handles first: they belong to the model, which frees
        // them on the next line, so holding them past this point would dangle.
        s->loras.clear();
        s->lora_scales.clear();
        if (s->vision != nullptr) mtmd_free(s->vision);
        if (s->model != nullptr) llama_model_free(s->model);
        delete s;
    });
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
    auto session = findSession(id);
    if (session == nullptr) return 0;
    return static_cast<jint>(llama_n_ctx(session->ctx));
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketllm_llm_LlamaBridge_applyChatTemplate(JNIEnv* env, jobject, jlong id,
                                                     jobjectArray roles, jobjectArray contents) {
    auto session = findSession(id);
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
    auto session = findSession(id);
    if (session != nullptr) session->stopGen.store(true);
}

extern "C" JNIEXPORT jintArray JNICALL
Java_com_pocketllm_llm_LlamaBridge_generate(JNIEnv* env, jobject, jlong id, jstring jPrompt,
                                            jint maxNewTokens, jfloat temperature, jfloat topP,
                                            jint topK, jlong seed, jstring jGrammar, jobject sink) {
    auto session = findSession(id);
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
        const int keep = nCtx - 16;
        if (keep <= 0) {
            nPrompt = 0; // degenerate context; the decode check below fails cleanly
        } else {
            // Preserve the leading BOS token; drop the oldest middle tokens.
            const size_t start =
                (!tokens.empty() && tokens.front() == llama_vocab_bos(vocab)) ? 1 : 0;
            const size_t overflow = tokens.size() - static_cast<size_t>(keep);
            if (overflow > 0 && start + overflow < tokens.size()) {
                tokens.erase(tokens.begin() + static_cast<long>(start),
                             tokens.begin() + static_cast<long>(start + overflow));
            }
            nPrompt = static_cast<int>(tokens.size());
        }
    }

    jclass sinkClass = env->GetObjectClass(sink);
    jmethodID onTokenMethod = env->GetMethodID(sinkClass, "onToken", "([B)Z");
    env->DeleteLocalRef(sinkClass);
    if (onTokenMethod == nullptr) return nullptr;

    llama_sampler* sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
    // Grammar goes FIRST: chain samplers act on the candidate array in order,
    // so grammar masks out disallowed tokens and the trailing greedy/dist
    // sampler then picks among the survivors. (Appending grammar after greedy
    // would be worse: greedy's p=1.0 marker survives and grammar's mask is
    // ignored.) This matches llama.cpp's own server chain layout.
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

    // Prompt evaluation must be chunked: llama.cpp asserts (GGML_ASSERT, aborts
    // the process) that a single llama_decode call carries at most n_batch
    // tokens. Agent-mode prompts - system + tool block + history - routinely
    // exceed small batch sizes such as the BatterySaver/Balanced presets.
    // Each chunk's logits are produced only for its last token, so the final
    // chunk leaves exactly the logits llama_sampler_sample(..., -1) needs.
    const int nBatch = (int)llama_n_batch(ctx);
    bool promptOk = nPrompt > 0;
    for (int i = 0; promptOk && i < nPrompt; i += nBatch) {
        const int chunk = std::min(nBatch, nPrompt - i);
        if (llama_decode(ctx, llama_batch_get_one(tokens.data() + i, chunk)) != 0) {
            promptOk = false;
        }
    }
    if (!promptOk) {
        llama_sampler_free(sampler);
        return nullptr;
    }

    jintArray result = nullptr;
    std::string pending;
    bool cancelled = false;
    int nGenerated = 0;

    auto invokeSink = [&](const char* data, size_t len) -> bool {
        jbyteArray chunk = env->NewByteArray(static_cast<jsize>(len));
        if (chunk == nullptr || env->ExceptionCheck()) return false;
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

// Quantizes a GGUF in place-ish: llama.cpp's own quantizer, so the K-quant
// block formats come from the reference implementation rather than a rewrite.
// Returns 0 on success, matching llama_model_quantize.
extern "C" JNIEXPORT jint JNICALL
Java_com_pocketllm_llm_LlamaBridge_quantizeFile(JNIEnv* env, jobject, jstring jIn,
                                                jstring jOut, jint ftype) {
    const char* in = env->GetStringUTFChars(jIn, nullptr);
    const char* out = env->GetStringUTFChars(jOut, nullptr);
    llama_model_quantize_params params = llama_model_quantize_default_params();
    params.ftype = (enum llama_ftype)ftype;
    const uint32_t rc = llama_model_quantize(in, out, &params);
    env->ReleaseStringUTFChars(jIn, in);
    env->ReleaseStringUTFChars(jOut, out);
    return (jint)rc;
}

// Loads the projection (mmproj) file that gives a text model vision. Returns
// true only when the projection actually reports vision support, so the caller
// can trust the answer rather than the request.
extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketllm_llm_LlamaBridge_loadVision(JNIEnv* env, jobject, jlong id, jstring jMmproj) {
    Session* s = reinterpret_cast<Session*>(id);
    if (s == nullptr || s->model == nullptr || jMmproj == nullptr) return JNI_FALSE;

    const char* p = env->GetStringUTFChars(jMmproj, nullptr);
    std::string mmproj(p == nullptr ? "" : p);
    env->ReleaseStringUTFChars(jMmproj, p);
    if (mmproj.empty()) return JNI_FALSE;

    if (s->vision != nullptr) {
        mtmd_free(s->vision);
        s->vision = nullptr;
    }

    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = true;
    params.print_timings = false;
    params.n_threads = 4;

    s->vision = mtmd_init_from_file(mmproj.c_str(), s->model, params);
    if (s->vision == nullptr) return JNI_FALSE;
    return mtmd_support_vision(s->vision) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_pocketllm_llm_LlamaBridge_hasVision(JNIEnv*, jobject, jlong id) {
    Session* s = reinterpret_cast<Session*>(id);
    return (s != nullptr && s->vision != nullptr && mtmd_support_vision(s->vision))
        ? JNI_TRUE : JNI_FALSE;
}

// One-shot image description: tokenize prompt+image, evaluate the chunks, then
// greedily decode. Returns null on any failure so the caller can fall back.
extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketllm_llm_LlamaBridge_visionDescribe(JNIEnv* env, jobject, jlong id,
        jstring jPrompt, jstring jImage, jint maxTokens) {
    Session* s = reinterpret_cast<Session*>(id);
    if (s == nullptr || s->vision == nullptr || s->ctx == nullptr || jImage == nullptr) return nullptr;

    const char* ip = env->GetStringUTFChars(jImage, nullptr);
    std::string image(ip == nullptr ? "" : ip);
    env->ReleaseStringUTFChars(jImage, ip);

    const char* pp = jPrompt == nullptr ? nullptr : env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(pp == nullptr ? "" : pp);
    if (pp != nullptr) env->ReleaseStringUTFChars(jPrompt, pp);

    // The marker must be present for the bitmap to be spliced into the prompt.
    if (prompt.find(mtmd_default_marker()) == std::string::npos) {
        prompt = std::string(mtmd_default_marker()) + "\n" + prompt;
    }

    mtmd_helper_bitmap_wrapper wrap = mtmd_helper_bitmap_init_from_file(s->vision, image.c_str(), false);
    if (wrap.bitmap == nullptr) return nullptr;

    mtmd_input_chunks* chunks = mtmd_input_chunks_init();
    mtmd_input_text text;
    text.text = prompt.c_str();
    // text_len is its own field and is NOT inferred from text: left
    // uninitialised, mtmd tokenises a garbage-length string and reports a
    // tokenize failure regardless of how good the image or marker are.
    text.text_len = prompt.size();
    text.add_special = true;
    text.parse_special = true;

    const mtmd_bitmap* bitmaps[1] = { wrap.bitmap };
    int32_t rc = mtmd_tokenize(s->vision, chunks, &text, bitmaps, 1);
    mtmd_bitmap_free(wrap.bitmap);
    if (rc != 0) {
        mtmd_input_chunks_free(chunks);
        return nullptr;
    }

    // Clear the KV cache BEFORE evaluating, not after: the prompt and the image
    // embeddings live in that cache, so clearing afterwards destroys the very
    // state the decode loop samples from (and leaves logits undefined).
    llama_memory_clear(llama_get_memory(s->ctx), false);

    llama_pos n_past = 0;
    if (mtmd_helper_eval_chunks(s->vision, s->ctx, chunks, n_past, 0, 512, true, &n_past) != 0) {
        mtmd_input_chunks_free(chunks);
        return nullptr;
    }
    mtmd_input_chunks_free(chunks);

    const llama_vocab* vocab = llama_model_get_vocab(s->model);
    std::string out;
    const int limit = maxTokens > 0 ? maxTokens : 256;

    for (int i = 0; i < limit; i++) {
        float* logits = llama_get_logits_ith(s->ctx, -1);
        if (logits == nullptr) break;
        const int n_vocab = llama_vocab_n_tokens(vocab);
        int best = 0;
        for (int t = 1; t < n_vocab; t++) if (logits[t] > logits[best]) best = t;
        if (llama_vocab_is_eog(vocab, best)) break;
        char buf[256];
        const int n = llama_token_to_piece(vocab, best, buf, sizeof(buf), 0, true);
        if (n > 0) out.append(buf, n);
        // Feed the chosen token back so the next step has a position to decode
        // into. A real llama_token, not a reinterpret_cast of the loop index.
        llama_token tok = best;
        llama_batch batch = llama_batch_get_one(&tok, 1);
        if (llama_decode(s->ctx, batch) != 0) break;
    }
    return env->NewStringUTF(out.c_str());
}

// Applies LLM LoRA adapters to a loaded session.
//
// spec is "path|scale;path|scale"; a missing scale means 1.0. Passing an empty
// string detaches whatever was applied before. Returns the number of adapters
// applied, or a negative code naming which stage failed.
extern "C" JNIEXPORT jint JNICALL
Java_com_pocketllm_llm_LlamaBridge_setLoras(JNIEnv* env, jobject, jlong id, jstring jLoras) {
    // id <= 0 is the caller's "no session" sentinel, and casting it would give a
    // wild pointer rather than a null one, which the nullptr check would miss.
    if (id <= 0) return -1;
    Session* s = reinterpret_cast<Session*>(id);
    if (s == nullptr || s->model == nullptr || s->ctx == nullptr) return -1;

    std::string spec;
    if (jLoras != nullptr) {
        const char* p = env->GetStringUTFChars(jLoras, nullptr);
        spec.assign(p != nullptr ? p : "");
        if (p != nullptr) env->ReleaseStringUTFChars(jLoras, p);
    }

    // Detach first: llama_set_adapters_lora only reconfigures when the set
    // differs, so leaving the old ones in place would silently keep them active.
    if (!s->loras.empty()) {
        if (llama_set_adapters_lora(s->ctx, nullptr, 0, nullptr) != 0) return -2;
        s->loras.clear();
        s->lora_scales.clear();
    }
    if (spec.empty()) return 0;

    size_t start = 0;
    int applied = 0;
    while (start < spec.size()) {
        size_t semi = spec.find(';', start);
        std::string entry = spec.substr(start,
            semi == std::string::npos ? std::string::npos : semi - start);
        start = (semi == std::string::npos) ? spec.size() : semi + 1;
        if (entry.empty()) continue;

        std::string path = entry;
        float scale = 1.0f;
        size_t bar = entry.find('|');
        if (bar != std::string::npos) {
            path = entry.substr(0, bar);
            const float v = std::strtof(entry.c_str() + bar + 1, nullptr);
            if (v > 0.0f) scale = v;
        }
        if (path.empty()) continue;

        llama_adapter_lora* adapter = llama_adapter_lora_init(s->model, path.c_str());
        if (adapter == nullptr) return -(10 + applied);
        s->loras.push_back(adapter);
        s->lora_scales.push_back(scale);
        applied++;
    }

    if (s->loras.empty()) return 0;
    if (llama_set_adapters_lora(s->ctx, s->loras.data(), s->loras.size(),
                                s->lora_scales.data()) != 0) return -3;
    return applied;
}
