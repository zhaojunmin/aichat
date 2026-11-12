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
Java_com_octos_aichat_data_local_LocalLlmEngine_nativeLoad(
        JNIEnv* env, jobject, jstring jConfig, jstring jTmp) {
    const char* cfg = env->GetStringUTFChars(jConfig, nullptr);
    std::string config(cfg ? cfg : "");
    env->ReleaseStringUTFChars(jConfig, cfg);
    Llm* llm = Llm::createLLM(config);
    if (!llm) { LOG("createLLM failed: %s", config.c_str()); return 0; }
    if (jTmp) {
        const char* tmp = env->GetStringUTFChars(jTmp, nullptr);
        if (tmp && tmp[0]) {
            std::string c = std::string("{\"tmp_path\":\"") + tmp + "\"}";
            llm->set_config(c);
        }
        env->ReleaseStringUTFChars(jTmp, tmp);
    }
    if (!llm->load()) { LOG("load failed"); delete llm; return 0; }
    LOG("model loaded");
    return reinterpret_cast<jlong>(llm);
}

JNIEXPORT jstring JNICALL
Java_com_octos_aichat_data_local_LocalLlmEngine_nativeChat(
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
    llm->response(msgs, &os, nullptr, (int)maxTokens);
    if (sys) env->ReleaseStringUTFChars(jSys, sys);
    if (usr) env->ReleaseStringUTFChars(jUser, usr);
    std::string out = os.str();
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL
Java_com_octos_aichat_data_local_LocalLlmEngine_nativeRelease(
        JNIEnv* env, jobject, jlong handle) {
    Llm* llm = reinterpret_cast<Llm*>(handle);
    if (llm) delete llm;
}

} // extern "C"
