package com.asafjac.jbplugins.demodriver

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.util.SystemInfo
import java.awt.Rectangle
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Records the screen with ffmpeg while a tape runs, then converts to whatever the tape asked
 * for.
 *
 * ffmpeg rather than an in-process encoder: capturing and encoding video well is a large
 * problem that ffmpeg has already solved, and shelling out keeps the plugin small enough to
 * stay honest about what it does.
 */
class Capture(
    private val settings: TapeSettings,
    private val projectRoot: File,
) {

    private var process: Process? = null
    private val master = File.createTempFile("demo-driver-", ".mp4").also { it.delete() }

    val isRecording: Boolean get() = process?.isAlive == true

    fun start(region: Rectangle) {
        // Even width and height: yuv420p subsamples by two, and an odd dimension makes
        // libx264 fail with "width not divisible by 2" after the whole take is finished.
        val w = region.width / 2 * 2
        val h = region.height / 2 * 2

        val input = when {
            SystemInfo.isWindows -> listOf(
                "-f", "gdigrab",
                "-framerate", "20",
                "-draw_mouse", "1",
                "-offset_x", region.x.toString(),
                "-offset_y", region.y.toString(),
                "-video_size", "${w}x$h",
                "-i", "desktop",
            )
            SystemInfo.isLinux -> listOf(
                "-f", "x11grab",
                "-framerate", "20",
                "-video_size", "${w}x$h",
                "-i", ":0.0+${region.x},${region.y}",
            )
            SystemInfo.isMac -> listOf(
                "-f", "avfoundation",
                "-framerate", "20",
                "-i", "1:none",
                // avfoundation cannot offset the grab, so the region is cropped afterwards.
                "-vf", "crop=$w:$h:${region.x}:${region.y}",
            )
            else -> error("screen capture is not wired up for this OS")
        }

        val command = listOf(settings.ffmpeg, "-hide_banner", "-loglevel", "error") +
            input +
            listOf("-c:v", "libx264", "-pix_fmt", "yuv420p", "-preset", "veryfast", "-y", master.absolutePath)

        thisLogger().info("demo-driver capture: ${command.joinToString(" ")}")
        process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
    }

    /**
     * Stops the recorder and writes every requested output.
     *
     * ffmpeg is asked to quit via `q` on stdin rather than being killed: a killed process
     * leaves the mp4 without its moov atom, so the file exists and is unplayable.
     */
    fun finish(): List<File> {
        val running = process ?: return emptyList()
        runCatching {
            running.outputStream.write('q'.code)
            running.outputStream.flush()
        }
        if (!running.waitFor(15, TimeUnit.SECONDS)) running.destroy()
        process = null

        if (!master.exists() || master.length() == 0L) return emptyList()

        val written = settings.outputs.map { out ->
            val target = File(out).let { if (it.isAbsolute) it else File(projectRoot, out) }
            target.parentFile?.mkdirs()
            if (target.extension.lowercase() == "gif") writeGif(target) else writeMp4(target)
            target
        }
        master.delete()
        return written
    }

    private fun scale() = "fps=${settings.framerate},scale=${settings.width}:-1:flags=lanczos"

    /**
     * Two passes, because a single-pass GIF uses the fixed 216-colour web palette and turns
     * an editor's syntax colours into mud. palettegen samples the actual frames first.
     */
    private fun writeGif(target: File) {
        val palette = File.createTempFile("demo-driver-pal-", ".png")
        run(listOf(
            settings.ffmpeg, "-hide_banner", "-loglevel", "error", "-i", master.absolutePath,
            "-vf", "${scale()},palettegen=max_colors=128:stats_mode=diff",
            "-y", palette.absolutePath,
        ))
        run(listOf(
            settings.ffmpeg, "-hide_banner", "-loglevel", "error",
            "-i", master.absolutePath, "-i", palette.absolutePath,
            "-lavfi", "${scale()}[x];[x][1:v]paletteuse=dither=bayer:bayer_scale=3:diff_mode=rectangle",
            "-y", target.absolutePath,
        ))
        palette.delete()
    }

    private fun writeMp4(target: File) = run(listOf(
        settings.ffmpeg, "-hide_banner", "-loglevel", "error", "-i", master.absolutePath,
        "-vf", scale(), "-c:v", "libx264", "-pix_fmt", "yuv420p", "-crf", "23", "-preset", "slow",
        "-y", target.absolutePath,
    ))

    private fun run(command: List<String>) {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(120, TimeUnit.SECONDS)) {
            process.destroy()
            error("ffmpeg timed out: ${command.joinToString(" ")}")
        }
        if (process.exitValue() != 0) error("ffmpeg failed: $output")
    }

    fun abort() {
        process?.destroy()
        process = null
        master.delete()
    }
}
