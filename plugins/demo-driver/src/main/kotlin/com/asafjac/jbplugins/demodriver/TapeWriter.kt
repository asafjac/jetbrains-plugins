package com.asafjac.jbplugins.demodriver

/**
 * Writes settings back into tape text.
 *
 * This is what makes the panel and the file two views of one thing rather than two sources of
 * truth. Without it a control could only ever be a runtime override, and a tape written by hand
 * or by a script would be the second-class input; with it, every control is literally an edit to
 * the file, and the file remains the only state there is.
 *
 * Steps, comments, blank lines and ordering are preserved. Only the managed `Set` and `Output`
 * lines are rewritten, and they are put back where the first of them was, so a tape keeps its
 * shape instead of being reformatted out from under whoever wrote it.
 */
object TapeWriter {

    private val MANAGED_SETTINGS = setOf(
        "crop", "padding", "cursor", "snap", "redact", "framerate", "width", "ffmpeg",
    )

    fun render(crop: Crop): String = when (crop) {
        is Crop.Window -> "window"
        is Crop.Editor -> "editor"
        is Crop.Fit -> "fit"
        is Crop.Component -> "component \"${crop.name}\""
        is Crop.Region -> "region ${crop.x},${crop.y} ${crop.width}x${crop.height}"
        is Crop.Follow -> "follow ${crop.what} ${crop.width}x${crop.height} ease ${crop.easeMs}ms"
    }

    /** The canonical block for [settings], in a stable order so diffs stay small. */
    private fun block(settings: TapeSettings): List<String> = buildList {
        settings.outputs.forEach { add("Output $it") }
        add("Set Crop ${render(settings.crop)}")
        if (settings.padding > 0) add("Set Padding ${settings.padding}")
        if (!settings.cursor) add("Set Cursor off")
        if (settings.snap) add("Set Snap on")
        settings.redact.forEach { add("Set Redact component \"$it\"") }
        add("Set Framerate ${settings.framerate}")
        add("Set Width ${settings.width}")
        if (settings.ffmpeg != "ffmpeg") add("Set Ffmpeg \"${settings.ffmpeg}\"")
    }

    fun apply(text: String, settings: TapeSettings): String {
        val lines = text.lines().toMutableList()
        val managed = lines.indices.filter { isManaged(lines[it]) }

        // With nothing to replace, the block goes after the leading comment header rather than at
        // the very top, so a tape that opens with an explanation keeps it first.
        val insertAt = managed.firstOrNull() ?: firstNonHeaderLine(lines)

        val kept = lines.filterIndexed { index, _ -> index !in managed }.toMutableList()
        // Removing lines shifts everything after them, so the insert point has to be measured
        // against what is left, not against the original.
        val shift = managed.count { it < insertAt }
        val at = (insertAt - shift).coerceIn(0, kept.size)

        kept.addAll(at, block(settings))

        // Collapse runs of blank lines left behind by removal, without touching anything else.
        val cleaned = mutableListOf<String>()
        kept.forEach { line ->
            if (line.isBlank() && cleaned.lastOrNull()?.isBlank() == true) return@forEach
            cleaned += line
        }
        return cleaned.joinToString("\n")
    }

    private fun isManaged(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.startsWith("#")) return false
        val verb = trimmed.substringBefore(' ').lowercase()
        if (verb == "output") return true
        if (verb != "set") return false
        val key = trimmed.substringAfter(' ', "").trim().substringBefore(' ').lowercase()
        return key in MANAGED_SETTINGS
    }

    /** Index just past the opening comment block and the blank line after it. */
    private fun firstNonHeaderLine(lines: List<String>): Int {
        var index = 0
        while (index < lines.size && (lines[index].trim().startsWith("#") || lines[index].isBlank())) {
            index++
        }
        return index
    }
}
