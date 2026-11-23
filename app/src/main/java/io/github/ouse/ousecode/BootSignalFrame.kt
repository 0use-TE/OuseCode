package io.github.ouse.ousecode

import android.annotation.TargetApi
import android.graphics.Canvas
import android.graphics.RenderNode

/** GPU-resident display list shared by the tear strips; never reads pixels to the CPU. */
@TargetApi(29)
internal class BootSignalFrame {
    private val node = RenderNode("OpenCode boot signal").apply { setUseCompositingLayer(true, null) }
    var ready = false
        private set
    private var width = 0
    private var height = 0

    fun record(w: Int, h: Int, draw: (Canvas) -> Unit) {
        width = w; height = h; node.setPosition(0, 0, w, h)
        val canvas = node.beginRecording(w, h)
        try { draw(canvas) } finally { node.endRecording() }
        ready = true
    }

    fun draw(canvas: Canvas, w: Int, h: Int) {
        canvas.save()
        canvas.scale(w.toFloat() / width, h.toFloat() / height)
        canvas.drawRenderNode(node)
        canvas.restore()
    }

    fun release() { node.discardDisplayList(); ready = false }
}
