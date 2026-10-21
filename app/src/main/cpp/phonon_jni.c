#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <sys/mman.h>
#include <unistd.h>
#include <stdint.h>
#include <stdlib.h>
#include <stdio.h>
#include <string.h>
#include "phonon2.h"
#include "threadpool.h"

void p2_release_thread_scratch(void);
typedef struct { void *base; size_t size; const void *data; size_t length; } Mapping;
typedef struct { P2Engine *engine; ThreadPool *pool; Mapping weights; } Owner;

static void fail(JNIEnv *env, const char *message) {
    jclass cls = (*env)->FindClass(env, "java/lang/IllegalStateException");
    if (cls) (*env)->ThrowNew(env, cls, message); /* messages are ASCII, transcript is not */
}
static int map_asset(AAssetManager *assets, const char *name, size_t expected, Mapping *out) {
    AAsset *asset = AAssetManager_open(assets, name, AASSET_MODE_RANDOM);
    if (!asset) return -1;
    off64_t offset = 0, length = 0;
    int fd = AAsset_openFileDescriptor64(asset, &offset, &length);
    AAsset_close(asset);
    if (fd < 0) return -1; /* Assets MUST be stored, never extracted/copied. */
    if (length != (off64_t)expected || offset < 0) { close(fd); return -1; }
    long page = sysconf(_SC_PAGESIZE);
    if (page <= 0) { close(fd); return -1; }
    off64_t aligned = offset - offset % page;
    size_t delta = (size_t)(offset - aligned), size = delta + (size_t)length;
    void *base = mmap(NULL, size, PROT_READ, MAP_PRIVATE, fd, aligned);
    close(fd);
    if (base == MAP_FAILED) return -1;
    *out = (Mapping){base, size, (const char *)base + delta, (size_t)length};
    return 0;
}
static void unmap_asset(Mapping *m) { if (m->base) munmap(m->base, m->size); memset(m, 0, sizeof(*m)); }
static void close_owner(Owner *owner) {
    if (!owner) return;
    /* Decode is synchronous: all work is joined before this owner-thread cleanup. */
    p2_set_pool(NULL);
    p2_pool_destroy(owner->pool); /* joins workers and runs their TLS destructors */
    p2_release_thread_scratch();
    p2_engine_close(owner->engine);
    unmap_asset(&owner->weights); /* mapped weights must outlive the engine */
    free(owner);
}
JNIEXPORT jlong JNICALL Java_com_cragnet_wysawyg_PhononNative_open(JNIEnv *env, jobject self, jobject manager) {
    (void)self;
    Owner *owner = calloc(1, sizeof(*owner));
    Mapping json = {0}, tokens = {0};
    if (!owner) { fail(env, "Could not allocate speech engine"); return 0; }
    AAssetManager *assets = AAssetManager_fromJava(env, manager);
    if (!assets || map_asset(assets, "phonon/weights.bin", 255253016, &owner->weights) ||
        map_asset(assets, "phonon/weights.json", 93161, &json) ||
        map_asset(assets, "phonon/tokens.txt", 54086, &tokens)) goto error;
    setenv("P2_CACHE_CODES", "0", 1);
    setenv("P2_THREADS", "4", 1);
    p2_verbose = 0;
    owner->pool = p2_pool_create(4);
    if (!owner->pool) goto error;
    p2_set_pool(owner->pool); /* explicit TLS pool BEFORE engine_open_mem calls p2_pool */
    owner->engine = p2_engine_open_mem(owner->weights.data, owner->weights.length,
        json.data, json.length, tokens.data, tokens.length);
    unmap_asset(&json); unmap_asset(&tokens); /* parsers own their metadata */
    if (!owner->engine) goto error;
    return (jlong)(intptr_t)owner;
error:
    unmap_asset(&json); unmap_asset(&tokens); close_owner(owner);
    fail(env, "Could not load bundled speech model. Reinstall the complete ARM64 APK.");
    return 0;
}
JNIEXPORT jbyteArray JNICALL Java_com_cragnet_wysawyg_PhononNative_transcribe(JNIEnv *env, jobject self, jlong handle, jint fd) {
    (void)self;
    Owner *owner = (Owner *)(intptr_t)handle;
    if (!owner || !owner->engine || fd < 0) { fail(env, "Speech engine is closed"); return NULL; }
    char path[64];
    snprintf(path, sizeof(path), "/proc/self/fd/%d", fd);
    float *audio = NULL;
    int count = 0, rate = 0, channels = 0;
    if (p2_wav_read(path, &audio, &count, &rate, &channels) != 0) {
        fail(env, "Could not read recording WAV"); return NULL;
    }
    if (rate <= 0 || count < 0 || (int64_t)count > (int64_t)rate * 300) {
        free(audio); fail(env, "Recording must be at most 5 minutes"); return NULL;
    }
    P2Result result = {0};
    int rc = p2_transcribe(owner->engine, audio, count, rate, &result);
    free(audio);
    if (rc || !result.text) { p2_result_free(&result); fail(env, "On-device decoding failed"); return NULL; }
    size_t length = strlen(result.text);
    if (length > 1024 * 1024 - 4096) {
        p2_result_free(&result); fail(env, "Transcription result is too large"); return NULL;
    }
    /* UTF-8 bytes, NOT JNI's modified UTF-8 NewStringUTF (emoji/non-BMP safe). */
    jbyteArray bytes = (*env)->NewByteArray(env, (jsize)length);
    if (bytes) (*env)->SetByteArrayRegion(env, bytes, 0, (jsize)length, (const jbyte *)result.text);
    p2_result_free(&result);
    return bytes;
}
JNIEXPORT void JNICALL Java_com_cragnet_wysawyg_PhononNative_close(JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self;
    close_owner((Owner *)(intptr_t)handle);
}
