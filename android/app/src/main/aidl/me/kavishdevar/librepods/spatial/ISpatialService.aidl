package me.kavishdevar.librepods.spatial;

import me.kavishdevar.librepods.spatial.ISpatialCallback;

/**
 * Spatial Audio renderer running as a Shizuku user service (shell, UID 2000).
 * Capture only runs while enabled, a selected app is playing and the output is Bluetooth.
 */
interface ISpatialService {
    /** token: binder owned by the app; the service stops everything when it dies. */
    void setClient(IBinder token, ISpatialCallback callback) = 1;
    /** mode: 0 NORMAL (plain stereo passthrough), 2 OWN 3D. */
    void configure(boolean enabled, int mode, in String[] packages) = 2;
    /** Full OWN 3D parameter vector, indexed by native id. */
    void setParams(in float[] values) = 3;
    void setParam(int id, float value) = 4;
    /** Periodic status pushes (CPU, levels, log) only while diagnostics are on. */
    void setDiagnostics(boolean on) = 5;
    String status() = 6;
    /** Offline render of a test signal on the current parameters (CPU per block). */
    String selfTest() = 7;
    void destroy() = 16777114;
}
