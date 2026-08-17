package com.asafjac.jbplugins.demodriver

import com.intellij.ide.DataManager
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.WindowManager
import java.io.File

/**
 * Performs a tape and captures it.
 *
 * Shared by the menu action and the tool window so there is exactly one execution path: two
 * copies would drift, and the one that drifted would be the one nobody was testing.
 */
object TapeRunner {

    fun launch(project: Project, tapeName: String, tape: Tape, onFinished: () -> Unit = {}) {
        ProgressManager.getInstance().run(RunTask(project, tapeName, tape, onFinished))
    }

    fun notify(project: Project, type: NotificationType, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Demo Driver")
            .createNotification(message, type)
            .notify(project)
    }

    /**
     * A background task, because every Robot call has to be off the EDT: those calls post native
     * events the EDT must then consume, so running them on it deadlocks the IDE.
     */
    private class RunTask(
        project: Project,
        private val tapeName: String,
        private val tape: Tape,
        private val onFinished: () -> Unit,
    ) : Task.Backgroundable(project, "Running $tapeName", true) {

        override fun run(indicator: ProgressIndicator) {
            val project = project ?: return
            val targets = Targets(project)
            val pointer = Pointer()
            val capture = Capture(tape.settings, File(project.basePath ?: "."))

            indicator.isIndeterminate = false
            var sampler: Thread? = null
            val track = java.util.Collections.synchronizedList(mutableListOf<FollowSample>())

            try {
                val recording = tape.settings.outputs.isNotEmpty()
                if (recording) {
                    indicator.text = "Resolving frame"
                    val region = targets.resolveCrop(tape.settings, tape.steps)
                    capture.redactions = tape.settings.redact.map { targets.componentBounds(it) }
                    capture.start(region)
                    // Give the recorder a moment to attach before anything moves, or the first
                    // second of every take is a blank frame.
                    Thread.sleep(1200)
                    sampler = startSampler(tape.settings.crop, targets, pointer, track)
                }

                tape.steps.forEachIndexed { index, step ->
                    indicator.checkCanceled()
                    indicator.fraction = index.toDouble() / tape.steps.size
                    indicator.text = "Step ${index + 1}/${tape.steps.size}: ${describe(step)}"
                    execute(step, targets, pointer)
                }

                sampler?.interrupt()
                indicator.text = "Rendering"
                val written = capture.finish(track.toList())

                notify(project, NotificationType.INFORMATION, buildString {
                    append("$tapeName finished (${tape.steps.size} steps)")
                    if (written.isNotEmpty()) {
                        append(". Wrote ").append(written.joinToString(", ") { it.name })
                    }
                })
            } catch (e: Exception) {
                sampler?.interrupt()
                capture.abort()
                if (indicator.isCanceled) return
                notify(project, NotificationType.ERROR,
                    "$tapeName failed at: ${indicator.text ?: "?"} - ${e.message}")
            } finally {
                onFinished()
            }
        }

        /**
         * Samples the viewport centre while the take runs, for a moving-viewport crop.
         *
         * Only a Follow crop needs this, and it is deliberately a plain thread rather than an IDE
         * listener: the positions wanted are the ones on screen at wall-clock times that line up
         * with recorded frames, which is not what an event callback gives.
         */
        private fun startSampler(
            crop: Crop,
            targets: Targets,
            pointer: Pointer,
            into: MutableList<FollowSample>,
        ): Thread? {
            val follow = crop as? Crop.Follow ?: return null
            val started = System.currentTimeMillis()
            return Thread {
                runCatching {
                    while (!Thread.currentThread().isInterrupted) {
                        val at = System.currentTimeMillis() - started
                        val point = if (follow.what == "caret") targets.caretPointNow() else pointer.at()
                        if (point != null) into.add(FollowSample(at, point))
                        Thread.sleep(50)
                    }
                }
            }.apply { isDaemon = true; start() }
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
         * An empty context would look like it worked and do nothing: actions such as `Back` read
         * the project and editor out of the context, so with `EMPTY_CONTEXT` they find no target
         * and quietly disable themselves mid-take.
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
    }

    fun describe(step: Step): String = when (step) {
        is Step.Open -> "open ${step.path}"
        is Step.Caret -> "caret \"${step.anchor}\"" + if (step.line > 0) " (line ${step.line})" else ""
        is Step.Glide -> "glide ${step.ms}ms"
        is Step.Click -> if (step.ctrl) "ctrl+click" else "click"
        is Step.Popup -> "popup pick \"${step.label}\""
        is Step.Action -> "action ${step.id}"
        is Step.Key -> "key ${step.name}"
        is Step.Sleep -> "sleep ${step.ms}ms"
    }
}
