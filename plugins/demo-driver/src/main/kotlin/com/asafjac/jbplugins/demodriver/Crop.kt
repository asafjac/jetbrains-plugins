package com.asafjac.jbplugins.demodriver

/**
 * What the recording contains.
 *
 * Ordered here from most durable to least. [Window], [Editor] and [Component] name things the
 * IDE can locate at run time, so they survive a resize, a font change or a newly docked panel.
 * [Region] is absolute pixels and is the one mode that needs redoing when anything moves - it
 * exists because sometimes those exact pixels are the point, not because it is a good default.
 */
sealed interface Crop {

    /** The whole IDE frame. */
    data object Window : Crop

    /** The editor component, excluding its tabs. */
    data object Editor : Crop

    /**
     * A named IDE part: `editor`, `editors` (editor plus tabs), `content`, or any tool
     * window id such as `Project` or `Terminal`.
     */
    data class Component(val name: String) : Crop

    /** Absolute screen pixels. */
    data class Region(val x: Int, val y: Int, val width: Int, val height: Int) : Crop

    /**
     * The bounding box of every target the tape touches.
     *
     * Resolved by walking the tape's own steps before recording starts, which is the tightest
     * frame that still contains the whole demo: nothing on screen the demo never touches.
     */
    data object Fit : Crop

    /**
     * A fixed-size viewport that tracks something as the take runs.
     *
     * [what] is `mouse` or `caret`. Unlike every other mode this cannot be satisfied by
     * choosing a rectangle once - the frame moves during capture, so the full area is
     * recorded and the moving window is applied afterwards from a sampled position track.
     */
    data class Follow(val what: String, val width: Int, val height: Int, val easeMs: Int) : Crop
}
