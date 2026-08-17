package com.asafjac.jbplugins.demodriver

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.ide.DataManager
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import java.io.File

/**
 * Runs the tape in the current editor.
 *
 * Acting on the open file rather than asking through a chooser: a tape is edited and re-run
 * many times in a row, and a dialog on every run is friction in exactly the loop this plugin
 * exists to shorten.
 */
class RunTapeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val name = event.getData(CommonDataKeys.VIRTUAL_FILE)?.name
        event.presentation.isEnabledAndVisible = event.project != null
        event.presentation.text = when {
            name?.endsWith(".tape") == true -> "Run Demo Tape: $name"
            else -> "Run Demo Tape"
        }
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)

        if (file == null || !file.name.endsWith(".tape")) {
            notify(project, NotificationType.WARNING,
                "Open a .tape file and run this again. Tapes live wherever you like; " +
                    "there is an example at demo/shots/.")
            return
        }

        val tape = try {
            TapeParser.parse(String(file.contentsToByteArray(), Charsets.UTF_8))
        } catch (e: Exception) {
            notify(project, NotificationType.ERROR, "Could not read ${file.name}: ${e.message}")
            return
        }
        if (tape.steps.isEmpty()) {
            notify(project, NotificationType.WARNING, "${file.name} has no steps.")
            return
        }

        ProgressManager.getInstance().run(RunTask(project, file.name, tape))
    }

    /**
     * A background task, because every Robot call has to be off the EDT: those calls post
     * native events the EDT must then consume, so running them on it deadlocks the IDE.
     */
    private class RunTask(
        project: Project,
        private val tapeName: String,
        private val tape: Tape,
    ) : Task.Backgroundable(project, "Running $tapeName", true) {

        override fun run(indicator: ProgressIndicator) {
            val project = project ?: return
            val targets = Targets(project)
            val pointer = Pointer()
            val capture = Capture(tape.settings, File(project.basePath ?: "."))

            indicator.isIndeterminate = false
            try {
                if (tape.settings.outputs.isNotEmpty()) {
                    capture.start(targets.captureRegion(tape.settings.crop))
                    // Give the recorder a moment to attach before anything moves, or the
                    // first second of every take is a blank frame.
                    Thread.sleep(1200)
                }

                tape.steps.forEachIndexed { index, step ->
                    indicator.checkCanceled()
                    indicator.fraction = index.toDouble() / tape.steps.size
                    indicator.text = "Step ${index + 1}/${tape.steps.size}: ${describe(step)}"
                    execute(step, targets, pointer)
                }

                val written = capture.finish()
                notify(project, NotificationType.INFORMATION, buildString {
                    append("$tapeName finished (${tape.steps.size} steps)")
                    if (written.isNotEmpty()) {
                        append(". Wrote ")
                        append(written.joinToString(", ") { it.name })
                    }
                })
            } catch (e: Exception) {
                capture.abort()
                if (indicator.isCanceled) return
                notify(project, NotificationType.ERROR,
                    "$tapeName failed at: ${indicator.text ?: "?"} - ${e.message}")
            }
        }

        private var target: java.awt.Point? = null

        private fun execute(step: Step, targets: Targets, pointer: Pointer) {
            when (step) {
                is Step.Open -> {
                    targets.openFile(step.path)
                    // Opening is asynchronous enough that an immediate Caret can land in the
                    // outgoing editor rather than the incoming one.
                    Thread.sleep(700)
                }
                is Step.Caret -> target = targets.caret(step.line, step.anchor)
                is Step.Glide -> pointer.glide(
                    target ?: error("Glide before any Caret - nothing to glide to"), step.ms)
                is Step.Click -> {
                    target?.let { if (it != pointer.at()) pointer.jump(it) }
                    pointer.click(step.ctrl)
                    Thread.sleep(400)
                }
                is Step.Popup -> {
                    // Let the popup finish appearing before its rows are measured.
                    Thread.sleep(500)
                    val row = targets.popupRow(step.label)
                    pointer.glide(row, 450)
                    Thread.sleep(350)
                    pointer.click(ctrl = false)
                    Thread.sleep(400)
                }
                is Step.Action -> {
                    invokeAction(step.id)
                    Thread.sleep(600)
                }
                is Step.Key -> pointer.key(step.name)
                is Step.Sleep -> Thread.sleep(step.ms.toLong())
            }
        }

        /**
         * Invokes an IDE action against the focused component's own data context.
         *
         * An empty context would look like it worked and do nothing: actions such as `Back`
         * read the project and editor out of the context, so with `EMPTY_CONTEXT` they find
         * no target and quietly disable themselves mid-take.
         */
        private fun invokeAction(id: String) {
            val action = ActionManager.getInstance().getAction(id)
                ?: error("no such IDE action id: '$id'")
            ApplicationManager.getApplication().invokeAndWait {
                val focused = IdeFocusManager.getInstance(project).focusOwner
                    ?: WindowManager.getInstance().getFrame(project)?.rootPane
                    ?: error("nothing focused to run '$id' against")
                val context = DataManager.getInstance().getDataContext(focused)
                ActionUtil.invokeAction(action, context, ActionPlaces.UNKNOWN, null, null)
            }
        }

        private fun describe(step: Step): String = when (step) {
            is Step.Open -> "open ${step.path}"
            is Step.Caret -> "caret \"${step.anchor}\""
            is Step.Glide -> "glide ${step.ms}ms"
            is Step.Click -> if (step.ctrl) "ctrl+click" else "click"
            is Step.Popup -> "pick \"${step.label}\""
            is Step.Action -> "action ${step.id}"
            is Step.Key -> "key ${step.name}"
            is Step.Sleep -> "sleep ${step.ms}ms"
        }
    }

    private companion object {
        fun notify(project: Project, type: NotificationType, message: String) {
            NotificationGroupManager.getInstance()
                .getNotificationGroup("Demo Driver")
                .createNotification(message, type)
                .notify(project)
        }
    }
}
