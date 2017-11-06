package ru.fix.completable.reactor.graph.organize;

import javafx.collections.ObservableList;
import javafx.scene.Node;
import lombok.val;
import ru.fix.completable.reactor.api.ReactorGraphModel;
import ru.fix.completable.reactor.graph.viewer.EndPointNode;
import ru.fix.completable.reactor.graph.viewer.GraphViewer;
import ru.fix.completable.reactor.graph.viewer.ProcessorNode;
import ru.fix.completable.reactor.graph.viewer.TransitionLine;

import java.util.*;
import java.util.stream.Collectors;

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

    static int deltaY = 100;
    Map<ReactorGraphModel.Identity, ReactorGraphModel.Processor> processors = null;
    Map<ReactorGraphModel.Identity, ReactorGraphModel.MergePoint> mergePoints = null;

    /**
     * рекурсия пробегает по всему графу, разбивая действия на уровни
     * @param graphModel
     */
    public void organize(ReactorGraphModel graphModel) {
        int level = 0;
        processors = graphModel.processors.stream().collect(Collectors.toMap(i -> i.getIdentity(), i -> i));
        mergePoints = graphModel.mergePoints.stream().collect(Collectors.toMap(i -> i.getIdentity(), i -> i));

        ReactorGraphModel.StartPoint startPoint = graphModel.getStartPoint();
        List<ReactorGraphModel.Identity> identityList = startPoint.getProcessingItems();
        for (ReactorGraphModel.Identity identity : identityList) {
            ReactorGraphModel.Processor processor = processors.get(identity);
            setTreeLevel(processor, level+1);
        }
    }

    private void setTreeLevel(ReactorGraphModel.Point point, int level) {
        if (point != null) {
            point.getCoordinates().setY(deltaY + level);
            if (point instanceof ReactorGraphModel.Processor) {
                ReactorGraphModel.Processor processor = (ReactorGraphModel.Processor) point;
                ReactorGraphModel.MergePoint mergePoint = mergePoints.get(point.getIdentity());
                setTreeLevel(mergePoint, level + 1);
            } else if (point instanceof ReactorGraphModel.MergePoint) {
                ReactorGraphModel.MergePoint mergePoint = (ReactorGraphModel.MergePoint) point;
                if (mergePoint.transitions != null) {
                    for (val transition : mergePoint.transitions) {
                        setTreeLevel(getPointFromTransition(transition), level + 1);
                    }
                }
            }
        }
    }

    private ReactorGraphModel.Point getPointFromTransition(ReactorGraphModel.Transition transition) {
        if (transition.isComplete) {

//            val endPointNode = new EndPointNode(
//                    translator,
//                    mergePoint,
//                    transition,
//                    actionListener,
//                    coordinateItems);
        } else if (transition.isOnAny) {
            if (transition.getHandleByProcessingItem() != null) {
                return processors.get(transition.getHandleByProcessingItem());
            } else if (transition.mergeProcessingItem != null) {
                return mergePoints.get(transition.mergeProcessingItem);
            }
        } else if (transition.getHandleByProcessingItem() != null) {
            return processors.get(transition.getHandleByProcessingItem());
        } else if (transition.mergeProcessingItem != null) {
            return mergePoints.get(transition.mergeProcessingItem);
        }
        return null;
    }
}
