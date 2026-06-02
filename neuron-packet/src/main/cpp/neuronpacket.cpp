#include <jni.h>
#include <string>
#include <android/log.h>
#include "packet/packet_io.h"

#define LOG_TAG "NeuronPacket"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
    neuron::packet::NeuronPacketIO packetIO;

    std::string jstringToString(JNIEnv* env, jstring str) {
        if (!str) return "";
        const char* chars = env->GetStringUTFChars(str, nullptr);
        std::string result(chars);
        env->ReleaseStringUTFChars(str, chars);
        return result;
    }

    jbyteArray vectorToJbyteArray(JNIEnv* env, const std::vector<uint8_t>& vec) {
        jbyteArray arr = env->NewByteArray(static_cast<jsize>(vec.size()));
        env->SetByteArrayRegion(arr, 0, static_cast<jsize>(vec.size()),
                                reinterpret_cast<const jbyte*>(vec.data()));
        return arr;
    }

    std::vector<uint8_t> jbyteArrayToVector(JNIEnv* env, jbyteArray arr) {
        if (!arr) return {};
        jsize len = env->GetArrayLength(arr);
        std::vector<uint8_t> vec(len);
        env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte*>(vec.data()));
        return vec;
    }

    jobjectArray stringVectorToJarray(JNIEnv* env, const std::vector<std::string>& vec) {
        jclass strClass = env->FindClass("java/lang/String");
        jobjectArray arr = env->NewObjectArray(static_cast<jsize>(vec.size()), strClass, nullptr);
        for (size_t i = 0; i < vec.size(); i++) {
            env->SetObjectArrayElement(arr, static_cast<jsize>(i), env->NewStringUTF(vec[i].c_str()));
        }
        return arr;
    }
}

extern "C" {

JNIEXPORT jstring JNICALL
Java_com_neuronpacket_NeuronPacketNative_getVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("1.0.0");
}

JNIEXPORT jobject JNICALL
Java_com_neuronpacket_NeuronPacketNative_exportPacket(
    JNIEnv* env,
    jobject,
    jstring outputPath,
    jstring name,
    jstring domain,
    jbyteArray payload,
    jstring adminPassword,
    jint loadingMode,
    jobjectArray userPasswords,
    jobjectArray userLabels
) {
    jclass resultClass = env->FindClass("com/neuronpacket/ExportResult");
    jmethodID constructor = env->GetMethodID(resultClass, "<init>", "(ZLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");

    try {
        neuron::packet::PacketMetadata metadata;
        metadata.name = jstringToString(env, name);
        metadata.domain = jstringToString(env, domain);
        metadata.loadingMode = static_cast<neuron::packet::LoadingMode>(loadingMode);

        neuron::packet::ExportConfig config;
        config.adminPassword = jstringToString(env, adminPassword);
        config.loadingMode = metadata.loadingMode;

        if (userPasswords && userLabels) {
            jsize count = env->GetArrayLength(userPasswords);
            for (jsize i = 0; i < count; i++) {
                auto pwd = (jstring)env->GetObjectArrayElement(userPasswords, i);
                auto lbl = (jstring)env->GetObjectArrayElement(userLabels, i);

                neuron::packet::UserCredentials user;
                user.password = jstringToString(env, pwd);
                user.label = jstringToString(env, lbl);
                user.permissions = neuron::packet::PERMISSION_READ;
                config.readOnlyUsers.push_back(user);
            }
        }

        auto payloadVec = jbyteArrayToVector(env, payload);
        auto result = packetIO.exportPacket(jstringToString(env, outputPath), metadata, payloadVec, config);

        return env->NewObject(resultClass, constructor,
            result.success,
            env->NewStringUTF(result.packetId.c_str()),
            env->NewStringUTF(result.recoveryKey.c_str()),
            env->NewStringUTF(result.errorMessage.c_str())
        );
    } catch (const std::exception& e) {
        LOGE("Export failed: %s", e.what());
        return env->NewObject(resultClass, constructor,
            false,
            env->NewStringUTF(""),
            env->NewStringUTF(""),
            env->NewStringUTF(e.what())
        );
    }
}

JNIEXPORT jobject JNICALL
Java_com_neuronpacket_NeuronPacketNative_openPacket(JNIEnv* env, jobject, jstring packetPath) {
    jclass resultClass = env->FindClass("com/neuronpacket/ImportResult");
    jmethodID constructor = env->GetMethodID(resultClass, "<init>", "(ZLjava/lang/String;ILjava/lang/String;)V");

    try {
        auto result = packetIO.openPacket(jstringToString(env, packetPath));

        return env->NewObject(resultClass, constructor,
            result.success,
            env->NewStringUTF(result.packetId.c_str()),
            static_cast<jint>(result.metadata.loadingMode),
            env->NewStringUTF(result.errorMessage.c_str())
        );
    } catch (const std::exception& e) {
        LOGE("Open failed: %s", e.what());
        return env->NewObject(resultClass, constructor,
            false,
            env->NewStringUTF(""),
            0,
            env->NewStringUTF(e.what())
        );
    }
}

JNIEXPORT jobject JNICALL
Java_com_neuronpacket_NeuronPacketNative_authenticate(JNIEnv* env, jobject, jstring password) {
    jclass resultClass = env->FindClass("com/neuronpacket/AuthResult");
    jmethodID constructor = env->GetMethodID(resultClass, "<init>", "(ZII[BLjava/lang/String;)V");

    try {
        auto result = packetIO.authenticate(jstringToString(env, password));

        return env->NewObject(resultClass, constructor,
            result.success,
            static_cast<jint>(result.slotId),
            static_cast<jint>(result.permissions),
            vectorToJbyteArray(env, result.decryptedDek),
            env->NewStringUTF(result.errorMessage.c_str())
        );
    } catch (const std::exception& e) {
        LOGE("Auth failed: %s", e.what());
        return env->NewObject(resultClass, constructor,
            false, 0, 0, nullptr,
            env->NewStringUTF(e.what())
        );
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_neuronpacket_NeuronPacketNative_decryptPayload(JNIEnv* env, jobject, jbyteArray dek) {
    try {
        auto dekVec = jbyteArrayToVector(env, dek);
        auto payload = packetIO.decryptPayload(dekVec);
        return vectorToJbyteArray(env, payload);
    } catch (const std::exception& e) {
        LOGE("Decrypt failed: %s", e.what());
        return nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_neuronpacket_NeuronPacketNative_closePacket(JNIEnv*, jobject) {
    packetIO.closePacket();
}

JNIEXPORT jboolean JNICALL
Java_com_neuronpacket_NeuronPacketNative_isOpen(JNIEnv*, jobject) {
    return packetIO.isOpen();
}

JNIEXPORT jboolean JNICALL
Java_com_neuronpacket_NeuronPacketNative_addUser(
    JNIEnv* env,
    jobject,
    jstring password,
    jstring label,
    jint permissions,
    jstring adminPassword
) {
    try {
        neuron::packet::UserCredentials user;
        user.password = jstringToString(env, password);
        user.label = jstringToString(env, label);
        user.permissions = static_cast<uint8_t>(permissions);

        return packetIO.addUser(user, jstringToString(env, adminPassword));
    } catch (const std::exception& e) {
        LOGE("Add user failed: %s", e.what());
        return false;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_neuronpacket_NeuronPacketNative_removeUser(JNIEnv* env, jobject, jint slotId, jstring adminPassword) {
    try {
        return packetIO.removeUser(static_cast<uint8_t>(slotId), jstringToString(env, adminPassword));
    } catch (const std::exception& e) {
        LOGE("Remove user failed: %s", e.what());
        return false;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_neuronpacket_NeuronPacketNative_changePassword(
    JNIEnv* env,
    jobject,
    jint slotId,
    jstring oldPassword,
    jstring newPassword
) {
    try {
        return packetIO.changePassword(
            static_cast<uint8_t>(slotId),
            jstringToString(env, oldPassword),
            jstringToString(env, newPassword)
        );
    } catch (const std::exception& e) {
        LOGE("Change password failed: %s", e.what());
        return false;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_neuronpacket_NeuronPacketNative_resetAdminPassword(
    JNIEnv* env,
    jobject,
    jstring recoveryKey,
    jstring newPassword
) {
    try {
        return packetIO.resetAdminPassword(
            jstringToString(env, recoveryKey),
            jstringToString(env, newPassword)
        );
    } catch (const std::exception& e) {
        LOGE("Reset admin password failed: %s", e.what());
        return false;
    }
}

JNIEXPORT jint JNICALL
Java_com_neuronpacket_NeuronPacketNative_getUserCount(JNIEnv*, jobject) {
    return static_cast<jint>(packetIO.header().userCount);
}

JNIEXPORT jint JNICALL
Java_com_neuronpacket_NeuronPacketNative_getLoadingMode(JNIEnv*, jobject) {
    return static_cast<jint>(packetIO.header().loadingMode);
}

JNIEXPORT jstring JNICALL
Java_com_neuronpacket_NeuronPacketNative_getPacketId(JNIEnv* env, jobject) {
    return env->NewStringUTF(neuron::packet::uuidToString(packetIO.header().packetId).c_str());
}

JNIEXPORT jstring JNICALL
Java_com_neuronpacket_NeuronPacketNative_getMetadataJson(JNIEnv* env, jobject) {
    return env->NewStringUTF(packetIO.header().metadataJson.c_str());
}

}