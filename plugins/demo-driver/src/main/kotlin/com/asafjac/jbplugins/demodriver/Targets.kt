package com.asafjac.jbplugins.demodriver

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.WindowManager
import com.intellij.ui.awt.RelativePoint
import java.awt.Point
import java.awt.Rectangle
import javax.swing.JList
import javax.swing.SwingUtilities

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
            "anchor \"$anchor\" not found" + if (line > 0) " on line $line" else " in the file"
        }

        // Aim at the middle of the anchor. A click on the leading edge is ambiguous between
        // this token and the one before it, which matters for a dotted path like a.b.c where
        // adjacent segments are what we are trying to tell apart.
        val target = found + anchor.length / 2
        editor.caretModel.moveToOffset(target)
        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)

        val inEditor = editor.offsetToXY(target)
        val origin = editor.contentComponent.locationOnScreen
        // offsetToXY gives the top-left of the character cell, so drop half a line to land
        // on the glyph rather than on the boundary with the line above.
        Point(origin.x + inEditor.x, origin.y + inEditor.y + editor.lineHeight / 2)
    }

    /**
     * Screen position of the popup row whose text contains [label].
     *
     * Matched on visible text rather than row index: a tape that says "row 2" breaks the
     * moment the number of results changes, which is exactly what happens when someone adds
     * another implementation.
     */
    fun popupRow(label: String): Point = onEdt {
        val list = findVisibleList() ?: error("no popup list is showing")
        val model = list.model
        val index = (0 until model.size).firstOrNull { i ->
            renderedText(list, i).contains(label, ignoreCase = true)
        } ?: error(
            "no popup row matching \"$label\". Showing: " +
                (0 until model.size).joinToString(", ") { "\"${renderedText(list, it)}\"" }
        )

        list.ensureIndexIsVisible(index)
        val cell: Rectangle = list.getCellBounds(index, index)
            ?: error("popup row $index has no bounds")
        val origin = list.locationOnScreen
        Point(origin.x + cell.width / 3, origin.y + cell.y + cell.height / 2)
    }

    /**
     * The rendered label of a row, taken from the cell renderer rather than `toString()`.
     *
     * The elements behind these rows are PSI, whose `toString()` is a debug description, not
     * what the user sees. Asking the renderer is what makes matching on the visible text work.
     */
    private fun renderedText(list: JList<*>, index: Int): String {
        val value = list.model.getElementAt(index)
        val component = runCatching {
            @Suppress("UNCHECKED_CAST")
            (list as JList<Any?>).cellRenderer
                .getListCellRendererComponent(list, value, index, false, false)
        }.getOrNull() ?: return value?.toString().orEmpty()
        return collectText(component).ifBlank { value?.toString().orEmpty() }
    }

    private fun collectText(component: java.awt.Component): String = buildString {
        when (component) {
            is javax.swing.JLabel -> append(component.text.orEmpty())
            is javax.swing.text.JTextComponent -> append(component.text.orEmpty())
        }
        if (component is java.awt.Container) {
            component.components.forEach { append(' ').append(collectText(it)) }
        }
    }

    /** Depth-first search for a showing JList in any visible window. */
    private fun findVisibleList(): JList<*>? {
        for (window in java.awt.Window.getWindows()) {
            if (!window.isShowing) continue
            descendants(window).filterIsInstance<JList<*>>()
                .firstOrNull { it.isShowing && it.model.size > 0 }
                ?.let { return it }
        }
        return null
    }

    private fun descendants(root: java.awt.Component): Sequence<java.awt.Component> = sequence {
        yield(root)
        if (root is java.awt.Container) root.components.forEach { yieldAll(descendants(it)) }
    }

    /** The capture region: the whole IDE frame, or just the editor component. */
    fun captureRegion(crop: String): Rectangle = onEdt {
        when (crop) {
            "editor" -> {
                val component = editor().contentComponent
                Rectangle(component.locationOnScreen, component.size)
            }
            else -> {
                val frame = WindowManager.getInstance().getFrame(project)
                    ?: error("no IDE frame for this project")
                Rectangle(frame.locationOnScreen, frame.size)
            }
        }
    }

    /** Unused today, kept because popup anchoring will need it when Record lands. */
    @Suppress("unused")
    fun relativeTo(component: javax.swing.JComponent): RelativePoint =
        RelativePoint(component, Point(0, 0)).also { SwingUtilities.isEventDispatchThread() }
}
