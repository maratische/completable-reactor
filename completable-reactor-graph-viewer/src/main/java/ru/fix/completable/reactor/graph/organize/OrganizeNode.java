package ru.fix.completable.reactor.graph.organize;

import javafx.collections.ObservableList;
import javafx.scene.Node;
import ru.fix.completable.reactor.graph.viewer.GraphViewer;
import ru.fix.completable.reactor.graph.viewer.ProcessorNode;

import java.util.Collections;
import java.util.List;

/**
 * Reorganize Nodes on Screen
 *
 * @author maratische
 */
public class OrganizeNode {

    public static void organize(ObservableList<Node> nodes,
                                List<GraphViewer.CoordinateItem> coordinateItems,
                                GraphViewer.ActionListener actionListener) {
        if (nodes != null && coordinateItems.size() > 0){
            for (Node node : nodes) {
                if (node instanceof ProcessorNode) {
                    node.setLayoutX(node.getLayoutX()+100);
//                    GraphViewer.CoordinateItem coordinateItem = coordinateItems.get(0);
//                    coordinateItem.setX(coordinateItem.getX() + 100);
//                    actionListener.coordinatesChanged(coordinateItems);
                }
            }
        }
    }
}
