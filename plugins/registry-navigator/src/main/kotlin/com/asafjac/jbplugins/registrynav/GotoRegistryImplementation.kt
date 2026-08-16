package com.asafjac.jbplugins.registrynav

import com.intellij.codeInsight.navigation.actions.GotoDeclarationHandler
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement

/**
 * Contributes each implementation's answer for the registry segment under the caret.
 *
 * Targets are added to whatever the IDE already resolves rather than replacing it, so
 * nothing that navigated before stops navigating.
 */
class GotoRegistryImplementation : GotoDeclarationHandler {

    override fun getGotoDeclarationTargets(
        sourceElement: PsiElement?,
        offset: Int,
        editor: Editor?,
    ): Array<PsiElement>? {
        val element = sourceElement ?: return null
        val hits = SlotResolver.resolveAtCaret(element, offset, SlotTrace())
        if (hits.isEmpty()) return null

        // Wrapped so each popup row names the registry it came from; with one row per
        // implementation that is the only thing telling near-identical names apart.
        return hits
            .map { RegistryTargetElement(it.target, it.registryName, it.label) }
            .toTypedArray()
    }

    override fun getActionText(context: DataContext): String? = null
}
