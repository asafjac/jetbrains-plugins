package com.asafjac.jbplugins.demodriver

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys

/**
 * Runs the tape in the current editor.
 *
 * Acting on the open file rather than asking through a chooser: a tape is edited and re-run many
 * times in a row, and a dialog on every run is friction in exactly the loop this plugin exists to
 * shorten. The tool window is the richer surface; this stays for the keyboard path.
 */
class RunTapeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        val name = event.getData(CommonDataKeys.VIRTUAL_FILE)?.name
        event.presentation.isEnabledAndVisible = event.project != null
        event.presentation.text =
            if (name?.endsWith(".tape") == true) "Run Demo Tape: $name" else "Run Demo Tape"
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val file = event.getData(CommonDataKeys.VIRTUAL_FILE)

        if (file == null || !file.name.endsWith(".tape")) {
            TapeRunner.notify(project, NotificationType.WARNING,
                "Open a .tape file and run this again, or use the Demo Driver tool window.")
            return
        }

        val tape = try {
            TapeParser.parse(String(file.contentsToByteArray(), Charsets.UTF_8))
        } catch (e: Exception) {
            TapeRunner.notify(project, NotificationType.ERROR, "Could not read ${file.name}: ${e.message}")
            return
        }
        if (tape.steps.isEmpty()) {
            TapeRunner.notify(project, NotificationType.WARNING, "${file.name} has no steps.")
            return
        }

        // Resolve ffmpeg before the take rather than during it: finding out at render time that
        // there is no encoder means the performance was for nothing.
        val resolved = if (tape.settings.outputs.isEmpty()) tape.settings.ffmpeg
        else Ffmpeg.ensure(project, tape.settings.ffmpeg) ?: return

        TapeRunner.launch(project, file.name, tape.copy(settings = tape.settings.copy(ffmpeg = resolved)))
    }
}
