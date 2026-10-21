#include <jni.h>
#include <sys/auxv.h>
#include <asm/hwcap.h>
JNIEXPORT jboolean JNICALL Java_com_cragnet_wysawyg_PhononNative_hasDotProduct(JNIEnv *env, jobject self) {
    (void)env; (void)self;
    return (getauxval(AT_HWCAP) & HWCAP_ASIMDDP) != 0;
}
