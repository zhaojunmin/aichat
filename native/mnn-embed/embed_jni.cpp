#include <jni.h>
#include <string>
#include <vector>
#include <memory>
#include <cmath>
#include <cstring>
#include <android/log.h>
#include <MNN/Interpreter.hpp>
#include <MNN/Tensor.hpp>
#include <MNN/MNNForwardType.h>

using namespace MNN;

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "EmbedJni", __VA_ARGS__)

// A loaded bge encoder: the MNN interpreter + a CPU session kept resident.
struct EmbedCtx {
    Interpreter* net;
    Session* sess;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_octos_aichat_data_memory_EmbeddingEngine_nativeEmbedLoad(
        JNIEnv* env, jobject, jstring jPath) {
    const char* p = env->GetStringUTFChars(jPath, nullptr);
    std::string path(p ? p : "");
    env->ReleaseStringUTFChars(jPath, p);

    Interpreter* net = Interpreter::createFromFile(path.c_str());
    if (!net) { LOG("createFromFile failed: %s", path.c_str()); return 0; }

    ScheduleConfig cfg;
    cfg.type = MNN_FORWARD_CPU;
    cfg.numThread = 4;
    Session* s = net->createSession(cfg);
    if (!s) { LOG("createSession failed"); Interpreter::destroy(net); return 0; }

    LOG("embed model loaded: %s", path.c_str());
    return reinterpret_cast<jlong>(new EmbedCtx{net, s});
}

// Runs the encoder for one sentence; returns the 512-d CLS vector, L2-normalised.
JNIEXPORT jfloatArray JNICALL
Java_com_octos_aichat_data_memory_EmbeddingEngine_nativeEmbed(
        JNIEnv* env, jobject, jlong handle, jintArray jIds, jintArray jMask) {
    auto* c = reinterpret_cast<EmbedCtx*>(handle);
    if (!c) return nullptr;

    const jsize L = env->GetArrayLength(jIds);
    if (L <= 0) return nullptr;
    std::vector<int> ids(L), mask(L);
    env->GetIntArrayRegion(jIds, 0, L, ids.data());
    env->GetIntArrayRegion(jMask, 0, L, mask.data());

    Tensor* inIds = c->net->getSessionInput(c->sess, "input_ids");
    Tensor* inMask = c->net->getSessionInput(c->sess, "attention_mask");
    if (!inIds || !inMask) { LOG("missing input tensor"); return nullptr; }

    c->net->resizeTensor(inIds, {1, (int)L});
    c->net->resizeTensor(inMask, {1, (int)L});
    c->net->resizeSession(c->sess);

    // Feed int32 ids/mask through host tensors (model inputs are int32 after conversion).
    std::shared_ptr<Tensor> hIds(new Tensor(inIds, Tensor::CAFFE));
    std::shared_ptr<Tensor> hMask(new Tensor(inMask, Tensor::CAFFE));
    ::memcpy(hIds->host<int>(), ids.data(), (size_t)L * sizeof(int));
    ::memcpy(hMask->host<int>(), mask.data(), (size_t)L * sizeof(int));
    inIds->copyFromHostTensor(hIds.get());
    inMask->copyFromHostTensor(hMask.get());

    c->net->runSession(c->sess);

    Tensor* out = c->net->getSessionOutput(c->sess, "last_hidden_state");
    if (!out) { LOG("missing output tensor"); return nullptr; }
    std::shared_ptr<Tensor> hOut(new Tensor(out, Tensor::CAFFE));
    out->copyToHostTensor(hOut.get());

    // last_hidden_state is [1, L, D]; sentence vector = CLS (token 0) + L2 norm.
    const auto dims = hOut->shape();
    const int D = (dims.size() >= 3) ? dims[2] : (int)(hOut->elementSize() / L);
    const float* od = hOut->host<float>();

    std::vector<float> v(D);
    double norm = 0.0;
    for (int i = 0; i < D; i++) { v[i] = od[i]; norm += (double)od[i] * od[i]; }
    const float inv = norm > 0.0 ? (float)(1.0 / std::sqrt(norm)) : 1.0f;
    for (int i = 0; i < D; i++) v[i] *= inv;

    jfloatArray res = env->NewFloatArray(D);
    env->SetFloatArrayRegion(res, 0, D, v.data());
    return res;
}

JNIEXPORT void JNICALL
Java_com_octos_aichat_data_memory_EmbeddingEngine_nativeEmbedRelease(
        JNIEnv* env, jobject, jlong handle) {
    auto* c = reinterpret_cast<EmbedCtx*>(handle);
    if (c) { Interpreter::destroy(c->net); delete c; }
}

} // extern "C"
