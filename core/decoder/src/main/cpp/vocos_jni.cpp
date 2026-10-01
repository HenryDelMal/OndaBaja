#include <jni.h>
#include <android/log.h>
#include "vocos.h"
#include <algorithm>
#include <chrono>
#include <cmath>
#include <memory>
#include <stdexcept>

namespace {
void fail(JNIEnv* env, const std::exception& error) {
    if (auto type=env->FindClass("java/lang/IllegalStateException"))
        env->ThrowNew(type,error.what());
}
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_henry_encodec_decoder_VocosDecoder_nativeCreate(JNIEnv* env,jobject,jstring path) {
    try {
        const char* chars=env->GetStringUTFChars(path,nullptr);
        if(!chars) return 0;
        std::string filename(chars);
        env->ReleaseStringUTFChars(path,chars);
        auto model=std::make_unique<vocos::codec>(filename);
        auto info=model->info();
        if(info.sample_rate!=24000 || info.channels!=1 || info.codebooks!=16)
            throw std::runtime_error("Expected official Vocos EnCodec-24k model");
        return reinterpret_cast<jlong>(model.release());
    } catch(const std::exception& error) { fail(env,error); return 0; }
}
extern "C" JNIEXPORT jfloatArray JNICALL
Java_com_henry_encodec_decoder_VocosDecoder_nativeDecode(JNIEnv* env,jobject,jlong handle,
    jintArray array,jint books,jint steps,jint trim,jint length,jboolean rescale,jboolean diagnostics) {
    try {
        if(!handle || steps<1 || steps>1024 || trim<0 || length<1 ||
            (books!=2 && books!=4 && books!=8 && books!=16) ||
            env->GetArrayLength(array)!=int64_t(steps)*books || int64_t(trim)+length>int64_t(steps)*320)
            throw std::runtime_error("Invalid Vocos token dimensions or unsupported bandwidth");
        std::vector<jint> values(size_t(steps)*books);
        env->GetIntArrayRegion(array,0,jsize(values.size()),values.data());
        if(env->ExceptionCheck()) return nullptr;
        vocos::tokens tokens{size_t(steps),uint32_t(books),{}};
        tokens.indices.reserve(values.size());
        for(auto v:values) {
            if(v<0 || v>1023) throw std::runtime_error("Invalid Vocos token index");
            tokens.indices.push_back(uint16_t(v));
        }
        auto started=std::chrono::steady_clock::now();
        auto pcm=reinterpret_cast<vocos::codec*>(handle)->decode(tokens);
        float peak=0;
        for(int i=trim;i<trim+length;++i) {
            if(!std::isfinite(pcm[i])) throw std::runtime_error("Nonfinite Vocos PCM");
            peak=std::max(peak,std::abs(pcm[i]));
        }
        float gain=rescale==JNI_TRUE && peak>0.99f ? 0.99f/peak : 1.0f;
        if (gain != 1.0f) for(int i=trim;i<trim+length;++i) pcm[i]*=gain;
        auto result=env->NewFloatArray(length);
        if(!result) return nullptr;
        env->SetFloatArrayRegion(result,0,length,pcm.data()+trim);
        if(diagnostics) {
            auto ms=std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now()-started).count();
            __android_log_print(ANDROID_LOG_INFO,"EnCodecDecoder",
                "Vocos decoded %d steps (%d codebooks) in %lldms, peak=%.4f gain=%.4f threads=1",
                steps,books,static_cast<long long>(ms),peak,gain);
        }
        return result;
    } catch(const std::exception& error) { fail(env,error); return nullptr; }
}
extern "C" JNIEXPORT void JNICALL
Java_com_henry_encodec_decoder_VocosDecoder_nativeDestroy(JNIEnv*,jobject,jlong handle) {
    delete reinterpret_cast<vocos::codec*>(handle);
}
