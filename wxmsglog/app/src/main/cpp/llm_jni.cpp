// llama.cpp 的 JNI 桥。
//
// 刻意只暴露四个方法：加载、生成、中断、释放。所有提示词拼装、JSON 约束、
// 结果解析都留在 Kotlin 侧——那边改一行就能调，不用重新编 .so。
//
// 关于线程：llama_context 不是线程安全的，一次只能有一个生成在跑。
// 这里用 session 内部锁保证这一点，上层不必操心。

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include "llama.h"

#define TAG "WxLlm"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace {

/** 一次加载对应一个 Session，handle 就是这个指针。 */
struct Session {
    llama_model       * model = nullptr;
    llama_context     * ctx   = nullptr;
    const llama_vocab * vocab = nullptr;
    int                 n_ctx = 0;

    std::mutex          lock;   // 同一个 session 不能并发生成
    std::atomic<bool>   abort{false};

    ~Session() {
        if (ctx) {
            llama_free(ctx);
            ctx = nullptr;
        }
        if (model) {
            llama_model_free(model);
            model = nullptr;
        }
        vocab = nullptr;
    }
};

std::once_flag g_backend_once;

/** 把 UTF-8 文本切成 token。parse_special 必须为 true，否则 <|im_start|> 会被拆碎。 */
std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text) {
    // 传 nullptr 先问需要多大缓冲，返回值取负
    int32_t need = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                                   nullptr, 0, false, true);
    if (need <= 0) return {};

    std::vector<llama_token> out((size_t) need);
    int32_t got = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                                 out.data(), need, false, true);
    if (got < 0) return {};
    out.resize((size_t) got);
    return out;
}

/** 取一个 JNI 字符串，null 返回空串。 */
std::string jstr(JNIEnv * env, jstring value) {
    if (!value) return {};
    const char * raw = env->GetStringUTFChars(value, nullptr);
    if (!raw) return {};
    std::string out(raw);
    env->ReleaseStringUTFChars(value, raw);
    return out;
}

/** 把 UTF-8 字节原样交给 Kotlin。四字节字符也不会被改坏。 */
jbyteArray toBytes(JNIEnv * env, const std::string & text) {
    jbyteArray arr = env->NewByteArray((jsize) text.size());
    if (arr != nullptr && !text.empty()) {
        env->SetByteArrayRegion(arr, 0, (jsize) text.size(),
                                reinterpret_cast<const jbyte *>(text.data()));
    }
    return arr;
}

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_wxmsglog_Llm_nativeLoad(JNIEnv * env, jobject /*thiz*/,
                                jstring jpath, jint nCtx, jint nThreads) {
    std::call_once(g_backend_once, [] { llama_backend_init(); });

    const std::string path = jstr(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;      // 纯 CPU：GPU 后端在国产 ROM 上驱动差异太大，不碰
    // v0.7.0 用 load_mode 枚举取代了老的 use_mmap / use_mlock 两个开关。
    // LLAMA_LOAD_MODE_MMAP 就是原来 use_mmap=true 的语义：1.3GB 按页换入，
    // 不会在加载时整块读进内存。不碰 mlock——安卓上锁内存会招来 OOM killer。
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        LOGE("模型加载失败: %s", path.c_str());
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    // 不能超过模型训练时的上下文，超了会直接返回空
    const int32_t trained = llama_model_n_ctx_train(model);
    cp.n_ctx = (uint32_t) (nCtx > 0 ? (nCtx < trained ? nCtx : trained) : 2048);
    cp.n_batch    = 512;
    cp.n_threads  = nThreads > 0 ? nThreads : 4;
    cp.n_threads_batch = cp.n_threads;

    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("上下文创建失败");
        llama_model_free(model);
        return 0;
    }

    auto * s = new Session();
    s->model = model;
    s->ctx   = ctx;
    s->vocab = llama_model_get_vocab(model);
    s->n_ctx = (int) llama_n_ctx(ctx);

    LOGI("模型就绪 n_ctx=%d threads=%d", s->n_ctx, cp.n_threads);
    return reinterpret_cast<jlong>(s);
}

JNIEXPORT void JNICALL
Java_com_wxmsglog_Llm_nativeAbort(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (s) s->abort.store(true);
}

JNIEXPORT void JNICALL
Java_com_wxmsglog_Llm_nativeFree(JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (!s) return;
    delete s;   // 析构里会 llama_free / llama_model_free
    LOGI("模型已释放");
}

/**
 * 跑一次生成，返回 UTF-8 字节数组。
 *
 * 刻意不用 NewStringUTF：那个函数认的是「modified UTF-8」，四字节字符
 * （emoji、𠮷 这类扩展区汉字）会被当成非法序列处理，输出直接乱掉。
 * 微信里这两类字符都不罕见，所以原样把字节交给 Kotlin，由它按 UTF-8 解。
 *
 * grammar 非空时用它约束输出——这是保证小模型吐出合法 JSON 的关键，
 * 比在提示词里写「只输出 JSON」可靠得多：采样阶段就把非法 token 掐掉了。
 */
JNIEXPORT jbyteArray JNICALL
Java_com_wxmsglog_Llm_nativeGenerate(JNIEnv * env, jobject /*thiz*/,
                                     jlong handle, jstring jprompt,
                                     jstring jgrammar, jint maxTokens) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (!s || !s->ctx) return env->NewByteArray(0);

    std::lock_guard<std::mutex> guard(s->lock);
    s->abort.store(false);

    const std::string prompt  = jstr(env, jprompt);
    const std::string grammar = jstr(env, jgrammar);

    std::vector<llama_token> tokens = tokenize(s->vocab, prompt);
    if (tokens.empty()) {
        LOGE("提示词切词失败");
        return toBytes(env, "");
    }
    // 留出生成空间，超长的对话只喂最后一段
    const size_t budget = (size_t) s->n_ctx - (size_t) (maxTokens > 0 ? maxTokens : 96) - 8;
    if (tokens.size() > budget) {
        tokens.erase(tokens.begin(), tokens.end() - (long) budget);
    }

    // 每次生成都是一段独立对话，先清掉上一轮的 KV
    llama_memory_t mem = llama_get_memory(s->ctx);
    if (mem) llama_memory_clear(mem, true);

    const int batch = (int) llama_n_batch(s->ctx);
    for (size_t i = 0; i < tokens.size(); i += (size_t) batch) {
        const int cur = (int) std::min((size_t) batch, tokens.size() - i);
        llama_batch b = llama_batch_get_one(tokens.data() + i, cur);
        if (llama_decode(s->ctx, b) != 0) {
            LOGE("提示词解码失败 pos=%zu", i);
            return toBytes(env, "");
        }
    }

    llama_sampler * smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    if (!grammar.empty()) {
        // 语法里出现词表里没有的字符时，这里会返回空。塞进链里必崩，
        // 所以退化成无约束生成——格式可能跑偏，但至少不会闪退。
        llama_sampler * g = llama_sampler_init_grammar(s->vocab, grammar.c_str(), "root");
        if (g) {
            llama_sampler_chain_add(smpl, g);
        } else {
            LOGE("语法解析失败，本次不约束输出");
        }
    }
    // 贪心解码：抽待办要的是一致性，同一句话每次结果都得一样
    llama_sampler_chain_add(smpl, llama_sampler_init_greedy());

    std::string out;
    out.reserve(1024);
    const int limit = maxTokens > 0 ? maxTokens : 96;

    for (int i = 0; i < limit; ++i) {
        if (s->abort.load()) break;

        llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, id)) break;

        char piece[512];
        int32_t n = llama_token_to_piece(s->vocab, id, piece, (int32_t) sizeof(piece), 0, false);
        if (n > 0) out.append(piece, (size_t) n);

        llama_batch b = llama_batch_get_one(&id, 1);
        if (llama_decode(s->ctx, b) != 0) break;
    }

    llama_sampler_free(smpl);
    return toBytes(env, out);
}

} // extern "C"
