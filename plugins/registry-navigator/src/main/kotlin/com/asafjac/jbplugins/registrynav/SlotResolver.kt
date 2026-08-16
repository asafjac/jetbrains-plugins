package com.asafjac.jbplugins.registrynav

import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifier
import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifierAlias
import com.intellij.lang.ecmascript6.psi.ES6ImportedBinding
import com.intellij.lang.javascript.psi.JSExpression
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSObjectLiteralExpression
import com.intellij.lang.javascript.psi.JSReferenceExpression
import com.intellij.lang.javascript.psi.JSReturnStatement
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.lang.javascript.psi.stubs.JSSuperClassIndex
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.stubs.StubIndex
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlTag

/** One implementation's answer for whichever segment the caret is on. */
data class RegistryHit(
    /** The class this answer comes from - "AcmeFooRegistry". */
    val registryName: String,
    val target: PsiElement,
    /** Popup row title: the component name, or the registry name when there isn't one. */
    val label: String,
)

/**
 * Everything the resolver learned on one invocation. Carried alongside the result so the
 * Diagnose action can explain a miss without a second, differently-behaving code path -
 * the usual reason "it works in the diagnostic but not in the editor".
 */
class SlotTrace {
    val steps = mutableListOf<String>()
    fun log(message: String) { steps.add(message) }
    override fun toString(): String = steps.joinToString("\n")
}

/**
 * Resolves a registry access to what each implementation supplies.
 *
 * A qualified access has one meaning per segment, and the segment under the caret decides
 * which is wanted. For `<FooRegistry.qux.Baz />`:
 *
 *   FooRegistry -> the registry classes  (BaseFooRegistry, AcmeFooRegistry, ZedFooRegistry)
 *   qux     -> the slot getters      (get qux() in each of them)
 *   Baz         -> the components        (BaseBaz, AcmeBaz, ZedBaz)
 *
 * Subclasses are found by superclass *name* via [JSSuperClassIndex] rather than by PSI
 * inheritance. Overriding registries import their base from a package rather than a relative
 * path, so the `extends` clause can resolve to a bundled `.d.ts` while the consumer's type
 * points at the source class - two different PsiElements, across which an inheritance
 * search finds nothing. Matching on the name sidesteps the question.
 */
object SlotResolver {

    fun isRegistryClassName(name: String?): Boolean = name != null && name.contains("Registry")

    /** A qualified registry access, and which of its segments the caret is on. */
    data class Access(
        val parts: List<String>,
        val segmentIndex: Int,
        val registryClass: JSClass,
        /** Document range of the segment under the caret, when it is known (JSX). */
        val segmentRange: TextRange?,
    )

    /** Hits for the segment under the caret, or an empty list. */
    fun resolveAtCaret(element: PsiElement, offset: Int, trace: SlotTrace): List<RegistryHit> {
        val access = accessAt(element, offset, trace)
        if (access == null) {
            trace.log("MISS: caret is not on a qualified registry access.")
            return emptyList()
        }

        val segment = access.parts[access.segmentIndex]
        trace.log("Segments: ${access.parts.joinToString(".")}")
        trace.log("Caret on segment ${access.segmentIndex}: '$segment'")
        trace.log("Registry class: '${access.registryClass.name}' in ${fileOf(access.registryClass)}")

        val hits = when (access.segmentIndex) {
            // The registry itself: answer with the classes, which is where a reader goes to
            // see what each implementation customizes.
            0 -> registryClassHits(access.registryClass, trace)
            // A slot, possibly followed by a path into the object it returns.
            else -> slotHits(
                slotName = access.parts[1],
                nestedPath = access.parts.subList(2, access.segmentIndex + 1),
                declaringClass = access.registryClass,
                trace = trace,
            )
        }

        trace.log("Hits: ${hits.size}")
        hits.forEach { trace.log("  ${it.registryName} -> ${it.label}") }
        return hits
    }

    /** The access under the caret, resolved from a JSX tag name or a plain expression. */
    fun accessAt(element: PsiElement, offset: Int, trace: SlotTrace): Access? =
        jsxAccess(element, offset, trace) ?: expressionAccess(element, trace)

    /**
     * `<FooRegistry.qux.Baz />`.
     *
     * XML holds a dotted tag name in one token, so segments are read off the token text and
     * the caret offset picks one. The registry class comes from resolving the *leading*
     * segment, which depends only on the registry variable's declared type rather than on
     * how the IDE chooses to resolve the rest of a JSX tag name.
     */
    private fun jsxAccess(element: PsiElement, offset: Int, trace: SlotTrace): Access? {
        if (PsiTreeUtil.getParentOfType(element, XmlTag::class.java, false) == null) return null

        val text = element.text ?: return null
        val parts = text.split('.')
        if (parts.size < 2 || parts.any { it.isEmpty() }) return null

        val start = element.textRange.startOffset
        val index = segmentIndexAt(parts, start, offset) ?: return null
        trace.log("JSX tag name: '$text'")

        val registryTarget = element.containingFile
            ?.findReferenceAt(start + parts[0].length / 2)
            ?.resolve()
        trace.log("registry '${parts[0]}' -> ${describe(registryTarget)}")

        val registryClass = classFromDeclaredType(registryTarget, trace)
            ?.takeIf { isRegistryClassName(it.name) }
            ?: return null

        return Access(parts, index, registryClass, segmentRange(parts, start, index))
    }

    /** Which dotted segment contains [offset]; null if the caret is past the name. */
    private fun segmentIndexAt(parts: List<String>, start: Int, offset: Int): Int? {
        var cursor = start
        parts.forEachIndexed { index, part ->
            // The trailing boundary belongs to the segment, so a caret placed at the end of
            // a word - where a click usually lands - resolves that word rather than failing.
            if (offset <= cursor + part.length) return index
            cursor += part.length + 1
        }
        return null
    }

    private fun segmentRange(parts: List<String>, start: Int, index: Int): TextRange {
        val from = start + parts.take(index).sumOf { it.length + 1 }
        return TextRange(from, from + parts[index].length)
    }

    /**
     * `FooRegistry.qux.Baz` outside JSX. The innermost reference containing
     * the caret already ends at the segment the caret is on, so the chain length is the
     * segment index.
     */
    private fun expressionAccess(element: PsiElement, trace: SlotTrace): Access? {
        // Caret on a getter's own declaration inside a registry file.
        val getter = PsiTreeUtil.getParentOfType(element, JSFunction::class.java)
        if (getter != null && getter.isGetProperty && getter.nameIdentifier?.textRange == element.textRange) {
            val owner = PsiTreeUtil.getParentOfType(getter, JSClass::class.java)
            if (owner != null && isRegistryClassName(owner.name)) {
                trace.log("Caret is on the getter declaration itself.")
                return Access(listOf(owner.name!!, getter.name!!), 1, owner, null)
            }
        }

        val reference = PsiTreeUtil.getParentOfType(element, JSReferenceExpression::class.java)
            ?: return null
        trace.log("Expression: '${reference.text.take(100)}'")

        val chain = referenceChain(reference)
        val parts = chain.mapNotNull { it.referenceName }
        if (parts.size != chain.size) return null

        val registryTarget = chain[0].resolve()
        trace.log("registry '${parts[0]}' -> ${describe(registryTarget)}")
        val registryClass = classFromDeclaredType(registryTarget, trace)
            ?.takeIf { isRegistryClassName(it.name) }
            ?: return null

        return Access(parts, chain.size - 1, registryClass, null)
    }

    /**
     * The reference chain outermost-qualifier first: `FooRegistry.qux.Baz`
     * yields [`FooRegistry`, `FooRegistry.qux`, `FooRegistry.qux.Baz`].
     *
     * Built by walking qualifiers rather than by splitting text, so it survives whitespace
     * and comments inside the chain.
     */
    private fun referenceChain(innermost: JSReferenceExpression): List<JSReferenceExpression> {
        val chain = ArrayDeque<JSReferenceExpression>()
        var current: JSReferenceExpression? = innermost
        var guard = 0
        while (current != null && guard++ < MAX_CHAIN) {
            chain.addFirst(current)
            current = current.qualifier as? JSReferenceExpression
        }
        return chain.toList()
    }

    /** Walks a variable declaration to the class named by its type annotation. */
    private fun classFromDeclaredType(target: PsiElement?, trace: SlotTrace): JSClass? {
        if (target == null) return null
        if (target is JSClass) return target

        val viaType = PsiTreeUtil.findChildOfType(target, JSReferenceExpression::class.java)?.resolve()
        trace.log("declared-type lookup -> ${describe(viaType)}")
        return viaType as? JSClass
    }

    /** Every registry class in the hierarchy, base first. */
    private fun registryClassHits(registryClass: JSClass, trace: SlotTrace): List<RegistryHit> {
        val classes = linkedSetOf(registryClass)
        collectSubclassesByName(registryClass, classes, trace)
        return classes.map { RegistryHit(it.name ?: "<anonymous>", it, it.name ?: "<anonymous>") }
    }

    /** Every class declaring the slot, resolved through [nestedPath] where given. */
    private fun slotHits(
        slotName: String,
        nestedPath: List<String>,
        declaringClass: JSClass,
        trace: SlotTrace,
    ): List<RegistryHit> {
        val root = rootDeclaringClass(slotName, declaringClass)
        val classes = linkedSetOf(root)
        collectSubclassesByName(root, classes, trace)

        return classes
            .mapNotNull { jsClass ->
                val getter = findGetter(jsClass, slotName) ?: return@mapNotNull null
                val component = if (nestedPath.isEmpty()) {
                    resolveComponent(returnedExpression(getter))
                } else {
                    // An override that spreads `...super.qux` without redefining this key has
                    // no answer of its own; dropping it keeps the popup to implementations that
                    // actually differ, as a non-overriding subclass is already dropped.
                    descend(getter, nestedPath) ?: return@mapNotNull null
                }
                val registryName = jsClass.name ?: "<anonymous>"
                RegistryHit(
                    registryName = registryName,
                    // A slot returning an object literal has no component to open, so the
                    // getter is the answer - that is exactly the case for a bare `qux`.
                    target = component ?: getter,
                    label = (component as? PsiNamedElement)?.name ?: "$registryName.$slotName",
                )
            }
            .distinctBy { it.target }
    }

    /**
     * Walks a path of property names into the object literal a slot returns:
     * `qux` + ["Baz"] lands on `AcmeBaz`.
     */
    private fun descend(getter: JSFunction, path: List<String>): PsiElement? {
        var literal = returnedExpression(getter) as? JSObjectLiteralExpression ?: return null

        path.forEachIndexed { index, name ->
            val property = literal.properties.firstOrNull { it.name == name } ?: return null
            // Shorthand (`{ Baz }`) may carry no explicit value node; the
            // name itself is then the reference to resolve.
            val value = property.value
                ?: PsiTreeUtil.findChildOfType(property, JSReferenceExpression::class.java)
                ?: return null

            if (index == path.lastIndex) return resolveComponent(value)
            literal = value as? JSObjectLiteralExpression ?: return null
        }
        return null
    }

    /**
     * Transitively collects subclasses by superclass *name*, so a overriding registry is found
     * whether or not its `extends` clause resolves to the same PsiElement as the base.
     */
    private fun collectSubclassesByName(root: JSClass, into: MutableSet<JSClass>, trace: SlotTrace) {
        val project = root.project
        val scope = GlobalSearchScope.allScope(project)
        val queue = ArrayDeque<String>()
        root.name?.let { queue.add(it) }
        val visited = mutableSetOf<String>()

        while (queue.isNotEmpty() && into.size < MAX_CLASSES) {
            val superName = queue.removeFirst()
            if (!visited.add(superName)) continue

            val subclasses = subclassesNamed(superName, project, scope)
            trace.log("JSSuperClassIndex['$superName'] -> ${subclasses.size} subclass(es)")
            for (subclass in subclasses) {
                if (into.add(subclass)) subclass.name?.let { queue.add(it) }
            }
        }
    }

    private fun subclassesNamed(
        superName: String,
        project: Project,
        scope: GlobalSearchScope,
    ): Collection<JSClass> =
        StubIndex.getElements(JSSuperClassIndex.KEY, superName, project, scope, JSClass::class.java)

    /** The base-most class still declaring the slot, following `extends` by name. */
    private fun rootDeclaringClass(slotName: String, from: JSClass): JSClass {
        var current = from
        repeat(MAX_DEPTH) {
            val parent = current.superClasses.firstOrNull() ?: return current
            if (parent == current || findGetter(parent, slotName) == null) return current
            current = parent
        }
        return current
    }

    private fun findGetter(jsClass: JSClass, slotName: String): JSFunction? =
        jsClass.functions.firstOrNull { it.isGetProperty && it.name == slotName }

    /** The expression a getter hands back - a component reference, or an object literal. */
    private fun returnedExpression(getter: JSFunction): JSExpression? {
        val body = getter.block ?: return null
        return PsiTreeUtil.findChildOfType(body, JSReturnStatement::class.java)?.expression
    }

    private fun resolveComponent(expression: JSExpression?): PsiElement? {
        val resolved = (expression as? JSReferenceExpression)?.resolve() ?: return null
        return followImport(resolved)
    }

    /**
     * Steps from an import binding to the declaration it names. Without this, navigation
     * lands in the registry's own import block - a declaration, but not an answer.
     */
    private fun followImport(start: PsiElement): PsiElement {
        var current = start
        repeat(MAX_IMPORT_HOPS) {
            val isImport = current is ES6ImportedBinding ||
                current is ES6ImportSpecifier ||
                current is ES6ImportSpecifierAlias
            if (!isImport) return current
            val next = current.reference?.resolve() ?: return current
            if (next == current) return current
            current = next
        }
        return current
    }

    private fun describe(element: PsiElement?): String = when (element) {
        null -> "null"
        else -> element.javaClass.simpleName +
            ((element as? PsiNamedElement)?.name?.let { " '$it'" } ?: "") +
            " in ${fileOf(element)}"
    }

    private fun fileOf(element: PsiElement): String =
        element.containingFile?.virtualFile?.name ?: element.containingFile?.name ?: "<no file>"

    private const val MAX_CHAIN = 12
    private const val MAX_DEPTH = 16
    private const val MAX_IMPORT_HOPS = 8
    private const val MAX_CLASSES = 200
}
