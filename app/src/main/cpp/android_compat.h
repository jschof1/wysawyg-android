/* Android 26 has posix_memalign, but aligned_alloc was only introduced in 28. */
#ifndef WYSAWYG_ANDROID_COMPAT_H
#define WYSAWYG_ANDROID_COMPAT_H
#include <stdlib.h>
#include <stdint.h>
static inline void *p2_android_aligned_alloc(size_t alignment, size_t size) {
    if (alignment < sizeof(void *) || (alignment & (alignment - 1)) || size % alignment) return NULL;
    void *p = NULL;
    return posix_memalign(&p, alignment, size) == 0 ? p : NULL;
}
#define aligned_alloc p2_android_aligned_alloc
#endif
