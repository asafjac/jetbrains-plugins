package com.asafjac.jbplugins.demodriver

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfo
import java.awt.Point
import java.awt.Rectangle
import java.io.File
import java.util.concurrent.TimeUnit

/** One sampled viewport centre, for a [Crop.Follow] track. */
data class FollowSample(val atMs: Long, val point: Point)

/**
 * Records the screen with ffmpeg while a tape runs, then converts to whatever the tape asked
 * for.
 *
 * ffmpeg rather than an in-process encoder: capturing and encoding video well is a large
 * problem ffmpeg has already solved, and shelling out keeps the plugin small enough to stay
 * honest about what it does.
 */
class Capture(
    private val settings: TapeSettings,
    private val projectRoot: File,
) {

    private var process: Process? = null
    private val master = File.createTempFile("demo-driver-", ".mp4").also { it.delete() }
    private var region: Rectangle = Rectangle()

    /** Regions painted over in the final render, in screen coordinates. */
    var redactions: List<Rectangle> = emptyList()

    val isRecording: Boolean get() = process?.isAlive == true

    fun start(region: Rectangle) {
        this.region = even(region)
        val r = this.region

        val input = when {
            SystemInfo.isWindows -> listOf(
                "-f", "gdigrab",
                "-framerate", CAPTURE_FPS,
                "-draw_mouse", if (settings.cursor) "1" else "0",
                "-offset_x", r.x.toString(),
                "-offset_y", r.y.toString(),
                "-video_size", "${r.width}x${r.height}",
                "-i", "desktop",
            )
            SystemInfo.isLinux -> listOf(
                "-f", "x11grab",
                "-framerate", CAPTURE_FPS,
                "-draw_mouse", if (settings.cursor) "1" else "0",
                "-video_size", "${r.width}x${r.height}",
                "-i", ":0.0+${r.x},${r.y}",
            )
            SystemInfo.isMac -> listOf(
                "-f", "avfoundation",
                "-capture_cursor", if (settings.cursor) "1" else "0",
                "-framerate", CAPTURE_FPS,
                "-i", "1:none",
            )
            else -> error("screen capture is not wired up for this OS")
        }

        // avfoundation cannot offset the grab, so the region is trimmed after the fact.
        val macCrop = if (SystemInfo.isMac) {
            listOf("-vf", "crop=${r.width}:${r.height}:${r.x}:${r.y}")
        } else emptyList()

        val command = listOf(settings.ffmpeg, "-hide_banner", "-loglevel", "error") + input + macCrop +
            listOf("-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "veryfast",
                "-y", master.absolutePath)

        thisLogger().info("demo-driver capture: ${command.joinToString(" ")}")
        process = ProcessBuilder(command).redirectErrorStream(true).start()
    }

    /**
     * Stops the recorder and writes every requested output.
     *
     * ffmpeg is asked to quit via `q` on stdin rather than being killed: a killed process
     * leaves the mp4 without its moov atom, so the file exists and is unplayable.
     */
    fun finish(track: List<FollowSample> = emptyList()): List<File> {
        val running = process ?: return emptyList()
        runCatching {
            running.outputStream.write('q'.code)
            running.outputStream.flush()
        }
        if (!running.waitFor(20, TimeUnit.SECONDS)) running.destroy()
        process = null

        if (!master.exists() || master.length() == 0L) return emptyList()

        val written = settings.outputs.map { out ->
            val target = File(out).let { if (it.isAbsolute) it else File(projectRoot, out) }
            target.parentFile?.mkdirs()
            if (target.extension.lowercase() == "gif") writeGif(target, track) else writeMp4(target, track)
            target
        }
        master.delete()
        return written
    }

    /**
     * The filter chain shared by both outputs: redaction, then the moving viewport, then scale.
     *
     * Order matters. Redaction is in capture coordinates so it must be applied before any crop
     * moves the frame of reference, and scaling comes last so the viewport is expressed in real
     * pixels rather than in output pixels.
     */
    private fun videoFilter(track: List<FollowSample>): String {
        val stages = mutableListOf<String>()

        redactions.forEach { box ->
            val local = Rectangle(box.x - region.x, box.y - region.y, box.width, box.height)
                .intersection(Rectangle(0, 0, region.width, region.height))
            if (!local.isEmpty) {
                // A solid fill rather than a blur: a blur of text can sometimes be read back,
                // and the point of redacting a tool window is that it cannot be.
                stages += "drawbox=x=${local.x}:y=${local.y}:w=${local.width}:h=${local.height}" +
                    ":color=black@1.0:t=fill"
            }
        }

        val follow = settings.crop as? Crop.Follow
        if (follow != null && track.isNotEmpty()) {
            stages += followCrop(follow, track)
        }

        stages += "fps=${settings.framerate}"
        stages += "scale=${settings.width}:-1:flags=lanczos"
        return stages.joinToString(",")
    }

    /**
     * A crop whose origin is a piecewise-linear function of time, built from the sampled track.
     *
     * ffmpeg's crop accepts expressions in `t`, so the viewport can be moved without a second
     * encode. Successive samples are interpolated with `lerp` so the motion is continuous
     * rather than snapping between sample points once every tenth of a second.
     */
    private fun followCrop(follow: Crop.Follow, track: List<FollowSample>): String {
        val w = follow.width.coerceAtMost(region.width) / 2 * 2
        val h = follow.height.coerceAtMost(region.height) / 2 * 2
        val maxX = (region.width - w).coerceAtLeast(0)
        val maxY = (region.height - h).coerceAtLeast(0)

        fun clamp(value: Int, limit: Int) = value.coerceIn(0, limit)

        // Thin the track to the output framerate: one keyframe per output frame is plenty, and
        // an expression with hundreds of nested branches gets slow for ffmpeg to evaluate.
        val minGap = (1000 / settings.framerate).toLong()
        val keys = mutableListOf<FollowSample>()
        track.forEach { sample ->
            if (keys.isEmpty() || sample.atMs - keys.last().atMs >= minGap) keys += sample
        }
        if (keys.size < 2) {
            val only = keys.firstOrNull() ?: return "crop=$w:$h:0:0"
            val x = clamp(only.point.x - region.x - w / 2, maxX)
            val y = clamp(only.point.y - region.y - h / 2, maxY)
            return "crop=$w:$h:$x:$y"
        }

        fun expr(pick: (FollowSample) -> Int): String {
            // Built back to front so each branch nests inside the previous one's else.
            var acc = pick(keys.last()).toString()
            for (i in keys.size - 2 downTo 0) {
                val t0 = keys[i].atMs / 1000.0
                val t1 = keys[i + 1].atMs / 1000.0
                val v0 = pick(keys[i])
                val v1 = pick(keys[i + 1])
                val span = (t1 - t0).coerceAtLeast(0.001)
                val lerp = "($v0+($v1-$v0)*(t-%.3f)/%.3f)".format(t0, span)
                acc = "if(lt(t,%.3f),%s,%s)".format(t1, lerp, acc)
            }
            return acc
        }

        val xs = expr { clamp(it.point.x - region.x - w / 2, maxX) }
        val ys = expr { clamp(it.point.y - region.y - h / 2, maxY) }
        // Colons and commas inside an expression have to be escaped or ffmpeg reads them as
        // filter-option and filter separators.
        return "crop=$w:$h:'${escape(xs)}':'${escape(ys)}'"
    }

    private fun escape(expression: String) = expression.replace(",", "\\,")

    /**
     * Two passes, because a single-pass GIF uses the fixed 216-colour web palette and turns an
     * editor's syntax colours into mud. palettegen samples the actual frames first.
     */
    private fun writeGif(target: File, track: List<FollowSample>) {
        val filter = videoFilter(track)
        val palette = File.createTempFile("demo-driver-pal-", ".png")
        run(listOf(
            settings.ffmpeg, "-hide_banner", "-loglevel", "error", "-i", master.absolutePath,
            "-vf", "$filter,palettegen=max_colors=128:stats_mode=diff",
            "-y", palette.absolutePath,
        ))
        run(listOf(
            settings.ffmpeg, "-hide_banner", "-loglevel", "error",
            "-i", master.absolutePath, "-i", palette.absolutePath,
            "-lavfi", "$filter[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle",
            "-y", target.absolutePath,
        ))
        palette.delete()
    }

    private fun writeMp4(target: File, track: List<FollowSample>) = run(listOf(
        settings.ffmpeg, "-hide_banner", "-loglevel", "error", "-i", master.absolutePath,
        "-vf", videoFilter(track), "-c:v", "libx264", "-pix_fmt", "yuv420p",
        "-crf", "23", "-preset", "slow", "-y", target.absolutePath,
    ))

    private fun run(command: List<String>) {
        thisLogger().info("demo-driver render: ${command.joinToString(" ")}")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(180, TimeUnit.SECONDS)) {
            process.destroy()
            error("ffmpeg timed out")
        }
        if (process.exitValue() != 0) error("ffmpeg failed: ${output.take(600)}")
    }

    fun abort() {
        process?.destroy()
        process = null
        master.delete()
    }

    /**
     * yuv420p subsamples by two, so an odd width or height makes libx264 fail - after the whole
     * take has already been performed.
     */
    private fun even(r: Rectangle) = Rectangle(r.x, r.y, r.width / 2 * 2, r.height / 2 * 2)

    private companion object {
        /** Captured high and thinned later, so slow output framerates still track motion. */
        const val CAPTURE_FPS = "20"
    }
}
