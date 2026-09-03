#import "LlamaBridge.h"
#include "llama.h"

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
    int n = -1;
    while (true) {
        n = llama_tokenize(vocab, text.c_str(), (int32_t)text.size(),
                           tokens.data(), (int32_t)tokens.size(), true, true);
        if (n >= 0) break;
        tokens.resize((size_t)(-n));
    }
    tokens.resize((size_t)n);
    return tokens;
}

} // namespace

@implementation LlamaContext {
    llama_model *_model;
    llama_context *_ctx;
    std::atomic<bool> _stopGen;
    std::mutex _busy;
}

- (nullable instancetype)initWithModelPath:(NSString *)path
                               contextSize:(int32_t)contextSize
                                 batchSize:(int32_t)batchSize
                                   threads:(int)threads
                                     error:(NSError **)error {
    self = [super init];
    if (!self) return nil;

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0; // CPU-only on iOS for now
    _model = llama_model_load_from_file(path.fileSystemRepresentation, mparams);
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
            size_t overflow = tokens.size() - (size_t)(nCtx - 16);
            tokens.erase(tokens.begin(), tokens.begin() + (long)overflow);
            nPrompt = (int)tokens.size();
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

        if (nPrompt <= 0 || llama_decode(self->_ctx,
                llama_batch_get_one(tokens.data(), nPrompt)) != 0) {
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

            char piece[512];
            int nPiece = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
            if (nPiece > 0) {
                pending.append(piece, (size_t)nPiece);
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
