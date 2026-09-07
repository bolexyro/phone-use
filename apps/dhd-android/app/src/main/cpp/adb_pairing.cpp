// Adapted from RikkaApps/Shizuku's Android 11 ADB pairing client.
// The upstream project and this adaptation are Apache-2.0 licensed.

#include <jni.h>
#include <cstring>
#include <cstdlib>
#include <cinttypes>

#include <openssl/curve25519.h>
#include <openssl/hkdf.h>
#include <openssl/evp.h>

#include <android/log.h>

#define LOG_TAG "DhdAdbPairing"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

static constexpr spake2_role_t kClientRole = spake2_role_alice;

static const uint8_t kClientName[] = "adb pair client";
static const uint8_t kServerName[] = "adb pair server";
static constexpr size_t kHkdfKeyLength = 16;

struct PairingContextNative {
    SPAKE2_CTX *spake2_ctx;
    uint8_t message[SPAKE2_MAX_MSG_SIZE];
    size_t message_size;
    EVP_AEAD_CTX *aes_ctx;
    uint64_t decrypt_sequence;
    uint64_t encrypt_sequence;
};

static jlong PairingContext_Constructor(JNIEnv *env, jclass, jboolean is_client, jbyteArray password_array) {
    const uint8_t *my_name = is_client ? kClientName : kServerName;
    const uint8_t *their_name = is_client ? kServerName : kClientName;
    const size_t my_name_size = is_client ? sizeof(kClientName) : sizeof(kServerName);
    const size_t their_name_size = is_client ? sizeof(kServerName) : sizeof(kClientName);

    auto *spake2_ctx = SPAKE2_CTX_new(
        is_client ? spake2_role_alice : spake2_role_bob,
        my_name,
        my_name_size,
        their_name,
        their_name_size);
    if (spake2_ctx == nullptr) {
        LOGE("Unable to create a SPAKE2 context.");
        return 0;
    }

    const auto password_size = env->GetArrayLength(password_array);
    auto *password = env->GetByteArrayElements(password_array, nullptr);
    size_t message_size = 0;
    uint8_t message[SPAKE2_MAX_MSG_SIZE];
    const int status = SPAKE2_generate_msg(
        spake2_ctx,
        message,
        &message_size,
        SPAKE2_MAX_MSG_SIZE,
        reinterpret_cast<uint8_t *>(password),
        password_size);
    env->ReleaseByteArrayElements(password_array, password, JNI_ABORT);

    if (status != 1 || message_size == 0) {
        LOGE("Unable to generate the SPAKE2 public key.");
        SPAKE2_CTX_free(spake2_ctx);
        return 0;
    }

    auto *context = static_cast<PairingContextNative *>(malloc(sizeof(PairingContextNative)));
    if (context == nullptr) {
        SPAKE2_CTX_free(spake2_ctx);
        return 0;
    }
    memset(context, 0, sizeof(PairingContextNative));
    context->spake2_ctx = spake2_ctx;
    memcpy(context->message, message, message_size);
    context->message_size = message_size;
    return reinterpret_cast<jlong>(context);
}

static jbyteArray PairingContext_Message(JNIEnv *env, jobject, jlong pointer) {
    auto *context = reinterpret_cast<PairingContextNative *>(pointer);
    if (context == nullptr) return nullptr;
    auto result = env->NewByteArray(static_cast<jsize>(context->message_size));
    env->SetByteArrayRegion(
        result,
        0,
        static_cast<jsize>(context->message_size),
        reinterpret_cast<const jbyte *>(context->message));
    return result;
}

static jboolean PairingContext_InitCipher(JNIEnv *env, jobject, jlong pointer, jbyteArray their_message_array) {
    auto *context = reinterpret_cast<PairingContextNative *>(pointer);
    if (context == nullptr) return JNI_FALSE;

    const auto their_message_size = env->GetArrayLength(their_message_array);
    if (their_message_size > SPAKE2_MAX_MSG_SIZE) return JNI_FALSE;
    auto *their_message = env->GetByteArrayElements(their_message_array, nullptr);

    size_t key_material_size = 0;
    uint8_t key_material[SPAKE2_MAX_KEY_SIZE];
    const int status = SPAKE2_process_msg(
        context->spake2_ctx,
        key_material,
        &key_material_size,
        sizeof(key_material),
        reinterpret_cast<uint8_t *>(their_message),
        their_message_size);
    env->ReleaseByteArrayElements(their_message_array, their_message, JNI_ABORT);
    if (status != 1) return JNI_FALSE;

    uint8_t key[kHkdfKeyLength];
    const uint8_t info[] = "adb pairing_auth aes-128-gcm key";
    if (!HKDF(
        key,
        sizeof(key),
        EVP_sha256(),
        key_material,
        key_material_size,
        nullptr,
        0,
        info,
        sizeof(info) - 1)) {
        return JNI_FALSE;
    }

    context->aes_ctx = EVP_AEAD_CTX_new(
        EVP_aead_aes_128_gcm(),
        key,
        sizeof(key),
        EVP_AEAD_DEFAULT_TAG_LENGTH);
    return context->aes_ctx == nullptr ? JNI_FALSE : JNI_TRUE;
}

static jbyteArray PairingContext_Encrypt(JNIEnv *env, jobject, jlong pointer, jbyteArray input_array) {
    auto *context = reinterpret_cast<PairingContextNative *>(pointer);
    if (context == nullptr || context->aes_ctx == nullptr) return nullptr;

    const auto input_size = env->GetArrayLength(input_array);
    auto *input = env->GetByteArrayElements(input_array, nullptr);
    const size_t output_capacity = static_cast<size_t>(input_size) +
        EVP_AEAD_max_overhead(EVP_AEAD_CTX_aead(context->aes_ctx));
    auto *output = static_cast<uint8_t *>(malloc(output_capacity));
    if (output == nullptr) {
        env->ReleaseByteArrayElements(input_array, input, JNI_ABORT);
        return nullptr;
    }

    const size_t nonce_size = EVP_AEAD_nonce_length(EVP_AEAD_CTX_aead(context->aes_ctx));
    uint8_t nonce[EVP_AEAD_MAX_NONCE_LENGTH] = {};
    memcpy(nonce, &context->encrypt_sequence, sizeof(context->encrypt_sequence));
    size_t written = 0;
    const int status = EVP_AEAD_CTX_seal(
        context->aes_ctx,
        output,
        &written,
        output_capacity,
        nonce,
        nonce_size,
        reinterpret_cast<uint8_t *>(input),
        input_size,
        nullptr,
        0);
    env->ReleaseByteArrayElements(input_array, input, JNI_ABORT);
    if (!status) {
        free(output);
        return nullptr;
    }
    ++context->encrypt_sequence;

    auto result = env->NewByteArray(static_cast<jsize>(written));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(written), reinterpret_cast<const jbyte *>(output));
    free(output);
    return result;
}

static jbyteArray PairingContext_Decrypt(JNIEnv *env, jobject, jlong pointer, jbyteArray input_array) {
    auto *context = reinterpret_cast<PairingContextNative *>(pointer);
    if (context == nullptr || context->aes_ctx == nullptr) return nullptr;

    const auto input_size = env->GetArrayLength(input_array);
    auto *input = env->GetByteArrayElements(input_array, nullptr);
    auto *output = static_cast<uint8_t *>(malloc(static_cast<size_t>(input_size)));
    if (output == nullptr) {
        env->ReleaseByteArrayElements(input_array, input, JNI_ABORT);
        return nullptr;
    }

    const size_t nonce_size = EVP_AEAD_nonce_length(EVP_AEAD_CTX_aead(context->aes_ctx));
    uint8_t nonce[EVP_AEAD_MAX_NONCE_LENGTH] = {};
    memcpy(nonce, &context->decrypt_sequence, sizeof(context->decrypt_sequence));
    size_t written = 0;
    const int status = EVP_AEAD_CTX_open(
        context->aes_ctx,
        output,
        &written,
        static_cast<size_t>(input_size),
        nonce,
        nonce_size,
        reinterpret_cast<uint8_t *>(input),
        input_size,
        nullptr,
        0);
    env->ReleaseByteArrayElements(input_array, input, JNI_ABORT);
    if (!status) {
        free(output);
        return nullptr;
    }
    ++context->decrypt_sequence;

    auto result = env->NewByteArray(static_cast<jsize>(written));
    env->SetByteArrayRegion(result, 0, static_cast<jsize>(written), reinterpret_cast<const jbyte *>(output));
    free(output);
    return result;
}

static void PairingContext_Destroy(JNIEnv *, jobject, jlong pointer) {
    auto *context = reinterpret_cast<PairingContextNative *>(pointer);
    if (context == nullptr) return;
    SPAKE2_CTX_free(context->spake2_ctx);
    if (context->aes_ctx != nullptr) EVP_AEAD_CTX_free(context->aes_ctx);
    free(context);
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *) {
    LOGI("JNI_OnLoad entered");
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) return -1;

    const JNINativeMethod methods[] = {
        {"nativeConstructor", "(Z[B)J", reinterpret_cast<void *>(PairingContext_Constructor)},
        {"nativeMessage", "(J)[B", reinterpret_cast<void *>(PairingContext_Message)},
        {"nativeInitCipher", "(J[B)Z", reinterpret_cast<void *>(PairingContext_InitCipher)},
        {"nativeEncrypt", "(J[B)[B", reinterpret_cast<void *>(PairingContext_Encrypt)},
        {"nativeDecrypt", "(J[B)[B", reinterpret_cast<void *>(PairingContext_Decrypt)},
        {"nativeDestroy", "(J)V", reinterpret_cast<void *>(PairingContext_Destroy)},
    };
    const char *class_name =
        "com/phonecontrol/assistant/developer/DhdAdbPairingContext";
    jclass pairing_context = env->FindClass(class_name);
    if (pairing_context == nullptr) {
        LOGE("JNI_OnLoad could not find %s", class_name);
        if (env->ExceptionCheck()) env->ExceptionDescribe();
        return -1;
    }
    if (env->RegisterNatives(pairing_context, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        LOGE("JNI_OnLoad could not register native pairing methods");
        if (env->ExceptionCheck()) env->ExceptionDescribe();
        return -1;
    }
    LOGI("JNI_OnLoad registered native pairing methods");
    return JNI_VERSION_1_6;
}
