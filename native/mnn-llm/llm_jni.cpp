#include <jni.h>
#include <string>
#include <sstream>
#include <vector>
#include <utility>
#include <android/log.h>
#include "llm/llm.hpp"

using MNN::Transformer::Llm;
using ChatMessages = std::vector<std::pair<std::string, std::string>>;

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "LocalLlm", __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_octos_aichat_ai_local_LlmEngine_nativeLoad(
        JNIEnv* env, jobject, jstring jConfig, jstring jTmp, jint timeoutMs) {
    const char* cfg = env->GetStringUTFChars(jConfig, nullptr);
    std::string config(cfg ? cfg : "");
    env->ReleaseStringUTFChars(jConfig, cfg);
    Llm* llm = Llm::createLLM(config);
    if (!llm) { LOG("createLLM failed: %s", config.c_str()); return 0; }
    // Runtime config, set once at load (set_config re-merges config and rebuilds
    // the chat template, so it is not something to redo per call).
    // timeout_ms: the engine stops generating once prefill + decode time reaches it
    // (checked per token), so a stuck/slow turn can't hold the engine forever.
    std::string c = "{\"timeout_ms\":" + std::to_string((int)timeoutMs);
    if (jTmp) {
        const char* tmp = env->GetStringUTFChars(jTmp, nullptr);
        if (tmp && tmp[0]) c += std::string(",\"tmp_path\":\"") + tmp + "\"";
        env->ReleaseStringUTFChars(jTmp, tmp);
    }
    c += "}";
    llm->set_config(c);
    if (!llm->load()) { LOG("load failed"); delete llm; return 0; }
    LOG("model loaded");
    return reinterpret_cast<jlong>(llm);
}

JNIEXPORT jstring JNICALL
Java_com_octos_aichat_ai_local_LlmEngine_nativeChat(
        JNIEnv* env, jobject, jlong handle, jstring jSys, jstring jUser, jint maxTokens) {
    Llm* llm = reinterpret_cast<Llm*>(handle);
    if (!llm) return env->NewStringUTF("");
    const char* sys = env->GetStringUTFChars(jSys, nullptr);
    const char* usr = env->GetStringUTFChars(jUser, nullptr);
    ChatMessages msgs;
    if (sys && sys[0]) msgs.emplace_back(std::string("system"), std::string(sys));
    msgs.emplace_back(std::string("user"), std::string(usr ? usr : ""));
    std::ostringstream os;
    llm->reset();                     // stateless: each call is a fresh turn
    // A timed-out turn leaves the engine in TIMEOUT state, and response(ChatMessages)
    // refuses to run in that state; reset() doesn't clear it but generate_init() does.
    // Called non-virtually (Llm::) so it binds to the exported symbol, independent
    // of vtable layout differences between our headers and the shipped engine.
    llm->Llm::generate_init(nullptr, nullptr);
    llm->response(msgs, &os, nullptr, (int)maxTokens);
    if (sys) env->ReleaseStringUTFChars(jSys, sys);
    if (usr) env->ReleaseStringUTFChars(jUser, usr);
    std::string out = os.str();
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_octos_aichat_ai_local_LlmEngine_nativeRelease(
        JNIEnv* env, jobject, jlong handle) {
    Llm* llm = reinterpret_cast<Llm*>(handle);
    if (llm) delete llm;
}

} // extern "C"
