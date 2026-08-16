package com.asafjac.jbplugins.registrynav

import com.intellij.navigation.ItemPresentation
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.impl.FakePsiElement
import javax.swing.Icon

/**
 * A component, labelled with the registry that supplies it.
 *
 * The Go to Declaration popup renders each target through its [ItemPresentation], and a bare
 * component gives "AcmeBar - AcmeBar.tsx". That answers "what is
 * it called", when the question being asked is "which implementation is this one". Wrapping lets the
 * row read `AcmeBar (AcmeFooRegistry)`, so a five-row popup is
 * distinguishable at a glance instead of five near-identical file names.
 *
 * Every structural call delegates to the real component, so selecting a row navigates exactly
 * as it would without the wrapper.
 */
class RegistryTargetElement(
    private val component: PsiElement,
    private val registryName: String,
    private val label: String,
) : FakePsiElement() {

    override fun getParent(): PsiElement = component

    /** Navigation and inspection should always see through to the real component. */
    override fun getNavigationElement(): PsiElement = component.navigationElement

    override fun getContainingFile(): PsiFile? = component.containingFile

    override fun getTextOffset(): Int = component.textOffset

    override fun isValid(): Boolean = component.isValid

    override fun getName(): String? = (component as? com.intellij.psi.PsiNamedElement)?.name

    override fun canNavigate(): Boolean = true

    override fun canNavigateToSource(): Boolean = true

    override fun navigate(requestFocus: Boolean) {
        (component.navigationElement as? com.intellij.pom.Navigatable)
            ?.takeIf { it.canNavigate() }
            ?.navigate(requestFocus)
    }

    override fun getPresentation(): ItemPresentation = object : ItemPresentation {
        override fun getPresentableText(): String = label

        /**
         * Shown dimmed after the name. The registry leads because it is what distinguishes
         * one row from another; the file name follows for the case where two implementations happen
         * to name their component the same thing.
         */
        override fun getLocationString(): String {
            val file = component.containingFile?.name
            return if (file != null) "$registryName - $file" else registryName
        }

        override fun getIcon(unused: Boolean): Icon? = component.getIcon(0)
    }

    override fun equals(other: Any?): Boolean =
        other is RegistryTargetElement &&
            other.component == component &&
            other.registryName == registryName &&
            other.label == label

    override fun hashCode(): Int = 31 * component.hashCode() + registryName.hashCode()
}
