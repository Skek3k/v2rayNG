#include <jni.h>
#include <string>
#include <vector>
#include <fstream>
#include <unistd.h>

static std::string xorDecode(const std::vector<unsigned char>& enc, unsigned char key) {
    std::string out;
    out.reserve(enc.size());
    for (unsigned char b : enc) {
        out.push_back(static_cast<char>(b ^ key));
    }
    return out;
}

static bool isDebuggerAttached() {
    std::ifstream statusFile("/proc/self/status");
    std::string line;
    while (std::getline(statusFile, line)) {
        if (line.rfind("TracerPid:", 0) == 0) {
            int pid = std::stoi(line.substr(10));
            if (pid > 0) return true;
            break;
        }
    }
    return false;
}

static bool hasInjectedFrameworks() {
    std::ifstream mapsFile("/proc/self/maps");
    std::string line;
    while (std::getline(mapsFile, line)) {
        if (line.find("frida") != std::string::npos ||
            line.find("xposed") != std::string::npos ||
            line.find("substrate") != std::string::npos ||
            line.find("lsposed") != std::string::npos) {
            return true;
        }
    }
    return false;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_v2ray_ang_ui_main_RuroaNative_verifyAppIntegrity(
        JNIEnv* env,
        jobject /* this */,
        jobject context) {

    if (context == nullptr) return JNI_FALSE;

    jclass contextClass = env->GetObjectClass(context);
    jmethodID getPkgMethod = env->GetMethodID(contextClass, "getPackageName", "()Ljava/lang/String;");
    auto pkgNameObj = (jstring) env->CallObjectMethod(context, getPkgMethod);

    if (pkgNameObj == nullptr) return JNI_FALSE;

    const char* pkgChars = env->GetStringUTFChars(pkgNameObj, nullptr);
    std::string currentPkg(pkgChars);
    env->ReleaseStringUTFChars(pkgNameObj, pkgChars);

    // "org.ruroa.net" ^ 0x5A
    std::vector<unsigned char> encPkg = {
        0x35, 0x28, 0x3D, 0x74, 0x28, 0x2F, 0x28, 0x35, 0x3B, 0x74, 0x34, 0x3F, 0x2E
    };
    if (currentPkg != xorDecode(encPkg, 0x5A)) {
        return JNI_FALSE;
    }

    if (isDebuggerAttached() || hasInjectedFrameworks()) {
        return JNI_FALSE;
    }

    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_v2ray_ang_ui_main_RuroaNative_getPrimarySubUrl(
        JNIEnv* env,
        jobject /* this */) {
    // "https://githubusercontent.com" ^ 0x5A
    std::vector<unsigned char> enc = {
        0x32, 0x2E, 0x2E, 0x2A, 0x29, 0x60, 0x75, 0x75, 0x28, 0x3B, 0x2D, 0x74,
        0x3D, 0x33, 0x2E, 0x32, 0x2F, 0x38, 0x2F, 0x29, 0x3F, 0x28, 0x39, 0x35,
        0x34, 0x2E, 0x3F, 0x34, 0x2E, 0x74, 0x39, 0x35, 0x37, 0x75, 0x09, 0x31,
        0x3F, 0x31, 0x69, 0x31, 0x75, 0x28, 0x2F, 0x28, 0x35, 0x3B, 0x77, 0x39,
        0x35, 0x28, 0x3F, 0x75, 0x37, 0x3B, 0x33, 0x34, 0x75, 0x29, 0x2F, 0x38,
        0x74, 0x2E, 0x22, 0x2E
    };
    return env->NewStringUTF(xorDecode(enc, 0x5A).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_v2ray_ang_ui_main_RuroaNative_getCdnSubUrl(
        JNIEnv* env,
        jobject /* this */) {
    // "https://jsdelivr.net" ^ 0x5A
    std::vector<unsigned char> enc = {
        0x32, 0x2E, 0x2E, 0x2A, 0x29, 0x60, 0x75, 0x75, 0x39, 0x3E, 0x34, 0x74,
        0x30, 0x29, 0x3E, 0x3F, 0x36, 0x33, 0x2C, 0x28, 0x74, 0x34, 0x3F, 0x2E,
        0x75, 0x3D, 0x32, 0x75, 0x09, 0x31, 0x3F, 0x31, 0x69, 0x31, 0x75, 0x28,
        0x2F, 0x28, 0x35, 0x3B, 0x77, 0x39, 0x35, 0x28, 0x3F, 0x1A, 0x37, 0x3B,
        0x33, 0x34, 0x75, 0x29, 0x2F, 0x38, 0x74, 0x2E, 0x22, 0x2E
    };
    return env->NewStringUTF(xorDecode(enc, 0x5A).c_str());
}
