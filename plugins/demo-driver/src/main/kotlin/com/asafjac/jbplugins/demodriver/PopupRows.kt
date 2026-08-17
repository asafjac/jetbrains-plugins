package com.asafjac.jbplugins.demodriver

import java.awt.Component
import java.awt.Container
import java.awt.Window
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.SwingUtilities
import javax.swing.text.JTextComponent

/**
 * Reading rows out of whatever popup is currently open.
 *
 * Shared by the runner, which picks a row by label, and the recorder, which writes down which row
 * was picked. One implementation because they have to agree: if the recorder wrote a label the
 * runner could not match, every recorded tape would fail on replay at the popup step.
 */
object PopupRows {

    /**
     * The rendered label of a row, taken from the cell renderer rather than `toString()`.
     *
     * The values behind these rows are usually PSI, whose `toString()` is a debug description and
     * not what the user sees. Asking the renderer is what makes matching on visible text work.
     */
    fun renderedText(list: JList<*>, index: Int): String {
        if (index < 0 || index >= list.model.size) return ""
        val value = list.model.getElementAt(index)
        val component = runCatching {
            @Suppress("UNCHECKED_CAST")
            (list as JList<Any?>).cellRenderer
                .getListCellRendererComponent(list, value, index, false, false)
        }.getOrNull() ?: return value?.toString().orEmpty()
        return collectText(component).trim().ifBlank { value?.toString().orEmpty() }
    }

    private fun collectText(component: Component): String = buildString {
        when (component) {
            is JLabel -> append(component.text.orEmpty())
            is JTextComponent -> append(component.text.orEmpty())
        }
        if (component is Container) {
            component.components.forEach { append(' ').append(collectText(it)) }
        }
    }

    /**
     * The popup list to act on.
     *
     * Windows are searched focused-first, because more than one can be showing: a notification
     * balloon or a stale popup elsewhere would otherwise be picked over the one the step meant.
     */
    fun visibleList(): JList<*>? {
        val windows = Window.getWindows().filter { it.isShowing }
        val ordered = windows.sortedByDescending { it.isFocused || it.isActive }
        for (window in ordered) {
            descendants(window).filterIsInstance<JList<*>>()
                .firstOrNull { it.isShowing && it.model.size > 0 && isPopupList(it) }
                ?.let { return it }
        }
        return null
    }

    fun allRows(list: JList<*>): List<String> =
        (0 until list.model.size).map { renderedText(list, it) }

    /**
     * The row [label] identifies, preferring precision over convenience.
     *
     * Exact, then leading-name, then prefix, and only then a substring. A plain `contains` picked
     * `AcmeBar` for the label `Bar`, so a tape asking for the base implementation silently took an
     * override instead - and both spellings exist side by side in any registry worth demoing.
     */
    fun indexOf(list: JList<*>, label: String): Int {
        val rows = allRows(list)
        val wanted = label.trim()

        rows.indexOfFirst { it.equals(wanted, ignoreCase = true) }.let { if (it >= 0) return it }
        rows.indexOfFirst { it.substringBefore(" (").trim().equals(wanted, ignoreCase = true) }
            .let { if (it >= 0) return it }
        rows.indexOfFirst { it.startsWith(wanted, ignoreCase = true) }.let { if (it >= 0) return it }
        return rows.indexOfFirst { it.contains(wanted, ignoreCase = true) }
    }

    /**
     * Whether a list belongs to a popup rather than to ordinary IDE furniture.
     *
     * Two cases, because the platform uses both: a heavyweight popup is its own window, while a
     * lightweight one is parented into the frame under a container whose name says so. Getting
     * this wrong in the permissive direction would make the recorder log a `Popup` step every
     * time someone clicked a list in a tool window.
     */
    fun isPopupList(list: JList<*>): Boolean {
        // Never our own step list; the panel is not part of the demo.
        if (SwingUtilities.getAncestorOfClass(DemoDriverPanel::class.java, list) != null) return false

        var component: Component? = list
        var depth = 0
        while (component != null && depth++ < 40) {
            val name = component.javaClass.simpleName
            if (name.contains("Popup") || name.contains("Balloon")) return true
            component = component.parent
        }
        // A heavyweight popup lives in a window that is not a frame or a dialog.
        val window = SwingUtilities.getWindowAncestor(list) ?: return false
        return window !is java.awt.Frame && window !is java.awt.Dialog
    }

    private fun descendants(root: Component): Sequence<Component> = sequence {
        yield(root)
        if (root is Container) root.components.forEach { yieldAll(descendants(it)) }
    }
}
