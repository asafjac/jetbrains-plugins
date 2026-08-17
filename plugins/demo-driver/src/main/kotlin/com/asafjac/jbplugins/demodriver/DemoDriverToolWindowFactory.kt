package com.asafjac.jbplugins.demodriver

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

/** Registers the Demo Driver panel as a tool window. */
class DemoDriverToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DemoDriverPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        toolWindow.contentManager.addContent(content)
    }

    // Available in any project: a tape can live anywhere, and hiding the window until one exists
    // would make the feature undiscoverable.
    override fun shouldBeAvailable(project: Project) = true
}
