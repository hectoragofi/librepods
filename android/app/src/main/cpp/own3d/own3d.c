// OWN 3D binaural renderer. See own3d.h and tools/gen_own3d_brir.py.
//
// Per 480-frame block (10 ms):
//  0. LR4 crossover (default 150 Hz). The low band is never processed: it is delayed to line
//     up with the rendered band and added back L to the left ear, R to the right (phase-coherent
//     sub, kick and 808), with its own level.
//  1. STFT upmix of the high band (960-pt, 50 % overlap, sqrt-Hann, perfect reconstruction):
//     time+frequency smoothed cross-spectra give coherence, balance and pan. Coherent centre-
//     panned energy partly goes to C; incoherent, balanced energy (ambience) to sides and rears;
//     clearly panned sources partly move from the fronts to the sides. Real, energy-preserving,
//     temporally smoothed masks.
//  2. 7 virtual speakers (position, distance, level, mute/solo, HRTF or direct) each convolved
//     with its binaural room filter = HRIR + sparse image-source early reflections, uniformly
//     partitioned overlap-save (pffft, 5 x 480 taps). Two filter banks: a worker thread builds
//     the spare one, the render thread crossfades over one block.
//  3. 8-line FDN late reverb at 24 kHz (2:1, its input is 320 Hz-6 kHz band-limited), pre-delay,
//     low level. Early reflections can get their own low cut (er_lowcut_hz).
//  4. Original-stereo blend, adaptive width (keeps IACC below plain stereo), tone (shelves +
//     5-band EQ), slow K-weighted loudness match, 2 ms look-ahead limiter at -1 dBFS.
//  5. Silence gating: ~1 s of silent input (plus 1.5 x RT60 of tail) and the renderer stops
//     (zeros out, almost no CPU); the first non-silent block resumes it with a 5 ms fade-in.
// Cost: silent filter partitions and muted speakers are skipped, the limiter's window minimum is
// a monotonic deque, 0 dB tone sections are bypassed, upmix masks are branch-free (NEON).
#include "own3d.h"
#include "pffft/pffft.h"
#include <math.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#ifdef _WIN32
#include <windows.h>
#define BARRIER() MemoryBarrier()
#define SLEEP_MS(x) Sleep(x)
#else
#include <time.h>
#include <unistd.h>
#define BARRIER() __atomic_thread_fence(__ATOMIC_SEQ_CST)
#define SLEEP_MS(x) usleep((x) * 1000)
#endif

#define B OWN3D_BLOCK
#define N (2 * B)
#define NS OWN3D_SPEAKERS
#define P 5
#define FL_LEN (P * B)
#define NL 8
#define FDN_MAX 4096
#define FS 48000.0f
#define PI_F 3.14159265358979f
#define LIM_THR 0.89f       /* -1 dBFS */
#define LA 96               /* limiter look-ahead, 2 ms */
#define LA_RING 128
#define LOW_RING 4096

enum { S_FL, S_FR, S_C, S_SL, S_SR, S_BL, S_BR };
#ifdef OWN3D_PROFILE
double own3d_prof[8];
static int64_t prof_t;
int64_t own3d_now_ns(void);
#define PT(i) do { int64_t t_ = own3d_now_ns(); if ((i) >= 0) own3d_prof[(i)] += (double)(t_ - prof_t); prof_t = t_; } while (0)
#else
#define PT(i) do { } while (0)
#endif
static const char *SPK_KEY[NS] = {"fl", "fr", "c", "sl", "sr", "rl", "rr"};
static const char *SPK_NAME[NS] = {"FL", "FR", "C", "SL", "SR", "RL", "RR"};
static const float SPK_AZ[NS] = {55, -55, 0, 95, -95, 140, -140};
static const float SPK_LEVEL[NS] = {0, 0, -1, 0, 0, -1, -1};
static const float SPK_DELAY_MS[NS] = {0, 0, 0, 4.0f, 5.5f, 9.0f, 11.5f};   /* decorrelation */

/* ---------------- parameters ---------------- */
enum { K_AZ, K_EL, K_DIST, K_LEVEL, K_MUTE, K_SOLO, K_DIRECT, K_N };
#define SP(s, k) ((s) * K_N + (k))
enum {
  P_SUB_LEVEL = NS * K_N, P_SUB_MUTE, P_XOVER,
  P_CENTER, P_AMBIENCE, P_SIDEPAN, P_DECORR,
  P_LS_F, P_LS_DB, P_HS_F, P_HS_DB, P_EQ1, P_EQ2, P_EQ3, P_EQ4, P_EQ5,
  P_ROOM_SIZE, P_ER_ORDER, P_ER_DB, P_LATE_DB, P_RT60, P_PREDELAY, P_DAMPING,
  P_BLEND, P_WIDTH, P_LOUD, P_OUT_DB, P_HRTF,
  P_ER_LOWCUT,   /* appended: ids of older presets stay valid */
  NPARAM
};
static Own3DParam ptab[NPARAM];
static char pkeys[NPARAM][24], plabels[NPARAM][40];
static const float EQ_F[5] = {60, 250, 1000, 4000, 12000};

static void defp(int id, const char *key, const char *label, const char *group, float mn, float mx, float def, float step, int rb) {
  snprintf(pkeys[id], sizeof pkeys[id], "%s", key);
  snprintf(plabels[id], sizeof plabels[id], "%s", label);
  ptab[id].key = pkeys[id]; ptab[id].label = plabels[id]; ptab[id].group = group;
  ptab[id].min = mn; ptab[id].max = mx; ptab[id].def = def; ptab[id].step = step; ptab[id].rebuild = rb;
}
static void params_init(void) {
  static int done;
  if (done) return;
  char k[24], l[40];
  for (int s = 0; s < NS; s++) {
    const char *g = "Speakers";
    snprintf(k, sizeof k, "%s_az", SPK_KEY[s]); snprintf(l, sizeof l, "%s azimuth (deg, +left)", SPK_NAME[s]);
    defp(SP(s, K_AZ), k, l, g, -180, 180, SPK_AZ[s], 1, 1);
    snprintf(k, sizeof k, "%s_el", SPK_KEY[s]); snprintf(l, sizeof l, "%s elevation (deg)", SPK_NAME[s]);
    defp(SP(s, K_EL), k, l, g, -40, 60, 0, 1, 1);
    snprintf(k, sizeof k, "%s_dist", SPK_KEY[s]); snprintf(l, sizeof l, "%s distance (m)", SPK_NAME[s]);
    defp(SP(s, K_DIST), k, l, g, 0.5f, 5, 2.0f, 0.1f, 1);
    snprintf(k, sizeof k, "%s_db", SPK_KEY[s]); snprintf(l, sizeof l, "%s level (dB)", SPK_NAME[s]);
    defp(SP(s, K_LEVEL), k, l, g, -24, 6, SPK_LEVEL[s], 0.5f, 0);
    snprintf(k, sizeof k, "%s_mute", SPK_KEY[s]); snprintf(l, sizeof l, "%s mute", SPK_NAME[s]);
    defp(SP(s, K_MUTE), k, l, g, 0, 1, 0, 1, 0);
    snprintf(k, sizeof k, "%s_solo", SPK_KEY[s]); snprintf(l, sizeof l, "%s solo", SPK_NAME[s]);
    defp(SP(s, K_SOLO), k, l, g, 0, 1, 0, 1, 0);
    snprintf(k, sizeof k, "%s_direct", SPK_KEY[s]); snprintf(l, sizeof l, "%s direct (no HRTF)", SPK_NAME[s]);
    defp(SP(s, K_DIRECT), k, l, g, 0, 1, 0, 1, 1);
  }
  defp(P_SUB_LEVEL, "sub_db", "Sub band level (dB)", "Speakers", -24, 9, 0, 0.5f, 0);
  defp(P_SUB_MUTE, "sub_mute", "Sub band mute", "Speakers", 0, 1, 0, 1, 0);
  defp(P_XOVER, "xover_hz", "Sub crossover (Hz)", "Upmix", 60, 300, 150, 5, 0);
  defp(P_CENTER, "center", "Centre extraction", "Upmix", 0, 1, 0.8f, 0.05f, 0);
  defp(P_AMBIENCE, "ambience", "Ambience to surrounds", "Upmix", 0, 1.3f, 1.0f, 0.05f, 0);
  defp(P_SIDEPAN, "sidepan", "Hard pans to sides", "Upmix", 0, 1, 0.8f, 0.05f, 0);
  defp(P_DECORR, "decorr", "Surround decorrelation", "Upmix", 0, 2, 1.0f, 0.1f, 1);
  defp(P_LS_F, "ls_hz", "Low shelf freq (Hz)", "Tone", 30, 250, 60, 5, 0);
  defp(P_LS_DB, "ls_db", "Low shelf (dB)", "Tone", -6, 9, 3, 0.5f, 0);
  defp(P_HS_F, "hs_hz", "High shelf freq (Hz)", "Tone", 3000, 16000, 8500, 250, 0);
  defp(P_HS_DB, "hs_db", "High shelf (dB)", "Tone", -6, 9, 3, 0.5f, 0);
  for (int i = 0; i < 5; i++) {
    snprintf(k, sizeof k, "eq%d_db", i + 1);
    snprintf(l, sizeof l, "EQ %g %s (dB)", EQ_F[i] >= 1000 ? EQ_F[i] / 1000 : EQ_F[i], EQ_F[i] >= 1000 ? "kHz" : "Hz");
    defp(P_EQ1 + i, k, l, "Tone", -9, 9, 0, 0.5f, 0);
  }
  defp(P_ROOM_SIZE, "room_size", "Room size (x)", "Room", 0.6f, 2.5f, 1.0f, 0.05f, 1);
  defp(P_ER_ORDER, "er_order", "Reflection order", "Room", 0, 2, 1, 1, 1);
  defp(P_ER_DB, "er_db", "Early reflections (dB)", "Room", -30, 6, -2, 0.5f, 1);
  defp(P_LATE_DB, "late_db", "Late reverb (dB re direct)", "Room", -40, -6, -22, 0.5f, 1);
  defp(P_RT60, "rt60", "Reverb time RT60 (s)", "Room", 0.15f, 1.2f, 0.28f, 0.01f, 1);
  defp(P_PREDELAY, "predelay_ms", "Reflection pre-delay (ms)", "Room", 0, 25, 6, 0.5f, 1);
  defp(P_DAMPING, "damping", "HF damping", "Room", 0, 1, 0.5f, 0.05f, 1);
  defp(P_BLEND, "blend", "Original stereo blend", "Global", 0, 1, 0.4f, 0.05f, 0);
  defp(P_WIDTH, "width", "Extra width (adaptive)", "Global", 0, 0.8f, 0.45f, 0.05f, 0);
  defp(P_LOUD, "loud_db", "Loudness vs NORMAL (dB)", "Global", -6, 6, 2, 0.5f, 0);
  defp(P_OUT_DB, "out_db", "OWN 3D output trim (dB)", "Global", -12, 6, 0, 0.5f, 0);
  defp(P_HRTF, "hrtf", "HRTF subject", "Global", 0, OWN3D_MAX_HRTF - 1, 0, 1, 1);
  defp(P_ER_LOWCUT, "er_lowcut_hz", "Reflection low cut (Hz, 20 = off)", "Room", 20, 800, 20, 10, 1);
  done = 1;
}
int own3d_param_count(void) { params_init(); return NPARAM; }
const Own3DParam *own3d_param_info(int id) { params_init(); return id >= 0 && id < NPARAM ? &ptab[id] : NULL; }
int own3d_param_find(const char *key) {
  params_init();
  for (int i = 0; i < NPARAM; i++) if (!strcmp(ptab[i].key, key)) return i;
  return -1;
}

/* ---------------- DSP helpers ---------------- */
/* the late reverb runs at 24 kHz (its input is low-passed at ~6 kHz): half the cost */
#define FS_FDN 24000.0f
static const int fdn_len[NL] = {577, 661, 773, 863, 977, 1051, 1171, 1259};
static const float fdn_sgn[NL] = {1, -1, 1, -1, 1, 1, -1, -1};

typedef struct { char name[32]; int ndirs, ntaps; float *vec; float *ir; } Hrtf;
typedef struct { float b0, b1, b2, a1, a2, z1, z2; } Biquad;
typedef struct {
  float buf[NL][FDN_MAX];
  int idx;
  float lp[NL];
  float preL[FDN_MAX], preR[FDN_MAX];
  int prePos;
  float hpYL, hpYR, hpXL, hpXR, hp2YL, hp2YR, hp2XL, hp2XR, lpL, lpR;
  /* 2:1 decimation / linear-interpolation state (render thread) */
  float dIn[2], yPrev[2], yCur[2];
  int phase;
} Fdn;
typedef struct {
  float *H[NS][2][P];
  float fdnG[NL], fdnDamp, lateGain;
  unsigned char nz[NS][2][P];   /* partition has energy above -100 dB of the strongest one */
  int nParts;
  int preDelay, lowDelay;
} Bank;

struct Own3D {
  PFFFT_Setup *fft;
  /* render thread */
  float *work, *tbuf, *XL, *XR, *Y[NS];
  float win[N];
  float histL[B], histR[B];
  float pll[B + 3], prr[B + 3], plrRe[B + 3], plrIm[B + 3], wSm[B + 1], aSm[B + 1], pSm[B + 1];
  float gm[6][B + 1];   /* per-bin gains: FL FR C SL SR rear */
  float ola[NS][B];
  float spk[NS][B];
  float xprev[NS][B];
  float *fdl[NS][P];
  int fdlPos;
  float *acc[2];
  float ear[2][B], earB[2][B];
  float spkGain[NS], subGain;
  int spkSilent[NS];   /* blocks since the speaker feed was last non-zero */
  float spkG0[NS];     /* gain at the start of the current block */
  Fdn fdn;
  /* filter banks: render uses bank[active]; worker builds bank[1 - active] then sets pending */
  Bank bank[2];
  volatile int active, pending, haveBank;
  /* worker scratch */
  float *wwork, *wtbuf;
  float dirT[NS][2][FL_LEN], erT[NS][2][FL_LEN];
  Fdn fdnScratch;
  /* parameters */
  volatile float param[NPARAM];
  float liveApplied[NPARAM];
  Hrtf hrtf[OWN3D_MAX_HRTF];
  int nHrtf;
  /* crossover + untouched low band + dry blend */
  Biquad lpL[2], lpR[2], hpL[2], hpR[2];
  float xoverHz;
  float lowL[LOW_RING], lowR[LOW_RING], dryL[LOW_RING], dryR[LOW_RING];
  int lowPos;
  float hiL[B], hiR[B];
  /* tone */
  Biquad tone[2][7];
  int toneOn[7];
  /* width */
  double msM, msS;
  float wGain, wTarget;
  /* loudness */
  Biquad kInL[2], kInR[2], kOutL[2], kOutR[2];
  double Ein, Eout;
  float agc, agcTarget, agcCal;
  int agcBlocks;
  /* limiter */
  float laL[LA_RING], laR[LA_RING], laT[LA_RING];
  int laPos;
  float dqV[LA_RING];
  unsigned dqI[LA_RING], dqH, dqT, laN;
  float lim;
  /* block FIFOs */
  float inL[B], inR[B];
  int inCount;
  int16_t outFifo[2 * 2 * B];
  int outCount;
  /* silence gating: after ~1 s of silent input (plus the reverb tail) the renderer stops */
  int silentBlocks, idle, fadeIn;
  long idleBlocks;
  float outPeak;
};

int64_t own3d_now_ns(void) {
#ifdef _WIN32
  static LARGE_INTEGER f;
  LARGE_INTEGER c;
  if (!f.QuadPart) QueryPerformanceFrequency(&f);
  QueryPerformanceCounter(&c);
  return (int64_t)((double)c.QuadPart * 1e9 / (double)f.QuadPart);
#else
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
#endif
}

static float *falloc(void) {
  float *p = (float *)pffft_aligned_malloc(N * sizeof(float));
  if (p) memset(p, 0, N * sizeof(float));
  return p;
}
static inline float db2a(float db) { return powf(10.0f, db / 20.0f); }

static inline float bq(Biquad *q, float x) {   /* transposed direct form II */
  float y = q->b0 * x + q->z1;
  q->z1 = q->b1 * x - q->a1 * y + q->z2;
  q->z2 = q->b2 * x - q->a2 * y;
  return y;
}
/* sets coefficients, keeps the state (so live retuning does not click) */
static void bq_coef(Biquad *q, double b0, double b1, double b2, double a0, double a1, double a2) {
  q->b0 = (float)(b0 / a0); q->b1 = (float)(b1 / a0); q->b2 = (float)(b2 / a0);
  q->a1 = (float)(a1 / a0); q->a2 = (float)(a2 / a0);
}
static void bq_butter(Biquad *q, double fc, int highpass) {   /* RBJ, Q = 1/sqrt(2) */
  double w = 2 * 3.14159265358979 * fc / 48000.0, c = cos(w), al = sin(w) / (2 * 0.70710678);
  if (highpass) bq_coef(q, (1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + al, -2 * c, 1 - al);
  else bq_coef(q, (1 - c) / 2, 1 - c, (1 - c) / 2, 1 + al, -2 * c, 1 - al);
}
static void bq_shelf(Biquad *q, double f0, double gainDb, int high) {   /* RBJ shelf, S = 1 */
  double A = pow(10.0, gainDb / 40.0), w = 2 * 3.14159265358979 * f0 / 48000.0, c = cos(w);
  double al = sin(w) / 2 * sqrt(2.0), sa = 2 * sqrt(A) * al;
  if (high) bq_coef(q, A * ((A + 1) + (A - 1) * c + sa), -2 * A * ((A - 1) + (A + 1) * c), A * ((A + 1) + (A - 1) * c - sa),
                    (A + 1) - (A - 1) * c + sa, 2 * ((A - 1) - (A + 1) * c), (A + 1) - (A - 1) * c - sa);
  else bq_coef(q, A * ((A + 1) - (A - 1) * c + sa), 2 * A * ((A - 1) - (A + 1) * c), A * ((A + 1) - (A - 1) * c - sa),
               (A + 1) + (A - 1) * c + sa, -2 * ((A - 1) + (A + 1) * c), (A + 1) + (A - 1) * c - sa);
}
static void bq_peak(Biquad *q, double f0, double gainDb, double Q) {
  double A = pow(10.0, gainDb / 40.0), w = 2 * 3.14159265358979 * f0 / 48000.0, al = sin(w) / (2 * Q), c = cos(w);
  bq_coef(q, 1 + al * A, -2 * c, 1 - al * A, 1 + al / A, -2 * c, 1 - al / A);
}
static void bq_run(Biquad q, float *x, int n) {   /* in place, from zero state */
  q.z1 = q.z2 = 0;
  for (int i = 0; i < n; i++) x[i] = bq(&q, x[i]);
}
static void kweight_init(Biquad k[2]) {   /* ITU-R BS.1770 K-weighting at 48 kHz */
  bq_coef(&k[0], 1.53512485958697, -2.69169618940638, 1.19839281085285, 1, -1.69065929318241, 0.73248077421585);
  bq_coef(&k[1], 1.0, -2.0, 1.0, 1, -1.99004745483398, 0.99007225036621);
  k[0].z1 = k[0].z2 = k[1].z1 = k[1].z2 = 0;
}

/* ---------------- HRIR sets ---------------- */
int own3d_add_hrtf_bin(Own3D *o, const char *name, const void *data, long len) {
  const unsigned char *p = (const unsigned char *)data;
  if (o->nHrtf >= OWN3D_MAX_HRTF || len < 16 || memcmp(p, "O3DH", 4)) return -1;
  int32_t ver, nd, nt;
  memcpy(&ver, p + 4, 4); memcpy(&nd, p + 8, 4); memcpy(&nt, p + 12, 4);
  if (ver != 1 || nd <= 0 || nt <= 0 || nt > FL_LEN / 2 || len < 16 + 4L * (2L * nd + 2L * nd * nt)) return -1;
  Hrtf *h = &o->hrtf[o->nHrtf];
  snprintf(h->name, sizeof h->name, "%s", name ? name : "hrtf");
  h->ndirs = nd; h->ntaps = nt;
  h->vec = (float *)malloc(sizeof(float) * 3 * nd);
  h->ir = (float *)malloc(sizeof(float) * 2 * nt * nd);
  if (!h->vec || !h->ir) { free(h->vec); free(h->ir); return -1; }
  for (int i = 0; i < nd; i++) {
    float d[2];
    memcpy(d, p + 16 + 8L * i, 8);
    float a = d[0] * PI_F / 180, e = d[1] * PI_F / 180;
    h->vec[3 * i] = cosf(e) * cosf(a); h->vec[3 * i + 1] = cosf(e) * sinf(a); h->vec[3 * i + 2] = sinf(e);
  }
  memcpy(h->ir, p + 16 + 8L * nd, sizeof(float) * 2 * nt * nd);
  return o->nHrtf++;
}

static const float *hrir_near(const Hrtf *h, float x, float y, float z) {
  float n = sqrtf(x * x + y * y + z * z) + 1e-9f, best = -2;
  int bi = 0;
  x /= n; y /= n; z /= n;
  for (int i = 0; i < h->ndirs; i++) {
    float d = h->vec[3 * i] * x + h->vec[3 * i + 1] * y + h->vec[3 * i + 2] * z;
    if (d > best) { best = d; bi = i; }
  }
  return h->ir + (size_t)bi * 2 * h->ntaps;
}

/* Adds hrir * gain at fractional delay t (samples) into dst[2][FL_LEN]; one-pole LP if lpc > 0. */
static void splat(float dst[2][FL_LEN], const float *hr, int ntaps, float t, float gain, float lpc) {
  int ti = (int)floorf(t);
  float fr = t - ti;
  if (ti < 0 || ti + ntaps + 1 >= FL_LEN) return;
  for (int e = 0; e < 2; e++) {
    float s = 0;
    for (int i = 0; i < ntaps; i++) {
      float v = hr[e * ntaps + i] * gain;
      if (lpc > 0) { s += lpc * (v - s); v = s; }
      dst[e][ti + i] += (1 - fr) * v;
      dst[e][ti + i + 1] += fr * v;
    }
  }
}

/* ---------------- FDN ---------------- */
static inline void fdn_tick(Fdn *f, const float *g, float damp, int preDelay, float xl, float xr, float *outL, float *outR) {
  /* pre-delay, then band-limit: 2nd-order HP ~320 Hz (two one-poles: gentle 12 dB/oct, kick,
     bass and low-mids stay dry, so the tail never thickens the low-mids), LP ~6 kHz.
     Runs at FS_FDN. */
  f->preL[f->prePos] = xl; f->preR[f->prePos] = xr;
  int rp = (f->prePos - preDelay) & (FDN_MAX - 1);
  xl = f->preL[rp]; xr = f->preR[rp];
  f->prePos = (f->prePos + 1) & (FDN_MAX - 1);
  const float ah = 0.9226f, cl = 0.7921f;
  f->hpYL = ah * (f->hpYL + xl - f->hpXL); f->hpXL = xl;
  f->hpYR = ah * (f->hpYR + xr - f->hpXR); f->hpXR = xr;
  f->hp2YL = ah * (f->hp2YL + f->hpYL - f->hp2XL); f->hp2XL = f->hpYL;
  f->hp2YR = ah * (f->hp2YR + f->hpYR - f->hp2XR); f->hp2XR = f->hpYR;
  f->lpL += cl * (f->hp2YL - f->lpL);
  f->lpR += cl * (f->hp2YR - f->lpR);
  float y[NL], v[NL];
  for (int i = 0; i < NL; i++) {
    y[i] = f->buf[i][(f->idx - fdn_len[i]) & (FDN_MAX - 1)];
    f->lp[i] = (1 - damp) * y[i] + damp * f->lp[i] + 1e-20f;
    v[i] = g[i] * f->lp[i];
  }
  for (int h = 1; h < NL; h <<= 1)       /* 8-point fast Hadamard, orthonormal */
    for (int i = 0; i < NL; i += h << 1)
      for (int j = i; j < i + h; j++) {
        float a = v[j], b = v[j + h];
        v[j] = a + b; v[j + h] = a - b;
      }
  for (int i = 0; i < NL; i++)
    f->buf[i][f->idx] = 0.35355339f * v[i] + fdn_sgn[i] * (i < 4 ? f->lpL : f->lpR);
  f->idx = (f->idx + 1) & (FDN_MAX - 1);
  *outL = y[0] - y[1] + y[2] - y[3] + y[4] - y[5] + y[6] - y[7];
  *outR = y[0] + y[1] - y[2] - y[3] + y[4] + y[5] - y[6] - y[7];
}

/* ---------------- filter build (worker thread) ---------------- */
/* Band powers (dB) of the direct sound: HRIR power spectra of the HRTF speakers, weighted by the
   share of the programme each one usually carries. 5 points per band. */
/* direct sound of speaker s (HRIR or plain impulse) into dst */
static void spk_direct(const Hrtf *h, const float *pp, int pk, int s, float dst[2][FL_LEN]) {
  const float c = 343.0f;
  float a = pp[SP(s, K_AZ)] * PI_F / 180, e = pp[SP(s, K_EL)] * PI_F / 180, dist = pp[SP(s, K_DIST)];
  float src[3] = {dist * cosf(e) * cosf(a), dist * cosf(e) * sinf(a), dist * sinf(e)};
  float off = SPK_DELAY_MS[s] * pp[P_DECORR] * FS / 1000.0f + (dist - 2.0f) / c * FS;
  if (off < 0) off = 0;
  float dg = 2.0f / dist;
  if (pp[SP(s, K_DIRECT)] > 0.5f) {
    /* direct: no HRTF, no room; a delayed impulse to the ear on its side (both for centre) */
    int ti = (int)(off + pk);
    float sn = sinf(a);
    float gl = sn > 0.2f ? 1.0f : sn < -0.2f ? 0.0f : 0.70710678f, gr = sn < -0.2f ? 1.0f : sn > 0.2f ? 0.0f : 0.70710678f;
    if (ti < FL_LEN) { dst[0][ti] = gl * dg; dst[1][ti] = gr * dg; }
    return;
  }
  splat(dst, hrir_near(h, src[0], src[1], src[2]), h->ntaps, off, dg, 0);
}
static void build_bank(Own3D *o, Bank *bk, const float *pp) {
  int hi = (int)pp[P_HRTF];
  if (hi < 0 || hi >= o->nHrtf) hi = 0;
  const Hrtf *h = &o->hrtf[hi];
  const float c = 343.0f, size = pp[P_ROOM_SIZE];
  /* shoebox in listener coordinates (x forward, y left, z up); ear 1.2 m above the floor */
  const float lo[3] = {-3.0f * size, -2.8f * size, -1.2f}, hiw[3] = {3.4f * size, 2.6f * size, 3.0f * size - 1.2f};
  const float beta = 0.45f, floorBeta = 0.30f;
  int R = (int)pp[P_ER_ORDER];
  float damp = pp[P_DAMPING];
  float bounceFc = 12000.0f - 9000.0f * damp;
  float er = db2a(pp[P_ER_DB]);
  memset(o->dirT, 0, sizeof o->dirT);
  memset(o->erT, 0, sizeof o->erT);
  /* main-peak delay of a frontal HRIR: direct speakers and the sub band are aligned to it */
  const float *h0 = hrir_near(h, 1, 0, 0);
  int pk = 0;
  float pkv = 0;
  for (int i = 0; i < h->ntaps; i++) {
    float v = fabsf(h0[i]) + fabsf(h0[h->ntaps + i]);
    if (v > pkv) { pkv = v; pk = i; }
  }
  for (int s = 0; s < NS; s++) {
    float a = pp[SP(s, K_AZ)] * PI_F / 180, e = pp[SP(s, K_EL)] * PI_F / 180, dist = pp[SP(s, K_DIST)];
    float src[3] = {dist * cosf(e) * cosf(a), dist * cosf(e) * sinf(a), dist * sinf(e)};
    float off = SPK_DELAY_MS[s] * pp[P_DECORR] * FS / 1000.0f + (dist - 2.0f) / c * FS;
    if (off < 0) off = 0;
    spk_direct(h, pp, pk, s, o->dirT[s]);
    if (pp[SP(s, K_DIRECT)] > 0.5f) continue;
    for (int nx = -R; nx <= R; nx++)
      for (int ny = -R; ny <= R; ny++)
        for (int nz = -R; nz <= R; nz++) {
          int ord = abs(nx) + abs(ny) + abs(nz);
          if (ord == 0 || ord > R) continue;
          int n3[3] = {nx, ny, nz};
          float p[3], g = powf(beta, (float)(abs(nx) + abs(ny)));
          for (int k = 0; k < 3; k++) {
            float w = hiw[k] - lo[k], u = src[k] - lo[k];
            p[k] = n3[k] * w + ((n3[k] & 1) ? w - u : u) + lo[k];
          }
          for (int k = 0; k < abs(nz); k++) {
            int floorHit = nz > 0 ? (k % 2 == 1) : (k % 2 == 0);
            g *= floorHit ? floorBeta : beta;
          }
          float d = sqrtf(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
          float t = (d - dist) / c * FS + off + pp[P_PREDELAY] * FS / 1000.0f;
          float lpc = 1.0f - expf(-2 * PI_F * (bounceFc / ord) / FS);
          splat(o->erT[s], hrir_near(h, p[0], p[1], p[2]), h->ntaps, t, g * 2.0f / d, lpc);
        }
  }
  /* early reflections: optional gentle high-pass (12 dB/oct); 20 Hz = off */
  if (pp[P_ER_LOWCUT] > 25) {
    Biquad hp;
    bq_butter(&hp, pp[P_ER_LOWCUT], 1);
    for (int s = 0; s < NS; s++) for (int ee = 0; ee < 2; ee++) bq_run(hp, o->erT[s][ee], FL_LEN);
  }
  /* Level reference: a frontal +-30 phantom centre at one ear has unit gain at ~250 Hz, so the
     rendered band meets the untouched sub band without a step, whatever the speaker layout. */
  const float *ha = hrir_near(h, 0.866f, 0.5f, 0), *hb = hrir_near(h, 0.866f, -0.5f, 0);
  double re = 0, im = 0;
  for (int i = 0; i < h->ntaps; i++) {
    float v = ha[i] + hb[i];
    re += v * cos(2 * 3.14159265358979 * 250.0 * i / 48000.0);
    im -= v * sin(2 * 3.14159265358979 * 250.0 * i / 48000.0);
  }
  float norm = (float)(1.0 / (sqrt(re * re + im * im) + 1e-9));
  for (int s = 0; s < NS; s++) {
    float dn = pp[SP(s, K_DIRECT)] > 0.5f ? 1.0f : norm;
    for (int ee = 0; ee < 2; ee++)
      for (int i = 0; i < FL_LEN; i++) o->dirT[s][ee][i] = o->dirT[s][ee][i] * dn + er * norm * o->erT[s][ee][i];
  }
  /* late reverb level relative to the direct energy of a frontal source */
  double ed = 0;
  for (int i = 0; i < h->ntaps; i++) ed += (h0[i] * h0[i] + h0[h->ntaps + i] * h0[h->ntaps + i]) * norm * norm;
  for (int i = 0; i < NL; i++) bk->fdnG[i] = powf(10.0f, -3.0f * fdn_len[i] / (FS_FDN * pp[P_RT60]));
  bk->fdnDamp = 0.5f * (0.15f + 0.5f * damp);   /* same loop damping as the former 48 kHz FDN */
  bk->preDelay = (int)((pp[P_PREDELAY] + 8.0f) * FS_FDN / 1000.0f);
  if (bk->preDelay > FDN_MAX - 1) bk->preDelay = FDN_MAX - 1;
  {
    Fdn *f = &o->fdnScratch;
    memset(f, 0, sizeof *f);
    double en = 0;
    for (int i = 0; i < (int)(2 * FS_FDN); i++) {
      float x, y;
      fdn_tick(f, bk->fdnG, bk->fdnDamp, 0, i == 0 ? 1.0f : 0.0f, 0.0f, &x, &y);
      en += 0.5 * ((double)x * x + (double)y * y);
    }
    /* a 48 kHz impulse reaches the 24 kHz FDN at 1/2 and each output sample is used twice */
    bk->lateGain = (float)(1.0 / sqrt(en / 2 + 1e-20) * sqrt(ed / 2 * pow(10.0, pp[P_LATE_DB] / 10.0)));
  }
  /* partitions that are (near) silent are skipped by the convolution: sparse late reflections
     and short layouts cost nothing */
  double emax = 0, ep[NS][2][P];
  for (int s = 0; s < NS; s++)
    for (int ee = 0; ee < 2; ee++)
      for (int p = 0; p < P; p++) {
        double e = 0;
        for (int i = 0; i < B; i++) e += (double)o->dirT[s][ee][p * B + i] * o->dirT[s][ee][p * B + i];
        ep[s][ee][p] = e; if (e > emax) emax = e;
      }
  bk->nParts = 0;
  for (int s = 0; s < NS; s++)
    for (int ee = 0; ee < 2; ee++)
      for (int p = 0; p < P; p++) {
        bk->nz[s][ee][p] = ep[s][ee][p] > emax * 1e-8;
        bk->nParts += bk->nz[s][ee][p];
        memcpy(o->wtbuf, &o->dirT[s][ee][p * B], B * sizeof(float));
        memset(o->wtbuf + B, 0, B * sizeof(float));
        pffft_transform(o->fft, o->wtbuf, bk->H[s][ee][p], o->wwork, PFFFT_FORWARD);
      }
  /* rendered band delay = STFT hop + HRIR main peak; the sub band and the dry blend wait as long */
  bk->lowDelay = B + pk;
}

void own3d_rebuild(Own3D *o) {
  if (o->nHrtf == 0) return;
  for (int i = 0; i < 200 && o->pending >= 0; i++) SLEEP_MS(1);   /* previous build still crossfading */
  if (o->pending >= 0) return;
  float pp[NPARAM];
  for (int i = 0; i < NPARAM; i++) pp[i] = o->param[i];
  int b = o->haveBank ? 1 - o->active : 0;
  build_bank(o, &o->bank[b], pp);
  BARRIER();
  if (!o->haveBank) { o->active = b; o->haveBank = 1; }
  else o->pending = b;
}

/* ---------------- upmix ---------------- */
static void upmix(Own3D *o, const float *L, const float *R) {
  for (int i = 0; i < B; i++) {
    o->tbuf[i] = o->histL[i] * o->win[i];
    o->tbuf[B + i] = L[i] * o->win[B + i];
  }
  pffft_transform_ordered(o->fft, o->tbuf, o->XL, o->work, PFFFT_FORWARD);
  for (int i = 0; i < B; i++) {
    o->tbuf[i] = o->histR[i] * o->win[i];
    o->tbuf[B + i] = R[i] * o->win[B + i];
  }
  pffft_transform_ordered(o->fft, o->tbuf, o->XR, o->work, PFFFT_FORWARD);
  memcpy(o->histL, L, sizeof o->histL);
  memcpy(o->histR, R, sizeof o->histR);

#ifdef OWN3D_PROFILE
  int64_t q0 = own3d_now_ns();
#endif
  /* statistics live at index k + 1 (0 and B + 2 are zero pads for the 3-bin smoothing) */
  const float al = 0.8f, eps = 1e-12f;
  float *pll = o->pll + 1, *prr = o->prr + 1, *pre = o->plrRe + 1, *pim = o->plrIm + 1;
  pll[0] = (1 - al) * (o->XL[0] * o->XL[0]) + al * pll[0];
  prr[0] = (1 - al) * (o->XR[0] * o->XR[0]) + al * prr[0];
  pre[0] = (1 - al) * (o->XL[0] * o->XR[0]) + al * pre[0];
  pim[0] = al * pim[0];
  pll[B] = (1 - al) * (o->XL[1] * o->XL[1]) + al * pll[B];
  prr[B] = (1 - al) * (o->XR[1] * o->XR[1]) + al * prr[B];
  pre[B] = (1 - al) * (o->XL[1] * o->XR[1]) + al * pre[B];
  pim[B] = al * pim[B];
  for (int k = 1; k < B; k++) {
    float ar = o->XL[2 * k], ai = o->XL[2 * k + 1], br = o->XR[2 * k], bi = o->XR[2 * k + 1];
    pll[k] = al * pll[k] + (1 - al) * (ar * ar + ai * ai);
    prr[k] = al * prr[k] + (1 - al) * (br * br + bi * bi);
    pre[k] = al * pre[k] + (1 - al) * (ar * br + ai * bi);  /* a * conj(b) */
    pim[k] = al * pim[k] + (1 - al) * (ai * br - ar * bi);
  }
  float amb_scale = o->param[P_AMBIENCE];
  float ks = 0.75f * amb_scale, kr = 0.60f * amb_scale, kk = ks * ks + kr * kr;
  if (kk > 0.95f) { float sc = sqrtf(0.95f / kk); ks *= sc; kr *= sc; kk = 0.95f; }
  const float cMax = o->param[P_CENTER], sidePan = o->param[P_SIDEPAN];
  const int kLow = (int)(o->xoverHz / 50.0f) + 1;   /* bins below the crossover: leave on the fronts */
  /* branch-free per-bin masks (vectorisable); edges use the zero pads, i.e. 2-bin smoothing */
  for (int k = 0; k <= B; k++) {
    /* 3-bin frequency smoothing of the statistics: steadier masks, less phasiness */
    float pl = pll[k - 1] + pll[k] + pll[k + 1], prv = prr[k - 1] + prr[k] + prr[k + 1];
    float cr = pre[k - 1] + pre[k] + pre[k + 1], ci = pim[k - 1] + pim[k] + pim[k + 1];
    float c2 = cr * cr + ci * ci;
    float pp2 = pl * prv;
    float coh2 = fminf(c2 / (pp2 + eps), 1.0f);
    float prod = sqrtf(pp2) + eps;
    float bal = 2 * prod / (pl + prv + 2 * eps);
    float pan = (pl - prv) / (pl + prv + eps);
    float inphase = fmaxf(cr, 0.0f) / (sqrtf(c2) + eps);
    float ps = o->pSm[k] + 0.25f * (pan - o->pSm[k]);
    o->pSm[k] = ps;
    float ap = fminf(fmaxf((fabsf(ps) - 0.4f) / 0.6f, 0.0f), 1.0f);
    float sp = sidePan * ap * ap * (3 - 2 * ap);   /* smoothstep: only clearly panned sources move */
    float lowk = k < kLow ? 0.0f : 1.0f;
    float spL = ps > 0 ? sp : 0, spR = ps < 0 ? sp : 0;
    spL *= lowk; spR *= lowk;
    float wc = fmaxf(1.0f - 1.25f * fabsf(pan), 0.0f);
    float w = cMax * wc * wc * coh2 * inphase * lowk;
    float amb = (1 - coh2) * bal * lowk;
    /* temporal smoothing of the masks themselves (~50 ms): no warbling on pumped material */
    w = o->wSm[k] + 0.25f * (w - o->wSm[k]); o->wSm[k] = w;
    amb = o->aSm[k] + 0.25f * (amb - o->aSm[k]); o->aSm[k] = amb;
    float rest = sqrtf(1 - w);
    float gF = rest * sqrtf(1 - kk * amb);
    float sa = rest * sqrtf(amb);
    float gS = sa * ks;
    o->gm[0][k] = gF * sqrtf(1 - spL);
    o->gm[1][k] = gF * sqrtf(1 - spR);
    o->gm[2][k] = sqrtf(w) * 0.70710678f;
    o->gm[3][k] = sqrtf(gS * gS + gF * gF * spL);
    o->gm[4][k] = sqrtf(gS * gS + gF * gF * spR);
    o->gm[5][k] = sa * kr;
  }
#ifdef OWN3D_PROFILE
  own3d_prof[7] += (double)(own3d_now_ns() - q0);
#endif
  {
    float *const *Y = o->Y;
    const float *XL = o->XL, *XR = o->XR;
    /* DC and Nyquist (packed in [0], [1]) */
    for (int j = 0; j < 2; j++) {
      int k = j == 0 ? 0 : B;
      Y[S_FL][j] = o->gm[0][k] * XL[j]; Y[S_FR][j] = o->gm[1][k] * XR[j]; Y[S_C][j] = o->gm[2][k] * (XL[j] + XR[j]);
      Y[S_SL][j] = o->gm[3][k] * XL[j]; Y[S_SR][j] = o->gm[4][k] * XR[j];
      Y[S_BL][j] = o->gm[5][k] * XL[j]; Y[S_BR][j] = o->gm[5][k] * XR[j];
    }
    for (int i = 2; i < N; i++) {
      int k = i >> 1;
      float a = XL[i], b = XR[i];
      Y[S_FL][i] = o->gm[0][k] * a; Y[S_FR][i] = o->gm[1][k] * b; Y[S_C][i] = o->gm[2][k] * (a + b);
      Y[S_SL][i] = o->gm[3][k] * a; Y[S_SR][i] = o->gm[4][k] * b;
      Y[S_BL][i] = o->gm[5][k] * a; Y[S_BR][i] = o->gm[5][k] * b;
    }
  }
  const float sc = 1.0f / N;
  for (int s = 0; s < NS; s++) {
    pffft_transform_ordered(o->fft, o->Y[s], o->tbuf, o->work, PFFFT_BACKWARD);
    for (int i = 0; i < B; i++) {
      o->spk[s][i] = o->ola[s][i] + o->tbuf[i] * o->win[i] * sc;
      o->ola[s][i] = o->tbuf[B + i] * o->win[B + i] * sc;
    }
  }
}

/* ---------------- binaural convolution ---------------- */
static void push_inputs(Own3D *o) {
  for (int s = 0; s < NS; s++) {
    if (o->spkGain[s] == 0.0f && o->spkG0[s] == 0.0f) {   /* muted all block: skip the FFT once the FDL is clear */
      if (o->spkSilent[s] >= P) continue;
      o->spkSilent[s]++;
      memset(o->fdl[s][o->fdlPos], 0, N * sizeof(float));
      memset(o->xprev[s], 0, B * sizeof(float));
      continue;
    }
    o->spkSilent[s] = 0;
    memcpy(o->tbuf, o->xprev[s], B * sizeof(float));
    memcpy(o->tbuf + B, o->spk[s], B * sizeof(float));
    memcpy(o->xprev[s], o->spk[s], B * sizeof(float));
    pffft_transform(o->fft, o->tbuf, o->fdl[s][o->fdlPos], o->work, PFFFT_FORWARD);
  }
}
static void convolve(Own3D *o, const Bank *bk, float out[2][B]) {
  for (int e = 0; e < 2; e++) {
    memset(o->acc[e], 0, N * sizeof(float));
    for (int s = 0; s < NS; s++) {
      if (o->spkGain[s] == 0.0f && o->spkSilent[s] >= P) continue;   /* muted and its history is empty */
      for (int p = 0; p < P; p++)
        if (bk->nz[s][e][p]) pffft_zconvolve_accumulate(o->fft, o->fdl[s][(o->fdlPos - p + P) % P], bk->H[s][e][p], o->acc[e], 1.0f / N);
    }
    pffft_transform(o->fft, o->acc[e], o->tbuf, o->work, PFFFT_BACKWARD);
    memcpy(out[e], o->tbuf + B, B * sizeof(float));   /* overlap-save: second half is valid */
  }
}

/* live parameters: recompute cheap coefficients when they changed */
static void apply_live(Own3D *o) {
  volatile float *p = o->param;
  int tone = 0;
  for (int i = P_LS_F; i <= P_EQ5; i++) if (p[i] != o->liveApplied[i]) { tone = 1; o->liveApplied[i] = p[i]; }
  if (tone) {
    for (int c = 0; c < 2; c++) {
      bq_shelf(&o->tone[c][0], p[P_LS_F], p[P_LS_DB], 0);
      bq_shelf(&o->tone[c][1], p[P_HS_F], p[P_HS_DB], 1);
      for (int i = 0; i < 5; i++) bq_peak(&o->tone[c][2 + i], EQ_F[i], p[P_EQ1 + i], 1.0);
    }
    for (int k = 0; k < 7; k++) {
      float gdb = k == 0 ? p[P_LS_DB] : k == 1 ? p[P_HS_DB] : p[P_EQ1 + k - 2];
      int on = fabsf(gdb) > 0.01f;
      if (on && !o->toneOn[k]) for (int c = 0; c < 2; c++) o->tone[c][k].z1 = o->tone[c][k].z2 = 0;
      o->toneOn[k] = on;   /* a 0 dB section is an identity: skip it */
    }
  }
  if (p[P_XOVER] != o->xoverHz) {
    o->xoverHz = p[P_XOVER];
    for (int k = 0; k < 2; k++) {
      bq_butter(&o->lpL[k], o->xoverHz, 0); bq_butter(&o->lpR[k], o->xoverHz, 0);
      bq_butter(&o->hpL[k], o->xoverHz, 1); bq_butter(&o->hpR[k], o->xoverHz, 1);
    }
  }
}

static void render_block(Own3D *o, int16_t *out) {
  if (!o->haveBank) own3d_rebuild(o);   /* first block only (startup) */
  PT(-1);
  apply_live(o);
  volatile float *pp = o->param;
  int xf = o->pending;                  /* new bank ready: crossfade over this block */
  BARRIER();
  const Bank *bk = &o->bank[o->active], *bn = xf >= 0 ? &o->bank[xf] : NULL;

  double ein = 0, eout = 0;
  for (int i = 0; i < B; i++) {
    float l = o->inL[i], r = o->inR[i];
    float kl = bq(&o->kInL[1], bq(&o->kInL[0], l)), kr = bq(&o->kInR[1], bq(&o->kInR[0], r));
    ein += kl * kl + kr * kr;
    /* LR4 = two cascaded Butterworth sections; LP + HP sums to an allpass (flat magnitude) */
    float ll = bq(&o->lpL[1], bq(&o->lpL[0], l)), lr = bq(&o->lpR[1], bq(&o->lpR[0], r));
    o->hiL[i] = bq(&o->hpL[1], bq(&o->hpL[0], l));
    o->hiR[i] = bq(&o->hpR[1], bq(&o->hpR[0], r));
    o->lowL[o->lowPos] = ll; o->lowR[o->lowPos] = lr;
    o->dryL[o->lowPos] = o->hiL[i]; o->dryR[o->lowPos] = o->hiR[i];
    o->lowPos = (o->lowPos + 1) & (LOW_RING - 1);
  }
  PT(0);
  upmix(o, o->hiL, o->hiR);
  PT(1);
  /* speaker levels / mute / solo (ramped over the block) */
  int anySolo = 0;
  for (int s = 0; s < NS; s++) if (pp[SP(s, K_SOLO)] > 0.5f) anySolo = 1;
  for (int s = 0; s < NS; s++) {
    float g = (pp[SP(s, K_MUTE)] > 0.5f || (anySolo && pp[SP(s, K_SOLO)] < 0.5f)) ? 0.0f : db2a(pp[SP(s, K_LEVEL)]);
    float g0 = o->spkGain[s];
    for (int i = 0; i < B; i++) o->spk[s][i] *= g0 + (g - g0) * (float)i / B;
    o->spkGain[s] = g; o->spkG0[s] = g0;
  }
  push_inputs(o);
  PT(2);
  convolve(o, bk, o->ear);
  if (bn) {
    convolve(o, bn, o->earB);
    for (int i = 0; i < B; i++) {
      float t = (i + 0.5f) / B;
      o->ear[0][i] += t * (o->earB[0][i] - o->ear[0][i]);
      o->ear[1][i] += t * (o->earB[1][i] - o->ear[1][i]);
    }
  }
  o->fdlPos = (o->fdlPos + 1) % P;
  PT(3);

  const Bank *fb = bn ? bn : bk;
  float lg = fb->lateGain;
  const float dry = anySolo ? 0.0f : pp[P_BLEND], wet = 1.0f - 0.5f * dry;
  float subT = (pp[P_SUB_MUTE] > 0.5f || anySolo) ? 0.0f : db2a(pp[P_SUB_LEVEL]), sub0 = o->subGain;
  o->subGain = subT;
  int baseA = (o->lowPos - B - bk->lowDelay) & (LOW_RING - 1);
  int baseB = (o->lowPos - B - fb->lowDelay) & (LOW_RING - 1);
  double em = 0, es = 0;
  for (int i = 0; i < B; i++) {
    float rl, rr;
    {
      Fdn *f = &o->fdn;
      float xl = o->spk[S_FL][i] + 0.7f * o->spk[S_C][i] + o->spk[S_SL][i] + o->spk[S_BL][i];
      float xr = o->spk[S_FR][i] + 0.7f * o->spk[S_C][i] + o->spk[S_SR][i] + o->spk[S_BR][i];
      if (f->phase == 0) { f->dIn[0] = xl; f->dIn[1] = xr; rl = 0.5f * (f->yPrev[0] + f->yCur[0]); rr = 0.5f * (f->yPrev[1] + f->yCur[1]); }
      else {
        f->yPrev[0] = f->yCur[0]; f->yPrev[1] = f->yCur[1];
        fdn_tick(f, fb->fdnG, fb->fdnDamp, fb->preDelay, 0.5f * (f->dIn[0] + xl), 0.5f * (f->dIn[1] + xr), &f->yCur[0], &f->yCur[1]);
        rl = f->yCur[0]; rr = f->yCur[1];
      }
      f->phase ^= 1;
    }
    float t = bn ? (i + 0.5f) / B : 0.0f;
    int la = (baseA + i) & (LOW_RING - 1), lb = (baseB + i) & (LOW_RING - 1);
    float dL = o->dryL[la] + t * (o->dryL[lb] - o->dryL[la]), dR = o->dryR[la] + t * (o->dryR[lb] - o->dryR[la]);
    float el = wet * (o->ear[0][i] + lg * rl) + dry * dL;
    float er = wet * (o->ear[1][i] + lg * rr) + dry * dR;
    o->ear[0][i] = el; o->ear[1][i] = er;
    em += (double)(el + er) * (el + er); es += (double)(el - er) * (el - er);
  }
  PT(4);
  /* adaptive width: c = interaural correlation of the rendered band (~0.3 s average); side gain
     that brings it to c * (1 - width), only when c > 0 (never pushes it negative / phasey) */
  o->msM = 0.97 * o->msM + 0.03 * em; o->msS = 0.97 * o->msS + 0.03 * es;
  {
    double c = (o->msM - o->msS) / (o->msM + o->msS + 1e-20), wt = 1.0;
    if (c > 0.05) {
      double c2 = c * (1.0 - pp[P_WIDTH]);
      wt = sqrt((o->msM / (o->msS + 1e-20)) * (1 - c2) / (1 + c2));
      if (wt > 1.6) wt = 1.6;
      if (wt < 1.0) wt = 1.0;
    }
    o->wTarget = (float)wt;
  }
  float w0 = o->wGain, w1 = o->wGain + 0.05f * (o->wTarget - o->wGain);
  o->wGain = w1;
  for (int i = 0; i < B; i++) {
    float t = bn ? (i + 0.5f) / B : 0.0f;
    int la = (baseA + i) & (LOW_RING - 1), lb = (baseB + i) & (LOW_RING - 1);
    float sg = sub0 + (subT - sub0) * (float)i / B;
    float lowL = sg * (o->lowL[la] + t * (o->lowL[lb] - o->lowL[la]));
    float lowR = sg * (o->lowR[la] + t * (o->lowR[lb] - o->lowR[la]));
    float wd = w0 + (w1 - w0) * (float)i / B;
    float el = o->ear[0][i], er = o->ear[1][i];
    float m = 0.5f * (el + er), sd = 0.5f * wd * (el - er);
    float yl = m + sd + lowL, yr = m - sd + lowR;
    /* loudness is measured before the tone controls, so the EQ changes what you hear */
    float kl = bq(&o->kOutL[1], bq(&o->kOutL[0], yl));
    float kr = bq(&o->kOutR[1], bq(&o->kOutR[0], yr));
    eout += kl * kl + kr * kr;
    for (int k = 0; k < 7; k++) if (o->toneOn[k]) { yl = bq(&o->tone[0][k], yl); yr = bq(&o->tone[1][k], yr); }
    o->ear[0][i] = yl; o->ear[1][i] = yr;
  }
  if (bn) { BARRIER(); o->active = xf; o->pending = -1; }
  PT(5);

  /* Loudness match: running mean for the first 2 s after a reset (fast lock-in), then ~8 s
     exponential, gated on near-silence; the gain moves with a ~1 s time constant. */
  if (ein > B * 2 * 1e-6) {
    if (o->agcBlocks < 800) o->agcBlocks++;
    const double a = 1.0 - 1.0 / o->agcBlocks;
    o->Ein = a * o->Ein + (1 - a) * ein;
    o->Eout = a * o->Eout + (1 - a) * eout;
    float t = (float)sqrt(o->Ein / (o->Eout + 1e-20)) * db2a(pp[P_LOUD]);
    if (o->agcCal > 0) {
      if (t < 0.316f * o->agcCal) t = 0.316f * o->agcCal;
      if (t > 3.16f * o->agcCal) t = 3.16f * o->agcCal;
    }
    o->agcTarget = t;
  }
  float trim = db2a(pp[P_OUT_DB]);
  float g0 = o->agc, g1 = o->agc + (o->agcBlocks < 200 ? 0.1f : 0.01f) * (o->agcTarget - o->agc);
  o->agc = g1;
  const float att = 0.08f, rel = 1.0f - expf(-1.0f / (0.3f * FS));
  for (int i = 0; i < B; i++) {
    float g = (g0 + (g1 - g0) * (float)i / B) * trim;
    float l = o->ear[0][i] * g, r = o->ear[1][i] * g;
    float pk = fmaxf(fabsf(l), fabsf(r));
    o->laL[o->laPos] = l; o->laR[o->laPos] = r;
    float tt = pk > LIM_THR ? LIM_THR / pk : 1.0f;
    /* minimum of the last LA + 1 gain targets: monotonic deque, O(1) per sample */
    while (o->dqT != o->dqH && o->dqV[(o->dqT - 1) & (LA_RING - 1)] >= tt) o->dqT--;
    o->dqV[o->dqT & (LA_RING - 1)] = tt; o->dqI[o->dqT & (LA_RING - 1)] = o->laN; o->dqT++;
    if (o->laN - o->dqI[o->dqH & (LA_RING - 1)] > LA) o->dqH++;
    o->laN++;
    float wmin = o->dqV[o->dqH & (LA_RING - 1)];
    if (wmin < o->lim) o->lim += (wmin - o->lim) * att;
    else o->lim += (wmin - o->lim) * rel;
    int op = (o->laPos - LA) & (LA_RING - 1);
    float ol = o->laL[op] * o->lim, orr = o->laR[op] * o->lim;
    o->laPos = (o->laPos + 1) & (LA_RING - 1);
    if (fabsf(ol) > 0.95f) ol = copysignf(0.95f + 0.05f * tanhf((fabsf(ol) - 0.95f) / 0.05f), ol);
    if (fabsf(orr) > 0.95f) orr = copysignf(0.95f + 0.05f * tanhf((fabsf(orr) - 0.95f) / 0.05f), orr);
    float ap = fmaxf(fabsf(ol), fabsf(orr));
    if (ap > o->outPeak) o->outPeak = ap;
    int vl = (int)lrintf(ol * 32767.0f), vr = (int)lrintf(orr * 32767.0f);
    out[2 * i] = (int16_t)(vl > 32767 ? 32767 : vl < -32767 ? -32767 : vl);
    out[2 * i + 1] = (int16_t)(vr > 32767 ? 32767 : vr < -32767 ? -32767 : vr);
  }
  PT(6);
}

void own3d_process(Own3D *o, const int16_t *in, int16_t *out, int frames, float gain) {
  if (o->nHrtf == 0) { memset(out, 0, (size_t)frames * 4); return; }
  const float sc = gain / 32768.0f;
  while (frames > 0) {
    int m = B - o->inCount;
    if (m > frames) m = frames;
    for (int i = 0; i < m; i++) {
      o->inL[o->inCount + i] = in[2 * i] * sc;
      o->inR[o->inCount + i] = in[2 * i + 1] * sc;
    }
    o->inCount += m;
    if (o->inCount == B) {
      float pk = 0;
      for (int i = 0; i < B; i++) pk = fmaxf(pk, fmaxf(fabsf(o->inL[i]), fabsf(o->inR[i])));
      const float thr = 1.0e-4f;   /* -80 dBFS: paused / digital silence */
      if (pk < thr) o->silentBlocks++; else o->silentBlocks = 0;
      int hang = 100 + (int)(150 * o->param[P_RT60]);   /* 1 s + 1.5 x RT60 */
      if (!o->idle && o->silentBlocks > hang && o->outPeak < thr) o->idle = 1;
      o->outPeak = 0;
      if (o->idle && pk >= thr) {   /* resume at once: state is silent already; 5 ms input fade-in */
        o->idle = 0;
        o->fadeIn = 1;
      }
      if (o->idle) {
        memset(o->outFifo + 2 * o->outCount, 0, (size_t)B * 2 * sizeof(int16_t));
        o->idleBlocks++;
      } else {
        if (o->fadeIn) {
          for (int i = 0; i < 240; i++) { float g = (i + 0.5f) / 240; o->inL[i] *= g; o->inR[i] *= g; }
          o->fadeIn = 0;
        }
        render_block(o, o->outFifo + 2 * o->outCount);
      }
      o->outCount += B;
      o->inCount = 0;
    }
    /* invariant: inCount + outCount == B before the push, so outCount >= m here */
    memcpy(out, o->outFifo, (size_t)m * 2 * sizeof(int16_t));
    o->outCount -= m;
    memmove(o->outFifo, o->outFifo + 2 * m, (size_t)o->outCount * 2 * sizeof(int16_t));
    in += 2 * m; out += 2 * m; frames -= m;
  }
}

void own3d_reset(Own3D *o) {
  memset(o->histL, 0, sizeof o->histL); memset(o->histR, 0, sizeof o->histR);
  memset(o->pll, 0, sizeof o->pll); memset(o->gm, 0, sizeof o->gm); memset(o->prr, 0, sizeof o->prr);
  memset(o->plrRe, 0, sizeof o->plrRe); memset(o->plrIm, 0, sizeof o->plrIm);
  memset(o->wSm, 0, sizeof o->wSm); memset(o->aSm, 0, sizeof o->aSm); memset(o->pSm, 0, sizeof o->pSm);
  memset(o->ola, 0, sizeof o->ola); memset(o->xprev, 0, sizeof o->xprev);
  memset(o->spkSilent, 0, sizeof o->spkSilent);
  for (int s = 0; s < NS; s++) for (int p = 0; p < P; p++) memset(o->fdl[s][p], 0, N * sizeof(float));
  memset(&o->fdn, 0, sizeof o->fdn);
  for (int k = 0; k < 2; k++) {
    o->lpL[k].z1 = o->lpL[k].z2 = o->lpR[k].z1 = o->lpR[k].z2 = 0;
    o->hpL[k].z1 = o->hpL[k].z2 = o->hpR[k].z1 = o->hpR[k].z2 = 0;
  }
  for (int c = 0; c < 2; c++) for (int k = 0; k < 7; k++) o->tone[c][k].z1 = o->tone[c][k].z2 = 0;
  kweight_init(o->kInL); kweight_init(o->kInR); kweight_init(o->kOutL); kweight_init(o->kOutR);
  memset(o->lowL, 0, sizeof o->lowL); memset(o->lowR, 0, sizeof o->lowR);
  memset(o->dryL, 0, sizeof o->dryL); memset(o->dryR, 0, sizeof o->dryR);
  memset(o->laL, 0, sizeof o->laL); memset(o->laR, 0, sizeof o->laR);
  for (int i = 0; i < LA_RING; i++) o->laT[i] = 1.0f;
  o->dqH = o->dqT = o->laN = 0;
  o->lim = 1.0f;
  o->msM = o->msS = 0; o->wGain = o->wTarget = 1.0f;
  o->agcBlocks = 0;
  o->Ein = o->Eout = 0;
  o->inCount = 0;
  o->outCount = B;
  o->silentBlocks = 0; o->idle = 0; o->fadeIn = 0; o->outPeak = 0;
  memset(o->outFifo, 0, sizeof o->outFifo);
}

/* Deterministic test music stand-in: pink-ish noise, half common (centre) and half
   independent per side, plus a hard-left tone burst and a 50 Hz bass. About -18 dBFS RMS. */
typedef struct { uint32_t s; float p1, p2, p3; float ph, phb; long n; } TestGen;
static float lcg(uint32_t *s) { *s = *s * 1664525u + 1013904223u; return ((int32_t)*s) * (1.0f / 2147483648.0f); }
static void testgen(TestGen *g, int16_t *buf, int frames) {
  for (int i = 0; i < frames; i++, g->n++) {
    g->p1 += 0.05f * (lcg(&g->s) - g->p1);
    g->p2 += 0.05f * (lcg(&g->s) - g->p2);
    g->p3 += 0.05f * (lcg(&g->s) - g->p3);
    float tone = ((g->n / 24000) % 4 == 1) ? 0.08f * sinf(g->ph) : 0.0f;
    g->ph += 2 * PI_F * 660.0f / FS; if (g->ph > 2 * PI_F) g->ph -= 2 * PI_F;
    g->phb += 2 * PI_F * 50.0f / FS; if (g->phb > 2 * PI_F) g->phb -= 2 * PI_F;
    float bass = 0.06f * sinf(g->phb);
    float l = 0.9f * g->p1 + 0.9f * g->p2 + tone + bass, r = 0.9f * g->p1 + 0.9f * g->p3 + bass;
    buf[2 * i] = (int16_t)(l * 32767.0f);
    buf[2 * i + 1] = (int16_t)(r * 32767.0f);
  }
}

Own3D *own3d_create(void) {
  params_init();
  Own3D *o = (Own3D *)calloc(1, sizeof(Own3D));
  if (!o) return NULL;
  o->fft = pffft_new_setup(N, PFFFT_REAL);
  o->work = falloc(); o->tbuf = falloc(); o->XL = falloc(); o->XR = falloc();
  o->wwork = falloc(); o->wtbuf = falloc();
  for (int s = 0; s < NS; s++) {
    o->Y[s] = falloc();
    for (int p = 0; p < P; p++) {
      o->fdl[s][p] = falloc();
      for (int b = 0; b < 2; b++) { o->bank[b].H[s][0][p] = falloc(); o->bank[b].H[s][1][p] = falloc(); }
    }
  }
  o->acc[0] = falloc(); o->acc[1] = falloc();
  for (int i = 0; i < N; i++) o->win[i] = sqrtf(0.5f - 0.5f * cosf(2.0f * PI_F * i / N));
  for (int i = 0; i < NPARAM; i++) { o->param[i] = ptab[i].def; o->liveApplied[i] = -1e9f; }
  o->xoverHz = -1;
  o->pending = -1;
  for (int s = 0; s < NS; s++) o->spkGain[s] = db2a(SPK_LEVEL[s]);
  o->subGain = 1;
  o->agc = o->agcTarget = 1.0f;
  own3d_reset(o);
  return o;
}

void own3d_calibrate(Own3D *o) {
  if (o->nHrtf == 0) return;
  int16_t buf[2 * B], tmp[2 * B];
  TestGen g = {12345u, 0, 0, 0, 0, 0, 0};
  o->agcCal = 0;   /* unclamped while calibrating */
  own3d_reset(o);
  for (int k = 0; k < 300; k++) { testgen(&g, buf, B); own3d_process(o, buf, tmp, B, 0.3f); }
  o->agc = o->agcCal = o->agcTarget;
  own3d_reset(o);
}

void own3d_destroy(Own3D *o) {
  if (!o) return;
  pffft_aligned_free(o->work); pffft_aligned_free(o->tbuf); pffft_aligned_free(o->XL); pffft_aligned_free(o->XR);
  pffft_aligned_free(o->wwork); pffft_aligned_free(o->wtbuf);
  for (int s = 0; s < NS; s++) {
    pffft_aligned_free(o->Y[s]);
    for (int p = 0; p < P; p++) {
      pffft_aligned_free(o->fdl[s][p]);
      for (int b = 0; b < 2; b++) { pffft_aligned_free(o->bank[b].H[s][0][p]); pffft_aligned_free(o->bank[b].H[s][1][p]); }
    }
  }
  pffft_aligned_free(o->acc[0]); pffft_aligned_free(o->acc[1]);
  for (int i = 0; i < o->nHrtf; i++) { free(o->hrtf[i].vec); free(o->hrtf[i].ir); }
  pffft_destroy_setup(o->fft);
  free(o);
}

int own3d_hrtf_count(const Own3D *o) { return o->nHrtf; }
void own3d_set_param(Own3D *o, int id, float v) {
  if (id < 0 || id >= NPARAM) return;
  if (v < ptab[id].min) v = ptab[id].min;
  if (v > ptab[id].max) v = ptab[id].max;
  if (id == P_HRTF && o->nHrtf > 0 && v > o->nHrtf - 1) v = (float)(o->nHrtf - 1);
  o->param[id] = v;
}
float own3d_get_param(const Own3D *o, int id) { return id >= 0 && id < NPARAM ? o->param[id] : 0; }
float own3d_agc_gain(const Own3D *o) { return o->agc; }
int own3d_idle(const Own3D *o) { return o->idle; }
long own3d_idle_blocks(const Own3D *o) { return o->idleBlocks; }

double own3d_selftest(Own3D *o, char *report, int report_len, double seconds) {
  if (!o || o->nHrtf == 0) { snprintf(report, report_len, "own3d selftest: no HRTF loaded"); return -1; }
  int blocks = (int)(seconds * FS / B);
  int16_t in[2 * B], out[2 * B];
  TestGen g = {777u, 0, 0, 0, 0, 0, 0};
  double sumNs = 0, maxNs = 0, ein = 0, eout = 0, rebuildMs;
  int peak = 0, clipped = 0;
  long n = 0;
  int64_t t0 = own3d_now_ns();
  own3d_rebuild(o);
  rebuildMs = (own3d_now_ns() - t0) / 1e6;
  own3d_reset(o);
  for (int k = 0; k < blocks; k++) {
    testgen(&g, in, B);
    int64_t a = own3d_now_ns();
    own3d_process(o, in, out, B, 1.0f);
    double dt = (double)(own3d_now_ns() - a);
    sumNs += dt; if (dt > maxNs) maxNs = dt;
    if (k >= blocks / 2) {
      for (int i = 0; i < 2 * B; i++) {
        ein += (double)in[i] * in[i];
        eout += (double)out[i] * out[i];
        int v = abs(out[i]); if (v > peak) peak = v;
        if (v >= 32700) clipped++;
      }
      n += 2 * B;
    }
  }
  double avgMs = sumNs / blocks / 1e6;
  double rin = 10 * log10(ein / n / (32768.0 * 32768.0) + 1e-20);
  double rout = 10 * log10(eout / n / (32768.0 * 32768.0) + 1e-20);
  int hi = (int)o->param[P_HRTF];
  snprintf(report, report_len,
           "own3d selftest [%s]: %d blocks avg %.3f ms max %.3f ms per 10ms block (%.1f%% of one core), filter rebuild %.1f ms (worker), %d/%d filter partitions; "
           "rms in %.1f dBFS out %.1f dBFS (%+.1f dB), peak %.1f dBFS, clipped %d, agc %.2f",
           o->hrtf[hi < o->nHrtf ? hi : 0].name, blocks, avgMs, maxNs / 1e6, avgMs * 10.0, rebuildMs, o->bank[o->active].nParts, NS * 2 * P,
           rin, rout, rout - rin, 20 * log10(peak / 32768.0 + 1e-9), clipped, o->agc);
  own3d_reset(o);
  return avgMs;
}
