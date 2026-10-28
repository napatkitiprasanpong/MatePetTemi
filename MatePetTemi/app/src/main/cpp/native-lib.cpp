#include <jni.h>
#include <string>
#include <vector>
#include "llama.h"
static llama_model* g_model = nullptr;
static llama_context* g_ctx = nullptr;
static llama_sampler* g_smpl = nullptr;
static const int N_CTX = 1024;

extern "C" JNIEXPORT jboolean JNICALL
Java_com_example_matepet_LlamaBridge_nativeLoad(JNIEnv* env, jobject, jstring jpath, jint threads) {
    llama_backend_init();
    const char* p = env->GetStringUTFChars(jpath, nullptr);
    llama_model_params mp = llama_model_default_params();
    mp.use_mmap = true;
    g_model = llama_model_load_from_file(p, mp);
    env->ReleaseStringUTFChars(jpath, p);
    if (!g_model) return JNI_FALSE;
    llama_context_params cp = llama_context_default_params();
    cp.n_ctx = N_CTX; cp.n_batch = 256; cp.n_threads = threads; cp.n_threads_batch = threads;
    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) return JNI_FALSE;
    g_smpl = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(g_smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_dist(1234));
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_example_matepet_LlamaBridge_nativeGenerate(JNIEnv* env, jobject, jstring jsys, jstring jusr, jint maxTok, jobject cb) {
    if (!g_ctx) return;
    const char* s = env->GetStringUTFChars(jsys, nullptr);
    const char* u = env->GetStringUTFChars(jusr, nullptr);
    llama_chat_message msgs[2] = {{"system", s}, {"user", u}};
    const char* tmpl = llama_model_chat_template(g_model, nullptr);
    std::vector<char> buf(4096);
    int n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int)buf.size());
    if (n > (int)buf.size()) { buf.resize(n); n = llama_chat_apply_template(tmpl, msgs, 2, true, buf.data(), (int)buf.size()); }
    env->ReleaseStringUTFChars(jsys, s); env->ReleaseStringUTFChars(jusr, u);
    if (n <= 0) return;
    std::string prompt(buf.data(), n);
    const llama_vocab* v = llama_model_get_vocab(g_model);
    int nt = -llama_tokenize(v, prompt.c_str(), (int)prompt.size(), nullptr, 0, true, true);
    if (nt <= 0 || nt + maxTok > N_CTX) return;
    std::vector<llama_token> toks(nt);
    llama_tokenize(v, prompt.c_str(), (int)prompt.size(), toks.data(), nt, true, true);
    llama_memory_clear(llama_get_memory(g_ctx), true);   // stateless turns keep RAM and latency low
    llama_sampler_reset(g_smpl);
    jmethodID m = env->GetMethodID(env->GetObjectClass(cb), "onToken", "([B)Z");
    llama_batch b = llama_batch_get_one(toks.data(), nt);
    llama_token t;
    for (int i = 0; i < maxTok; i++) {
        if (llama_decode(g_ctx, b)) break;
        t = llama_sampler_sample(g_smpl, g_ctx, -1);
        if (llama_vocab_is_eog(v, t)) break;
        char pc[128];
        int l = llama_token_to_piece(v, t, pc, sizeof(pc), 0, true);
        if (l < 0) break;
        jbyteArray arr = env->NewByteArray(l);
        env->SetByteArrayRegion(arr, 0, l, (const jbyte*)pc);
        jboolean cont = env->CallBooleanMethod(cb, m, arr);
        env->DeleteLocalRef(arr);
        if (!cont) break;
        b = llama_batch_get_one(&t, 1);
    }
}
