package com.asafjac.jbplugins.demodriver

import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.system.CpuArch
import com.intellij.util.io.Decompressor
import java.io.File
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Finds ffmpeg, and fetches one if the machine has none.
 *
 * A recorder that requires the user to install a command-line tool first is a recorder most
 * people never get working, so this looks in the obvious places and offers to download a build
 * into the IDE's own system directory. Nothing is fetched without asking: the download is an
 * executable from the internet, and that is the user's decision to make, with the URL in front
 * of them.
 */
object Ffmpeg {

    /** Where a downloaded build lives, kept out of the project so it is never committed. */
    private val home: File get() = File(PathManager.getSystemPath(), "demo-driver/ffmpeg")

    /**
     * A usable ffmpeg, or null.
     *
     * [configured] is whatever the tape asked for and always wins, so a machine with several
     * builds can be pinned to one.
     */
    fun locate(configured: String = "ffmpeg"): String? {
        if (configured != "ffmpeg" && works(configured)) return configured

        val cached = downloadedBinary()
        if (cached != null && works(cached.absolutePath)) return cached.absolutePath

        if (works("ffmpeg")) return "ffmpeg"

        return candidates().firstOrNull { works(it) }
    }

    /**
     * Ensures ffmpeg is available, downloading it if the user agrees.
     *
     * Returns null when it is still unavailable, having already explained why, so callers can
     * simply stop rather than construct their own error.
     */
    fun ensure(project: Project, configured: String = "ffmpeg"): String? {
        locate(configured)?.let { return it }

        val source = source() ?: run {
            Messages.showErrorDialog(
                project,
                "No ffmpeg found, and no download is wired up for this OS.\n\n" +
                    "Install ffmpeg and either put it on PATH or add this to the tape:\n" +
                    "    Set Ffmpeg \"/path/to/ffmpeg\"",
                "Demo Driver",
            )
            return null
        }

        val answer = Messages.showYesNoDialog(
            project,
            "Demo Driver needs ffmpeg to record, and none was found on this machine.\n\n" +
                "Download it now?\n\n" +
                "From: ${source.url}\n" +
                "Into: ${home.absolutePath}\n" +
                "Size: about ${source.approxMb} MB\n\n" +
                "Nothing outside that folder is touched. If you would rather install it " +
                "yourself, add to the tape:\n    Set Ffmpeg \"/path/to/ffmpeg\"",
            "Download ffmpeg?",
            "Download",
            "Cancel",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return null

        var result: String? = null
        ProgressManager.getInstance().run(
            object : Task.WithResult<String?, Exception>(project, "Downloading ffmpeg", true) {
                override fun compute(indicator: ProgressIndicator): String? = download(source, indicator)
            }.also { task ->
                // Task.WithResult runs synchronously under the progress dialog, so the value is
                // available immediately afterwards.
                result = runCatching { task.queue(); task.result }.getOrElse { failure ->
                    Messages.showErrorDialog(
                        project,
                        "Could not download ffmpeg: ${failure.message}\n\n" +
                            "Install it yourself and add to the tape:\n    Set Ffmpeg \"/path/to/ffmpeg\"",
                        "Demo Driver",
                    )
                    null
                }
            })
        return result
    }

    private fun download(source: Source, indicator: ProgressIndicator): String? {
        indicator.isIndeterminate = false
        home.mkdirs()
        val archive = File(home, "download." + source.extension)

        indicator.text = "Fetching ${source.url}"
        URI(source.url).toURL().openConnection().apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", "demo-driver-plugin")
        }.getInputStream().use { input ->
            archive.outputStream().use { output ->
                val buffer = ByteArray(1 shl 16)
                var total = 0L
                val expected = source.approxMb * 1024L * 1024L
                while (true) {
                    indicator.checkCanceled()
                    val read = input.read(buffer)
                    if (read < 0) break
                    output.write(buffer, 0, read)
                    total += read
                    indicator.fraction = (total.toDouble() / expected).coerceIn(0.0, 0.99)
                }
            }
        }

        indicator.text = "Extracting"
        indicator.isIndeterminate = true
        val into = File(home, "unpacked").apply { deleteRecursively(); mkdirs() }
        // Both take a Path, not a File.
        when (source.extension) {
            "zip" -> Decompressor.Zip(archive.toPath()).extract(into.toPath())
            else -> Decompressor.Tar(archive.toPath()).extract(into.toPath())
        }
        archive.delete()

        val binary = downloadedBinary()
            ?: error("the archive did not contain an ffmpeg executable")
        if (!SystemInfo.isWindows) binary.setExecutable(true)

        if (!works(binary.absolutePath)) error("the downloaded ffmpeg would not run")
        thisLogger().info("demo-driver: provisioned ffmpeg at ${binary.absolutePath}")
        return binary.absolutePath
    }

    /** Archives nest the binary under a versioned folder, so it has to be searched for. */
    private fun downloadedBinary(): File? {
        val name = if (SystemInfo.isWindows) "ffmpeg.exe" else "ffmpeg"
        val root = File(home, "unpacked")
        if (!root.isDirectory) return null
        return root.walkTopDown().maxDepth(6).firstOrNull { it.isFile && it.name == name }
    }

    private fun candidates(): List<String> = when {
        SystemInfo.isWindows -> listOf(
            "C:\\ffmpeg\\bin\\ffmpeg.exe",
            System.getenv("LOCALAPPDATA")?.plus("\\Microsoft\\WinGet\\Links\\ffmpeg.exe"),
            System.getenv("ProgramFiles")?.plus("\\ffmpeg\\bin\\ffmpeg.exe"),
        ).filterNotNull()
        SystemInfo.isMac -> listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg")
        else -> listOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/snap/bin/ffmpeg")
    }

    /** Runs `-version`, because a path existing is not the same as a binary that works. */
    private fun works(path: String): Boolean = runCatching {
        val process = ProcessBuilder(path, "-hide_banner", "-version")
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroy()
            return false
        }
        process.exitValue() == 0
    }.getOrDefault(false)

    private data class Source(val url: String, val extension: String, val approxMb: Int)

    /**
     * Well-known static builds, one per OS.
     *
     * These are the builds the ffmpeg project itself points at for each platform. A URL going
     * stale surfaces as a plain download failure whose message names the manual alternative,
     * rather than as a silent misconfiguration.
     */
    private fun source(): Source? = when {
        SystemInfo.isWindows -> Source(
            "https://www.gyan.dev/ffmpeg/builds/ffmpeg-release-essentials.zip", "zip", 90)
        SystemInfo.isLinux && CpuArch.isArm64() -> Source(
            "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-arm64-static.tar.xz", "tar.xz", 40)
        SystemInfo.isLinux -> Source(
            "https://johnvansickle.com/ffmpeg/releases/ffmpeg-release-amd64-static.tar.xz", "tar.xz", 40)
        else -> null
    }
}
