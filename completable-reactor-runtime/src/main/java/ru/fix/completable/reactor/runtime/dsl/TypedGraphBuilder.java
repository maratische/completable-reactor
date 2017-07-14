package ru.fix.completable.reactor.runtime.dsl;

/**
 * @author Kamil Asfandiyarov
 */
public interface TypedGraphBuilder<PayloadType> {

    HandlerBuilder0<PayloadType> processor();

     <SubgraphPayloadType> SubgraphHandlerBuilder<SubgraphPayloadType, PayloadType> subgraph(
             Class<SubgraphPayloadType> subgraphPayload);

    MergePointMergerBuilder<PayloadType> mergePoint();
}

