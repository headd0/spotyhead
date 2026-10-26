/*
 * Vendored from Kyant0/backdrop v2.0.0 (io.github.kyant0:backdrop)
 * https://github.com/Kyant0/backdrop — Copyright 2025 Kyant0, Apache License 2.0
 *
 * Vendored so the library ships as source with this app (binary AARs compiled
 * against older Compose broke at runtime) and to add a backdrop resolution
 * scale for cheaper effect rendering. KMP expect/actual declarations were
 * merged into this single Android source set. Package renamed accordingly.
 * Myspoti copy taken from BitChord's vendored tree (github.com/kushagrasinghx/BitChord,
 * app/src/main/java/com/music/bitchord/ui/components/backdrop at edc1e6f), which adds
 * backdropScale and exportedBackdrop to drawBackdrop. Package renamed again for Myspoti.
 */
package com.music.spotui.ui.immersive.shell.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import com.music.spotui.ui.immersive.shell.backdrop.Backdrop
import com.music.spotui.ui.immersive.shell.backdrop.internal.InverseLayerScope

private val DefaultOnDraw: ContentDrawScope.() -> Unit = { drawContent() }

@Composable
fun rememberLayerBackdrop(
    graphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    onDraw: ContentDrawScope.() -> Unit = DefaultOnDraw
): LayerBackdrop {
    return remember(graphicsLayer, onDraw) {
        LayerBackdrop(graphicsLayer, onDraw)
    }
}

@Stable
class LayerBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer,
    internal val onDraw: ContentDrawScope.() -> Unit
) : Backdrop {

    override val isCoordinatesDependent: Boolean = true

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)

    /**
     * The nodes recording into this backdrop, oldest first. During a page transition two pages
     * record at once; the newest one (the page arriving) owns [layerCoordinates], so the one
     * leaving can neither overwrite it with its own position nor blank it when it detaches.
     */
    internal class Source {
        var coordinates: LayoutCoordinates? = null
        /** This source's own recording: every page keeps its own, so two pages in a transition are both sampled. */
        var layer: GraphicsLayer? = null
    }
    internal val sources = ArrayList<Source>()

    internal fun publish(source: Source) {
        if (sources.lastOrNull() === source) layerCoordinates = source.coordinates
    }

    internal fun retire(source: Source) {
        sources.remove(source)
        layerCoordinates = sources.lastOrNull()?.coordinates
    }

    private var inverseLayerScope: InverseLayerScope? = null

    /** Size of the largest recording (0x0 when no page has recorded yet). */
    internal fun recordedSize(): androidx.compose.ui.unit.IntSize =
        sources.mapNotNull { it.layer?.size }.maxByOrNull { it.width.toLong() * it.height } ?: androidx.compose.ui.unit.IntSize.Zero

    /** Whether [c] sits inside one of the recorded pages (it would then draw itself into itself). */
    internal fun isInsideSource(c: LayoutCoordinates): Boolean = sources.any { s ->
        var n: LayoutCoordinates? = c
        var inside = false
        while (n != null) { if (n === s.coordinates) { inside = true; break }; n = n.parentCoordinates }
        inside
    }

    /**
     * Draws every attached page's recording, oldest first, each at its page's current position
     * ([offsetOf] maps the page's coordinates to the offset of the surface inside it). During a
     * transition both pages are drawn where they are this frame, so the glass sees what is on screen.
     */
    internal fun DrawScope.drawSources(offsetOf: (LayoutCoordinates) -> Offset) {
        for (s in sources.toList()) {
            val c = s.coordinates ?: continue
            val layer = s.layer ?: continue
            if (layer.isReleased) {
                app.myspoti.host.android.Logbook.log("glass: backdrop source sampled after release, skipped")
                continue
            }
            if (!c.isAttached || layer.size.width <= 0) continue
            val o = offsetOf(c)
            withTransform({ translate(-o.x, -o.y) }) { drawLayer(layer) }
        }
    }

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val coordinates = coordinates ?: return
        withTransform({
            if (layerBlock != null) {
                with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
            }
        }) {
            drawSources { src ->
                try {
                    src.localPositionOf(coordinates)
                } catch (_: Exception) {
                    coordinates.positionInWindow() - src.positionInWindow()
                }
            }
        }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}
