package com.asafjac.jbplugins.demodriver

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable

/**
 * Turns hover documentation off for the duration of a take, when the tape asks.
 *
 * The replay moves a real pointer across code, which raises quick documentation and gutter
 * tooltips that were never in the recording and cover the thing being demonstrated. It is opt-out
 * rather than always-off because a demo of a hover feature needs them.
 *
 * The previous value is captured and restored, including when the take fails: leaving somebody's
 * editor permanently changed by a recording would be a poor trade for a tidier GIF.
 */
class HoverDocs(private val wanted: Boolean) {

    private var previous: Boolean? = null

    fun applyForTake() {
        if (wanted) return
        ApplicationManager.getApplication().invokeAndWait {
            val settings = EditorSettingsExternalizable.getInstance()
            previous = settings.isShowQuickDocOnMouseOverElement
            settings.isShowQuickDocOnMouseOverElement = false
        }
    }

    fun restore() {
        val was = previous ?: return
        previous = null
        ApplicationManager.getApplication().invokeAndWait {
            EditorSettingsExternalizable.getInstance().isShowQuickDocOnMouseOverElement = was
        }
    }
}
