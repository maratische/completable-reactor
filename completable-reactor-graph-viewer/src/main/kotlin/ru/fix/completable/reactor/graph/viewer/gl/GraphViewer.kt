package ru.fix.completable.reactor.graph.viewer.gl

import javafx.scene.Scene
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.layout.Pane
import jdk.internal.org.objectweb.asm.Handle
import ru.fix.completable.reactor.api.ReactorGraphModel
import ru.fix.completable.reactor.graph.viewer.gl.code.CoordinateCodePhrase
import ru.fix.completable.reactor.graph.viewer.model.TreeNode
import ru.fix.completable.reactor.model.*
import java.util.ArrayList
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Created by Kamil Asfandiyarov.
 */
class GraphViewer {
    var scene: Scene
        private set

    private var actionListeners = CopyOnWriteArrayList<ActionListener>()

    private val viewPaneActionListener = object : ActionListener {
        override fun goToSource(source: Source) {
            for (listener in actionListeners) {
                listener.goToSource(source)
            }
        }

        override fun goToSubgraph(subgraphPayloadClass: String) {
            for (listener in actionListeners) {
                listener.goToSubgraph(subgraphPayloadClass)
            }
        }

        override fun coordinatesChanged(graphModel: GraphModel) {
            for (listener in actionListeners) {
                listener.coordinatesChanged(graphModel)
            }
        }
    }

    private val shortcuts: MutableMap<Shortcut, ShortcutType> = ConcurrentHashMap()

    init {
        scene = Scene(Pane())
        scene.stylesheets.add(javaClass.getResource("/css/styles.css").toExternalForm())
    }

    /**
     * fix default coordinates on nodes in graph
     *
     * @param graph
     */
    fun fixCoordinates(graph: GraphModel) {
        val treeNode = TreeNode(graph.startPoint as Figure)
        if (graph.startPoint != null && graph.startPoint.handleBy != null) {
            for (handle in graph.startPoint.handleBy) {
                if (handle is VertexFigure) {
                    recursiveBuldTree(graph, treeNode, handle)
                }
            }
        }
        recursiveFixCoordinates(treeNode,
                graph.startPoint.coordinates!!.x, graph.startPoint.coordinates!!.y, 200, 100)
    }

    private fun recursiveBuldTree(graph: GraphModel, parentNode: TreeNode<Figure>, handler: VertexFigure) {
        val node = parentNode.addChild(handler)
        if (handler is TransitionableFigure) {
            processTransition(handler.transitions, graph, node, parentNode)
        } else {
            val merger = graph.mergers.get(handler.name)
            if (merger != null) {
                val mergerNode = node.addChild(merger)
                processTransition(merger.transitions, graph, mergerNode, parentNode)
            }
        }
    }

    private fun processTransition(transitions: MutableList<Transition>, graph: GraphModel, node: TreeNode<Figure>, parentNode: TreeNode<Figure>) {
        if (transitions != null) {
            for (transition in transitions) {
                if (transition.target != null) {
                    when (transition.target) {
                        is VertexFigure -> {
                            recursiveBuldTree(graph, node, transition.target as VertexFigure)
                        }
                        is EndPoint -> {
                            parentNode.addChild(transition.target)
                        }
                    }
                }
            }
        }
    }

    internal var DEFAULT_POSITION = 100

    private fun recursiveFixCoordinates(parentNode: TreeNode<Figure>, parentX: Int, parentY: Int, deltaX: Int, deltaY: Int) {
        var index = -1 * (parentNode.childs().size / 2)
        for (`object` in parentNode.childs()) {
            val node = `object`
            if (node.data is Figure) {
                if (node.data.coordinates == null) {
                    node.data.coordinates = Coordinates(deltaX * index++ + parentX, deltaY + parentY)
                }
                recursiveFixCoordinates(node, node.data.coordinates!!.x, node.data.coordinates!!.y, deltaX, deltaY)
            } else {
                node.data
            }
        }
    }

    fun openGraph(graphs: List<GraphModel>) {
        if (graphs.isEmpty()) {
            return
        }

        if (graphs.size == 1) {

            var graphViewPane = GraphViewPane(viewPaneActionListener, { this.getShortcut(it) })
            graphViewPane.setPrefSize(700.0, 600.0)

            scene.root = graphViewPane

            fixCoordinates(graphs[0]);
            graphViewPane.openGraph(graphs[0])

            //Shortcuts
            addShortcutListener(graphViewPane)

        } else {
            val previouslySelectedTabIndex = scene.root
                    .let { it as? TabPane }
                    ?.selectionModel?.selectedIndex


            val tabPane = TabPane()
            tabPane.setPrefSize(700.0, 600.0)
            scene.root = tabPane

            for (graph in graphs) {
                var graphViewPane = GraphViewPane(viewPaneActionListener, { this.getShortcut(it) })

                val tab = Tab().apply {
                    text = "${graph.graphClass}<${graph.startPoint.payloadType}>"
                    content = graphViewPane
                }
                tabPane.tabs.add(tab)

                fixCoordinates(graph);
                graphViewPane.openGraph(graph)

                addShortcutListener(graphViewPane)
            }

            if (previouslySelectedTabIndex != null && previouslySelectedTabIndex < tabPane.tabs.size) {
                tabPane.selectionModel.select(previouslySelectedTabIndex)
            }
        }
    }

    private fun addShortcutListener(pane: GraphViewPane) {
        pane.setOnKeyReleased { keyEvent ->
            shortcuts.forEach { shortcut, shortcutType ->
                if (shortcut.getPredicate().test(keyEvent)) {
                    when (shortcutType) {
                        ShortcutType.GOTO_GRAPH -> pane.graphModel?.startPoint?.source?.let {
                            viewPaneActionListener.goToSource(it)
                        }
                    }
                }

            }
        }
    }


    fun registerListener(actionListener: ActionListener): GraphViewer {
        actionListeners.add(actionListener)
        return this
    }

    fun setShortcut(shortcutType: ShortcutType, shortcut: Shortcut) {
        shortcuts[shortcut] = shortcutType
    }

    fun getShortcut(shortcutType: ShortcutType): Shortcut? {
        for ((shortcut, type) in shortcuts) {
            if (type == shortcutType) {
                return shortcut
            }
        }
        return null
    }

    interface ActionListener {
        /**
         * Viewer asks IDE to navigate to source code
         *
         * @param source source code location
         */
        fun goToSource(source: Source)

        /**
         * Viewer asks IDE to navigate to subgraph view
         * @param subgraphPayloadType payload class name
         */
        fun goToSubgraph(subgraphPayloadType: String)

        /**
         * Graph nodes coordinates changed
         *
         * @param coordinateItems new coordinates
         */
        fun coordinatesChanged(graphModel: GraphModel)
    }
}