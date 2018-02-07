package ru.fix.completable.reactor.graph.viewer.gl;

import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.ScrollPane
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Pane
import ru.fix.completable.reactor.model.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

interface PositionListener {
    fun positionChanged()
}

/**
 * Created by Kamil Asfandiyarov
 */
class GraphViewPane(
        val actionListener: GraphViewer.ActionListener,
        val shortcutProvider: (ShortcutType) -> Shortcut?
) : ScrollPane() {

    val MIN_SCALE = 0.3
    val MAX_SCALE = 2.0

    val WORLD_SIZE = 10000.0
    val ZOOM_CHANGE_FACTOR = 1.15

    val worldSize = WORLD_SIZE

    private val pane = Pane()

    private val translator = CoordinateTranslator(WORLD_SIZE)

    private val autoLayout = AutoLayout()

    var graphModel: GraphModel? = null
        private set

    init {

        pane.getStyleClass().add("graphViewer")

        this.setPannable(true)

        this.setContent(pane)

        pane.setPrefSize(this.worldSize, this.worldSize)
        this.setVvalue(0.5)
        this.setHvalue(0.5)


        pane.setOnScroll { scrollEvent ->

            if (scrollEvent.isControlDown) {
                scrollEvent.consume()

                var zoomChangeFactor =
                        if (scrollEvent.deltaY > 0) {
                            ZOOM_CHANGE_FACTOR
                        } else {
                            1 / ZOOM_CHANGE_FACTOR
                        }

                val newScaleX = pane.scaleX * zoomChangeFactor
                val newScaleY = pane.scaleY * zoomChangeFactor

                if (newScaleX > MIN_SCALE && newScaleX < MAX_SCALE && newScaleY > MIN_SCALE && newScaleY < MAX_SCALE) {
                    pane.scaleX = newScaleX
                    pane.scaleY = newScaleY
                }

            } else {
                /**
                 * To prevent strange behavior on scroll within IDE in MacOS
                 */
                scrollEvent.consume()
            }
        }


        initializePopupMenu()

        subscribeScrollingPayloadListenerOnResizeEvent();
    }

    fun initializePopupMenu() {
        val contextMenu = ContextMenu()

        val buildGraphMenuItem = MenuItem("Graph location")
        buildGraphMenuItem.setOnAction { graphModel?.startPoint?.source?.let { actionListener.goToSource(it) } }
        contextMenu.items.add(buildGraphMenuItem)


        pane.setOnContextMenuRequested { contextMenuEvent ->
            contextMenu.show(pane, contextMenuEvent.getScreenX(), contextMenuEvent.getScreenY())
            contextMenuEvent.consume()
        }

        this.addEventHandler(MouseEvent.MOUSE_PRESSED, { event ->
            contextMenu.hide()
        })
    }


    fun openGraph(graphModel: GraphModel): GraphViewPane {
        this.graphModel = graphModel

        pane.children.removeAll()

        val graphNodeTreeForAutoLayout = AtomicReference<GraphNode>()

        val positionListener = object : PositionListener {

            private val nodeTree: GraphNode by lazy(LazyThreadSafetyMode.NONE) { graphNodeTreeForAutoLayout.get() }

            override fun positionChanged() {

                autoLayout.layout(nodeTree)

                actionListener.coordinatesChanged(graphModel)
            }
        }

        /**
         * Handler Nodes
         */
        val handlers = HashMap<Handler, HandlerNode>()

        for (handler in graphModel.handlers.values) {
            val handlerNode = HandlerNode(
                    translator,
                    handler,
                    actionListener,
                    positionListener)

            handlers[handler] = handlerNode
            pane.children.add(handlerNode)
        }

        /**
         * Subgraph Nodes
         */
        val subgraphs = HashMap<Subgraph, SubgraphNode>()

        for (subgraph in graphModel.subgraphs.values) {
            val subgraphNode = SubgraphNode(
                    translator,
                    subgraph,
                    actionListener,
                    positionListener
            )
            subgraphs[subgraph] = subgraphNode
            pane.children.add(subgraphNode)
        }

        /**
         * StartPoint Node
         */
        val startPointNode = StartPointNode(
                translator,
                graphModel.startPoint,
                actionListener,
                positionListener)
        pane.children.add(startPointNode)

        graphNodeTreeForAutoLayout.set(startPointNode)

        /**
         * EndPoint Node
         */
        val endpoints = HashMap<EndPoint, EndPointNode>()
        for (endPoint in graphModel.endpoints.values) {
            val endPointNode = EndPointNode(
                    translator,
                    endPoint,
                    actionListener,
                    positionListener)
            endpoints[endPoint] = endPointNode
            pane.children.addAll(endPointNode)
        }

        /**
         * Mergers
         */
        val mergers = HashMap<Merger, MergerNode>()
        for (merger in graphModel.mergers.values) {
            val mergerNode = MergerNode(
                    translator,
                    merger,
                    actionListener,
                    positionListener)
            mergers[merger] = mergerNode
            pane.children.add(mergerNode)
        }

        /**
         * Routers
         */
        val routers = HashMap<Router, RouterNode>()
        for (router in graphModel.routers.values) {
            val routerNode = RouterNode(
                    translator,
                    router,
                    actionListener,
                    positionListener)
            routers[router] = routerNode
            pane.children.add(routerNode)
        }

        fun transitionable(figure: TransitionableFigure): GraphNode =
                when (figure) {
                    is Merger -> mergers[figure]!!
                    is Router -> routers[figure]!!
                    else -> throw IllegalArgumentException("figure: $figure")
                }

        fun figureNode(figure: Figure): GraphNode {
            return when (figure) {
                is Subgraph -> subgraphs[figure]!!
                is Handler -> handlers[figure]!!
                is Merger -> mergers[figure]!!
                is Router -> routers[figure]!!
                is EndPoint -> endpoints[figure]!!
                is StartPoint -> startPointNode
            }
        }

        /**
         * Transition line: handler -> merger
         */
        graphModel.handlers.values.asSequence()
                .mapNotNull { handler ->
                    graphModel.mergers[handler.name]?.let { merger ->
                        Pair(handlers[handler]!!, mergers[merger]!!)
                    }
                }
                .forEach { (handlerNode, mergerNode) ->

                    val line = TransitionLine(pane, handlerNode, mergerNode, null, actionListener)
                    pane.children.add(line)
                    line.toBack()

                    handlerNode.graphChildren.add(mergerNode)
                }

        /**
         * Transition line: subgraph -> merger
         */
        graphModel.handlers.values.asSequence()
                .mapNotNull { handler ->
                    graphModel.mergers[handler.name]?.let { merger ->
                        Pair(handlers[handler]!!, mergers[merger]!!)
                    }
                }
                .forEach { (handlerNode, mergerNode) ->

                    val line = TransitionLine(pane, handlerNode, mergerNode, null, actionListener)
                    pane.children.add(line)
                    line.toBack()

                    handlerNode.graphChildren.add(mergerNode)
                }

        /**
         * Transition line: merger, router -> any
         */
        graphModel.transitionable.values.asSequence()
                .filter { it.transitions.isNotEmpty() }
                .map { Pair(it, transitionable(it)) }
                .forEach { (transitionable, transitionableNode) ->
                    for (transition in transitionable.transitions) {

                        val line = TransitionLine(
                                pane,
                                transitionableNode,
                                figureNode(transition.target),
                                transition,
                                actionListener)

                        pane.children.add(line)
                        line.toBack()

                        transitionableNode.graphChildren.add(figureNode(transition.target))
                    }
                }


        /**
         * StartPoint outgoing transitions
         */
        graphModel.startPoint.handleBy.forEach { figure ->
            val line = TransitionLine(
                    pane,
                    startPointNode,
                    figureNode(figure),
                    null,
                    actionListener
            )
            pane.children.add(line)
            line.toBack()

            startPointNode.graphChildren.add(figureNode(figure))
        }


        /**
         * Bring mergers and routers to front
         */
        mergers.values.forEach { it.toFront() }
        routers.values.forEach { it.toFront() }

        /**
         * Scroll pane so Payload Node would be in center position
         */
        val startPointCoordinates = graphModel.startPoint.coordinates ?: DEFAULT_COORDINATES

        this.hvalue = (WORLD_SIZE / 2 + startPointCoordinates.x) / WORLD_SIZE
        this.vvalue = (WORLD_SIZE / 2 + startPointCoordinates.y) / WORLD_SIZE

        enableSingleScrollingPayloadToTopCenterOnFirstResize()

        autoLayout.layout(startPointNode)

        return this
    }

    private val payloadIsWidthCentralized = AtomicBoolean()
    private val payloadIsHeightCentralized = AtomicBoolean()

    private fun enableSingleScrollingPayloadToTopCenterOnFirstResize() {
        payloadIsWidthCentralized.set(false)
        payloadIsHeightCentralized.set(false)
    }

    private fun subscribeScrollingPayloadListenerOnResizeEvent() {
        this.heightProperty().addListener { observable, oldValue, newValue ->

            val model = graphModel ?: return@addListener

            val startPointCoordinates = model.startPoint.coordinates ?: DEFAULT_COORDINATES

            if (payloadIsHeightCentralized.compareAndSet(false, true)) {
                val startPointY = startPointCoordinates.y
                val approximateMargin = 50.0
                this.vvalue = ((WORLD_SIZE / 2 + startPointY + newValue.toDouble() / 2 - approximateMargin) /
                        WORLD_SIZE)
            }
        }

        this.widthProperty().addListener { observable, oldValue, newValue ->

            val model = graphModel ?: return@addListener

            val startPointCoordinates = model.startPoint.coordinates ?: DEFAULT_COORDINATES

            if (payloadIsWidthCentralized.compareAndSet(false, true)) {
                val startPointX = startPointCoordinates.x
                val approximatePayloadNodeWidth = 100.0
                this.hvalue = (WORLD_SIZE / 2 + startPointX + approximatePayloadNodeWidth / 2) / WORLD_SIZE
            }
        }
    }

}