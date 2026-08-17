package com.asafjac.jbplugins.demodriver

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.AnActionResult
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.awt.AWTEvent
import java.awt.Component
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import javax.swing.JList
import javax.swing.SwingUtilities

/**
 * Watches the IDE and writes down what you did, as tape.
 *
 * Authoring a tape by hand means guessing at timings you will get wrong on the first try, so the
 * intended loop is: perform the walkthrough once, tidy the durations, then replay it exactly as
 * often as the demo needs re-recording.
 *
 * Two rules shape what gets written.
 *
 * A caret is recorded as the *text* under it, never an offset or a column, and always preceded by
 * the file it is in. Recording coordinates would hand back tapes that break on the next edit, and
 * omitting the file made every step after a navigation aim at whichever document happened to be
 * open.
 *
 * Where a gesture invoked a named IDE action, the action wins and the gesture is dropped. Ctrl and
 * click is how *this* keymap reaches Go to Declaration; `Action GotoDeclaration` is what was meant,
 * and it still replays on a keymap that binds it elsewhere.
 */
class TapeRecorder(private val project: Project) {

    private val lines = mutableListOf<String>()
    private var lastEventAt = 0L
    private var lastFile: String? = null
    private var disposable: Disposable? = null
    private var awtListener: AWTEventListener? = null

    /**
     * Steps arriving before this are consequences of a navigation rather than intent.
     *
     * Picking a popup row opens a file and moves a caret, and recording those would make the replay
     * click at the destination it had just been sent to.
     */
    private var suppressUntil = 0L

    /** Where in [lines] the most recent raw gesture began, so an action can replace it. */
    private var gestureFrom = -1
    private var gestureAt = 0L

    val isRecording: Boolean get() = disposable != null

    val stepCount: Int get() = lines.count { it.isNotBlank() && !it.startsWith("#") }

    fun start() {
        if (isRecording) return
        lines.clear()
        lastFile = null
        gestureFrom = -1
        lastEventAt = System.currentTimeMillis()

        val parent = Disposer.newDisposable("demo-driver-recorder")
        disposable = parent

        EditorFactory.getInstance().eventMulticaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) = recordCaret(event)
        }, parent)

        project.messageBus.connect(parent).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile ?: return
                    ensureFile(relative(file.path))
                }
            })

        installInputWatcher()

        // Application-level, since actions are not project-scoped. Recording the id rather than the
        // keystroke keeps the tape readable and independent of anyone's keymap.
        ApplicationManager.getApplication().messageBus.connect(parent)
            .subscribe(AnActionListener.TOPIC, object : AnActionListener {
                override fun afterActionPerformed(
                    action: AnAction,
                    event: AnActionEvent,
                    result: AnActionResult,
                ) {
                    val id = ActionManager.getInstance().getId(action) ?: return
                    if (id in IGNORED_ACTIONS) return
                    recordAction(id)
                }
            })
    }

    /** Stops recording and returns the tape text. */
    fun stop(settings: TapeSettings = TapeSettings()): String {
        awtListener?.let { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
        awtListener = null
        disposable?.let { Disposer.dispose(it) }
        disposable = null

        val header = listOf(
            "# Recorded by Demo Driver. Tidy the Sleep durations, then replay.",
            "#",
            "# Targets are text, not pixels, so this keeps working after the file is edited.",
            "",
        )
        val body = if (lines.isEmpty()) listOf("# Nothing was recorded.") else lines
        return TapeWriter.apply((header + body).joinToString("\n"), settings)
    }

    /**
     * Watches raw mouse and key events.
     *
     * The editor's own mouse listener was not enough: by the time it fires the modifier state has
     * moved on, so a ctrl and click arrived as a plain click. An AWT listener sees the press with
     * its modifiers intact, and also catches popup rows and keystrokes, which the editor listener
     * never sees at all.
     */
    private fun installInputWatcher() {
        val listener = AWTEventListener { event ->
            // Swallow failures: this runs for every mouse and key event in the IDE, and a recorder
            // that could throw here would break the IDE it is watching.
            runCatching {
                when {
                    event is MouseEvent && event.id == MouseEvent.MOUSE_PRESSED -> onMousePressed(event)
                    event is KeyEvent && event.id == KeyEvent.KEY_PRESSED -> onKeyPressed(event)
                }
            }
        }
        Toolkit.getDefaultToolkit().addAWTEventListener(
            listener, AWTEvent.MOUSE_EVENT_MASK or AWTEvent.KEY_EVENT_MASK)
        awtListener = listener
    }

    private fun onMousePressed(event: MouseEvent) {
        if (event.button != MouseEvent.BUTTON1) return

        // A click in a popup is a row selection, not a gesture in the editor.
        popupListUnder(event)?.let { list ->
            val point = SwingUtilities.convertPoint(event.component, event.point, list)
            recordPopup(list, list.locationToIndex(point))
            return
        }

        if (!inEditor(event.component)) return
        // Modifiers are read here, at press time, the only moment they are reliable.
        val ctrl = event.isControlDown || event.isMetaDown
        gap()
        gestureFrom = lines.size
        gestureAt = System.currentTimeMillis()
        lines += "Glide 700ms"
        lines += if (ctrl) "CtrlClick" else "Click"
    }

    private fun onKeyPressed(event: KeyEvent) {
        // Enter inside a popup is taking the row, not a keystroke worth replaying on its own.
        if (event.keyCode == KeyEvent.VK_ENTER) {
            PopupRows.visibleList()?.let { list ->
                recordPopup(list, list.selectedIndex)
                return
            }
        }

        // A modifier combination is somebody reaching for a shortcut. The action listener names it
        // properly a moment later, so only the window is opened here and no key is written; the
        // alternative records both the chord and the action it triggered.
        if (event.modifiersEx and MODIFIER_MASK != 0) {
            gestureFrom = lines.size
            gestureAt = System.currentTimeMillis()
            return
        }

        val name = KEY_NAMES[event.keyCode] ?: return
        gap()
        gestureFrom = lines.size
        gestureAt = System.currentTimeMillis()
        lines += "Key $name"
    }

    /**
     * Records a named action, replacing the gesture that triggered it.
     *
     * Without this a ctrl and click on a symbol produces a glide, a ctrl-click and an action, and
     * the replay performs the navigation twice.
     */
    private fun recordAction(id: String) {
        val triggeredByGesture = gestureFrom in 0..lines.size &&
            System.currentTimeMillis() - gestureAt <= GESTURE_WINDOW_MS

        if (triggeredByGesture) {
            // Keep any Glide: the pointer travelling to the symbol is what makes a take watchable,
            // and it is not part of what the action means.
            val keep = lines.subList(gestureFrom, lines.size).count { it.startsWith("Glide") }
            while (lines.size > gestureFrom + keep) lines.removeAt(lines.size - 1)
        } else {
            gap()
        }
        gestureFrom = -1
        lines += "Action $id"
        // An action that navigates produces its own Open and Caret events.
        suppress()
    }

    private fun popupListUnder(event: MouseEvent): JList<*>? {
        val component: Component = event.component ?: return null
        val list = component as? JList<*>
            ?: SwingUtilities.getAncestorOfClass(JList::class.java, component) as? JList<*>
            ?: return null
        if (!PopupRows.isPopupList(list)) return null
        // A click on the popup's border or header is not a row.
        val point = SwingUtilities.convertPoint(event.component, event.point, list)
        return if (list.locationToIndex(point) >= 0) list else null
    }

    private fun recordPopup(list: JList<*>, index: Int) {
        if (index < 0) return
        val label = PopupRows.renderedText(list, index)
        if (label.isBlank()) return
        // A row reads "AcmeBaz (AcmeFooRegistry - AcmeBaz.ts)". The leading name is the stable part
        // and is what the runner matches on, so the tape survives a file being renamed.
        val short = label.substringBefore(" (").trim().ifBlank { label }
        gap()
        gestureFrom = -1
        lines += "Popup \"$short\""
        suppress()
    }

    private fun inEditor(component: Component?): Boolean =
        component != null &&
            EditorFactory.getInstance().allEditors.any { editor ->
                component == editor.contentComponent ||
                    SwingUtilities.isDescendingFrom(component, editor.contentComponent)
            }

    private fun recordCaret(event: CaretEvent) {
        if (suppressed()) return
        val editor = event.editor
        if (editor.project != null && editor.project != project) return
        val document = editor.document
        val offset = runCatching { editor.logicalPositionToOffset(event.newPosition) }.getOrNull() ?: return

        val anchor = wordAt(document.charsSequence, offset) ?: return
        val line = document.getLineNumber(offset) + 1

        // Name the file this caret is in, before the caret itself. A Caret step applies to whatever
        // the replay happens to have open, so a tape that changed file without saying so silently
        // aimed every later step at the wrong document.
        FileDocumentManager.getInstance().getFile(document)?.let { ensureFile(relative(it.path)) }

        // Collapse a repeat of the same target: a click often produces several caret events.
        val step = "Caret $line \"$anchor\""
        if (lines.lastOrNull() == step) return
        gap()
        lines += step
    }

    /**
     * Emits an `Open` when the file changed, whatever else is going on.
     *
     * Deliberately not subject to the navigation suppression window. A file change caused by
     * navigating is exactly the case that has to be written down: on replay the popup or action may
     * land somewhere else, or not at all, so the tape has to state where it expects to be rather
     * than trust that it got there.
     */
    private fun ensureFile(relative: String) {
        if (relative == lastFile) return
        lastFile = relative
        gap()
        lines += "Open $relative"
    }

    private fun suppress() {
        suppressUntil = System.currentTimeMillis() + SUPPRESS_MS
    }

    private fun suppressed(): Boolean = System.currentTimeMillis() < suppressUntil

    /**
     * Emits the pause since the previous step.
     *
     * Real pauses are what make a replay watchable, so they are preserved rather than normalised
     * away, but quantised to a tenth of a second: nobody wants to read `Sleep 1873ms`, and the
     * difference is invisible on video.
     */
    private fun gap() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastEventAt
        lastEventAt = now
        if (elapsed < MIN_GAP_MS) return
        val tenths = (elapsed.coerceAtMost(MAX_GAP_MS) / 100.0).toInt()
        lines += "Sleep ${tenths / 10.0}s"
    }

    /** The identifier around [offset], which is what a Caret step anchors on. */
    private fun wordAt(text: CharSequence, offset: Int): String? {
        if (offset !in text.indices) return null
        fun part(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'
        if (!part(text[offset]) && (offset == 0 || !part(text[offset - 1]))) return null
        var start = offset
        while (start > 0 && part(text[start - 1])) start--
        var end = offset
        while (end < text.length && part(text[end])) end++
        return text.subSequence(start, end).toString().ifBlank { null }
    }

    private fun relative(path: String): String {
        val base = project.basePath ?: return path
        return path.removePrefix("$base/")
    }

    private companion object {
        const val MIN_GAP_MS = 250L
        /** A pause longer than this is someone thinking, not part of the demo. */
        const val MAX_GAP_MS = 4000L
        /** How long after a navigation its resulting Open and Caret events are ignored. */
        const val SUPPRESS_MS = 1200L
        /** How soon after a gesture an action is taken to be that gesture's meaning. */
        const val GESTURE_WINDOW_MS = 600L

        val MODIFIER_MASK = InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or
            InputEvent.META_DOWN_MASK or InputEvent.SHIFT_DOWN_MASK

        /** Keys worth replaying. Typing is not recorded; a demo that types is a different feature. */
        val KEY_NAMES = mapOf(
            KeyEvent.VK_ESCAPE to "Escape",
            KeyEvent.VK_ENTER to "Enter",
            KeyEvent.VK_TAB to "Tab",
            KeyEvent.VK_UP to "Up",
            KeyEvent.VK_DOWN to "Down",
            KeyEvent.VK_LEFT to "Left",
            KeyEvent.VK_RIGHT to "Right",
        )

        /**
         * Actions that are part of driving the recorder, or noise.
         *
         * Without this the tape's first step is the command that started recording, and every
         * scroll shows up as a step.
         */
        val IGNORED_ACTIONS = setOf(
            "DemoDriver.RunTape",
            "EditorScrollUp", "EditorScrollDown", "EditorUp", "EditorDown",
            "EditorLeft", "EditorRight", "EditorPageUp", "EditorPageDown",
            "EditorMouseWheelScroll",
            "ActivateDemoDriverToolWindow",
        )
    }
}
