package com.asafjac.jbplugins.registrynav

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile

/**
 * Reports what the resolver saw at the caret.
 *
 * Exists because a silent miss is otherwise indistinguishable from the plugin not being
 * loaded at all, and the two have completely different fixes. It calls the same
 * [SlotResolver] entry point the Go to Declaration handler uses, so it cannot succeed where
 * real navigation fails.
 */
class DiagnoseSlotAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.getData(CommonDataKeys.EDITOR) != null &&
            event.getData(CommonDataKeys.PSI_FILE) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        val file: PsiFile = event.getData(CommonDataKeys.PSI_FILE) ?: return

        val offset = editor.caretModel.offset
        val element = file.findElementAt(offset)

        val trace = SlotTrace()
        trace.log("Plugin: Registry Navigator (loaded)")
        trace.log("File: ${file.name}  (language: ${file.language.id})")
        trace.log("Caret offset: $offset")
        trace.log("Element at caret: ${element?.javaClass?.simpleName ?: "null"} '${element?.text?.take(40) ?: ""}'")
        trace.log("")

        val hits = if (element == null) {
            trace.log("MISS: no PSI element at the caret.")
            emptyList()
        } else {
            SlotResolver.resolveAtCaret(element, offset, trace)
        }

        val verdict = if (hits.isNotEmpty()) {
            "RESULT: Go to Declaration will offer ${hits.size} target(s)."
        } else {
            "RESULT: nothing to contribute - Go to Declaration behaves as it normally would."
        }

        Messages.showMultilineInputDialog(
            project,
            "Copy this and send it along if navigation isn't working:",
            "Registry Navigator - Diagnostics",
            "$trace\n\n$verdict",
            Messages.getInformationIcon(),
            null,
        )
    }
}
