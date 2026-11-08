// OWN 3D: stereo -> 7 virtual speakers + untouched sub band -> binaural (measured HRTF + clean
// room), 48 kHz. Portable C (NDK clang, MSVC for the host test).
//
// Threads: one render thread calls own3d_process(). Any thread may call own3d_set_param().
// Parameters marked "rebuild" only take effect after own3d_rebuild(), which builds the new
// binaural filters into a spare bank; call it from a worker thread (it never blocks the audio
// thread). The render thread then crossfades to the new bank over one block (no clicks).
// Live parameters apply at the next block with a short ramp.
#pragma once
#include <stdint.h>
#ifdef __cplusplus
extern "C" {
#endif

#define OWN3D_BLOCK 480   /* internal block: 10 ms at 48 kHz */
#define OWN3D_LATENCY (2 * OWN3D_BLOCK + 96) /* frames: block FIFO + STFT hop + 2 ms limiter look-ahead (+ HRIR onset) */
#define OWN3D_MAX_HRTF 6
#define OWN3D_SPEAKERS 7  /* FL FR C SL SR RL RR */

typedef struct Own3D Own3D;

typedef struct {
  const char *key;    /* stable id for presets / JSON, e.g. "fl_az" */
  const char *label;  /* UI label */
  const char *group;  /* UI section */
  float min, max, def, step;
  int rebuild;        /* 1 = needs own3d_rebuild() */
} Own3DParam;

int own3d_param_count(void);
const Own3DParam *own3d_param_info(int id);
int own3d_param_find(const char *key);

Own3D *own3d_create(void);
void own3d_destroy(Own3D *o);
/* Adds an HRIR set (tools/gen_own3d_brir.py .bin image). Returns its index or -1. */
int own3d_add_hrtf_bin(Own3D *o, const char *name, const void *data, long len);
int own3d_hrtf_count(const Own3D *o);
void own3d_set_param(Own3D *o, int id, float value);
float own3d_get_param(const Own3D *o, int id);
/* Builds filters for the current parameters into the spare bank (worker thread). Waits while a
   previous build is still being crossfaded in (at most ~10 ms). */
void own3d_rebuild(Own3D *o);
/* Seeds the loudness match from a short synthetic render. Call once after adding HRTFs. */
void own3d_calibrate(Own3D *o);
/* Clears signal state (delay lines, overlap buffers); the loudness match re-locks fast. */
void own3d_reset(Own3D *o);
/* Interleaved stereo s16 in -> out, any frame count, fixed latency OWN3D_LATENCY. */
void own3d_process(Own3D *o, const int16_t *in, int16_t *out, int frames, float gain);
float own3d_agc_gain(const Own3D *o);
/* 1 while the renderer is gated off (silent input for ~1 s + reverb tail; output is zeros and
   nearly no CPU is used). The first non-silent block resumes it with a 5 ms fade-in. */
int own3d_idle(const Own3D *o);
long own3d_idle_blocks(const Own3D *o);
/* Renders `seconds` of a synthetic signal on this instance (then resets it); one-line report. */
double own3d_selftest(Own3D *o, char *report, int report_len, double seconds);
int64_t own3d_now_ns(void);

#ifdef __cplusplus
}
#endif
