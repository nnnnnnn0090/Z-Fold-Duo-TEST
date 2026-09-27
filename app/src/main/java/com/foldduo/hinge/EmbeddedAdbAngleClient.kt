package com.foldduo.hinge

import android.os.SystemClock
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.stream.AdbStream
import java.io.Closeable

/** Reads Samsung's private folding-angle sensor through its system wallpaper engine. */
class EmbeddedAdbAngleClient(
    private val onStatus: (String) -> Unit,
    private val onAngle: (angle: Float, timestampNanos: Long) -> Unit,
) : Closeable {
    private val controlLock = Any()

    @Volatile
    private var session: Session? = null

    val isConnected: Boolean
        get() = session != null

    fun shell(command: String): Result<String> {
        val current = session
            ?: return Result.failure(IllegalStateException("ADB is not connected"))
        return synchronized(controlLock) {
            runCatching { current.control.shell(command).allOutput }
        }
    }

    fun connect(host: String, port: Int) {
        close()
        onStatus("内蔵ADB接続中")

        val adb = Kadb.create(host, port, connectTimeout = 5_000)
        var control: Kadb? = null
        var probe: AdbStream? = null
        var angle: AdbStream? = null
        var live: AdbStream? = null
        try {
            check(adb.shell("echo ZFoldDuo").allOutput.trim() == "ZFoldDuo")
            val controlAdb = Kadb.create(host, port, connectTimeout = 5_000)
            control = controlAdb
            check(controlAdb.shell("echo ZFoldDuo-control").allOutput.trim() == "ZFoldDuo-control")

            // FoldInteractive normally unsubscribes while the cover panel is active.
            // Its internal wake command re-enables the same private sensor without
            // changing either display's power or topology.
            controlAdb.shell(PRIVATE_SENSOR_WAKE_COMMAND)

            probe = adb.open("shell:$ANGLE_PROBE_COMMAND")
            angle = adb.open("shell:$ANGLE_LOG_COMMAND")
            live = adb.open("shell:$LIVE_CAPTURE_COMMAND")
            val started = Session(adb, controlAdb, probe, angle, live)
            session = started
            Thread({ watchProbe(started) }, "ZFoldDuo-private-probe").start()
            Thread({ readPrivateAngle(started) }, "ZFoldDuo-private-angle").start()
            Thread({ readLiveCapture(started) }, "ZFoldDuo-live-capture").start()
            onStatus("Samsung内部ヒンジ計測中")
        } catch (error: Throwable) {
            runCatching { probe?.close() }
            runCatching { angle?.close() }
            runCatching { live?.close() }
            runCatching { control?.close() }
            runCatching { adb.close() }
            throw error
        }
    }

    override fun close() {
        val current = session ?: return
        session = null
        runCatching { current.probe.close() }
        runCatching { current.angle.close() }
        runCatching { current.live.close() }
        runCatching { current.control.close() }
        runCatching { current.adb.close() }
    }

    private fun watchProbe(current: Session) {
        try {
            while (session === current) {
                current.probe.source.readUtf8Line() ?: error("private sensor probe stopped")
            }
        } catch (error: Throwable) {
            terminate(current, "内部ヒンジprobe切断: ${safeMessage(error)}")
        }
    }

    private fun readPrivateAngle(current: Session) {
        try {
            while (session === current) {
                val line = current.angle.source.readUtf8Line() ?: error("private angle stream stopped")
                if (line.contains(PRIVATE_SENSOR_STOPPED)) {
                    shell(PRIVATE_SENSOR_WAKE_COMMAND)
                    continue
                }
                val value = PRIVATE_ANGLE.find(line)?.groupValues?.get(1)?.toFloatOrNull() ?: continue
                val hinge = value.coerceIn(0f, 180f)
                onAngle(hinge, SystemClock.elapsedRealtimeNanos())
                onStatus("Samsung内部ヒンジ計測中")
            }
        } catch (error: Throwable) {
            terminate(current, "内部ヒンジ切断: ${safeMessage(error)}")
        }
    }

    private fun readLiveCapture(current: Session) {
        try {
            while (session === current) {
                current.live.source.readUtf8Line() ?: error("live capture stopped")
            }
        } catch (error: Throwable) {
            terminate(current, "画面キャプチャ切断: ${safeMessage(error)}")
        }
    }

    private fun terminate(current: Session, message: String) {
        if (session !== current) return
        session = null
        runCatching { current.probe.close() }
        runCatching { current.angle.close() }
        runCatching { current.live.close() }
        runCatching { current.control.close() }
        runCatching { current.adb.close() }
        onStatus(message)
    }

    private data class Session(
        val adb: Kadb,
        val control: Kadb,
        val probe: AdbStream,
        val angle: AdbStream,
        val live: AdbStream,
    )

    companion object {
        private const val ANGLE_ACTION = "zfoldduo_angle"
        private const val ANGLE_PROBE_COMMAND =
            "while :; do service call wallpaper 90 i32 5 s16 $ANGLE_ACTION >/dev/null; sleep 0.025; done"
        private const val PRIVATE_SENSOR_WAKE_COMMAND =
            "service call wallpaper 90 i32 5 s16 android.wallpaper.wakingup >/dev/null"
        private const val PRIVATE_SENSOR_STOPPED = "unregisterSensor: mIsSensorRegistered[true]"
        private const val ANGLE_LOG_COMMAND =
    "logcat -v brief -T 1 --regex='(mCurrentAngle|" +
        "unregisterSensor: mIsSensorRegistered\\[true\\])' '*:V'"
        private const val LIVE_CAPTURE_COMMAND =
            "CLASSPATH=${'$'}(pm path com.foldduo.hinge | head -n 1 | cut -d: -f2) " +
                "exec app_process /system/bin com.foldduo.hinge.capture.LiveCaptureBridge"
        private val PRIVATE_ANGLE =
    Regex("""mCurrentAngle[^0-9+\-]*([-+]?\d+(?:\.\d+)?)""")

        private fun safeMessage(error: Throwable): String =
            error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
    }
}
