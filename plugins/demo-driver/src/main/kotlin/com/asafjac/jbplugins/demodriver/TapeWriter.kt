package com.asafjac.jbplugins.demodriver

/**
 * Writes settings and steps back into tape text.
 *
 * This is what makes the panel and the file two views of one thing rather than two sources of
 * truth. Without it a control could only ever be a runtime override, and a tape written by hand or
 * by a script would be the second-class input; with it, every control is literally an edit to the
 * file, and the file remains the only state there is.
 *
 * Comments, blank lines, indentation and ordering are preserved. Only the lines being changed are
 * touched, so a tape keeps the shape whoever wrote it gave it.
 */
object TapeWriter {

    private const val NL = "\n"

    private val MANAGED_SETTINGS = setOf(
        "crop", "padding", "cursor", "tooltips", "snap", "redact", "framerate", "width", "ffmpeg",
    )

    fun render(crop: Crop): String = when (crop) {
        is Crop.Window -> "window"
        is Crop.Editor -> "editor"
        is Crop.Fit -> "fit"
        is Crop.Component -> "component \"${crop.name}\""
        is Crop.Region -> "region ${crop.x},${crop.y} ${crop.width}x${crop.height}"
        is Crop.Follow -> "follow ${crop.what} ${crop.width}x${crop.height} ease ${crop.easeMs}ms"
    }

    /** A step as tape source, in the canonical spelling the parser reads back. */
    fun renderStep(step: Step): String = when (step) {
        is Step.Open -> "Open ${step.path}"
        is Step.Caret -> "Caret " + anchor(step.line, step.anchor, step.nth)
        is Step.Select -> "Select " + anchor(step.line, step.anchor, step.nth)
        is Step.SelectRange -> "Select " +
            anchor(step.fromLine, step.fromAnchor, step.fromNth) + " to " +
            anchor(step.toLine, step.toAnchor, step.toNth)
        is Step.SelectLines -> "Select lines ${step.fromLine} ${step.toLine}"
        is Step.Scroll -> "Scroll ${step.line}"
        is Step.Glide -> "Glide ${duration(step.ms)}"
        is Step.Click -> if (step.ctrl) "CtrlClick" else "Click"
        is Step.Popup -> "Popup \"${step.label}\""
        is Step.Action -> "Action ${step.id}"
        is Step.Key -> "Key ${step.name}"
        is Step.Sleep -> "Sleep ${duration(step.ms)}"
        is Step.WaitFor -> "WaitFor ${step.what} ${duration(step.ms)}"
    }

    /** `39 "Baz" nth 2`, omitting the parts that carry no information. */
    private fun anchor(line: Int, anchor: String, nth: Int): String = buildString {
        if (line > 0) append(line).append(' ')
        append('"').append(anchor).append('"')
        if (nth > 1) append(" nth ").append(nth)
    }

    /** Sub-second values read better in milliseconds; longer ones in seconds. */
    private fun duration(ms: Int): String = if (ms < 1000) "${ms}ms" else "${(ms / 100) / 10.0}s"

    /** The canonical settings block, in a stable order so diffs stay small. */
    private fun block(settings: TapeSettings): List<String> = buildList {
        settings.outputs.forEach { add("Output $it") }
        add("Set Crop ${render(settings.crop)}")
        if (settings.padding > 0) add("Set Padding ${settings.padding}")
        if (!settings.cursor) add("Set Cursor off")
        if (!settings.tooltips) add("Set Tooltips off")
        if (settings.snap) add("Set Snap on")
        settings.redact.forEach { add("Set Redact component \"$it\"") }
        add("Set Framerate ${settings.framerate}")
        add("Set Width ${settings.width}")
        if (settings.ffmpeg != "ffmpeg") add("Set Ffmpeg \"${settings.ffmpeg}\"")
    }

    fun apply(text: String, settings: TapeSettings): String {
        val lines = text.lines().toMutableList()
        val managed = lines.indices.filter { isManagedSetting(lines[it]) }

        // With nothing to replace, the block goes after the leading comment header rather than at
        // the very top, so a tape that opens with an explanation keeps it first.
        val insertAt = managed.firstOrNull() ?: firstNonHeaderLine(lines)

        val kept = lines.filterIndexed { index, _ -> index !in managed }.toMutableList()
        // Removing lines shifts everything after them, so the insert point has to be measured
        // against what is left, not against the original.
        val shift = managed.count { it < insertAt }
        kept.addAll((insertAt - shift).coerceIn(0, kept.size), block(settings))

        // Collapse runs of blank lines left behind by removal, without touching anything else.
        val cleaned = mutableListOf<String>()
        kept.forEach { line ->
            if (line.isBlank() && cleaned.lastOrNull()?.isBlank() == true) return@forEach
            cleaned += line
        }
        return cleaned.joinToString(NL)
    }

    /**
     * Replaces one step's line, keeping its indentation.
     *
     * Indentation is preserved because a tape may group steps visually, and a panel edit that
     * silently unindented one would read as the file having been reformatted.
     */
    fun replaceStep(text: String, sourceLine: Int, step: Step): String =
        editLines(text, sourceLine) { lines, index ->
            val indent = lines[index].takeWhile { it == ' ' || it == '\t' }
            lines[index] = indent + renderStep(step)
        }

    fun removeStep(text: String, sourceLine: Int): String =
        editLines(text, sourceLine) { lines, index -> lines.removeAt(index) }

    fun duplicateStep(text: String, sourceLine: Int): String =
        editLines(text, sourceLine) { lines, index -> lines.add(index + 1, lines[index]) }

    /**
     * Swaps a step with its neighbouring step, skipping blanks and comments.
     *
     * Swapping with the raw adjacent line would drag a comment away from the step it describes, or
     * bury a step inside a comment block.
     */
    fun moveStep(text: String, sourceLine: Int, delta: Int): String =
        editLines(text, sourceLine) { lines, index ->
            var to = index + delta
            while (to in lines.indices && !isStepLine(lines[to])) to += delta
            if (to in lines.indices) {
                val moved = lines.removeAt(index)
                lines.add(to, moved)
            }
        }

    private fun editLines(text: String, sourceLine: Int, edit: (MutableList<String>, Int) -> Unit): String {
        val lines = text.lines().toMutableList()
        val index = sourceLine - 1
        if (index !in lines.indices) return text
        edit(lines, index)
        return lines.joinToString(NL)
    }

    private fun isStepLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return false
        return !isManagedSetting(line)
    }

    private fun isManagedSetting(line: String): Boolean {
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
