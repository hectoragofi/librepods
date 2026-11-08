// JNI bridge for local.librepods.spatialshizuku.Own3D
#include <jni.h>
#include <stdint.h>
#include <stdio.h>
#include <android/log.h>
#include "own3d.h"

#define FN(name) Java_local_librepods_spatialshizuku_Own3D_##name
#define H(h) ((Own3D *)(intptr_t)(h))

JNIEXPORT jlong JNICALL FN(nCreate)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return (jlong)(intptr_t)own3d_create(); }
JNIEXPORT void JNICALL FN(nDestroy)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; own3d_destroy(H(h)); }
JNIEXPORT jint JNICALL FN(nAddHrtf)(JNIEnv *env, jclass cls, jlong h, jstring name, jbyteArray data) {
  (void)cls;
  const char *nm = (*env)->GetStringUTFChars(env, name, NULL);
  jsize len = (*env)->GetArrayLength(env, data);
  jbyte *p = (*env)->GetByteArrayElements(env, data, NULL);
  int r = own3d_add_hrtf_bin(H(h), nm, p, len);
  (*env)->ReleaseByteArrayElements(env, data, p, JNI_ABORT);
  (*env)->ReleaseStringUTFChars(env, name, nm);
  return r;
}
JNIEXPORT void JNICALL FN(nCalibrate)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; own3d_calibrate(H(h)); }
JNIEXPORT void JNICALL FN(nRebuild)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; own3d_rebuild(H(h)); }
JNIEXPORT void JNICALL FN(nSetParam)(JNIEnv *env, jclass cls, jlong h, jint id, jfloat v) { (void)env; (void)cls; own3d_set_param(H(h), id, v); }
JNIEXPORT void JNICALL FN(nReset)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; own3d_reset(H(h)); }
JNIEXPORT jfloat JNICALL FN(nAgc)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; return own3d_agc_gain(H(h)); }
JNIEXPORT jboolean JNICALL FN(nIdle)(JNIEnv *env, jclass cls, jlong h) { (void)env; (void)cls; return own3d_idle(H(h)) ? JNI_TRUE : JNI_FALSE; }
JNIEXPORT jint JNICALL FN(nParamCount)(JNIEnv *env, jclass cls) { (void)env; (void)cls; return own3d_param_count(); }
/* "key|label|group|min|max|def|step|rebuild" */
JNIEXPORT jstring JNICALL FN(nParamInfo)(JNIEnv *env, jclass cls, jint id) {
  (void)cls;
  const Own3DParam *p = own3d_param_info(id);
  char buf[256];
  if (!p) buf[0] = 0;
  else snprintf(buf, sizeof buf, "%s|%s|%s|%g|%g|%g|%g|%d", p->key, p->label, p->group, p->min, p->max, p->def, p->step, p->rebuild);
  return (*env)->NewStringUTF(env, buf);
}
/* in/out: interleaved stereo shorts, `frames` frames each. */
JNIEXPORT void JNICALL FN(nProcess)(JNIEnv *env, jclass cls, jlong h, jshortArray in, jshortArray out, jint frames, jfloat gain) {
  (void)cls;
  jshort *pi = (jshort *)(*env)->GetPrimitiveArrayCritical(env, in, NULL);
  jshort *po = (jshort *)(*env)->GetPrimitiveArrayCritical(env, out, NULL);
  if (pi && po) own3d_process(H(h), (const int16_t *)pi, (int16_t *)po, frames, gain);
  if (po) (*env)->ReleasePrimitiveArrayCritical(env, out, po, 0);
  if (pi) (*env)->ReleasePrimitiveArrayCritical(env, in, pi, JNI_ABORT);
}
JNIEXPORT jstring JNICALL FN(nSelfTest)(JNIEnv *env, jclass cls, jlong h, jdouble seconds) {
  (void)cls;
  char rep[640];
  own3d_selftest(H(h), rep, sizeof rep, seconds);
  __android_log_print(ANDROID_LOG_INFO, "Own3D", "%s", rep);
  return (*env)->NewStringUTF(env, rep);
}
