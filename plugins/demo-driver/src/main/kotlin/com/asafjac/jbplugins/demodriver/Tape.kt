package com.asafjac.jbplugins.demodriver

/**
 * One instruction in a tape.
 *
 * Targets are symbolic on purpose. A step says "the text `Baz` on line 39", never a pixel,
 * so a font change, a window resize or a zoom level cannot invalidate a tape that used to
 * work. Resolving a symbol to a coordinate is [DemoRunner]'s job, done fresh on every run.
 */
sealed interface Step {
    /** Open a file, path relative to the project root. */
    data class Open(val path: String) : Step

    /**
     * Put the caret on [anchor] within [line], and make that the current mouse target.
     * A line of 0 means "first occurrence anywhere in the file".
     */
    data class Caret(val line: Int, val anchor: String) : Step

    /** Move the pointer to the current target over [ms], eased. */
    data class Glide(val ms: Int) : Step

    data class Click(val ctrl: Boolean) : Step

    /** Pick a row from the popup that is currently open, matched on its visible text. */
    data class Popup(val label: String) : Step

    /** Invoke an IDE action by id, e.g. `Back`, `GotoImplementation`. */
    data class Action(val id: String) : Step

    /** Press a named key, e.g. `Escape`, `Enter`. */
    data class Key(val name: String) : Step

    data class Sleep(val ms: Int) : Step
}

/**
 * Capture settings, all optional with usable defaults.
 *
 * Every field here is written by exactly one `Set` line and nothing else, so the panel and
 * the tape can round-trip: no setting exists that only a UI can express, which is what keeps
 * a hand-written or generated tape a first-class input rather than a degraded one.
 */
data class TapeSettings(
    val outputs: List<String> = emptyList(),
    val framerate: Int = 10,
    val width: Int = 940,
    val crop: Crop = Crop.Window,
    /** Grown around the resolved crop, ignored for an absolute Region. */
    val padding: Int = 0,
    val cursor: Boolean = true,
    /** Named components painted over, for hiding a tool window that shows real work. */
    val redact: List<String> = emptyList(),
    /** Round a Region's edges out to nearby component edges. */
    val snap: Boolean = false,
    val ffmpeg: String = "ffmpeg",
)

data class Tape(val settings: TapeSettings, val steps: List<Step>)

/**
 * Reads the tape format, which is deliberately shaped like a charmbracelet/vhs `.tape`:
 * `Set` for configuration, `Output` for destinations, verbs for actions, `#` for comments.
 * Following an existing convention rather than inventing one means a reader who has met VHS
 * already knows how to skim this.
 *
 *     Output docs/demo.gif
 *     Set Framerate 10
 *
 *     Open demo/src/App.tsx
 *     Caret 39 "Baz"
 *     Glide 800ms
 *     CtrlClick
 *     Sleep 2s
 *     Popup "AcmeBaz"
 */
object TapeParser {

    class ParseError(val line: Int, message: String) : Exception("line $line: $message")

    fun parse(text: String): Tape {
        var settings = TapeSettings()
        val steps = mutableListOf<Step>()

        text.lines().forEachIndexed { index, raw ->
            val lineNo = index + 1
            // Strip comments, but not inside a quoted anchor - anchors can legitimately
            // contain a '#', and silently truncating one would produce a target that is
            // never found, reported as "anchor not found" far from the real cause.
            val line = stripComment(raw).trim()
            if (line.isEmpty()) return@forEachIndexed

            val verb = line.substringBefore(' ').lowercase()
            val rest = line.substringAfter(' ', "").trim()

            when (verb) {
                "output" -> settings = settings.copy(outputs = settings.outputs + rest)
                "set" -> settings = applySetting(settings, rest, lineNo)
                "open" -> steps += Step.Open(rest.trim('"'))
                "caret" -> steps += parseCaret(rest, lineNo)
                "glide" -> steps += Step.Glide(duration(rest, lineNo))
                "click" -> steps += Step.Click(ctrl = false)
                "ctrlclick" -> steps += Step.Click(ctrl = true)
                "popup" -> steps += Step.Popup(quoted(rest, lineNo))
                "action" -> steps += Step.Action(rest.trim('"'))
                "key" -> steps += Step.Key(rest.trim('"'))
                "sleep" -> steps += Step.Sleep(duration(rest, lineNo))
                else -> throw ParseError(lineNo, "unknown command '$verb'")
            }
        }
        return Tape(settings, steps)
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
        // `component "Project"` ends in a quote, so trimming globally would eat the closing
        // one and leave an unbalanced string for parseCrop to choke on.
        val value = rest.substringAfter(' ', "").trim()
        return when (key) {
            "framerate" -> current.copy(framerate = value.trim('"').toIntOrNull()
                ?: throw ParseError(lineNo, "Framerate needs a number, got '$value'"))
            "width" -> current.copy(width = value.trim('"').toIntOrNull()
                ?: throw ParseError(lineNo, "Width needs a number, got '$value'"))
            "padding" -> current.copy(padding = value.trim('"').toIntOrNull()
                ?: throw ParseError(lineNo, "Padding needs a number, got '$value'"))
            "cursor" -> current.copy(cursor = onOff(value.trim('"'), lineNo))
            "snap" -> current.copy(snap = onOff(value.trim('"'), lineNo))
            "redact" -> current.copy(redact = current.redact + parseComponentName(value, lineNo))
            "crop" -> current.copy(crop = parseCrop(value, lineNo))
            "ffmpeg" -> current.copy(ffmpeg = value.trim('"'))
            else -> throw ParseError(lineNo, "unknown setting '$key'")
        }
    }

    private fun onOff(value: String, lineNo: Int): Boolean = when (value.lowercase()) {
        "on", "true", "yes" -> true
        "off", "false", "no" -> false
        else -> throw ParseError(lineNo, "expected on or off, got '$value'")
    }

    /** `component "Project"` -> `Project`; a bare word is accepted too. */
    private fun parseComponentName(value: String, lineNo: Int): String {
        val rest = value.removePrefix("component").trim()
        if (rest.isEmpty()) throw ParseError(lineNo, "Redact needs a component name")
        return rest.trim('"')
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
                // "region 360,160 1030x600"
                val origin = parts.getOrNull(1) ?: throw ParseError(lineNo, "region needs X,Y")
                val size = parts.getOrNull(2) ?: throw ParseError(lineNo, "region needs WxH")
                val (x, y) = origin.split(',').also {
                    if (it.size != 2) throw ParseError(lineNo, "region origin must be X,Y")
                }
                val (w, h) = size.lowercase().split('x').also {
                    if (it.size != 2) throw ParseError(lineNo, "region size must be WxH")
                }
                Crop.Region(
                    x.trim().toIntOrNull() ?: throw ParseError(lineNo, "bad region X '$x'"),
                    y.trim().toIntOrNull() ?: throw ParseError(lineNo, "bad region Y '$y'"),
                    w.trim().toIntOrNull() ?: throw ParseError(lineNo, "bad region width '$w'"),
                    h.trim().toIntOrNull() ?: throw ParseError(lineNo, "bad region height '$h'"),
                )
            }
            "follow" -> {
                val what = parts.getOrNull(1)?.lowercase()
                    ?: throw ParseError(lineNo, "follow needs mouse or caret")
                if (what !in setOf("mouse", "caret")) {
                    throw ParseError(lineNo, "follow takes mouse or caret, got '$what'")
                }
                val size = parts.getOrNull(2) ?: throw ParseError(lineNo, "follow needs WxH")
                val (w, h) = size.lowercase().split('x').also {
                    if (it.size != 2) throw ParseError(lineNo, "follow size must be WxH")
                }
                val easeAt = parts.indexOfFirst { it.equals("ease", true) }
                val ease = if (easeAt >= 0) durationOrNull(parts.getOrNull(easeAt + 1)) ?: 350 else 350
                Crop.Follow(
                    what,
                    w.trim().toIntOrNull() ?: throw ParseError(lineNo, "bad follow width '$w'"),
                    h.trim().toIntOrNull() ?: throw ParseError(lineNo, "bad follow height '$h'"),
                    ease,
                )
            }
            else -> throw ParseError(lineNo, "unknown crop mode '${parts[0]}'")
        }
    }

    private fun durationOrNull(text: String?): Int? = text?.let {
        runCatching { duration(it, 0) }.getOrNull()
    }

    /** `Caret 39 "Baz"` or `Caret "Baz"` for the first occurrence in the file. */
    private fun parseCaret(rest: String, lineNo: Int): Step.Caret {
        val quoteAt = rest.indexOf('"')
        if (quoteAt < 0) throw ParseError(lineNo, "Caret needs a quoted anchor, e.g. Caret 39 \"Baz\"")
        val linePart = rest.substring(0, quoteAt).trim()
        val line = if (linePart.isEmpty()) 0 else linePart.toIntOrNull()
            ?: throw ParseError(lineNo, "Caret line must be a number, got '$linePart'")
        return Step.Caret(line, quoted(rest.substring(quoteAt), lineNo))
    }

    private fun quoted(text: String, lineNo: Int): String {
        val open = text.indexOf('"')
        val close = text.lastIndexOf('"')
        if (open < 0 || close <= open) throw ParseError(lineNo, "expected a quoted string in '$text'")
        return text.substring(open + 1, close)
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
