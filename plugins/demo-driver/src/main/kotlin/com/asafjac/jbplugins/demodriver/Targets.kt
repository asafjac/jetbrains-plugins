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

    fun openFile(relativePath: String) = onEdt {
        val base = project.basePath ?: error("project has no base path")
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath("$base/$relativePath")
            ?: error("no such file: $relativePath")
        FileEditorManager.getInstance(project).openFile(file, true)
        Unit
    }

    private fun editor(): Editor =
        FileEditorManager.getInstance(project).selectedTextEditor
            ?: error("no editor is open - the tape needs an Open step first")

    /**
     * Places the caret on [anchor] and returns its position on screen.
     *
     * [line] is 1-based to match what the gutter shows; 0 searches the whole file. Anchoring
     * on text rather than a column means the tape survives reformatting and edits above it.
     */
    fun caret(line: Int, anchor: String): Point = onEdt {
        val editor = editor()
        val document = editor.document
        val text = document.charsSequence.toString()

        val searchFrom: Int
        val searchTo: Int
        if (line <= 0) {
            searchFrom = 0
            searchTo = text.length
        } else {
            val index = line - 1
            require(index < document.lineCount) { "line $line is past the end of the file" }
            searchFrom = document.getLineStartOffset(index)
            searchTo = document.getLineEndOffset(index)
        }

        val found = text.indexOf(anchor, searchFrom)
        require(found in searchFrom until searchTo) {
            "anchor '$anchor' not found" + if (line > 0) " on line $line" else " in the file"
        }

        // Aim at the middle of the anchor. A click on the leading edge is ambiguous between
        // this token and the one before it, which matters for a dotted path like a.b.c where
        // adjacent segments are exactly what we are trying to tell apart.
        val target = found + anchor.length / 2
        editor.caretModel.moveToOffset(target)
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)

        val inEditor = editor.offsetToXY(target)
        val origin = editor.contentComponent.locationOnScreen
        // offsetToXY gives the top-left of the character cell, so drop half a line to land on
        // the glyph rather than on the boundary with the line above.
        Point(origin.x + inEditor.x, origin.y + inEditor.y + editor.lineHeight / 2)
    }

    /**
     * Screen position of the popup row whose text contains [label].
     *
     * Matched on visible text rather than row index: a tape that says "row 2" breaks the moment
     * the number of results changes, which is exactly what happens when someone adds another
     * implementation.
     */
    fun popupRow(label: String): Point = onEdt {
        val list = PopupRows.visibleList() ?: error("no popup list is showing")
        val model = list.model
        val index = (0 until model.size).firstOrNull { i ->
            PopupRows.renderedText(list, i).contains(label, ignoreCase = true)
        } ?: error(
            "no popup row matching '$label'. Showing: " +
                (0 until model.size).joinToString(", ") { "'" + PopupRows.renderedText(list, it) + "'" }
        )

        list.ensureIndexIsVisible(index)
        val cell = list.getCellBounds(index, index) ?: error("popup row $index has no bounds")
        val origin = list.locationOnScreen
        Point(origin.x + cell.width / 3, origin.y + cell.y + cell.height / 2)
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
