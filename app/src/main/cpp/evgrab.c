#include <errno.h>
#include <fcntl.h>
#include <jni.h>
#include <linux/input.h>
#include <sys/ioctl.h>
#include <unistd.h>

// Opens an evdev node and takes an exclusive grab on it, so no other reader
// (including Android's InputReader) receives its events. Returns the fd, or -errno.
JNIEXPORT jint JNICALL
Java_com_odiousapps_btremote_GrabService_openGrabbed(JNIEnv *env, jclass clazz, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    int fd = open(p, O_RDONLY | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (fd < 0) return -errno;
    if (ioctl(fd, EVIOCGRAB, 1) < 0) {
        int err = errno;
        close(fd);
        return -err;
    }
    return fd;
}

// Reads an evdev node's device name (sysfs names aren't readable by the shell user).
JNIEXPORT jstring JNICALL
Java_com_odiousapps_btremote_GrabService_deviceName(JNIEnv *env, jclass clazz, jstring path) {
    const char *p = (*env)->GetStringUTFChars(env, path, NULL);
    int fd = open(p, O_RDONLY | O_CLOEXEC);
    (*env)->ReleaseStringUTFChars(env, path, p);
    if (fd < 0) return NULL;
    char name[256] = {0};
    int len = ioctl(fd, EVIOCGNAME(sizeof(name) - 1), name);
    close(fd);
    return len < 0 ? NULL : (*env)->NewStringUTF(env, name);
}
