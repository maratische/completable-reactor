package ru.fix.completable.reactor.runtime;

import lombok.extern.slf4j.Slf4j;
import lombok.val;
import ru.fix.completable.reactor.api.Reactored;
import ru.fix.completable.reactor.runtime.dsl.*;
import ru.fix.completable.reactor.runtime.internal.CRReactorGraph;
import ru.fix.completable.reactor.runtime.internal.ReactorReflector;
import ru.fix.completable.reactor.runtime.internal.dsl.*;
import ru.fix.completable.reactor.runtime.validators.ProcessorsHaveIncomingFlowsValidator;
import ru.fix.completable.reactor.runtime.validators.TerminalVertexExistValidator;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Provides fluent API for building {@link ReactorGraph}
 *
 * @author Kamil Asfandiyarov
 */
@Slf4j
public class ReactorGraphBuilder<PayloadType> {

    private final Class<PayloadType> payloadType;
    private final Object graphConfiguration;

    public ReactorGraphBuilder(Class<PayloadType> payloadType, Object graphConfiguration) {
        this.payloadType = payloadType;
        this.graphConfiguration = graphConfiguration;
    }

    /**
     * Build ProcessorDescription
     */
    public HandlerBuilder0<PayloadType> processor() {
        val processorDescription = new CRProcessorDescription<PayloadType>();
        return new CRHandlerBuilder0<>(processorDescription);
    }

    /**
     * Build SubgraphDescription
     */
    public <SubgraphPayloadType> SubgraphHandlerBuilder<SubgraphPayloadType, PayloadType> subgraph(Class<SubgraphPayloadType> subgraphPayload) {
        CRSubgraphDescription<PayloadType> subgraphDescription = new CRSubgraphDescription<>(subgraphPayload);
        subgraphDescription.setBuildSource(ReactorReflector.getMethodInvocationPoint().orElse(null));

        subgraphDescription.setSubgraphTitle(subgraphPayload.getSimpleName());

        Optional.ofNullable(subgraphPayload.getAnnotation(Reactored.class))
                .map(Reactored::value)
                .ifPresent(subgraphDescription::setSubgraphDoc);

        return new CRSubgraphHandlerBuilder<>(subgraphDescription);
    }

    /**
     * Build MergePointDescription
     */
    public MergePointMergerBuilder<PayloadType> mergePoint() {
        CRMergePointDescription<PayloadType> mergePointDescription = new CRMergePointDescription<>();
        return new CRMergePointMergerBuilder<>(mergePointDescription);
    }

    /**
     * Build ReactorGraph for given payload
     */
    public PayloadBuilder<PayloadType> payload() {
        val builderContext = new BuilderContext<PayloadType>(graphConfiguration, new CRReactorGraph<>(payloadType));

        builderContext.getGraphValidators().add(new TerminalVertexExistValidator());
        builderContext.getGraphValidators().add(new ProcessorsHaveIncomingFlowsValidator());

        builderContext.getGraph().getStartPoint().setBuilderPayloadSource(
                ReactorReflector.getMethodInvocationPoint().orElse(null));

        return new CRPayloadBuilder<>(builderContext);
    }


    /**
     * Write *.rg file representation of graph structure
     * *.rg files used by IDE and Graph Viewer to visualize graph
     *
     * @param graphs
     * @throws Exception
     */
    public static void write(ReactorGraph<?>... graphs) throws Exception {
        for (ReactorGraph<?> graph : graphs) {

            val model = graph.serialize();
            model.serializationPointSource = ReactorReflector.getMethodInvocationPoint().orElse(null);

            val content = ReactorMarshaller.marshall(model);

            val path = Paths.get(((CRReactorGraph) graph).getPayloadClass().getName() + ".rg");
            Files.write(path, content.getBytes(StandardCharsets.UTF_8));
        }
    }
}
