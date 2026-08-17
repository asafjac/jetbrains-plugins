package com.asafjac.jbplugins.demodriver

/**
 * One instruction in a tape.
 *
 * Targets are symbolic on purpose. A step says "the text `Baz` on line 39", never a pixel, so a
 * font change, a window resize or a zoom level cannot invalidate a tape that used to work.
 */
sealed interface Step {

    /** Open a file. Relative to the project root, or absolute for anything outside it. */
    data class Open(val path: String) : Step

    /**
     * Put the caret on [anchor] and make it the current mouse target.
     *
     * [line] is a hint rather than a requirement: it is searched first, then the whole file, so a
     * tape survives lines being inserted above its target. [nth] picks which occurrence, because a
     * dotted path can repeat a word on one line and the first match is often the wrong one.
     */
    data class Caret(val line: Int, val anchor: String, val nth: Int = 1) : Step

    /** Select [anchor] rather than just placing the caret in it. */
    data class Select(val line: Int, val anchor: String, val nth: Int = 1) : Step

    /**
     * Select from one anchor to another, spanning lines.
     *
     * A multi-line drag has no single anchor to name, and a raw offset range would break on the
     * next edit; naming both ends keeps the durability the rest of the format has.
     */
    data class SelectRange(
        val fromLine: Int,
        val fromAnchor: String,
        val fromNth: Int,
        val toLine: Int,
        val toAnchor: String,
        val toNth: Int,
    ) : Step

    /**
     * Select whole lines.
     *
     * The fallback for a drag whose ends do not sit on identifiers, where there is nothing to
     * anchor to and lines are the only honest description.
     */
    data class SelectLines(val fromLine: Int, val toLine: Int) : Step

    /** Scroll so [line] is visible, without moving the caret. */
    data class Scroll(val line: Int) : Step

    /** Move the pointer to the current target over [ms], eased. */
    data class Glide(val ms: Int) : Step

    data class Click(val ctrl: Boolean) : Step

    /** Pick a row from the open popup, matched on its visible text. */
    data class Popup(val label: String) : Step

    /** Invoke an IDE action by id, e.g. `Back`, `GotoImplementation`. */
    data class Action(val id: String) : Step

    /** Press a named key, e.g. `Escape`, `Enter`. */
    data class Key(val name: String) : Step

    data class Sleep(val ms: Int) : Step

    /**
     * Wait for something to appear, up to [ms].
     *
     * The alternative is a fixed `Sleep` long enough for the slowest machine, which makes every take
     * drag on every other machine and still fails when indexing picks that moment.
     */
    data class WaitFor(val what: String, val ms: Int) : Step
}

/**
 * Capture settings, all optional with usable defaults.
 *
 * Every field is written by exactly one `Set` line and nothing else, so the panel and the tape can
 * round-trip: no setting exists that only a UI can express, which is what keeps a hand-written or
 * generated tape a first-class input rather than a degraded one.
 */
data class TapeSettings(
    val outputs: List<String> = emptyList(),
    val framerate: Int = 10,
    val width: Int = 940,
    val crop: Crop = Crop.Window,
    /** Grown around the resolved crop, ignored for an absolute Region. */
    val padding: Int = 0,
    val cursor: Boolean = true,
    /**
     * Whether hover documentation may appear during a take.
     *
     * On by default, because a demo of a hover feature needs them; switchable because the replay's
     * own pointer motion raises tooltips that were never in the recording.
     */
    val tooltips: Boolean = true,
    /** Named components painted over, for hiding a tool window that shows real work. */
    val redact: List<String> = emptyList(),
    /** Round a Region's edges out to nearby component edges. */
    val snap: Boolean = false,
    val ffmpeg: String = "ffmpeg",
)

/**
 * A parsed tape.
 *
 * [stepLines] holds the 1-based source line each step came from, parallel to [steps]. Editing a step
 * means rewriting exactly that line, which is what lets the panel change one duration without
 * reformatting the file or disturbing the comments around it.
 */
data class Tape(
    val settings: TapeSettings,
    val steps: List<Step>,
    val stepLines: List<Int> = emptyList(),
)

/**
 * Reads the tape format, deliberately shaped like a charmbracelet/vhs `.tape`: `Set` for
 * configuration, `Output` for destinations, verbs for actions, `#` for comments. Following an
 * existing convention rather than inventing one means a reader who has met VHS can skim this.
 */
object TapeParser {

    class ParseError(val line: Int, message: String) : Exception("line $line: $message")

    fun parse(text: String): Tape {
        var settings = TapeSettings()
        val steps = mutableListOf<Step>()
        val stepLines = mutableListOf<Int>()

        text.lines().forEachIndexed { index, raw ->
            val lineNo = index + 1
            // Strip comments, but not inside a quoted anchor: anchors can contain a '#', and
            // truncating one silently produces a target that is never found, reported far from the
            // real cause.
            val line = stripComment(raw).trim()
            if (line.isEmpty()) return@forEachIndexed

            val verb = line.substringBefore(' ').lowercase()
            val rest = line.substringAfter(' ', "").trim()

            val before = steps.size
            when (verb) {
                "output" -> settings = settings.copy(outputs = settings.outputs + rest)
                "set" -> settings = applySetting(settings, rest, lineNo)
                "open" -> steps += Step.Open(rest.trim('"'))
                "caret" -> steps += anchored(rest, lineNo) { l, a, n -> Step.Caret(l, a, n) }
                "select" -> steps += parseSelect(rest, lineNo)
                "scroll" -> steps += Step.Scroll(int(rest, "Scroll", lineNo))
                "glide" -> steps += Step.Glide(duration(rest, lineNo))
                "click" -> steps += Step.Click(ctrl = false)
                "ctrlclick" -> steps += Step.Click(ctrl = true)
                "popup" -> steps += Step.Popup(quoted(rest, lineNo))
                "action" -> steps += Step.Action(rest.trim('"'))
                "key" -> steps += Step.Key(rest.trim('"'))
                "sleep" -> steps += Step.Sleep(duration(rest, lineNo))
                "waitfor" -> steps += parseWaitFor(rest, lineNo)
                else -> throw ParseError(lineNo, "unknown command '$verb'")
            }
            if (steps.size > before) stepLines += lineNo
        }
        return Tape(settings, steps, stepLines)
    }

    private fun stripComment(raw: String): String {
        var inQuotes = false
        raw.forEachIndexed { i, c ->
            if (c == '"') inQuotes = !inQuotes
            if (c == '#' && !inQuotes) return raw.substring(0, i)
        }
        return raw
    }

    private fun applySetting(current: TapeSettings, rest: String, lineNo: Int): TapeSettings {
        val key = rest.substringBefore(' ').lowercase()
        // Quotes are stripped per setting, not here: a composite value such as
        // `component "Project"` ends in a quote, so trimming globally would eat the closing one.
        val value = rest.substringAfter(' ', "").trim()
        return when (key) {
            "framerate" -> current.copy(framerate = int(value, "Framerate", lineNo))
            "width" -> current.copy(width = int(value, "Width", lineNo))
            "padding" -> current.copy(padding = int(value, "Padding", lineNo))
            "cursor" -> current.copy(cursor = onOff(value.trim('"'), lineNo))
            "tooltips" -> current.copy(tooltips = onOff(value.trim('"'), lineNo))
            "snap" -> current.copy(snap = onOff(value.trim('"'), lineNo))
            "redact" -> current.copy(redact = current.redact + componentName(value, lineNo))
            "crop" -> current.copy(crop = parseCrop(value, lineNo))
            "ffmpeg" -> current.copy(ffmpeg = value.trim('"'))
            else -> throw ParseError(lineNo, "unknown setting '$key'")
        }
    }

    private fun int(value: String, what: String, lineNo: Int): Int =
        value.trim().trim('"').toIntOrNull()
            ?: throw ParseError(lineNo, "$what needs a number, got '$value'")

    private fun onOff(value: String, lineNo: Int): Boolean = when (value.lowercase()) {
        "on", "true", "yes" -> true
        "off", "false", "no" -> false
        else -> throw ParseError(lineNo, "expected on or off, got '$value'")
    }

    private fun componentName(value: String, lineNo: Int): String {
        val rest = value.removePrefix("component").trim()
        if (rest.isEmpty()) throw ParseError(lineNo, "Redact needs a component name")
        return rest.trim('"')
    }

    private fun parseWaitFor(rest: String, lineNo: Int): Step.WaitFor {
        val what = rest.substringBefore(' ').lowercase()
        if (what.isEmpty()) throw ParseError(lineNo, "WaitFor needs a target, e.g. popup")
        if (what !in setOf("popup", "editor")) {
            throw ParseError(lineNo, "WaitFor takes popup or editor, got '$what'")
        }
        val timeout = rest.substringAfter(' ', "").trim()
        return Step.WaitFor(what, if (timeout.isEmpty()) 5000 else duration(timeout, lineNo))
    }

    /**
     * `Select lines A B` | `Select [line] "anchor" [nth N] [to [line] "anchor" [nth N]]`
     *
     * One verb rather than three, because all of them are the same intent at different precisions
     * and a reader should not have to remember which spelling goes with which.
     */
    private fun parseSelect(rest: String, lineNo: Int): Step {
        if (rest.trim().lowercase().startsWith("lines")) {
            val bounds = rest.trim().substring(5).trim().split(' ', ',').filter { it.isNotBlank() }
            if (bounds.size != 2) throw ParseError(lineNo, "Select lines needs two line numbers")
            return Step.SelectLines(
                int(bounds[0], "Select lines from", lineNo), int(bounds[1], "Select lines to", lineNo))
        }

        // The separator is only a separator outside the quotes; an anchor may contain the word.
        val closeAt = rest.indexOf('"', rest.indexOf('"') + 1)
        val toAt = if (closeAt < 0) -1 else rest.indexOf(" to ", closeAt)
        if (toAt < 0) return anchored(rest, lineNo) { l, a, n -> Step.Select(l, a, n) }

        val from = anchored(rest.substring(0, toAt), lineNo) { l, a, n -> Triple(l, a, n) }
        val to = anchored(rest.substring(toAt + 4), lineNo) { l, a, n -> Triple(l, a, n) }
        return Step.SelectRange(from.first, from.second, from.third, to.first, to.second, to.third)
    }

    /** `<verb> [line] "anchor" [nth N]` - the shape Caret and Select share. */
    private fun <T> anchored(rest: String, lineNo: Int, build: (Int, String, Int) -> T): T {
        val quoteAt = rest.indexOf('"')
        if (quoteAt < 0) throw ParseError(lineNo, "needs a quoted anchor, e.g. 39 \"Baz\"")
        val linePart = rest.substring(0, quoteAt).trim()
        val line = if (linePart.isEmpty()) 0 else linePart.toIntOrNull()
            ?: throw ParseError(lineNo, "line must be a number, got '$linePart'")

        val closeAt = rest.indexOf('"', quoteAt + 1)
        if (closeAt < 0) throw ParseError(lineNo, "unterminated anchor in '$rest'")
        val anchor = rest.substring(quoteAt + 1, closeAt)

        val tail = rest.substring(closeAt + 1).trim().split(' ').filter { it.isNotBlank() }
        val nth = if (tail.size >= 2 && tail[0].equals("nth", ignoreCase = true)) {
            tail[1].toIntOrNull() ?: throw ParseError(lineNo, "nth must be a number, got '${tail[1]}'")
        } else 1
        if (nth < 1) throw ParseError(lineNo, "nth starts at 1")
        return build(line, anchor, nth)
    }

    private fun quoted(text: String, lineNo: Int): String {
        val open = text.indexOf('"')
        val close = text.lastIndexOf('"')
        if (open < 0 || close <= open) throw ParseError(lineNo, "expected a quoted string in '$text'")
        return text.substring(open + 1, close)
    }

    /**
     * `window` | `editor` | `component "X"` | `region X,Y WxH` | `fit`
     *   | `follow mouse|caret WxH [ease Nms]`
     */
    private fun parseCrop(value: String, lineNo: Int): Crop {
        val parts = value.split(' ').filter { it.isNotBlank() }
        if (parts.isEmpty()) throw ParseError(lineNo, "Crop needs a mode")
        return when (parts[0].lowercase()) {
            "window" -> Crop.Window
            "editor" -> Crop.Editor
            "fit" -> Crop.Fit
            "component" -> Crop.Component(
                value.substringAfter("component").trim().trim('"')
                    .ifEmpty { throw ParseError(lineNo, "Crop component needs a name") })
            "region" -> {
                val origin = parts.getOrNull(1) ?: throw ParseError(lineNo, "region needs X,Y")
                val size = parts.getOrNull(2) ?: throw ParseError(lineNo, "region needs WxH")
                val xy = origin.split(',')
                if (xy.size != 2) throw ParseError(lineNo, "region origin must be X,Y")
                val wh = size.lowercase().split('x')
                if (wh.size != 2) throw ParseError(lineNo, "region size must be WxH")
                Crop.Region(
                    int(xy[0], "region X", lineNo), int(xy[1], "region Y", lineNo),
                    int(wh[0], "region width", lineNo), int(wh[1], "region height", lineNo))
            }
            "follow" -> {
                val what = parts.getOrNull(1)?.lowercase()
                    ?: throw ParseError(lineNo, "follow needs mouse or caret")
                if (what !in setOf("mouse", "caret")) {
                    throw ParseError(lineNo, "follow takes mouse or caret, got '$what'")
                }
                val wh = (parts.getOrNull(2) ?: throw ParseError(lineNo, "follow needs WxH"))
                    .lowercase().split('x')
                if (wh.size != 2) throw ParseError(lineNo, "follow size must be WxH")
                val easeAt = parts.indexOfFirst { it.equals("ease", ignoreCase = true) }
                val ease = if (easeAt >= 0) {
                    parts.getOrNull(easeAt + 1)
                        ?.let { runCatching { duration(it, lineNo) }.getOrNull() } ?: 350
                } else 350
                Crop.Follow(
                    what, int(wh[0], "follow width", lineNo), int(wh[1], "follow height", lineNo), ease)
            }
            else -> throw ParseError(lineNo, "unknown crop mode '${parts[0]}'")
        }
    }

    /** `800ms`, `2s`, `2.5s`, or a bare number read as milliseconds. */
    private fun duration(text: String, lineNo: Int): Int {
        val t = text.trim().lowercase()
        return when {
            t.endsWith("ms") -> t.dropLast(2).trim().toDoubleOrNull()?.toInt()
            t.endsWith("s") -> t.dropLast(1).trim().toDoubleOrNull()?.times(1000)?.toInt()
            else -> t.toDoubleOrNull()?.toInt()
        } ?: throw ParseError(lineNo, "cannot read duration '$text'")
    }
}
