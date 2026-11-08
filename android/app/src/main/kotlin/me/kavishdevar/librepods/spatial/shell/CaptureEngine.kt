package me.kavishdevar.librepods.spatial.shell

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioRouting
import android.media.AudioTrack
import android.os.Handler
import android.os.Process
import local.librepods.spatialshizuku.Own3D
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.tanh

/**
 * One capture session: a temporary shell-owned AudioPolicy loops the selected apps' media back
 * to us (one AudioMix, one RULE_MATCH_UID per app: UID rules are OR'd with each other and AND'd
 * with the usage rule, see AudioPolicyMix.cpp areMixCriteriaMatched), and a plain stereo
 * AudioTrack that no platform spatializer touches plays the result.
 *
 * The policy is registered in [run] and always unregistered in its finally block.
 * Ported from the standalone test app's Engine (fixed mode, NORMAL / OWN 3D only).
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "MissingPermission")
internal class CaptureEngine(
    private val ctx: Context,
    val uids: IntArray,
    private val own: Own3D?,
    private val handler: Handler,
    private val listener: Listener,
) {
    interface Listener {
        /** Rendering started / paused on silence / resumed. */
        fun onSilenceChanged(silent: Boolean)
        /** The render track left Bluetooth (e.g. AirPods removed): stop at once. */
        fun onRouteLost(device: String)
        /** Every ~2 s while diagnostics are on. */
        fun onStats(stats: Stats)
        fun log(line: String)
    }

    data class Stats(val avgMsPerBlock: Double, val maxMs: Double, val peak: Int, val routed: String, val agc: Float)

    @Volatile var cancelled = false
    @Volatile var mode = MODE_OWN
    @Volatile var diagnostics = false

    private var thread: Thread? = null

    fun start(onEnded: (Throwable?) -> Unit) {
        thread = Thread({
            var error: Throwable? = null
            try {
                run()
            } catch (t: Throwable) {
                error = t
            }
            onEnded(error)
        }, "spatial-render").also { it.start() }
    }

    fun stop(joinMs: Long) {
        cancelled = true
        val t = thread ?: return
        if (t !== Thread.currentThread()) try { t.join(joinMs) } catch (_: InterruptedException) {}
    }

    private fun call(o: Any, name: String, types: Array<Class<*>>, vararg args: Any?): Any? =
        o.javaClass.getMethod(name, *types).invoke(o, *args)

    private fun run() {
        val rule = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val mix = Class.forName("android.media.audiopolicy.AudioMix")
        val policy = Class.forName("android.media.audiopolicy.AudioPolicy")
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val rb = Class.forName(rule.name + "\$Builder").getConstructor().newInstance()
        val addRule = arrayOf<Class<*>>(Int::class.javaPrimitiveType!!, Any::class.java)
        call(rb, "addMixRule", addRule, rule.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null), attrs)
        val uidRule = rule.getField("RULE_MATCH_UID").getInt(null)
        for (uid in uids) call(rb, "addMixRule", addRule, uidRule, Integer.valueOf(uid))
        val r = call(rb, "build", arrayOf())
        val stereo = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val mb = Class.forName(mix.name + "\$Builder").getConstructor(rule).newInstance(r)
        call(mb, "setFormat", arrayOf(AudioFormat::class.java), stereo)
        call(mb, "setRouteFlags", arrayOf(Int::class.javaPrimitiveType!!), ROUTE_FLAG_LOOP_BACK)
        val m = call(mb, "build", arrayOf())!!
        val pb = Class.forName(policy.name + "\$Builder").getConstructor(Context::class.java).newInstance(ctx)
        call(pb, "addMix", arrayOf(mix), m)
        val p = call(pb, "build", arrayOf())!!
        val register = AudioManager::class.java.getDeclaredMethod("registerAudioPolicyStatic", policy)
        register.isAccessible = true
        val result = register.invoke(null, p) as Int
        if (result != 0) throw IllegalStateException("registerAudioPolicy=$result")
        listener.log("policy registered for ${uids.size} app(s)")

        var rec: AudioRecord? = null
        var out: AudioTrack? = null
        var capture: Thread? = null
        try {
            rec = call(p, "createAudioRecordSink", arrayOf(mix), m) as AudioRecord?
                ?: throw IllegalStateException("null record sink")
            // NEVER: no platform spatializer may touch our (already binaural) output.
            val flatAttrs = AudioAttributes.Builder(attrs)
                .setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER)
                .build()
            out = AudioTrack.Builder()
                .setAudioAttributes(flatAttrs)
                .setAudioFormat(stereo)
                .setBufferSizeInBytes(7680)
                .build()
            val track = out
            val routing = AudioRouting.OnRoutingChangedListener { router ->
                val dev = router.routedDevice ?: return@OnRoutingChangedListener
                if (!isBluetooth(dev.type)) listener.onRouteLost(describe(dev))
            }
            track.addOnRoutingChangedListener(routing, handler)
            rec.startRecording()

            // Capture and render run on separate threads so a full render track never stalls the
            // capture buffer. The queue is shallow and drops the OLDEST chunk when full.
            val queue = ArrayBlockingQueue<ShortArray>(6)
            val recorder = rec
            capture = Thread({
                audioPriority()
                val pool = Array(16) { ShortArray(960) }
                var pi = 0
                try {
                    while (!cancelled) {
                        val buf = pool[pi]
                        val n = recorder.read(buf, 0, buf.size) // blocking
                        if (n <= 0) {
                            if (n < 0) break
                            continue
                        }
                        val chunk = if (n == buf.size) buf else buf.copyOf(n)
                        if (n == buf.size) pi = (pi + 1) and 15
                        if (!queue.offer(chunk)) { queue.poll(); queue.offer(chunk) }
                    }
                } catch (t: Throwable) {
                    if (!cancelled) listener.log("capture: $t")
                }
            }, "spatial-capture").apply { priority = Thread.MAX_PRIORITY; start() }

            audioPriority()
            val output = ShortArray(960)
            var playingMode = -1
            var silent = true            // starts paused: play() on the first non-silent block
            var zeroFrames = 0L
            var statNs = 0L; var statMaxNs = 0L; var statFrames = 0L; var peak = 0
            var lastStats = System.nanoTime()
            listener.onSilenceChanged(true)
            while (!cancelled) {
                val input = queue.poll(50, TimeUnit.MILLISECONDS) ?: continue
                val n = input.size
                val frames = n / 2
                var blockPeak = 0
                for (i in 0 until n) { val a = abs(input[i].toInt()); if (a > blockPeak) blockPeak = a }

                // Silence gate: after ~1 s of digital silence stop writing and pause the track,
                // resume on the first block with signal.
                if (blockPeak == 0) {
                    zeroFrames += frames
                    if (!silent && zeroFrames >= SAMPLE_RATE) {
                        silent = true
                        track.pause(); track.flush()
                        listener.onSilenceChanged(true)
                    }
                    if (silent) continue
                } else {
                    zeroFrames = 0
                    if (silent) {
                        silent = false
                        own?.reset()
                        track.play()
                        listener.onSilenceChanged(false)
                    }
                }

                val want = mode
                if (want != playingMode) {
                    if (want == MODE_OWN) own?.reset()
                    playingMode = want
                }
                if (playingMode == MODE_OWN && own != null) {
                    val t0 = System.nanoTime()
                    own.process(input, output, frames, 1f)
                    val dt = System.nanoTime() - t0
                    statNs += dt; statFrames += frames; if (dt > statMaxNs) statMaxNs = dt
                } else {
                    for (i in 0 until n) output[i] = clip(input[i].toFloat())
                }
                if (blockPeak > peak) peak = blockPeak
                var written = 0
                while (!cancelled && written < n) {
                    val w = track.write(output, written, n - written, AudioTrack.WRITE_BLOCKING)
                    if (w <= 0) break
                    written += w
                }

                if (diagnostics) {
                    val now = System.nanoTime()
                    if (now - lastStats >= 2_000_000_000L) {
                        val avgMs = if (statFrames > 0) statNs / 1e6 * BLOCK / statFrames else 0.0
                        listener.onStats(Stats(avgMs, statMaxNs / 1e6, peak, track.routedDevice?.let(::describe) ?: "-", own?.agc() ?: 1f))
                        statNs = 0; statMaxNs = 0; statFrames = 0; peak = 0; lastStats = now
                    }
                }
            }
            track.removeOnRoutingChangedListener(routing)
        } finally {
            cancelled = true
            try { rec?.stop() } catch (_: Throwable) {}
            try { capture?.join(500) } catch (_: InterruptedException) {}
            try { out?.release() } catch (_: Throwable) {}
            try { rec?.release() } catch (_: Throwable) {}
            val unregister = AudioManager::class.java.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policy)
            unregister.isAccessible = true
            unregister.invoke(null, p)
            listener.log("policy removed")
        }
    }

    private fun audioPriority() {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        } catch (_: Throwable) {
            try { Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO) } catch (_: Throwable) {}
        }
    }

    companion object {
        const val MODE_NORMAL = 0
        const val MODE_OWN = 2
        const val SAMPLE_RATE = 48000
        const val BLOCK = 480 // 10 ms
        private const val ROUTE_FLAG_LOOP_BACK = 2

        fun isBluetooth(type: Int) = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
            type == AudioDeviceInfo.TYPE_BLE_BROADCAST

        fun describe(d: AudioDeviceInfo) = "${d.productName} (type ${d.type})"

        /** Soft-knee limiter: linear to 0.7 FS, then tanh-shaped into 1.0 FS. */
        fun clip(v: Float): Short {
            var x = v
            var a = abs(x) / 32767f
            if (a > 0.7f) {
                a = 0.7f + 0.3f * tanh((a - 0.7f) / 0.3f)
                x = Math.signum(x) * a * 32767f
            }
            return max(-32767f, minOf(32767f, x)).toInt().toShort()
        }
    }
}
