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

    /** The first showing list with rows, in any visible window. */
    fun visibleList(): JList<*>? {
        for (window in Window.getWindows()) {
            if (!window.isShowing) continue
            descendants(window).filterIsInstance<JList<*>>()
                .firstOrNull { it.isShowing && it.model.size > 0 && isPopupList(it) }
                ?.let { return it }
        }
        return null
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
