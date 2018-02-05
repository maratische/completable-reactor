package ru.fix.completable.reactor.graph.viewer.gl

import javafx.scene.layout.VBox
import ru.fix.completable.reactor.model.Figure

open class GraphNode(override val figure: Figure) : VBox(), AutoLayoutable {

    override val graphChildren = ArrayList<GraphNode>()

    override var positionX: Double
        get() = layoutX
        set(value) {
            layoutY = value
        }

    override var positionY: Double
        get() = layoutY
        set(value) {
            layoutY = value
        }
    override val isUserDefinedPosition: Boolean
        get() = figure.coordinates != null
}