#import "LlamaBridge.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#include <algorithm>
#include <atomic>
#include <mutex>
#include <string>
#include <vector>

BOOL LlamaSupportsGpuOffload(void) {
    return llama_supports_gpu_offload() ? YES : NO;
}

void LlamaGlobalInit(void) {
    llama_backend_init();
}

namespace {

struct Session {
    llama_model *model = nullptr;
    llama_context *ctx = nullptr;
    std::atomic<bool> stopGen{false};
    std::mutex busy;
};

size_t utf8SequenceLength(unsigned char lead) {
    if ((lead & 0x80u) == 0) return 1;
    if ((lead & 0xE0u) == 0xC0) return 2;
    if ((lead & 0xF0u) == 0xE0) return 3;
    if ((lead & 0xF8u) == 0xF0) return 4;
    return 1;
}

std::vector<llama_token> tokenize(const llama_vocab *vocab, const std::string &text) {
    std::vector<llama_token> tokens(std::max(text.size() / 2u + (size_t)256, (size_t)256));
    // llama_tokenize returns the required size negated. Negating INT_MIN is UB,
    // so do the negation in 64-bit and cap the grow loop.
    for (int guard = 0; guard < 8; guard++) {
        const int n = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(),
                                     tokens.data(), (int32_t)tokens.size(), true, true);
        if (n >= 0) {
            tokens.resize((size_t)n);
            return tokens;
        }
        const int64_t need = -(int64_t)n;
        if (need <= (int64_t)tokens.size()) break; // pathological; bail
        tokens.resize((size_t)need);
    }
    tokens.clear();
    return tokens;
}

} // namespace


// Appends directly to the app's persistent log, flushed per line. Stage logging
// has to survive the process being killed -- that is exactly the case being
// diagnosed -- so it must not buffer or live only in memory.
static NSString *g_logPath = nil;

static void bridgeLog(NSString *msg) {
    static FILE *f = nullptr;
    static NSString *opened = nil;
    NSString *path = g_logPath;
    if (path.length == 0) {
        NSArray *dirs = NSSearchPathForDirectoriesInDomains(
            NSDocumentDirectory, NSUserDomainMask, YES);
        path = [dirs.firstObject stringByAppendingPathComponent:@"pocketllm.log"];
    }
    if (f == nullptr || ![opened isEqualToString:path]) {
        if (f != nullptr) { fclose(f); f = nullptr; }
        f = fopen(path.UTF8String, "a");
        opened = path;
    }
    if (f == nullptr) return;
    // Same shape as LogStore's lines (timestamp + [source] + message). The Logs
    // tab rebuilds its list by parsing the file and drops any line without a
    // leading timestamp, so a bare line written here is invisible on reload --
    // which is why the native stage logs never appeared.
    NSDateFormatter *df = [[NSDateFormatter alloc] init];
    df.dateFormat = @"yyyy-MM-dd HH:mm:ss.SSS";
    NSString *line = [NSString stringWithFormat:@"%@  [native]  %@",
                      [df stringFromDate:[NSDate date]], msg];
    fprintf(f, "%s\n", line.UTF8String);
    fflush(f);
}

// llama and mtmd report *why* something failed through this hook; without it the
// reason is printed to a console nobody is watching.
static void llamaLogBridge(enum ggml_log_level level, const char *text, void *user) {
    if (text == nullptr) return;
    NSString *t = [NSString stringWithUTF8String:text];
    if (t.length == 0) return;
    bridgeLog([NSString stringWithFormat:@"[llama-native] %@", [t stringByTrimmingCharactersInSet:[NSCharacterSet newlineCharacterSet]]]);
}

@implementation LlamaContext {
    llama_model *_model;
    llama_context *_ctx;
    mtmd_context *_vision;
    NSInteger _lastVisionTokens;
    std::atomic<bool> _stopGen;
    std::mutex _busy;
}

+ (void)setLogPath:(NSString *)path {
    g_logPath = [path copy];
    bridgeLog(@"bridge: log path set");
}

- (nullable instancetype)initWithModelPath:(NSString *)path
                               contextSize:(int32_t)contextSize
                                 batchSize:(int32_t)batchSize
                                   threads:(int)threads
                                nGpuLayers:(int32_t)nGpuLayers
                                     error:(NSError **)error {
    self = [super init];
    if (!self) return nil;

    const char *cpath = path.fileSystemRepresentation; // NULL for unrepresentable names
    if (cpath == NULL) {
        if (error) *error = [NSError errorWithDomain:@"PocketLLM" code:2
            userInfo:@{NSLocalizedDescriptionKey: @"model path not representable in filesystem encoding"}];
        return nil;
    }
    llama_model_params mparams = llama_model_default_params();
    // The caller decides how many layers to offload. A build with no GPU backend
    // (the simulator slice) cannot honour any of them, so the request is clamped
    // here rather than at every call site. If Metal is compiled in but cannot
    // create a device, llama.cpp logs and falls back to CPU on its own - the
    // model still loads either way.
    const BOOL gpuAvailable = llama_supports_gpu_offload() ? YES : NO;
    mparams.n_gpu_layers = gpuAvailable ? nGpuLayers : 0;
    _usingGpu = (gpuAvailable && nGpuLayers > 0) ? YES : NO;
    _model = llama_model_load_from_file(cpath, mparams);
    if (_model == nullptr) {
        if (error) *error = [NSError errorWithDomain:@"PocketLLM" code:1
            userInfo:@{NSLocalizedDescriptionKey: @"failed to load model"}];
        return nil;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = (uint32_t)contextSize;
    cparams.n_batch = (uint32_t)batchSize;
    cparams.n_ubatch = (uint32_t)batchSize;
    cparams.n_threads = threads;
    cparams.n_threads_batch = threads;
    _ctx = llama_init_from_model(_model, cparams);
    if (_ctx == nullptr) {
        llama_model_free(_model);
        _model = nullptr;
        if (error) *error = [NSError errorWithDomain:@"PocketLLM" code:2
            userInfo:@{NSLocalizedDescriptionKey: @"failed to create context"}];
        return nil;
    }
    return self;
}

- (void)dealloc {
    if (_vision) mtmd_free(_vision);
    if (_ctx) llama_free(_ctx);
    if (_model) llama_model_free(_model);
}

- (int32_t)contextLength {
    return _ctx ? (int32_t)llama_n_ctx(_ctx) : 0;
}

- (nullable NSString *)applyTemplate:(NSArray<NSArray<NSString *> *> *)messages {
    if (!_ctx || messages.count == 0) return nil;
    std::vector<llama_chat_message> msgs;
    msgs.reserve(messages.count);
    std::vector<std::string> storage;
    storage.reserve(messages.count * 2);
    for (NSArray<NSString *> *pair in messages) {
        if (pair.count < 2) continue;
        storage.push_back(pair[0].UTF8String ?: "");
        storage.push_back(pair[1].UTF8String ?: "");
        msgs.push_back(llama_chat_message{storage[storage.size() - 2].c_str(),
                                          storage[storage.size() - 1].c_str()});
    }
    std::vector<char> buffer(8192);
    int32_t written = -1;
    while (buffer.size() <= (1u << 22)) {
        written = llama_chat_apply_template(nullptr, msgs.data(), msgs.size(), true,
                                            buffer.data(), (int32_t)buffer.size());
        if (written >= 0 && (size_t)written < buffer.size() - 1) break;
        buffer.resize(buffer.size() * 2);
    }
    if (written < 0 || (size_t)written >= buffer.size()) return nil;
    buffer[(size_t)written] = '\0';
    return [NSString stringWithUTF8String:buffer.data()];
}

#pragma mark - Vision (mmproj)

+ (NSString *)visionMarker {
    const char *m = mtmd_default_marker();
    return (m != nullptr) ? @(m) : @"";
}

- (BOOL)loadVisionWithMmprojPath:(NSString *)path error:(NSError **_Nullable)error {
    if (!_model) {
        if (error) *error = [NSError errorWithDomain:@"LlamaBridge" code:2
            userInfo:@{NSLocalizedDescriptionKey: @"no model loaded"}];
        return NO;
    }
    if (_vision) {
        mtmd_free(_vision);
        _vision = nullptr;
    }
    mtmd_context_params params = mtmd_context_params_default();
    params.use_gpu = false;   // GGML_METAL is off for the simulator slice
    params.print_timings = false;
    params.n_threads = 4;

    bridgeLog(@"bridge: loading mmproj");
    _vision = mtmd_init_from_file(path.UTF8String, _model, params);
    if (!_vision) {
        if (error) *error = [NSError errorWithDomain:@"LlamaBridge" code:3
            userInfo:@{NSLocalizedDescriptionKey: @"could not load mmproj"}];
        return NO;
    }
    if (!mtmd_support_vision(_vision)) {
        if (error) *error = [NSError errorWithDomain:@"LlamaBridge" code:4
            userInfo:@{NSLocalizedDescriptionKey: @"mmproj reports no vision support"}];
        return NO;
    }
    return YES;
}

- (BOOL)supportsVision {
    return _vision != nullptr && mtmd_support_vision(_vision);
}

- (NSInteger)lastVisionTokens {
    return _lastVisionTokens;
}

- (void)describeImage:(NSString *)imagePath
               prompt:(NSString *)prompt
            maxTokens:(int)maxTokens
              onToken:(void (^)(NSString *))onToken
           completion:(void (^)(BOOL, NSString *_Nullable))completion {
    // Logged before the guard and before the mutex: if the lock is held by a
    // generation in flight this call blocks here, and any logging placed after
    // the lock would be invisible for exactly that case.
    bridgeLog(@"bridge: describe entered");
    llama_log_set(llamaLogBridge, nullptr);
    if (!_vision || !_ctx) {
        bridgeLog(@"bridge: no vision context");
        completion(NO, @"vision not loaded");
        return;
    }
    bridgeLog(@"bridge: waiting for busy lock");
    std::lock_guard<std::mutex> lock(_busy);
    bridgeLog(@"bridge: busy lock acquired");
    _stopGen.store(false);

    std::string text = prompt.UTF8String ?: "";
    // The marker is where the image tokens are spliced in; without it the
    // bitmap is never referenced by the prompt at all.
    const char *marker = mtmd_default_marker();
    if (marker && text.find(marker) == std::string::npos) {
        text = std::string(marker) + "\n" + text;
    }

    mtmd_helper_bitmap_wrapper wrap =
        mtmd_helper_bitmap_init_from_file(_vision, imagePath.UTF8String, false);
    if (wrap.bitmap == nullptr) {
        completion(NO, @"could not decode image");
        return;
    }

    bridgeLog(@"bridge: bitmap decoded, tokenizing");
    mtmd_input_chunks *chunks = mtmd_input_chunks_init();
    const mtmd_bitmap *bitmaps[1] = { wrap.bitmap };
    mtmd_input_text input;
    input.text = text.c_str();
    // text_len is a separate field and is NOT derived from text: leaving it
    // uninitialised made mtmd tokenise a garbage-length string, which is what
    // surfaced as "tokenize failed".
    input.text_len = text.size();
    input.add_special = true;
    input.parse_special = true;

    bridgeLog([NSString stringWithFormat:@"bridge: tokenizing chars=%zu", input.text_len]);
    if (mtmd_tokenize(_vision, chunks, &input, bitmaps, 1) != 0) {
        mtmd_input_chunks_free(chunks);
        completion(NO, @"tokenize failed");
        return;
    }

    // Start from an empty cache. A previous text generation leaves tokens in the
    // KV while mtmd begins from n_past = 0, and the stale entries are enough to
    // make the image batch fail to evaluate.
    bridgeLog([NSString stringWithFormat:@"bridge: tokenized chunks=%zu, eval starting", mtmd_input_chunks_size(chunks)]);
    llama_memory_clear(llama_get_memory(_ctx), true);

    llama_pos n_past = 0;
    const int32_t n_batch = 512;
    const uint32_t n_ctx_now = llama_n_ctx(_ctx);
    const size_t n_chunks = mtmd_input_chunks_size(chunks);
    if (mtmd_helper_eval_chunks(_vision, _ctx, chunks, n_past, 0, n_batch, true, &n_past) != 0) {
        // Carry the context size in the message: this call fails when the image
        // tokens do not fit, and without the numbers the reason is invisible.
        NSString *why = [NSString stringWithFormat:
            @"failed to evaluate the image prompt (n_ctx=%u, n_batch=%d, chunks=%zu) — "
            @"an image prompt needs a larger context than plain text",
            n_ctx_now, n_batch, n_chunks];
        mtmd_input_chunks_free(chunks);
        completion(NO, why);
        return;
    }
    bridgeLog([NSString stringWithFormat:@"bridge: eval done n_past=%d", (int)n_past]);
    _lastVisionTokens = (NSInteger)n_past;
    mtmd_input_chunks_free(chunks);

    const llama_vocab *vocab = llama_model_get_vocab(_model);
    bridgeLog(@"bridge: decode starting");
    int produced = 0;
    for (; produced < maxTokens; produced++) {
        if (_stopGen.load()) break;
        const float *logits = llama_get_logits_ith(_ctx, -1);
        if (!logits) break;
        const int n_vocab = llama_vocab_n_tokens(vocab);
        llama_token best = 0;
        float bestScore = logits[0];
        for (int i = 1; i < n_vocab; i++) {
            if (logits[i] > bestScore) { bestScore = logits[i]; best = i; }
        }
        if (llama_vocab_is_eog(vocab, best)) break;

        char piece[256];
        const int n = llama_token_to_piece(vocab, best, piece, (int32_t)sizeof(piece), 0, true);
        if (n > 0) {
            NSString *pieceStr = [[NSString alloc] initWithBytes:piece length:(NSUInteger)n
                                                         encoding:NSUTF8StringEncoding];
            if (pieceStr) onToken(pieceStr);
        }
        llama_batch batch = llama_batch_get_one(&best, 1);
        if (llama_decode(_ctx, batch) != 0) break;
    }
    completion(YES, nil);
}

- (void)stop {
    _stopGen.store(true);
}

- (void)generate:(NSString *)prompt
       maxTokens:(int)maxTokens
      temperature:(float)temperature
             topP:(float)topP
             topK:(int)topK
          onToken:(void (^)(NSString *))onToken
       completion:(void (^)(int, int, BOOL))completion {
    if (!_ctx || !onToken || !completion) return;
    dispatch_async(dispatch_get_global_queue(QOS_CLASS_USER_INITIATED, 0), ^{
        if (!self->_busy.try_lock()) { completion(0, 0, NO); return; }
        struct BusyUnlock { std::mutex &m; ~BusyUnlock() { m.unlock(); } } unlock{self->_busy};
        self->_stopGen.store(false);

        const llama_vocab *vocab = llama_model_get_vocab(self->_model);
        const int nCtx = (int)llama_n_ctx(self->_ctx);
        std::string text = prompt.UTF8String ?: "";

        std::vector<llama_token> tokens = tokenize(vocab, text);
        int nPrompt = (int)tokens.size();
        if (nPrompt >= nCtx) {
            const int keep = nCtx - 16;
            if (keep <= 0) {
                nPrompt = 0; // degenerate context; decode check below fails cleanly
            } else {
                // Preserve the leading BOS token; drop the oldest middle tokens
                // instead of blindly erasing from the front (which removed BOS
                // and the system prompt).
                const size_t start = (!tokens.empty() && tokens.front() == llama_vocab_bos(vocab)) ? 1 : 0;
                const size_t overflow = tokens.size() - (size_t)keep;
                if (overflow > 0 && start + overflow < tokens.size()) {
                    tokens.erase(tokens.begin() + (long)start, tokens.begin() + (long)(start + overflow));
                }
                nPrompt = (int)tokens.size();
            }
        }

        llama_sampler *sampler = llama_sampler_chain_init(llama_sampler_chain_default_params());
        if (temperature <= 0.0f) {
            llama_sampler_chain_add(sampler, llama_sampler_init_greedy());
        } else {
            if (topK > 0) llama_sampler_chain_add(sampler, llama_sampler_init_top_k(topK));
            if (topP < 1.0f) llama_sampler_chain_add(sampler, llama_sampler_init_top_p(topP, 1));
            llama_sampler_chain_add(sampler, llama_sampler_init_temp_ext(temperature, 0.0f, 1.0f));
            llama_sampler_chain_add(sampler, llama_sampler_init_dist(0xFFFFFFFFu));
        }

        // Chunked prompt eval: llama.cpp GGML_ASSERTs (process abort) if one
        // llama_decode carries more than n_batch tokens; agent prompts with the
        // tool block easily exceed small batch sizes. Last-token-only logits per
        // chunk leaves exactly what llama_sampler_sample(..., -1) reads.
        const int nBatch = (int)llama_n_batch(self->_ctx);
        bool promptOk = nPrompt > 0;
        for (int i = 0; promptOk && i < nPrompt; i += nBatch) {
            const int chunk = std::min(nBatch, nPrompt - i);
            if (llama_decode(self->_ctx, llama_batch_get_one(tokens.data() + i, chunk)) != 0) {
                promptOk = false;
            }
        }
        if (!promptOk) {
            llama_sampler_free(sampler);
            completion(0, 0, NO);
            return;
        }

        std::string pending;
        bool cancelled = false;
        int nGenerated = 0;
        const int HARD_CAP = 2048;
        int effectiveMax = maxTokens < 0 ? 0 : (maxTokens > HARD_CAP ? HARD_CAP : maxTokens);

        for (int i = 0; i < effectiveMax && nPrompt + nGenerated < nCtx; i++) {
            if (self->_stopGen.load()) { cancelled = true; break; }

            llama_token tok = llama_sampler_sample(sampler, self->_ctx, -1);
            if (llama_vocab_is_eog(vocab, tok)) break;
            // Feed the accepted token back so penalty/mirostat samplers track
            // state across calls. llama_sampler_sample does NOT do this itself.
            llama_sampler_accept(sampler, tok);

            // Grow the buffer until the piece fits: a fixed 512-byte stack
            // buffer silently truncates long CJK/emoji/multi-codepoint pieces.
            std::vector<char> pieceBuf(64);
            int nPiece = llama_token_to_piece(vocab, tok, pieceBuf.data(), (int)pieceBuf.size(), 0, true);
            if (nPiece > (int)pieceBuf.size()) {
                pieceBuf.resize((size_t)nPiece);
                nPiece = llama_token_to_piece(vocab, tok, pieceBuf.data(), (int)pieceBuf.size(), 0, true);
            }
            if (nPiece > 0) {
                pending.append(pieceBuf.data(), (size_t)nPiece);
                size_t offset = 0;
                while (offset < pending.size()) {
                    size_t seq = utf8SequenceLength((unsigned char)pending[offset]);
                    if (offset + seq > pending.size()) break;
                    NSString *chunk = [[NSString alloc] initWithBytes:pending.data() + offset
                                                               length:seq encoding:NSUTF8StringEncoding];
                    if (chunk) onToken(chunk);
                    offset += seq;
                }
                pending.erase(0, offset);
                if (self->_stopGen.load()) { cancelled = true; nGenerated++; break; }
            }

            nGenerated++;
            if (self->_stopGen.load()) { cancelled = true; break; }
            llama_batch batch = llama_batch_get_one(&tok, 1);
            if (llama_decode(self->_ctx, batch) != 0) break;
        }

        if (!pending.empty() && !cancelled) {
            NSString *chunk = [[NSString alloc] initWithBytes:pending.data()
                                                       length:pending.size() encoding:NSUTF8StringEncoding];
            if (chunk) onToken(chunk);
        }

        llama_sampler_free(sampler);
        completion(nPrompt, nGenerated, cancelled);
    });
}

@end
