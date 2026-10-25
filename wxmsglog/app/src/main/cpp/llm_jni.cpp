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
#include <cmath>
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

    /**
     * 上一次 prefill 过的 token 序列（**不含**生成出来的部分）。
     *
     * 这是 KV 前缀复用的依据。抽待办时每条消息的提示词开头都一样——
     * system 指令、"当前时间 …"——那几百个 token 的 KV 完全没必要重算。
     * 下次生成时算出公共前缀，只 prefill 后面那截新增的内容。
     *
     * 失败或中断时清空，让下一轮老老实实从头算，避免状态错乱。
     */
    std::vector<llama_token> prefix;

    /** 复用的解码 batch。每个 chunk 重新填内容，避免反复 malloc。 */
    llama_batch batch     = {};
    int         batch_cap = 0;

    std::mutex          lock;   // 同一个 session 不能并发生成
    std::atomic<bool>   abort{false};

    ~Session() {
        if (batch_cap > 0) {
            llama_batch_free(batch);
            batch_cap = 0;
        }
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

/**
 * 把一个 chunk 送进 decode。
 *
 * 不用 llama_batch_get_one：那个 helper 不设 pos，靠 llama_decode 内部
 * 自动推断位置。但 KV 前缀复用必须能从**任意位置**接着算（比如从第 174
 * 个 token 开始），位置必须由我们明确指定，不能交给隐式行为。
 *
 * logits 只对 chunk 的最后一个 token 置 1——生成只需要最后一个位置的输出，
 * 前面那些算出来也没人看，标 0 能让后端少拷贝一批数据。
 */
bool decodeChunk(Session * s, const llama_token * toks, int n, llama_pos pos0) {
    if (n <= 0) return true;

    if (s->batch_cap < n) {
        if (s->batch_cap > 0) llama_batch_free(s->batch);
        const int cap = std::max(n, 512);
        s->batch = llama_batch_init(cap, 0, 1);
        s->batch_cap = cap;
    }

    llama_batch & b = s->batch;
    b.n_tokens = n;
    for (int j = 0; j < n; ++j) {
        b.token[j]     = toks[j];
        b.pos[j]       = pos0 + (llama_pos) j;
        b.n_seq_id[j]  = 1;
        b.seq_id[j][0] = 0;
        b.logits[j]    = (j == n - 1) ? 1 : 0;
    }
    return llama_decode(s->ctx, b) == 0;
}

std::once_flag g_backend_once;

/**
 * 把 UTF-8 文本切成 token。
 * parse_special 必须为 true，否则 <|im_start|> 会被拆碎。
 *
 * @param add_special 生成模型传 false（Qwen3 的模板自带全部特殊 token）；
 *                    向量模型要传 true——BGE 的输入需要前面那个 [CLS]。
 */
std::vector<llama_token> tokenize(const llama_vocab * vocab, const std::string & text,
                                  bool add_special) {
    // 传 nullptr 先问需要多大缓冲，返回值取负
    int32_t need = -llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                                   nullptr, 0, add_special, true);
    if (need <= 0) return {};

    std::vector<llama_token> out((size_t) need);
    int32_t got = llama_tokenize(vocab, text.c_str(), (int32_t) text.size(),
                                 out.data(), need, add_special, true);
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
    // 一次 decode 能吃多少 token。抽待办的提示词只有几百 token，512 就够；
    // 但日报那种一千多 token 的长提示词要分几次 decode，每次都要重新调度
    // 一遍线程，1024 能少一轮。多占的临时内存只有几兆。
    cp.n_batch    = 1024;
    cp.n_threads  = nThreads > 0 ? nThreads : 4;
    cp.n_threads_batch = cp.n_threads;

    // ---- 下面两项是这轮提速的主要来源 ----
    //
    // KV cache 用 Q8_0 而不是 F16：占用直接减半。生成阶段是**内存带宽瓶颈**
    // ——每产出一个 token 都要把整个 KV 读一遍，读的量少一半，速度就上来一截。
    // 精度上 Q8 的误差远小于权重本身的 4bit 量化，实际输出基本无差别。
    //
    // Flash Attention：把注意力计算从"每个 query 重读一遍 KV"改成按块流式处理，
    // 同样是在省带宽。CPU 后端支持，但在极老的设备上可能编不出来。
    cp.type_k = GGML_TYPE_Q8_0;
    cp.type_v = GGML_TYPE_Q8_0;
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_ENABLED;

    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        // 这两项属于「锦上添花」，不能因为它们失败就让整个 AI 功能不可用。
        // 退回最保守的配置再来一次，慢一点但一定能跑。
        LOGE("KV 量化 / Flash Attention 组合不被支持，退回默认参数");
        cp.type_k = GGML_TYPE_F16;
        cp.type_v = GGML_TYPE_F16;
        cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
        ctx = llama_init_from_model(model, cp);
    }
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

    LOGI("模型就绪 n_ctx=%d threads=%d kv=%s fa=%s",
         s->n_ctx, cp.n_threads,
         cp.type_k == GGML_TYPE_Q8_0 ? "q8_0" : "f16",
         cp.flash_attn_type == LLAMA_FLASH_ATTN_TYPE_ENABLED ? "on" : "off");
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

    std::vector<llama_token> tokens = tokenize(s->vocab, prompt, false);
    if (tokens.empty()) {
        LOGE("提示词切词失败");
        return toBytes(env, "");
    }
    // 留出生成空间。超长提示词要裁剪，但**不能从头裁**——开头那段是
    // <|im_start|>system 里的任务指令，裁掉之后模型不知道自己要干什么，
    // 会开始胡说；结尾的 <|im_start|>assistant 也是必须留的，那是生成的起点。
    // 所以只砍中间：头部保留任务指令，尾部保留最近的用户内容和 assistant 标记。
    const size_t budget = (size_t) s->n_ctx - (size_t) (maxTokens > 0 ? maxTokens : 96) - 8;
    if (tokens.size() > budget) {
        const size_t before = tokens.size();
        const size_t head = budget / 3;
        const size_t tail = budget - head;
        std::vector<llama_token> trimmed;
        trimmed.reserve(budget);
        trimmed.insert(trimmed.end(), tokens.begin(), tokens.begin() + (long) head);
        trimmed.insert(trimmed.end(), tokens.end() - (long) tail, tokens.end());
        tokens.swap(trimmed);
        LOGI("提示词过长已裁剪：%zu -> %zu token", before, tokens.size());
    }

    // ---- KV 前缀复用 ----
    //
    // 原来是每次都 llama_memory_clear 全清，再把整段提示词重新 prefill 一遍。
    // 但连续抽待办时，每条消息的提示词开头**完全一样**：
    //
    //     <|im_start|>system 你是待办提取器…<|im_end|>
    //     <|im_start|>user 当前时间 2026-10-25 20:54
    //     ↓ 到这里之前一个 token 都不差
    //     张三：明天下午三点前把材料发我
    //
    // 那前面这一百多个 token 的 KV 是现成的，重算纯粹是浪费。prefill 是纯计算
    // 而且 attention 那部分是平方级，省下来的是实打实的时间。
    //
    // 做法：算出和新提示词的公共前缀长度 common，只删掉 common 之后的 KV，
    // 然后从 common 位置接着 decode。第一条没有可复用的前缀，等于全量重算。
    size_t common = 0;
    {
        const size_t limit = std::min(s->prefix.size(), tokens.size());
        while (common < limit && s->prefix[common] == tokens[common]) ++common;
    }

    // 前缀不能等于全长：至少要留最后一个 token 走一次 decode。
    // 否则拿不到本轮的 logits，采样会读到上一轮生成残留的那一份，结果完全错乱。
    if (common >= tokens.size()) common = tokens.size() - 1;

    llama_memory_t mem = llama_get_memory(s->ctx);
    if (mem) {
        if (common > 0) {
            // 保留 [0, common)，把后面连同上一轮生成的 token 一起删掉
            if (!llama_memory_seq_rm(mem, 0, (llama_pos) common, -1)) {
                // 部分删除失败（比如 KV 被切成不连续的块），只能全清重来
                llama_memory_clear(mem, true);
                common = 0;
            }
        } else {
            llama_memory_clear(mem, true);
        }
    } else {
        common = 0;
    }
    if (common > 0) {
        LOGI("复用 KV 前缀 %zu / %zu token", common, tokens.size());
    }

    const int batch = std::max(1, (int) llama_n_batch(s->ctx));
    for (size_t i = common; i < tokens.size(); i += (size_t) batch) {
        const int cur = (int) std::min((size_t) batch, tokens.size() - i);
        if (!decodeChunk(s, tokens.data() + i, cur, (llama_pos) i)) {
            LOGE("提示词解码失败 pos=%zu", i);
            s->prefix.clear();   // 状态不明，下一轮从头来
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
    const llama_pos base = (llama_pos) tokens.size();   // 生成内容的起始位置
    int generated = 0;
    bool ok = true;

    for (int i = 0; i < limit; ++i) {
        if (s->abort.load()) break;

        llama_token id = llama_sampler_sample(smpl, s->ctx, -1);
        if (llama_vocab_is_eog(s->vocab, id)) break;

        char piece[512];
        int32_t n = llama_token_to_piece(s->vocab, id, piece, (int32_t) sizeof(piece), 0, false);
        if (n > 0) out.append(piece, (size_t) n);

        // 位置必须显式接着提示词往后排——复用了前缀之后，KV 里已有 0..base-1
        if (!decodeChunk(s, &id, 1, base + (llama_pos) generated)) {
            ok = false;
            break;
        }
        ++generated;
    }

    llama_sampler_free(smpl);

    // 只有正常跑完才把这段提示词记成新前缀。中断或解码失败时 KV 状态不可信，
    // 拿它当前缀下一轮会算错——宁可从零重新 prefill。
    if (ok && !s->abort.load()) {
        s->prefix = tokens;
    } else {
        s->prefix.clear();
    }
    return toBytes(env, out);
}

/**
 * 加载向量模型。
 *
 * 和生成模型共用同一个 Session 结构，区别全在上下文参数：
 * embeddings=true 让它算完不算 logits，pooling 用 CLS（BGE 系列的约定），
 * n_ctx 给 512 就够——一条消息截断之后不会超过这个长度。
 */
JNIEXPORT jlong JNICALL
Java_com_wxmsglog_Llm_nativeLoadEmbed(JNIEnv * env, jobject /*thiz*/, jstring jpath) {
    std::call_once(g_backend_once, [] { llama_backend_init(); });

    const std::string path = jstr(env, jpath);

    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.load_mode = LLAMA_LOAD_MODE_MMAP;

    llama_model * model = llama_model_load_from_file(path.c_str(), mp);
    if (!model) {
        LOGE("向量模型加载失败: %s", path.c_str());
        return 0;
    }

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = 512;
    cp.n_batch = 512;
    cp.n_threads = 4;
    cp.n_threads_batch = 4;
    cp.embeddings    = true;
    cp.pooling_type  = LLAMA_POOLING_TYPE_CLS;

    llama_context * ctx = llama_init_from_model(model, cp);
    if (!ctx) {
        LOGE("向量上下文创建失败");
        llama_model_free(model);
        return 0;
    }

    auto * s = new Session();
    s->model = model;
    s->ctx   = ctx;
    s->vocab = llama_model_get_vocab(model);
    s->n_ctx = (int) llama_n_ctx(ctx);

    LOGI("向量模型就绪，维度 %d", llama_model_n_embd(model));
    return reinterpret_cast<jlong>(s);
}

/**
 * 把一段文字变成向量。
 * 返回的是 L2 归一化之后的数组——存进库里就是单位向量，检索时一次点积即余弦。
 */
JNIEXPORT jfloatArray JNICALL
Java_com_wxmsglog_Llm_nativeEmbed(JNIEnv * env, jobject /*thiz*/,
                                  jlong handle, jstring jtext) {
    auto * s = reinterpret_cast<Session *>(handle);
    if (!s || !s->ctx) return env->NewFloatArray(0);

    std::lock_guard<std::mutex> guard(s->lock);

    const std::string text = jstr(env, jtext);
    std::vector<llama_token> tokens = tokenize(s->vocab, text, true);
    if (tokens.empty()) return env->NewFloatArray(0);

    // 超长就截断。向量模型抓的是「大意」，不需要每个字都进去
    const size_t limit = (size_t) s->n_ctx - 4;
    if (tokens.size() > limit) tokens.resize(limit);

    llama_memory_t mem = llama_get_memory(s->ctx);
    if (mem) llama_memory_clear(mem, true);

    llama_batch batch = llama_batch_get_one(tokens.data(), (int32_t) tokens.size());
    if (llama_decode(s->ctx, batch) != 0) {
        LOGE("向量计算失败");
        return env->NewFloatArray(0);
    }

    float * e = llama_get_embeddings(s->ctx);
    const int32_t dim = llama_model_n_embd(s->model);
    if (!e || dim <= 0) return env->NewFloatArray(0);

    float sq = 0.0f;
    for (int32_t i = 0; i < dim; ++i) sq += e[i] * e[i];
    const float norm = std::sqrt(sq);
    if (norm <= 1e-8f) return env->NewFloatArray(0);

    std::vector<float> unit((size_t) dim);
    for (int32_t i = 0; i < dim; ++i) unit[(size_t) i] = e[i] / norm;

    jfloatArray arr = env->NewFloatArray(dim);
    if (arr) env->SetFloatArrayRegion(arr, 0, dim, unit.data());
    return arr;
}

} // extern "C"
