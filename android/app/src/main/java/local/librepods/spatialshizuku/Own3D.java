package local.librepods.spatialshizuku;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

/**
 * Java side of the OWN 3D native renderer (libown3d.so, app/src/main/cpp/own3d).
 *
 * The package name matches the standalone test app on purpose: own3d_jni.c binds to
 * {@code Java_local_librepods_spatialshizuku_Own3D_*}, so the native sources can be re-copied
 * from the test app unchanged.
 *
 * All tuning goes through numbered parameters (see {@link Param}); parameters that change the
 * binaural filters are rebuilt on a worker thread and crossfaded in.
 */
public final class Own3D implements AutoCloseable {
    /** Bundled HRIR sets (app/src/main/resources/own3d/*.bin, SADIE II, Apache-2.0), in "hrtf" order. */
    public static final String[] HRTF_FILES = {"D2", "D1", "H3", "H15"};
    public static final String[] HRTF_NAMES = {"KU100", "KEMAR", "Human H3", "Human H15"};

    static native long nCreate();
    static native void nDestroy(long h);
    static native int nAddHrtf(long h, String name, byte[] data);
    static native void nCalibrate(long h);
    static native void nRebuild(long h);
    static native void nSetParam(long h, int id, float v);
    static native void nReset(long h);
    static native float nAgc(long h);
    static native int nParamCount();
    static native String nParamInfo(int id);
    static native void nProcess(long h, short[] in, short[] out, int frames, float gain);
    static native String nSelfTest(long h, double seconds);

    private static volatile boolean loaded;

    /**
     * Loads libown3d.so. In the app process System.loadLibrary is enough; inside the Shizuku user
     * service the class loader may not carry the app's library path, so fall back to the extracted
     * library directory and then to the (uncompressed) copy inside the APK.
     */
    public static synchronized void load(String nativeLibraryDir, String apkPath, String abi) {
        if (loaded) return;
        Throwable first = null;
        try {
            System.loadLibrary("own3d");
            loaded = true;
            return;
        } catch (Throwable t) {
            first = t;
        }
        if (nativeLibraryDir != null) {
            File f = new File(nativeLibraryDir, "libown3d.so");
            if (f.exists()) {
                try {
                    System.load(f.getAbsolutePath());
                    loaded = true;
                    return;
                } catch (Throwable ignored) {
                }
            }
        }
        if (apkPath != null && abi != null) {
            try {
                System.load(apkPath + "!/lib/" + abi + "/libown3d.so");
                loaded = true;
                return;
            } catch (Throwable ignored) {
            }
        }
        throw new UnsatisfiedLinkError("libown3d.so not loadable: " + first);
    }

    public static boolean isLoaded() {
        return loaded;
    }

    /** Parameter metadata from the native table (needs the native library loaded). */
    public static final class Param {
        public final int id;
        public final String key, label, group;
        public final float min, max, def, step;
        public final boolean rebuild;

        Param(int id, String info) {
            String[] f = info.split("\\|");
            this.id = id;
            key = f[0];
            label = f[1];
            group = f[2];
            min = Float.parseFloat(f[3]);
            max = Float.parseFloat(f[4]);
            def = Float.parseFloat(f[5]);
            step = Float.parseFloat(f[6]);
            rebuild = "1".equals(f[7]);
        }

        public boolean isSwitch() {
            return min == 0 && max == 1 && step == 1;
        }
    }

    private static Param[] params;

    public static synchronized Param[] params() {
        if (params == null) {
            int n = nParamCount();
            params = new Param[n];
            for (int i = 0; i < n; i++) params[i] = new Param(i, nParamInfo(i));
        }
        return params;
    }

    public static float[] defaults() {
        Param[] p = params();
        float[] v = new float[p.length];
        for (int i = 0; i < p.length; i++) v[i] = p[i].def;
        return v;
    }

    public static int find(String key) {
        for (Param p : params()) if (p.key.equals(key)) return p.id;
        return -1;
    }

    private long h;
    public final int hrtfs;
    private final Thread builder;
    private boolean rebuildWanted, closing;

    public Own3D() {
        h = nCreate();
        if (h == 0) throw new IllegalStateException("own3d create failed");
        int n = 0;
        for (String f : HRTF_FILES) {
            byte[] data = resource("own3d/" + f + ".bin");
            if (data == null || nAddHrtf(h, f, data) < 0) {
                nDestroy(h);
                h = 0;
                throw new IllegalStateException("HRTF " + f + " missing or invalid");
            }
            n++;
        }
        hrtfs = n;
        nCalibrate(h);
        builder = new Thread(() -> {
            while (true) {
                synchronized (this) {
                    while (!rebuildWanted && !closing) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    if (closing) return;
                    rebuildWanted = false;
                }
                nRebuild(h); // builds into the spare bank; the render thread crossfades
            }
        }, "own3d-builder");
        builder.setDaemon(true);
        builder.start();
    }

    private static byte[] resource(String path) {
        try (InputStream in = Own3D.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** Sets a parameter; rebuild-class parameters schedule a filter rebuild (coalesced). */
    public void setParam(int id, float v) {
        nSetParam(h, id, v);
        if (params()[id].rebuild) requestRebuild();
    }

    synchronized void requestRebuild() {
        rebuildWanted = true;
        notifyAll();
    }

    /** Applies a full parameter vector and rebuilds once, synchronously (call before playback). */
    public void setAll(float[] v) {
        for (int i = 0; i < v.length && i < params().length; i++) nSetParam(h, i, v[i]);
        nRebuild(h);
    }

    public void reset() {
        nReset(h);
    }

    public float agc() {
        return nAgc(h);
    }

    public String selfTest(double seconds) {
        return nSelfTest(h, seconds);
    }

    /** in/out: interleaved stereo s16, frames = shorts/2. Adds a fixed ~22 ms latency. */
    public void process(short[] in, short[] out, int frames, float gain) {
        nProcess(h, in, out, frames, gain);
    }

    @Override
    public void close() {
        synchronized (this) {
            closing = true;
            notifyAll();
        }
        try {
            builder.join(1000);
        } catch (InterruptedException ignored) {
        }
        if (h != 0) {
            nDestroy(h);
            h = 0;
        }
    }
}
