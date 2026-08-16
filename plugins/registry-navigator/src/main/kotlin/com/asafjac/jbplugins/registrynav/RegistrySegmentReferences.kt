package com.asafjac.jbplugins.registrynav

import com.intellij.openapi.util.TextRange
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.PsiReferenceBase
import com.intellij.psi.PsiReferenceContributor
import com.intellij.psi.PsiReferenceProvider
import com.intellij.psi.PsiReferenceRegistrar
import com.intellij.psi.xml.XmlToken
import com.intellij.util.ProcessingContext

/**
 * Narrows the Ctrl-hover highlight on a JSX registry tag to the segment under the cursor.
 *
 * A JSX tag name is one XML token, and the JavaScript plugin puts a single reference across
 * all of it, so `<FooRegistry.qux.Baz />` underlines end to end and
 * reads as one indivisible link. Contributing a reference per segment gives the platform a
 * narrower range to prefer, so each part highlights - and navigates - on its own.
 *
 * This deliberately does not replace the existing reference. References accumulate, and the
 * platform picks among them; a segment we cannot resolve contributes nothing at all, leaving
 * the JavaScript plugin's own reference as the only one and normal JSX navigation intact.
 */
class RegistrySegmentReferenceContributor : PsiReferenceContributor() {

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement(XmlToken::class.java),
            RegistrySegmentReferenceProvider(),
            // Above the default so the narrower, segment-scoped reference is preferred over
            // the full-width one when both contain the offset.
            PsiReferenceRegistrar.HIGHER_PRIORITY,
        )
    }
}

private class RegistrySegmentReferenceProvider : PsiReferenceProvider() {

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext,
    ): Array<PsiReference> {
        val text = element.text ?: return PsiReference.EMPTY_ARRAY
        if (!text.contains('.')) return PsiReference.EMPTY_ARRAY

        val parts = text.split('.')
        if (parts.size < 2 || parts.any { it.isEmpty() }) return PsiReference.EMPTY_ARRAY

        // Only claim a tag we actually understand. Resolving here costs one lookup per JSX
        // tag containing a dot, so it is gated on the name looking like a registry first.
        if (!SlotResolver.isRegistryClassName(parts[0])) return PsiReference.EMPTY_ARRAY

        val references = mutableListOf<PsiReference>()
        var cursor = 0
        parts.forEachIndexed { index, part ->
            val range = TextRange(cursor, cursor + part.length)
            references.add(RegistrySegmentReference(element, range, index))
            cursor += part.length + 1
        }
        return references.toTypedArray()
    }
}

/**
 * One segment of a dotted JSX registry tag name.
 *
 * Resolves to the base implementation; the overriding implementations are contributed separately by
 * [GotoRegistryImplementation], which turns a click into the multi-implementation popup.
 */
private class RegistrySegmentReference(
    element: PsiElement,
    range: TextRange,
    private val segmentIndex: Int,
) : PsiReferenceBase<PsiElement>(element, range, /* soft = */ true) {

    override fun resolve(): PsiElement? {
        val offset = element.textRange.startOffset + rangeInElement.startOffset
        return SlotResolver.resolveAtCaret(element, offset, SlotTrace())
            .firstOrNull { it.target != element }
            ?.target
    }

    /**
     * Marked soft, and resolving to null when the segment is not understood, so an
     * unrecognized tag is reported by the JavaScript plugin's reference as before rather
     * than as an unresolved-symbol error raised by this one.
     */
    override fun getVariants(): Array<Any> = emptyArray()

    override fun toString(): String = "RegistrySegmentReference(segment=$segmentIndex)"
}
