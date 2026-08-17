package com.asafjac.jbplugins.demodriver

import java.awt.Point
import java.awt.Robot
import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/**
 * Drives the real pointer and keyboard.
 *
 * Every method here must run OFF the EDT. Robot's press/release calls post native events
 * that the EDT has to consume; calling them from the EDT deadlocks the IDE. Coordinates
 * arrive already resolved from [Targets], which does run on the EDT.
 */
class Pointer {

    private val robot = Robot().apply { isAutoWaitForIdle = false }

    fun at(): Point = java.awt.MouseInfo.getPointerInfo().location

    fun jump(to: Point) = robot.mouseMove(to.x, to.y)

    /**
     * Moves in eased steps rather than jumping.
     *
     * A jump reads as a cut on video, which is the whole reason a recorded demo looks
     * machine-made. Ease-in-out over ~40 steps reads as a hand moving a mouse.
     */
    fun glide(to: Point, ms: Int) {
        val from = at()
        val steps = 40
        val delay = (ms.toLong() / steps).coerceAtLeast(1L)
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            val e = if (t < 0.5) 2 * t * t else -1 + (4 - 2 * t) * t
            robot.mouseMove(
                (from.x + (to.x - from.x) * e).toInt(),
                (from.y + (to.y - from.y) * e).toInt(),
            )
            Thread.sleep(delay)
        }
        robot.mouseMove(to.x, to.y)
    }

    fun click(ctrl: Boolean) {
        if (ctrl) {
            robot.keyPress(KeyEvent.VK_CONTROL)
            // Long enough for the ctrl-hover underline to render before the click, so the
            // recording shows the link state the viewer expects to see.
            Thread.sleep(HOVER_MS)
        }
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        Thread.sleep(70)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        if (ctrl) {
            Thread.sleep(100)
            robot.keyRelease(KeyEvent.VK_CONTROL)
        }
    }

    fun key(name: String) {
        val code = KEYS[name.lowercase()]
            ?: throw IllegalArgumentException("unknown key '$name' (known: ${KEYS.keys.sorted()})")
        robot.keyPress(code)
        Thread.sleep(40)
        robot.keyRelease(code)
    }

    private companion object {
        const val HOVER_MS = 700L
        val KEYS = mapOf(
            "escape" to KeyEvent.VK_ESCAPE,
            "esc" to KeyEvent.VK_ESCAPE,
            "enter" to KeyEvent.VK_ENTER,
            "tab" to KeyEvent.VK_TAB,
            "up" to KeyEvent.VK_UP,
            "down" to KeyEvent.VK_DOWN,
            "left" to KeyEvent.VK_LEFT,
            "right" to KeyEvent.VK_RIGHT,
        )
    }
}
