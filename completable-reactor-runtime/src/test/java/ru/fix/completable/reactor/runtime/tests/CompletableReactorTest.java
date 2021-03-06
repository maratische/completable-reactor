package ru.fix.completable.reactor.runtime.tests;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;
import lombok.experimental.var;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;
import ru.fix.commons.profiler.impl.SimpleProfiler;
import ru.fix.completable.reactor.api.Reactored;
import ru.fix.completable.reactor.runtime.CompletableReactor;
import ru.fix.completable.reactor.runtime.ReactorGraph;
import ru.fix.completable.reactor.runtime.ReactorGraphBuilder;
import ru.fix.completable.reactor.runtime.dsl.MergePoint;
import ru.fix.completable.reactor.runtime.dsl.Processor;
import ru.fix.completable.reactor.runtime.dsl.Subgraph;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.Assert.*;

/**
 * @author Kamil Asfandiyarov
 */
@Slf4j
public class CompletableReactorTest {

    private SimpleProfiler profiler;
    private CompletableReactor reactor;


    enum Status {OK, UNUSED}


    @Reactored({
            "IdListPayload contains list of integer ids.",
            " When IdListPayload goes through processors chain each processor adds their id",
            " so at the end we can clarify by witch processor and in what order this payload was processed."})
    @Data
    @Accessors(chain = true)
    static class IdListPayload {
        final List<Integer> idSequence = new ArrayList<>();

    }

    @Before
    public void before() throws Exception {
        profiler = new SimpleProfiler();
        reactor = new CompletableReactor(profiler)
                .setDebugProcessingVertexGraphState(true);
    }

    Processor<IdListPayload> buildProcessor(ReactorGraphBuilder builder, IdProcessor idProcessor) {
        return builder.processor()
                .forPayload(IdListPayload.class)
                .withHandler(idProcessor::handle)
                .withMerger((pld, id) -> {
                    pld.getIdSequence().add(id);
                    return Status.OK;
                })
                .buildProcessor();
    }

    static void printGraph(ReactorGraph<?>... graphs) throws Exception {
        ReactorGraphBuilder.write(graphs);
    }


    @Reactored({
            "Test will check that single processor id end up at payloads idList.",
            "Expected result: {1}"
    })
    static class SingleProcessorPayload extends IdListPayload {
    }

    @Test
    public void single_processor() throws Exception {

        class Config {
            final ReactorGraphBuilder builder = new ReactorGraphBuilder(this);


            Processor<IdListPayload> idProcessor1 = buildProcessor(builder, new IdProcessor(1));

            ReactorGraph buildGraph() {
                return builder.payload(SingleProcessorPayload.class)
                        .handle(idProcessor1)

                        .mergePoint(idProcessor1)
                        .onAny().complete()

                        .coordinates()
                        .start(226, 98)
                        .proc(idProcessor1, 261, 163)
                        .merge(idProcessor1, 300, 251)
                        .complete(idProcessor1, 308, 336)

                        .buildGraph();
            }
        }
        ;

        val graph = new Config().buildGraph();

        printGraph(graph);

        reactor.registerReactorGraph(graph);

        SingleProcessorPayload resultPayload = reactor.submit(new SingleProcessorPayload())
                .getResultFuture()
                .get(10, TimeUnit.SECONDS);

        assertEquals(Arrays.asList(1), resultPayload.getIdSequence());
    }

    @Reactored({
            "Test will check that two processor ids end up at payloads idList in correct order.",
            "Expected result: {1, 2}"
    })
    static class TwoProcessorSequentialMergePayload extends IdListPayload {
    }

    @Test
    public void two_processors_sequential_merge() throws Exception {

        class Config {
            final ReactorGraphBuilder builder = new ReactorGraphBuilder(this);

            Processor<IdListPayload> idProcessor1 = buildProcessor(builder, new IdProcessor(1));
            Processor<IdListPayload> idProcessor2 = buildProcessor(builder, new IdProcessor(2));

            ReactorGraph buildGraph() {
                return builder.payload(TwoProcessorSequentialMergePayload.class)


                        .handle(idProcessor1)
                        .handle(idProcessor2)

                        .mergePoint(idProcessor1)
                        .on(Status.OK, Status.UNUSED).merge(idProcessor2)

                        .mergePoint(idProcessor2)
                        .onAny().complete()

                        .coordinates()
                        .start(366, 103)
                        .proc(idProcessor1, 358, 184)
                        .proc(idProcessor2, 549, 183)
                        .merge(idProcessor1, 427, 291)
                        .merge(idProcessor2, 571, 356)
                        .complete(idProcessor2, 610, 454)

                        .buildGraph();
            }

        }
        val graph = new Config().buildGraph();

        printGraph(graph);

        reactor.registerReactorGraph(graph);


        TwoProcessorSequentialMergePayload resultPayload = reactor.submit(new TwoProcessorSequentialMergePayload())
                .getResultFuture()
                .get(10, TimeUnit.SECONDS);

        assertEquals(Arrays.asList(1, 2), resultPayload.getIdSequence());
    }

    @Reactored({
            "Detached processor is a processor without merger.",
            "Main flow dow not wait for detached processor to complete.",
            "In test Detached processor will run and complete deferred in time.",
            "When result of chain is ready detached processor will be activated.",
            "Test will check that chain execution will complete on detached processor finish.",
            "Expected result: {1, 2}"
    })
    static class DetachedProcessorPayload extends IdListPayload {
    }

    @Test
    public void detached_processor() throws Exception {

        IdProcessor detachedProcessor = new IdProcessor(3).withLaunchingLatch();

        class Config {
            final ReactorGraphBuilder builder = new ReactorGraphBuilder(this);

            Processor<IdListPayload> idProcessor1 = buildProcessor(builder, new IdProcessor(1));
            Processor<IdListPayload> idProcessor2 = buildProcessor(builder, new IdProcessor(2));

            Processor<DetachedProcessorPayload> idProcessor3 = builder.processor()
                    .forPayload(DetachedProcessorPayload.class)
                    .withHandler(detachedProcessor::handle)
                    .withoutMerger()
                    .buildProcessor();

            ReactorGraph buildGraph() {
                return builder.payload(DetachedProcessorPayload.class)
                        .handle(idProcessor1)
                        .handle(idProcessor2)
                        .handle(idProcessor3)

                        .mergePoint(idProcessor1)
                        .on(Status.OK).merge(idProcessor2)

                        .mergePoint(idProcessor2)
                        .onAny().complete()

                        .coordinates()
                        .start(489, 96)
                        .proc(idProcessor1, 364, 178)
                        .proc(idProcessor2, 530, 180)
                        .proc(idProcessor3, 701, 172)
                        .merge(idProcessor1, 414, 268)
                        .merge(idProcessor2, 589, 341)
                        .complete(idProcessor2, 701, 378)

                        .buildGraph();
            }
        }

        val graph = new Config().buildGraph();
        printGraph(graph);

        reactor.registerReactorGraph(graph);

        CompletableReactor.Execution<DetachedProcessorPayload> result = reactor.submit(new DetachedProcessorPayload());

        assertEquals(Arrays.asList(1, 2), result.getResultFuture().get(5, TimeUnit.SECONDS).getIdSequence());

        assertTrue("result future is complete", result.getResultFuture().isDone());
        assertFalse("execution chain is not complete since detached processor still working", result.getChainExecutionFuture().isDone());

        detachedProcessor.launch();

        result.getChainExecutionFuture().get(5, TimeUnit.SECONDS);
        assertTrue("execution chain is complete when detached processor is finished", result.getChainExecutionFuture().isDone());
    }


    @Reactored({
            "Subgraph behave the same way as plain processor.",
            "The only difference is that instead of simple async operation CompletableReactor launches subgraph execution"
    })
    static class SubgraphPayload extends IdListPayload {
    }

    @Reactored({
            "Parent graph is a simple graph that calls subgraph during its flow."
    })
    static class ParentGraphPayload extends IdListPayload {
    }

    @Test
    public void run_subgraph() throws Exception {

        class Config {
            ReactorGraphBuilder builder = new ReactorGraphBuilder(this);


            Processor<IdListPayload> idProcessor11 = buildProcessor(builder, new IdProcessor(11));
            Processor<IdListPayload> idProcessor12 = buildProcessor(builder, new IdProcessor(12));
            Processor<IdListPayload> idProcessor13 = buildProcessor(builder, new IdProcessor(13));


            ReactorGraph<SubgraphPayload> childGraph() {
                return builder
                        .payload(SubgraphPayload.class)
                        .handle(idProcessor11)
                        .handle(idProcessor12)

                        .mergePoint(idProcessor11).onAny().merge(idProcessor12)
                        .mergePoint(idProcessor12).onAny().handle(idProcessor13)

                        .mergePoint(idProcessor13).onAny().complete()
                        .coordinates()
                        .proc(idProcessor11, 306, 216)
                        .proc(idProcessor12, 612, 218)
                        .proc(idProcessor13, 539, 596)
                        .merge(idProcessor11, 430, 365)
                        .merge(idProcessor12, 620, 421)
                        .merge(idProcessor13, 613, 693)
                        .start(500, 100)
                        .complete(idProcessor13, 587, 776)

                        .buildGraph();
            }


            Processor<IdListPayload> idProcessor1 = buildProcessor(builder, new IdProcessor(1));
            Processor<IdListPayload> idProcessor2 = buildProcessor(builder, new IdProcessor(2));
            Processor<IdListPayload> idProcessor3 = buildProcessor(builder, new IdProcessor(3));

            Subgraph<ParentGraphPayload> subgraphProcessor = builder.subgraph(SubgraphPayload.class)
                    .forPayload(ParentGraphPayload.class)
                    .passArg(payload -> new SubgraphPayload())
                    .withMerger((payload, result) -> {
                        payload.getIdSequence().addAll(result.getIdSequence());
                        return Status.OK;
                    })
                    .buildSubgraph();

            ReactorGraph<ParentGraphPayload> parentGraph() {
                return builder.payload(ParentGraphPayload.class)

                        .handle(idProcessor1)

                        .mergePoint(idProcessor1)
                        .onAny().handle(idProcessor2)
                        .onAny().handle(subgraphProcessor)

                        .mergePoint(subgraphProcessor).onAny().merge(idProcessor2)
                        .mergePoint(idProcessor2).onAny().handle(idProcessor3)

                        .mergePoint(idProcessor3).onAny().complete()
                        .coordinates()

                        .proc(idProcessor1, 406, 228)
                        .proc(idProcessor2, 626, 408)
                        .proc(idProcessor3, 415, 730)
                        .proc(subgraphProcessor, 195, 418)
                        .merge(idProcessor1, 475, 342)
                        .merge(subgraphProcessor, 341, 565)
                        .merge(idProcessor2, 488, 620)
                        .merge(idProcessor3, 490, 840)
                        .start(460, 120)
                        .complete(idProcessor3, 460, 930)

                        .buildGraph();
            }
        }

        Config config = new Config();
        val childGraph = config.childGraph();
        val parentGraph = config.parentGraph();

        reactor.registerReactorGraph(childGraph);

        printGraph(childGraph, parentGraph);

        reactor.registerReactorGraph(parentGraph);

        ParentGraphPayload resultPaylaod = reactor.submit(new ParentGraphPayload()).getResultFuture().get(5, TimeUnit.SECONDS);
        assertEquals(Arrays.asList(1, 11, 12, 13, 2, 3), resultPaylaod.getIdSequence());
    }

    @Reactored({
            "Test demonstrates usage of mocked processor instead of real one.",
            "Test will check that single processor id end up at payloads idList.",
            "Expected result: {42}"
    })
    static class SingleInterfaceProcessorPayload extends IdListPayload {
    }

    @Test
    public void use_interface_mock_as_processor_with_mockito() throws Exception {

        IdProcessorInterface processorInterface = Mockito.mock(IdProcessorInterface.class);
        Mockito.when(processorInterface.handle()).thenReturn(CompletableFuture.completedFuture(42));

        class Config {
            ReactorGraphBuilder builder = new ReactorGraphBuilder(this);

            Processor<SingleInterfaceProcessorPayload> idProcessor1 = builder.processor()
                    .forPayload(SingleInterfaceProcessorPayload.class)
                    .withHandler(processorInterface::handle)
                    .withMerger((pld, id) -> {
                        pld.getIdSequence().add(id);
                        return Status.OK;
                    })
                    .buildProcessor();

            ReactorGraph<SingleInterfaceProcessorPayload> graph() {
                return builder.payload(SingleInterfaceProcessorPayload.class)

                        .handle(idProcessor1)

                        .mergePoint(idProcessor1)
                        .onAny().complete()

                        .coordinates()
                        .proc(idProcessor1, 450, 184)
                        .merge(idProcessor1, 522, 299)
                        .start(500, 100)
                        .complete(idProcessor1, 498, 398)

                        .buildGraph();
            }
        }

        val graph = new Config().graph();

        printGraph(graph);

        reactor.registerReactorGraph(graph);

        SingleInterfaceProcessorPayload resultPayload = reactor.submit(new SingleInterfaceProcessorPayload())
                .getResultFuture()
                .get(10, TimeUnit.SECONDS);

        assertEquals(Arrays.asList(42), resultPayload.getIdSequence());
    }


    @Test
    public void use_class_mock_as_processor_with_mockito() throws Exception {

        IdProcessor processor = Mockito.mock(IdProcessor.class);
        Mockito.when(processor.handle()).thenReturn(CompletableFuture.completedFuture(78));


        class Config {
            ReactorGraphBuilder graphBuilder = new ReactorGraphBuilder(this);

            Processor<SingleInterfaceProcessorPayload> idProcessor1 = graphBuilder.processor()
                    .forPayload(SingleInterfaceProcessorPayload.class)
                    .withHandler(processor::handle)
                    .withMerger((pld, id) -> {
                        pld.getIdSequence().add(id);
                        return Status.OK;
                    })
                    .buildProcessor();

            ReactorGraph<SingleInterfaceProcessorPayload> graph() {
                return graphBuilder.payload(SingleInterfaceProcessorPayload.class)

                        .handle(idProcessor1)

                        .mergePoint(idProcessor1)
                        .onAny().complete()

                        .coordinates()
                        .proc(idProcessor1, 450, 184)
                        .merge(idProcessor1, 522, 299)
                        .start(500, 100)
                        .complete(idProcessor1, 498, 398)

                        .buildGraph();
            }
        }
        val graph = new Config().graph();

        printGraph(graph);

        reactor.registerReactorGraph(graph);

        SingleInterfaceProcessorPayload resultPayload = reactor.submit(new SingleInterfaceProcessorPayload())
                .getResultFuture()
                .get(10, TimeUnit.SECONDS);

        assertEquals(Arrays.asList(78), resultPayload.getIdSequence());
    }


    @Reactored({
            "Test will check that parallel processors work fine when only one of transitions are activated.",
            "Expected result: {0, 1}"
    })
    @Data
    @EqualsAndHashCode(callSuper = true)
    static class DeadBranchPayload extends IdListPayload {
        ThreeStateStatus threeStateStatus;
    }

    enum ThreeStateStatus {A, B, AB}

    @Test
    public void parallel_processors_with_one_dead_branch_way() throws Exception {
        class Config {
            ReactorGraphBuilder graphBuilder = new ReactorGraphBuilder(this);

            Processor<DeadBranchPayload> idProcessor0 = graphBuilder.processor()
                    .forPayload(DeadBranchPayload.class)
                    .withHandler(new IdProcessor(0)::handle)
                    .withMerger((pld, id) -> {
                        pld.getIdSequence().add(id);
                        pld.setThreeStateStatus(ThreeStateStatus.A);
                        return ThreeStateStatus.A;
                    })
                    .buildProcessor();


            Processor<DeadBranchPayload> idProcessor1 = graphBuilder.processor()
                    .forPayload(DeadBranchPayload.class)
                    .withHandler(new IdProcessor(1)::handle)
                    .withMerger((pld, id) -> {
                        pld.getIdSequence().add(id);
                        return pld.getThreeStateStatus();
                    })
                    .buildProcessor();

            Processor<DeadBranchPayload> idProcessor2 = graphBuilder.processor()
                    .forPayload(DeadBranchPayload.class)
                    .withHandler(new IdProcessor(2)::handle)
                    .withMerger((pld, id) -> {
                        pld.getIdSequence().add(id);
                        return Status.OK;
                    })
                    .buildProcessor();

            ReactorGraph<DeadBranchPayload> graph() {
                return graphBuilder.payload(DeadBranchPayload.class)

                        .handle(idProcessor0)

                        .mergePoint(idProcessor0)
                        .on(ThreeStateStatus.A).handle(idProcessor1)
                        .on(ThreeStateStatus.B).handle(idProcessor2)
                        .on(ThreeStateStatus.AB).handle(idProcessor1)
                        .on(ThreeStateStatus.AB).handle(idProcessor2)

                        .mergePoint(idProcessor1)
                        .on(ThreeStateStatus.A).complete()
                        .on(ThreeStateStatus.AB, ThreeStateStatus.B).merge(idProcessor2)

                        .mergePoint(idProcessor2)
                        .onAny().complete()

                        .coordinates()
                        .start(500, 100)
                        .proc(idProcessor0, 420, 210)
                        .proc(idProcessor1, 600, 420)
                        .proc(idProcessor2, 260, 420)
                        .merge(idProcessor0, 500, 320)
                        .merge(idProcessor1, 560, 510)
                        .merge(idProcessor2, 450, 540)
                        .complete(idProcessor1, 651, 595)
                        .complete(idProcessor2, 520, 660)
                        .buildGraph();
            }
        }

        val graph = new Config().graph();

        printGraph(graph);

        reactor.registerReactorGraph(graph);

        CompletableReactor.Execution<DeadBranchPayload> result = reactor.submit(new DeadBranchPayload());

        DeadBranchPayload resultPayload = result
                .getResultFuture()
                .get(10, TimeUnit.SECONDS);

        assertEquals(Arrays.asList(0, 1), resultPayload.getIdSequence());
    }


    @Reactored({
            "Detached merge point works as an regular merge point ",
            "but there is no processor or subgraph or theirs result to merge.",
            "Merge point simply modify payload and send it through outgoing transitions.",
            "Expected result: {42, 1, 0}"
    })
    @Data
    @EqualsAndHashCode(callSuper = true)
    static class DetachedMergePointFromStartPointPayload extends IdListPayload {
    }

    @Test
    public void detached_merge_point_from_start_point() throws Exception {
        final int MERGE_POINT_ID = 42;

        class Config {
            ReactorGraphBuilder graphBuilder = new ReactorGraphBuilder(this);

            Processor<IdListPayload> idProcessor0 = buildProcessor(graphBuilder, new IdProcessor(0));
            Processor<IdListPayload> idProcessor1 = buildProcessor(graphBuilder, new IdProcessor(1));

            MergePoint<DetachedMergePointFromStartPointPayload> mergePoint = graphBuilder.mergePoint()
                    .forPayload(DetachedMergePointFromStartPointPayload.class)
                    .withMerger(
                            "mergePointTitle",
                            new String[]{
                                    "merge point documentation",
                                    "here"},
                            pld -> {
                                pld.getIdSequence().add(MERGE_POINT_ID);
                                return Status.OK;
                            })
                    .buildMergePoint();


            ReactorGraph<DetachedMergePointFromStartPointPayload> graph() {
                return graphBuilder.payload(DetachedMergePointFromStartPointPayload.class)

                        .handle(idProcessor0)
                        .handle(idProcessor1)
                        .merge(mergePoint)

                        .mergePoint(mergePoint)
                        .onAny().merge(idProcessor1)

                        .mergePoint(idProcessor1)
                        .onAny().merge(idProcessor0)

                        .mergePoint(idProcessor0)
                        .onAny().complete()

                        .coordinates()
                        .start(107, 9)
                        .proc(idProcessor0, 22, 78)
                        .proc(idProcessor1, 211, 79)
                        .merge(idProcessor0, 126, 215)
                        .merge(idProcessor1, 267, 187)
                        .merge(mergePoint, 424, 160)
                        .complete(idProcessor0, 57, 274)

                        .buildGraph();
            }
        }

        val graph = new Config().graph();
        printGraph(graph);

        reactor.registerReactorGraph(graph);

        CompletableReactor.Execution<DetachedMergePointFromStartPointPayload> result = reactor.submit(new DetachedMergePointFromStartPointPayload());

        DetachedMergePointFromStartPointPayload resultPayload = result
                .getResultFuture()
                .get(10, TimeUnit.SECONDS);

        assertEquals(Arrays.asList(42, 1, 0), resultPayload.getIdSequence());
    }

    @Reactored({
            "Detached merge point works as an regular merge point ",
            "but there is no processor or subgraph or theirs result to merge.",
            "Merge point simply modify payload and send it through outgoing transitions.",
            "Expected result: {0, 1, 42}"

    })
    @Data
    @EqualsAndHashCode(callSuper = true)
    static class DetachedMergePointFromProcessorsMergePointPayload extends IdListPayload {
    }

    @Test
    public void detached_merge_point_from_processors_merge_point() throws Exception {
        final int MERGE_POINT_ID = 42;

        class Config {
            ReactorGraphBuilder graphBuilder = new ReactorGraphBuilder(this);

            Processor<IdListPayload> idProcessor0 = buildProcessor(graphBuilder, new IdProcessor(0));
            Processor<IdListPayload> idProcessor1 = buildProcessor(graphBuilder, new IdProcessor(1));

            MergePoint<DetachedMergePointFromProcessorsMergePointPayload> mergePoint = graphBuilder.mergePoint()
                    .forPayload(DetachedMergePointFromProcessorsMergePointPayload.class)
                    .withMerger(
                            "addMergePointId",
                            new String[]{
                                    "Adds merge point id",
                                    "to payload sequence"},
                            pld -> {
                                pld.getIdSequence().add(MERGE_POINT_ID);
                                return Status.OK;
                            })

                    .buildMergePoint();

            ReactorGraph<DetachedMergePointFromProcessorsMergePointPayload> graph() {
                return graphBuilder.payload(DetachedMergePointFromProcessorsMergePointPayload.class)

                        .handle(idProcessor0)
                        .handle(idProcessor1)

                        .mergePoint(idProcessor0)
                        .onAny().merge(idProcessor1)

                        .mergePoint(idProcessor1)
                        .onAny().merge(mergePoint)

                        .mergePoint(mergePoint)
                        .onAny().complete()

                        .coordinates()
                        .start(95, 62)
                        .proc(idProcessor0, 164, 131)
                        .proc(idProcessor1, 330, 127)
                        .merge(idProcessor0, 235, 224)
                        .merge(idProcessor1, 357, 241)
                        .merge(mergePoint, 461, 289)
                        .complete(mergePoint, 406, 369)

                        .buildGraph();

            }
        }

        val graph = new Config().graph();
        printGraph(graph);

        reactor.registerReactorGraph(graph);

        CompletableReactor.Execution<DetachedMergePointFromProcessorsMergePointPayload> result = reactor.submit(new DetachedMergePointFromProcessorsMergePointPayload());

        DetachedMergePointFromProcessorsMergePointPayload resultPayload = result
                .getResultFuture()
                .get(10, TimeUnit.SECONDS);

        assertEquals(Arrays.asList(0, 1, 42), resultPayload.getIdSequence());

    }


    @Reactored({
            "OptionalProcessorExecution shows how to use detached merge point to avoid unnecessary processor execution",
            "Expected result for right: {1 ,2}",
            "Expected result for left: {2}"
    })

    @Accessors(chain = true)
    @Data
    @EqualsAndHashCode(callSuper = true)
    static class OptionalProcessorExecutionPayload extends IdListPayload {
        OPTIONAL_DECISION whereToGo;
    }

    enum OPTIONAL_DECISION {
        LEFT, RIGHT
    }

    @Test
    public void optional_processor_execution() throws Exception {

        class Config {
            ReactorGraphBuilder graphBuilder = new ReactorGraphBuilder(this);

            Processor<IdListPayload> idProcessor1 = buildProcessor(graphBuilder, new IdProcessor(1));
            Processor<IdListPayload> idProcessor2 = buildProcessor(graphBuilder, new IdProcessor(2));
            Processor<IdListPayload> idProcessor3 = buildProcessor(graphBuilder, new IdProcessor(3));

            MergePoint<OptionalProcessorExecutionPayload> mergePoint = graphBuilder.mergePoint()
                    .forPayload(OptionalProcessorExecutionPayload.class)
                    .withMerger(
                            "getWhereToGo",
                            new String[]{
                                    "returns destination from payload"
                            },
                            pld -> {
                                return pld.getWhereToGo();
                            })
                    .buildMergePoint();


            ReactorGraph<OptionalProcessorExecutionPayload> graph() {
                return graphBuilder.payload(OptionalProcessorExecutionPayload.class)

                        .merge(mergePoint)

                        .mergePoint(mergePoint)
                        .on(OPTIONAL_DECISION.LEFT).handle(idProcessor2)
                        .on(OPTIONAL_DECISION.RIGHT).handle(idProcessor1)

                        .mergePoint(idProcessor1)
                        .onAny().handle(idProcessor2)

                        .mergePoint(idProcessor2)
                        .onAny().handle(idProcessor3)

                        .mergePoint(idProcessor3)
                        .onAny().complete()

                        .coordinates()
                        .start(0, 17)
                        .proc(idProcessor1, 246, 147)
                        .proc(idProcessor2, -17, 252)
                        .proc(idProcessor3, -15, 413)
                        .merge(idProcessor1, 287, 223)
                        .merge(idProcessor2, 21, 328)
                        .merge(idProcessor3, 23, 504)
                        .merge(mergePoint, 98, 79)
                        .complete(idProcessor3, 129, 537)

                        .buildGraph();

            }
        }

        val graph = new Config().graph();
        printGraph(graph);

        reactor.registerReactorGraph(graph);

        CompletableReactor.Execution<OptionalProcessorExecutionPayload> result = reactor.submit(new OptionalProcessorExecutionPayload()
                .setWhereToGo(OPTIONAL_DECISION.RIGHT));

        OptionalProcessorExecutionPayload resultPayload = result
                .getResultFuture()
                .get(10, TimeUnit.SECONDS);

        assertEquals(Arrays.asList(1, 2, 3), resultPayload.getIdSequence());


        result = reactor.submit(new OptionalProcessorExecutionPayload()
                .setWhereToGo(OPTIONAL_DECISION.LEFT));


        resultPayload = result
                .getResultFuture()
                .get(10, TimeUnit.SECONDS);

        assertEquals(Arrays.asList(2, 3), resultPayload.getIdSequence());
    }


    @Reactored({
            "It possible for MergePoint to have several outgoing transitions.",
            "This transitions could be executed conditionally.",
            "If condition evaluated to false than this outgoing transitions marked as dead.",
            "If all incoming transitions to processor marked as dead, then this processor and it's merge point marked as dead.",
    })
    @Accessors(chain = true)
    @Data
    static class DeadTransitionBreaksFlow extends IdListPayload {
        public enum FlowDecision {
            THREE, TWO
        }

        FlowDecision flowDecision;
    }

    @Test
    public void dead_transition_breaks_flow() throws Exception {

        class Config {
            ReactorGraphBuilder graphBuilder = new ReactorGraphBuilder(this);

            Processor<DeadTransitionBreaksFlow> idProcessor1 = graphBuilder.processor()
                    .forPayload(DeadTransitionBreaksFlow.class)
                    .withHandler(new IdProcessor(1)::handle)
                    .withMerger((pld, id) -> {
                        pld.getIdSequence().add(id);
                        return pld.getFlowDecision();
                    })
                    .buildProcessor();

            Processor<IdListPayload> idProcessor2 = buildProcessor(graphBuilder, new IdProcessor(2));
            Processor<IdListPayload> idProcessor3 = buildProcessor(graphBuilder, new IdProcessor(3));
            Processor<IdListPayload> idProcessor4 = buildProcessor(graphBuilder, new IdProcessor(4));

            MergePoint<DeadTransitionBreaksFlow> decisionMergePoint = graphBuilder.mergePoint()
                    .forPayload(DeadTransitionBreaksFlow.class)
                    .withMerger(DeadTransitionBreaksFlow::getFlowDecision)
                    .buildMergePoint();

            ReactorGraph<DeadTransitionBreaksFlow> graph() {
                return graphBuilder.payload(DeadTransitionBreaksFlow.class)
                        .merge(decisionMergePoint)

                        .mergePoint(decisionMergePoint)
                        .on(DeadTransitionBreaksFlow.FlowDecision.THREE).handle(idProcessor1)
                        .on(DeadTransitionBreaksFlow.FlowDecision.THREE).handle(idProcessor2)
                        .on(DeadTransitionBreaksFlow.FlowDecision.THREE).handle(idProcessor3)
                        .on(DeadTransitionBreaksFlow.FlowDecision.TWO).handle(idProcessor1)
                        .on(DeadTransitionBreaksFlow.FlowDecision.TWO).handle(idProcessor3)

                        .mergePoint(idProcessor1)
                        .on(DeadTransitionBreaksFlow.FlowDecision.THREE).merge(idProcessor2)
                        .on(DeadTransitionBreaksFlow.FlowDecision.TWO).merge(idProcessor3)

                        .mergePoint(idProcessor2)
                        .onAny().merge(idProcessor3)

                        .mergePoint(idProcessor3)
                        .onAny().handle(idProcessor4)

                        .mergePoint(idProcessor4)
                        .onAny().complete()

                        .coordinates()
                        .start(500, 100)
                        .proc(idProcessor1, 399, 309)
                        .proc(idProcessor2, 551, 319)
                        .proc(idProcessor3, 725, 302)
                        .proc(idProcessor4, 713, 609)
                        .merge(idProcessor1, 422, 473)
                        .merge(idProcessor2, 584, 410)
                        .merge(idProcessor3, 704, 526)
                        .merge(idProcessor4, 754, 706)
                        .merge(decisionMergePoint, 551, 173)
                        .complete(idProcessor4, 765, 773)

                        .buildGraph();

            }
        }

        val graph = new Config().graph();
        printGraph(graph);

        reactor.registerReactorGraph(graph);

        var result = reactor.submit(
                new DeadTransitionBreaksFlow()
                        .setFlowDecision(DeadTransitionBreaksFlow.FlowDecision.THREE))
                .getResultFuture()
                .get(5, TimeUnit.MINUTES);

        assertEquals(Arrays.asList(1, 2, 3, 4), result.getIdSequence());

        result = reactor.submit(
                new DeadTransitionBreaksFlow()
                        .setFlowDecision(DeadTransitionBreaksFlow.FlowDecision.TWO))
                .getResultFuture()
                .get(5, TimeUnit.MINUTES);

        assertEquals(Arrays.asList(1, 3, 4), result.getIdSequence());
    }

    @Reactored({
            "Implicit merge group generated for start point if it linked with detached merge point",
            " and if this merge point does not belong to other merge group that contains processor",
            " or subgraph merge point.",
            "Expected result: {2, 0, 1, 3}"
    })
    @Data
    @EqualsAndHashCode(callSuper = true)
    static class StartPointMergeGroupPayload extends IdListPayload {
    }

    @Test
    public void graph_with_detached_merge_point_connected_to_start_point() throws Exception {

        Semaphore mergePoint2Semaphore = new Semaphore(0);

        class Config {
            final ReactorGraphBuilder builder = new ReactorGraphBuilder(this);
            Processor<IdListPayload> idProcessor0 = buildProcessor(builder, new IdProcessor(0));
            Processor<IdListPayload> idProcessor1 = buildProcessor(builder, new IdProcessor(1));

            MergePoint<StartPointMergeGroupPayload> mergePoint2 = builder.mergePoint()
                    .forPayload(CompletableReactorTest.StartPointMergeGroupPayload.class)
                    .withMerger(
                            "mergePoint-2",
                            pld -> {
                                try {
                                    mergePoint2Semaphore.acquire();
                                } catch (Exception exc) {
                                    log.error("Failed to acquire semaphore", exc);
                                }

                                pld.getIdSequence().add(2);
                                return CompletableReactorTest.Status.OK;
                            })
                    .buildMergePoint();

            Processor<IdListPayload> idProcessor3 = buildProcessor(builder, new IdProcessor(3));

            ReactorGraph<StartPointMergeGroupPayload> buildGraph() {
                return builder.payload(CompletableReactorTest
                        .StartPointMergeGroupPayload.class)

                        .handle(idProcessor0)
                        .handle(idProcessor1)
                        .merge(mergePoint2)

                        .mergePoint(idProcessor0)
                        .onAny().merge(idProcessor1)

                        .mergePoint(idProcessor1)
                        .onAny().merge(idProcessor3)

                        .mergePoint(mergePoint2)
                        .onAny().handle(idProcessor3)
                        .onAny().merge(idProcessor0)

                        .mergePoint(idProcessor3)
                        .onAny().complete()

                        .coordinates()
                        .start(476, 88)
                        .proc(idProcessor0, 339, 224)
                        .proc(idProcessor1, 537, 218)
                        .proc(idProcessor3, 747, 247)
                        .merge(idProcessor0, 382, 314)
                        .merge(idProcessor1, 580, 346)
                        .merge(idProcessor3, 809, 403)
                        .merge(mergePoint2, 739, 140)
                        .complete(idProcessor3, 914, 512)

                        .buildGraph();
            }
        }

        val graph = new Config().buildGraph();

        printGraph(graph);

        reactor.registerReactorGraph(graph);

        val resultFuture = reactor.submit(new StartPointMergeGroupPayload()).getResultFuture();

        try {
            resultFuture.get(1, TimeUnit.SECONDS);
            fail("Failed to wait for mergePoint2");
        } catch (TimeoutException exc) {
            //ignore timeout exception
        }

        mergePoint2Semaphore.release();

        val result = resultFuture.get(5, TimeUnit.MINUTES);

        assertEquals(Arrays.asList(2, 0, 1, 3), result.getIdSequence());

    }

    @Reactored({
            "Dead transition deactivates merge point and all outgoing transitions from this merge point",
            "Expected result: {2, 0, 1, 3}"
    })
    @Data
    @EqualsAndHashCode(callSuper = true)
    static class DeadTransitionPayload extends IdListPayload {
        public enum Status {
            FIRST, SECOND
        }
    }

    @Test
    public void dead_transition_kills_merge_point_and_all_outgoing_transitions() throws Exception {

        Semaphore processor4mergerSemaphore = new Semaphore(0);


        class Config {
            final ReactorGraphBuilder builder = new ReactorGraphBuilder(this);

            Processor<IdListPayload> idProcessor0 = builder.processor()
                    .forPayload(IdListPayload.class)
                    .withHandler(new IdProcessor(0)::handle)
                    .withMerger((pld, id) -> {
                        pld.getIdSequence().add(id);
                        return DeadTransitionPayload.Status.FIRST;
                    })
                    .buildProcessor();

            Processor<IdListPayload> idProcessor1 = buildProcessor(builder, new IdProcessor(1));
            Processor<IdListPayload> idProcessor2 = buildProcessor(builder, new IdProcessor(2));
            Processor<IdListPayload> idProcessor3 = buildProcessor(builder, new IdProcessor(3));


            Processor<IdListPayload> idProcessor4 = builder.processor()
                    .forPayload(IdListPayload.class)
                    .withHandler(new IdProcessor(4)::handle)
                    .withMerger((pld, id) -> {
                        try {
                            pld.getIdSequence().add(id);
                            processor4mergerSemaphore.acquire();
                            return Status.OK;
                        } catch (Exception exc) {
                            throw new RuntimeException(exc);
                        }
                    })
                    .buildProcessor();


            ReactorGraph<DeadTransitionPayload> buildGraph() {
                return builder.payload(DeadTransitionPayload.class)
                        .handle(idProcessor0)
                        .handle(idProcessor1)
                        .handle(idProcessor2)

                        .mergePoint(idProcessor0)
                        .on(DeadTransitionPayload.Status.FIRST).handle(idProcessor4)
                        .on(DeadTransitionPayload.Status.SECOND).merge(idProcessor1)

                        .mergePoint(idProcessor1)
                        .onAny().merge(idProcessor2)

                        .mergePoint(idProcessor2)
                        .onAny().handle(idProcessor3)

                        .mergePoint(idProcessor3)
                        .onAny().complete()

                        .mergePoint(idProcessor4)
                        .onAny().complete()

                        .coordinates()
                        .start(461, 96)
                        .proc(idProcessor0, 366, 177)
                        .proc(idProcessor2, 649, 181)
                        .proc(idProcessor1, 502, 178)
                        .proc(idProcessor3, 708, 396)
                        .proc(idProcessor4, 289, 339)
                        .merge(idProcessor0, 407, 250)
                        .merge(idProcessor1, 538, 304)
                        .merge(idProcessor2, 682, 315)
                        .merge(idProcessor3, 755, 477)
                        .merge(idProcessor4, 330, 436)
                        .complete(idProcessor3, 820, 514)
                        .complete(idProcessor4, 396, 475)

                        .buildGraph();
            }
        }

        val graph = new Config().buildGraph();

        printGraph(graph);

        reactor.registerReactorGraph(graph);

        val resultFuture = reactor.submit(new DeadTransitionPayload()).getResultFuture();


        try {
            log.info("wait for 2 seconds to test if graph executes merge points number 2 or 3");
            val result = resultFuture.get(2, TimeUnit.SECONDS);
            fail("Failed to wait graph execution. " + result);
        } catch (TimeoutException exc) {
            //ignore timeout exception
        }

        processor4mergerSemaphore.release();

        val result = resultFuture.get(5, TimeUnit.MINUTES);

        assertEquals(Arrays.asList(0, 4), result.getIdSequence());
    }

    @Reactored({
            "Test will check that single detached merge point id end up at payloads idList.",
            "Expected result: {1}"
    })
    static class SingleDetachedMergePointPayload extends IdListPayload {
    }

    @Test
    public void single_detached_merge_point() throws Exception {

        class Config {
            final ReactorGraphBuilder builder = new ReactorGraphBuilder(this);

            MergePoint<IdListPayload> mergePoint = builder.mergePoint()
                    .forPayload(IdListPayload.class)
                    .withMerger(pld -> {
                        pld.idSequence.add(1);
                        return Status.OK;
                    })
                    .buildMergePoint();


            ReactorGraph buildGraph() {
                return builder.payload(SingleDetachedMergePointPayload.class)
                        .merge(mergePoint)
                        .mergePoint(mergePoint)
                        .onAny().complete()
                        .coordinates()
                        .start(500, 100)
                        .merge(mergePoint, 615, 179)
                        .complete(mergePoint, 615, 263)

                        .buildGraph();
            }
        }

        val graph = new Config().buildGraph();

        printGraph(graph);

        reactor.registerReactorGraph(graph);

        SingleDetachedMergePointPayload resultPayload = reactor.submit(new SingleDetachedMergePointPayload())
                .getResultFuture()
                .get(10, TimeUnit.SECONDS);

        assertEquals(Arrays.asList(1), resultPayload.getIdSequence());
    }
}