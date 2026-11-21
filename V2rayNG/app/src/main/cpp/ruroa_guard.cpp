#include <jni.h>
#include <string>
#include <fstream>
#include <sstream>
#include <vector>
#include <cstdlib>
#include <unistd.h>

namespace {
    constexpr uint32_t SECRET_SEED = 0x7A9E3C51;
    const char* ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    bool isDebuggerAttached() {
        std::ifstream status("/proc/self/status");
        std::string line;
        while (std::getline(status, line)) {
            if (line.rfind("TracerPid:", 0) == 0) {
                int pid = std::atoi(line.substr(10).c_str());
                if (pid != 0) return true;
                break;
            }
        }
        return false;
    }

    bool isHookingFrameworkDetected() {
        std::ifstream maps("/proc/self/maps");
        std::string line;
        while (std::getline(maps, line)) {
            if (line.find("frida") != std::string::npos ||
                line.find("xposed") != std::string::npos ||
                line.find("substrate") != std::string::npos ||
                line.find("gum-js-loop") != std::string::npos) {
                return true;
            }
        }
        return false;
    }

    std::string computeCheckSuffix(const std::string& base5) {
        uint32_t hash = SECRET_SEED;
        for (size_t i = 0; i < base5.size(); ++i) {
            hash ^= (static_cast<uint32_t>(base5[i]) + 0x9e3779b9 + (hash << 6) + (hash >> 2));
            hash = (hash * 16777619u) ^ (i * 31u);
        }
        std::string suffix;
        suffix += ALPHABET[(hash >> 0) & 31];
        suffix += ALPHABET[(hash >> 5) & 31];
        suffix += ALPHABET[(hash >> 10) & 31];
        return suffix;
    }

    std::string xorDecode(const std::vector<uint8_t>& enc, uint8_t key) {
        std::string out;
        out.reserve(enc.size());
        for (size_t i = 0; i < enc.size(); ++i) {
            out.push_back(static_cast<char>(enc[i] ^ (key + (i & 0x0F))));
        }
        return out;
    }
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_v2ray_ang_ui_main_RuroaNative_verifyEnvironment(JNIEnv* env, jobject /*thiz*/, jstring pkgName) {
    if (isDebuggerAttached() || isHookingFrameworkDetected()) {
        std::abort();
    }

    const char* rawPkg = env->GetStringUTFChars(pkgName, nullptr);
    std::string currentPkg(rawPkg ? rawPkg : "");
    env->ReleaseStringUTFChars(pkgName, rawPkg);

    if (currentPkg != "com.v2ray.ang") {
        std::abort();
    }
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_v2ray_ang_ui_main_RuroaNative_signReferralBase(JNIEnv* env, jobject /*thiz*/, jstring base5Str) {
    const char* raw = env->GetStringUTFChars(base5Str, nullptr);
    std::string base5(raw ? raw : "");
    env->ReleaseStringUTFChars(base5Str, raw);

    if (base5.size() != 5) {
        return env->NewStringUTF("");
    }
    std::string fullCode = base5 + computeCheckSuffix(base5);
    return env->NewStringUTF(fullCode.c_str());
}

JNIEXPORT jboolean JNICALL
Java_com_v2ray_ang_ui_main_RuroaNative_validateReferralCode(JNIEnv* env, jobject /*thiz*/, jstring codeStr, jstring myCodeStr) {
    if (isDebuggerAttached() || isHookingFrameworkDetected()) {
        return JNI_FALSE;
    }

    const char* rawCode = env->GetStringUTFChars(codeStr, nullptr);
    const char* rawMy = env->GetStringUTFChars(myCodeStr, nullptr);
    std::string code(rawCode ? rawCode : "");
    std::string myCode(rawMy ? rawMy : "");
    env->ReleaseStringUTFChars(codeStr, rawCode);
    env->ReleaseStringUTFChars(myCodeStr, rawMy);

    if (code.size() != 8 || code == myCode) {
        return JNI_FALSE;
    }

    std::string base5 = code.substr(0, 5);
    std::string expectedSuffix = computeCheckSuffix(base5);
    return (code.substr(5, 3) == expectedSuffix) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_v2ray_ang_ui_main_RuroaNative_getChannelUrl(JNIEnv* env, jobject /*thiz*/) {
    std::vector<uint8_t> enc = {
        0x3d, 0x22, 0x22, 0x28, 0x2a, 0x60, 0x74, 0x73,
        0x29, 0x70, 0x32, 0x35, 0x4e, 0x10, 0x16, 0x16,
        0x0a, 0x07
    };
    std::string url = xorDecode(enc, 0x55);
    return env->NewStringUTF(url.c_str());
}

}
