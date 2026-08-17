package com.asafjac.jbplugins.demodriver

import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ex.AnActionListener
import com.intellij.openapi.editor.event.CaretEvent
import com.intellij.openapi.editor.event.CaretListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import java.awt.event.MouseEvent

/**
 * Watches the IDE and writes down what you did, as tape.
 *
 * Authoring a tape by hand means guessing at timings you will get wrong on the first try, so the
 * intended loop is: perform the walkthrough once, tidy the durations, then replay it exactly as
 * many times as the demo needs re-recording.
 *
 * The important detail is that a caret position is written as the *text* under it, never as an
 * offset or a column. Recording coordinates would hand back tapes that break on the next edit,
 * throwing away the durability that is the whole point of the format.
 */
class TapeRecorder(private val project: Project) {

    private val lines = mutableListOf<String>()
    private var lastEventAt = 0L
    private var lastFile: String? = null
    private var disposable: Disposable? = null

    val isRecording: Boolean get() = disposable != null

    /** Steps captured so far, for showing progress while recording. */
    val stepCount: Int get() = lines.count { it.isNotBlank() && !it.startsWith("#") }

    fun start() {
        if (isRecording) return
        lines.clear()
        lastFile = null
        lastEventAt = System.currentTimeMillis()

        val parent = Disposer.newDisposable("demo-driver-recorder")
        disposable = parent

        val multicaster = EditorFactory.getInstance().eventMulticaster

        multicaster.addCaretListener(object : CaretListener {
            override fun caretPositionChanged(event: CaretEvent) {
                // Only a click or a jump is interesting; a caret dragged along by typing would
                // produce a step per character.
                recordCaret(event)
            }
        }, parent)

        multicaster.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseClicked(event: EditorMouseEvent) = recordClick(event)
        }, parent)

        project.messageBus.connect(parent).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) {
                    val file = event.newFile ?: return
                    val relative = relative(file.path)
                    if (relative == lastFile) return
                    lastFile = relative
                    gap()
                    lines += "Open $relative"
                }
            })

        // Application-level, since actions are not project-scoped. Recording the id rather than
        // the keystroke keeps the tape readable and keymap-independent.
        com.intellij.openapi.application.ApplicationManager.getApplication().messageBus
            .connect(parent)
            .subscribe(AnActionListener.TOPIC, object : AnActionListener {
                override fun afterActionPerformed(
                    action: AnAction,
                    event: AnActionEvent,
                    result: com.intellij.openapi.actionSystem.AnActionResult,
                ) {
                    val id = ActionManager.getInstance().getId(action) ?: return
                    if (id in IGNORED_ACTIONS) return
                    gap()
                    lines += "Action $id"
                }
            })
    }

    /** Stops recording and returns the tape text. */
    fun stop(settings: TapeSettings = TapeSettings()): String {
        disposable?.let { Disposer.dispose(it) }
        disposable = null

        val header = listOf(
            "# Recorded by Demo Driver. Tidy the Sleep durations, then replay.",
            "#",
            "# Targets are text, not pixels, so this keeps working after the file is edited.",
            "# Popup picks are not captured; add them by hand where a Choose Declaration",
            "# popup appeared, for example:  Popup \"AcmeBaz\"",
            "",
        )
        val body = if (lines.isEmpty()) listOf("# Nothing was recorded.") else lines
        return TapeWriter.apply((header + body).joinToString("\n"), settings)
    }

    private fun recordCaret(event: CaretEvent) {
        val editor = event.editor
        if (editor.project != null && editor.project != project) return
        val document = editor.document
        val offset = runCatching { editor.logicalPositionToOffset(event.newPosition) }.getOrNull() ?: return

        val anchor = wordAt(document.charsSequence, offset) ?: return
        val line = document.getLineNumber(offset) + 1

        // Collapse a repeat of the same target: a click often produces several caret events.
        val step = "Caret $line \"$anchor\""
        if (lines.lastOrNull() == step) return
        gap()
        lines += step
    }

    private fun recordClick(event: EditorMouseEvent) {
        val mouse = event.mouseEvent
        if (mouse.button != MouseEvent.BUTTON1) return
        // The caret listener has already emitted the target; this only records the gesture.
        lines += "Glide 700ms"
        lines += if (mouse.isControlDown || mouse.isMetaDown) "CtrlClick" else "Click"
    }

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
        if (!part(text[offset])) {
            // A click just past a word is common; step back one so it still resolves.
            if (offset == 0 || !part(text[offset - 1])) return null
        }
        var start = offset
        while (start > 0 && part(text[start - 1])) start--
        var end = offset
        while (end < text.length && part(text[end])) end++
        val word = text.subSequence(start, end).toString()
        return word.ifBlank { null }
    }

    private fun relative(path: String): String {
        val base = project.basePath ?: return path
        return path.removePrefix("$base/")
    }

    private companion object {
        const val MIN_GAP_MS = 250L
        /** A pause longer than this is someone thinking, not part of the demo. */
        const val MAX_GAP_MS = 4000L

        /**
         * Actions that are part of driving the recorder, or noise.
         *
         * Without this the tape's first step is always the command that started recording, and
         * every editor scroll shows up as a step.
         */
        val IGNORED_ACTIONS = setOf(
            "DemoDriver.RunTape",
            "EditorScrollUp", "EditorScrollDown", "EditorUp", "EditorDown",
            "EditorLeft", "EditorRight", "EditorPageUp", "EditorPageDown",
            "ActivateDemoDriverToolWindow",
        )
    }
}
