package com.asafjac.jbplugins.demodriver

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import java.awt.GraphicsEnvironment
import java.awt.Point
import java.awt.Rectangle
import kotlin.math.abs

/**
 * Turns symbols into screen coordinates by asking the IDE, never by measuring pixels.
 *
 * This is the reason the plugin exists. Driving a demo with hand-measured coordinates means
 * re-measuring after any font, theme, zoom or window change; `editor.offsetToXY` gives the
 * exact position of a character, so a tape written once keeps working.
 *
 * Everything here touches Swing and the editor, so it runs on the EDT via [onEdt].
 */
class Targets(private val project: Project) {

    /** Runs [block] on the EDT and returns its value. */
    private fun <T> onEdt(block: () -> T): T {
        var result: Result<T>? = null
        ApplicationManager.getApplication().invokeAndWait { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    /**
     * Opens a file named relative to the project, or absolutely.
     *
     * A demo that steps into a library or a generated file lands outside the project root, where a
     * relative path cannot reach; resolving both means those recordings replay instead of failing
     * on a path that was never relative in the first place.
     */
    fun openFile(path: String) = onEdt {
        val fs = LocalFileSystem.getInstance()
        val candidates = buildList {
            if (isAbsolute(path)) add(path) else project.basePath?.let { add("$it/$path") }
            // Try the other reading too: a tape may have been written on a machine whose project
            // root differed, and an absolute path from there is still worth attempting relatively.
            if (!isAbsolute(path)) add(path) else project.basePath?.let { add("$it/${path.trimStart('/')}") }
        }
        val file = candidates.firstNotNullOfOrNull { fs.refreshAndFindFileByPath(it) }
            ?: error("no such file: $path")
        FileEditorManager.getInstance(project).openFile(file, true)
        Unit
    }

    private fun isAbsolute(path: String): Boolean =
        path.startsWith("/") || (path.length > 2 && path[1] == ':')

    private fun editor(): Editor =
        FileEditorManager.getInstance(project).selectedTextEditor
            ?: error("no editor is open - the tape needs an Open step first")

    /**
     * Places the caret on the [nth] occurrence of [anchor] and returns its position on screen.
     *
     * [line] is a hint, not a requirement. It is searched first and the whole file second, so a tape
     * keeps working when lines are inserted above its target; pinning the line meant every such edit
     * broke the tape, which is exactly the fragility anchoring on text was supposed to avoid.
     */
    fun caret(line: Int, anchor: String, nth: Int = 1): Point = onEdt {
        val editor = editor()
        val offset = findAnchor(editor.document, line, anchor, nth)
        // Aim at the middle of the anchor. A click on the leading edge is ambiguous between this
        // token and the one before it, which matters for a dotted path like a.b.c where adjacent
        // segments are exactly what we are trying to tell apart.
        moveTo(editor, offset + anchor.length / 2)
    }

    /** Selects the [nth] occurrence of [anchor] and returns the middle of the selection. */
    fun select(line: Int, anchor: String, nth: Int = 1): Point = onEdt {
        val editor = editor()
        val offset = findAnchor(editor.document, line, anchor, nth)
        editor.selectionModel.setSelection(offset, offset + anchor.length)
        moveTo(editor, offset + anchor.length / 2)
    }

    /**
     * Selects from one anchor to the end of another and returns the middle of the selection.
     *
     * Ends are ordered by offset rather than trusted in the order written, so a range recorded from
     * a bottom-up drag still selects the same text.
     */
    fun selectRange(
        fromLine: Int,
        fromAnchor: String,
        fromNth: Int,
        toLine: Int,
        toAnchor: String,
        toNth: Int,
    ): Point = onEdt {
        val editor = editor()
        val a = findAnchor(editor.document, fromLine, fromAnchor, fromNth)
        val b = findAnchor(editor.document, toLine, toAnchor, toNth)
        val start = minOf(a, b)
        val end = maxOf(a + fromAnchor.length, b + toAnchor.length)
        editor.selectionModel.setSelection(start, end)
        // Aim at the start rather than the midpoint: for a selection spanning lines the midpoint can
        // be off screen, and the pointer belongs where the drag began.
        moveTo(editor, start)
    }

    /** Selects whole lines, inclusive. */
    fun selectLines(fromLine: Int, toLine: Int): Point = onEdt {
        val editor = editor()
        val document = editor.document
        val last = (document.lineCount - 1).coerceAtLeast(0)
        val first = (minOf(fromLine, toLine) - 1).coerceIn(0, last)
        val final = (maxOf(fromLine, toLine) - 1).coerceIn(0, last)
        editor.selectionModel.setSelection(
            document.getLineStartOffset(first), document.getLineEndOffset(final))
        moveTo(editor, document.getLineStartOffset(first))
    }

    /**
     * Scrolls so [line] is the top visible line, without disturbing the caret.
     *
     * Top rather than centred, because that is the line the recorder writes down: centring a
     * recorded top line reproduces a view scrolled half a screen away from the one recorded.
     */
    fun scrollTo(line: Int) = onEdt {
        val editor = editor()
        val index = (line - 1).coerceIn(0, (editor.document.lineCount - 1).coerceAtLeast(0))
        val position = com.intellij.openapi.editor.LogicalPosition(index, 0)
        editor.scrollingModel.scrollVertically(editor.logicalPositionToXY(position).y)
        Unit
    }

    private fun moveTo(editor: Editor, offset: Int): Point {
        editor.caretModel.moveToOffset(offset)
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
        val inEditor = editor.offsetToXY(offset)
        val origin = editor.contentComponent.locationOnScreen
        // offsetToXY gives the top-left of the character cell, so drop half a line to land on the
        // glyph rather than on the boundary with the line above.
        return Point(origin.x + inEditor.x, origin.y + inEditor.y + editor.lineHeight / 2)
    }

    /**
     * Offset of the [nth] occurrence of [anchor], preferring [line].
     *
     * The occurrence matters: `FooRegistry.qux.Baz` can repeat a word on one line, and taking the
     * first match sends the replay to a different segment than the one that was recorded.
     */
    private fun findAnchor(
        document: com.intellij.openapi.editor.Document,
        line: Int,
        anchor: String,
        nth: Int,
    ): Int {
        val text = document.charsSequence.toString()

        fun nthIn(from: Int, to: Int): Int? {
            var at = from
            var seen = 0
            while (at < to) {
                val found = text.indexOf(anchor, at)
                if (found < 0 || found >= to) return null
                if (++seen == nth) return found
                at = found + 1
            }
            return null
        }

        if (line in 1..document.lineCount) {
            val index = line - 1
            nthIn(document.getLineStartOffset(index), document.getLineEndOffset(index))
                ?.let { return it }
        }
        nthIn(0, text.length)?.let { return it }
        error(
            "anchor '$anchor'" + (if (nth > 1) " occurrence $nth" else "") +
                " not found" + if (line > 0) " on line $line or anywhere in the file" else " in the file")
    }

    /**
     * Screen position of the popup row matching [label], waiting up to [timeoutMs] for one.
     *
     * Waiting rather than sleeping: a fixed pause has to be long enough for the slowest machine,
     * which drags out every take on every other machine and still loses to an indexing pass.
     */
    fun popupRow(label: String, timeoutMs: Int = 5000): Point {
        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSeen = ""
        while (System.currentTimeMillis() < deadline) {
            val found = onEdt {
                val list = PopupRows.visibleList() ?: return@onEdt null
                val index = PopupRows.indexOf(list, label)
                if (index < 0) {
                    lastSeen = PopupRows.allRows(list).joinToString(", ") { "'" + it + "'" }
                    return@onEdt null
                }
                list.ensureIndexIsVisible(index)
                val cell = list.getCellBounds(index, index) ?: return@onEdt null
                val origin = list.locationOnScreen
                Point(origin.x + cell.width / 3, origin.y + cell.y + cell.height / 2)
            }
            if (found != null) return found
            Thread.sleep(80)
        }
        error(
            if (lastSeen.isEmpty()) "no popup appeared within ${timeoutMs}ms"
            else "no popup row matching '$label' within ${timeoutMs}ms. Showing: $lastSeen")
    }

    /** Waits for a popup to exist at all. */
    fun waitForPopup(timeoutMs: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (onEdt { PopupRows.visibleList() != null }) return true
            Thread.sleep(80)
        }
        return false
    }

    /** Waits for an editor to be open and laid out. */
    fun waitForEditor(timeoutMs: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ready = onEdt {
                val editor = FileEditorManager.getInstance(project).selectedTextEditor
                editor != null && editor.contentComponent.isShowing
            }
            if (ready) return true
            Thread.sleep(80)
        }
        return false
    }

    /**
     * Bounds of a named IDE part.
     *
     * Names follow what the IDE itself calls things - `Project`, `Terminal`, `Structure` are
     * tool window ids - so one tape frames correctly on a machine whose layout differs.
     */
    fun componentBounds(name: String): Rectangle = onEdt {
        fun bounds(component: java.awt.Component) = Rectangle(component.locationOnScreen, component.size)

        when (name.lowercase()) {
            "window", "frame" -> bounds(frame())
            "content" -> bounds(frame().contentPane)
            "editor" -> bounds(editor().contentComponent)
            "editors", "editortabs" -> bounds(FileEditorManagerEx.getInstanceEx(project).splitters)
            else -> {
                val manager = ToolWindowManager.getInstance(project)
                val id = manager.toolWindowIds.firstOrNull { it.equals(name, ignoreCase = true) }
                    ?: error(
                        "unknown component '$name'. Known: editor, editors, content, window, " +
                            manager.toolWindowIds.sorted().joinToString(", "))
                val window = manager.getToolWindow(id) ?: error("tool window '$id' is unavailable")
                if (!window.isVisible) error("tool window '$id' is not visible, so it cannot be framed")
                bounds(window.component)
            }
        }
    }

    private fun frame() = WindowManager.getInstance().getFrame(project) ?: error("no IDE frame")

    /** Screen position of the caret right now, for sampling a follow track. */
    fun caretPointNow(): Point? = onEdt {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return@onEdt null
        val xy = editor.offsetToXY(editor.caretModel.offset)
        val origin = editor.contentComponent.locationOnScreen
        Point(origin.x + xy.x, origin.y + xy.y + editor.lineHeight / 2)
    }

    /**
     * Resolves the crop to a rectangle, walking the tape first when the mode is [Crop.Fit].
     */
    fun resolveCrop(settings: TapeSettings, steps: List<Step>): Rectangle {
        val base = when (val crop = settings.crop) {
            is Crop.Window -> componentBounds("window")
            is Crop.Editor -> componentBounds("editor")
            is Crop.Component -> componentBounds(crop.name)
            is Crop.Region -> Rectangle(crop.x, crop.y, crop.width, crop.height)
                .let { if (settings.snap) snapToComponents(it) else it }
            // Follow records the full frame and moves a window over it afterwards, so the
            // captured area has to cover everywhere the viewport could be placed.
            is Crop.Follow -> componentBounds("window")
            is Crop.Fit -> fitToTargets(steps)
        }

        // An absolute region means those exact pixels; padding it would quietly contradict the
        // one mode whose whole promise is exactness.
        val pad = if (settings.crop is Crop.Region) 0 else settings.padding
        return clampToDesktop(
            Rectangle(base.x - pad, base.y - pad, base.width + pad * 2, base.height + pad * 2))
    }

    /**
     * Bounding box of every Caret target in the tape, with the original editors restored.
     *
     * The pre-pass has to actually open files and move carets, because a target's position is
     * only knowable once its file is laid out. Editors it opened are closed again afterwards,
     * or every `fit` recording would begin with tabs the tape had not opened yet.
     */
    private fun fitToTargets(steps: List<Step>): Rectangle {
        val manager = FileEditorManager.getInstance(project)
        val before = onEdt { manager.openFiles.toList() }
        val selected = onEdt { manager.selectedFiles.firstOrNull() }

        val points = mutableListOf<Point>()
        try {
            steps.forEach { step ->
                when (step) {
                    is Step.Open -> openFile(step.path)
                    // A target that cannot be resolved should not sink the whole take; it will
                    // fail loudly during the run itself, where the error names the step.
                    is Step.Caret -> runCatching { points += caret(step.line, step.anchor) }
                    else -> Unit
                }
            }
        } finally {
            onEdt {
                manager.openFiles.filterNot { it in before }.forEach { manager.closeFile(it) }
                selected?.let { manager.openFile(it, true) }
            }
        }

        if (points.isEmpty()) error("Crop fit found no Caret targets to frame")
        val minX = points.minOf { it.x }
        val minY = points.minOf { it.y }
        val maxX = points.maxOf { it.x }
        val maxY = points.maxOf { it.y }
        // One target is a point, not an area, so give it something to sit inside. Popups open
        // below and to the right of a click, so the box leans that way.
        return Rectangle(minX - 260, minY - 150, (maxX - minX) + 620, (maxY - minY) + 420)
    }

    /**
     * Grows a hand-drawn region out to nearby component edges.
     *
     * A dragged rectangle lands a few pixels inside or outside a panel boundary, which shows up
     * as a sliver of the neighbouring component along one edge. Snapping removes the sliver
     * without the user having to drag precisely.
     */
    private fun snapToComponents(region: Rectangle): Rectangle = onEdt {
        val threshold = 24
        val edges = mutableListOf<Rectangle>()
        fun collect(component: java.awt.Component) {
            if (component.isShowing && component.width > 40 && component.height > 40) {
                runCatching { edges += Rectangle(component.locationOnScreen, component.size) }
            }
            if (component is java.awt.Container) component.components.forEach { collect(it) }
        }
        runCatching { collect(frame()) }

        var x1 = region.x
        var y1 = region.y
        var x2 = region.x + region.width
        var y2 = region.y + region.height
        edges.forEach { e ->
            if (abs(e.x - x1) <= threshold) x1 = e.x
            if (abs(e.y - y1) <= threshold) y1 = e.y
            if (abs(e.x + e.width - x2) <= threshold) x2 = e.x + e.width
            if (abs(e.y + e.height - y2) <= threshold) y2 = e.y + e.height
        }
        Rectangle(x1, y1, x2 - x1, y2 - y1)
    }

    /**
     * Keeps the region inside the capturable desktop.
     *
     * Not cosmetic: a grab area extending past the desktop makes the recorder refuse to start
     * at all, so an over-generous padding would otherwise fail the take before it began.
     */
    private fun clampToDesktop(region: Rectangle): Rectangle {
        val devices = GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        val virtual = devices.fold(Rectangle()) { acc, device ->
            if (acc.isEmpty) device.defaultConfiguration.bounds else acc.union(device.defaultConfiguration.bounds)
        }
        val clipped = region.intersection(virtual)
        return if (clipped.isEmpty) virtual else clipped
    }
}
