package ru.fix.completable.reactor.runtime.gl


import mu.KotlinLogging
import ru.fix.commons.profiler.ProfiledCall;
import ru.fix.commons.profiler.Profiler;
import ru.fix.completable.reactor.runtime.ProfilerNames;
import ru.fix.completable.reactor.runtime.ReactorGraph;
import ru.fix.completable.reactor.runtime.cloning.ThreadsafeCopyMaker;
import ru.fix.completable.reactor.runtime.debug.DebugSerializer;
import ru.fix.completable.reactor.runtime.execution.ReactorGraphExecution
import ru.fix.completable.reactor.runtime.immutability.ImmutabilityChecker;
import ru.fix.completable.reactor.runtime.immutability.ImmutabilityControlLevel;
import ru.fix.completable.reactor.runtime.internal.CRProcessingItem;
import ru.fix.completable.reactor.runtime.internal.CRReactorGraph;
import ru.fix.completable.reactor.runtime.internal.gl.GlReactorGraph
import ru.fix.completable.reactor.runtime.tracing.Tracer;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

typealias SubgraphRunner = (Any?) -> CompletableFuture<Any?>

private val log = KotlinLogging.logger {}

class ExecutionBuilder(
        val profiler: Profiler,
        val immutabilityChecker: ImmutabilityChecker,
        val threadsafeCopyMaker: ThreadsafeCopyMaker,
        val subgraphRunner: SubgraphRunner,
        val debugSerializer: DebugSerializer,
        val tracer: Tracer) {

    /**
     * If this flag is enabled then internal processing graph state will be attached to Execution result.
     * This allows easy access to execution state during debug.
     * One of drawbacks of this future is that GC will be prevented form removing internal state objects
     * until all reference to execution result dies.
     * <p>
     * By default this flag is disabled.
     */
    var debugProcessingVertexGraphState: Boolean = false

    /**
     * Immutability check ensures that there is no payload modification during handling.
     */
    @Volatile
    var immutabilityControlLevel = ImmutabilityControlLevel.NO_CONTROL


    class MergePayloadContext(
            var payload: Any? = null,
            /**
             * Transition marked as dead when merge status does not match transition condition
             */
            var isDeadTransition: Boolean = false,
            /**
             * Terminal graph state reached.
             * No further merging (or payload modification) is allowed.
             * All transitions except copy-transition for detached processors is allowed.
             */
            var isTerminal: Boolean = false,

            var mergeResult: Any? = null
    )

    private val INVALID_MERGE_PAYLOAD_CONTEXT = MergePayloadContext()


    class TransitionPayloadContext(
            var payload: Any? = null,
            /**
             * Transition marked as dead when merge status does not match transition condition
             */
            var isDeadTransition: Boolean = false,
            /**
             * Terminal graph state reached.
             * No further merging (or payload modification) is allowed.
             * All transitions except copy-transition for detached processors is allowed.
             */
            var isTerminal: Boolean = false
    )

    val INVALID_TRANSITION_PAYLOAD_CONTEXT = TransitionPayloadContext()


    class HandlePayloadContext(

            var payload: Any? = null,
            /**
             * Transition marked as dead when merge status does not match transition condition
             */
            var isDeadTransition: Boolean = false,
            /**
             * Terminal graph state reached.
             * No further merging (or payload modification) is allowed.
             * All transitions except copy-transition for detached processors is allowed.
             */
            var isTerminal: Boolean = false,
            var processorResult: Any? = null
    )

    val INVALID_HANDLE_PAYLOAD_CONTEXT = HandlePayloadContext()

    /**
     * Represent deferred computation result of a transition
     * //TODO replace with type alias
     */
    class TransitionFuture<PayloadContextType>(
            val feature: CompletableFuture<PayloadContextType>
    )

    /**
     * Each Processing Vertex (pvx) is mapped to Vertex (vx).
     * Tree of vertexes (vx) represents graph model and it is immutable.
     * Tree of Processing Vertexes (pvx) represents execution graph. Runtime build execution graph each time payload
     * submitted to ReactorGraph.
     *
     * <img src="./doc-files/ProcessingVertex.png" alt="">
     */
    class ProcessingVertex(val vertex: Vertex) {

        val incomingHandlingFlows = ArrayList<TransitionFuture<TransitionPayloadContext>>()

        val incomingMergingFlows = ArrayList<TransitionFuture<MergePayloadContext>>()

        val handlingFeature = CompletableFuture<HandlePayloadContext>()

        val mergingFeature = CompletableFuture<MergePayloadContext>()

        /**
         * Holds information about outgoing transitions from this merge point.
         * If during merging process we will receive merge status that match any of terminal transitions
         * then we should complete mergingFeature with Terminal MergePayloadContext
         */
        val outgoingTransitions = ArrayList<GlTransition>()

        /**
         * This is Merger or Router
         */
        val isMergerable: Boolean
            get() = vertex.merger != null || vertex.router != null
    }


    /**
     * @param reactorGraph
     * @param <PayloadType>
     * @return
     */
    fun <PayloadType> build(reactorGraph: ReactorGraph<PayloadType>): ReactorGraphExecution<PayloadType> {

        val glReactorGraph = reactorGraph as GlReactorGraph<PayloadType>

        /**
         * Internal representation of processing graph based on processing vertices.
         * //TODO: check, maybe we do not need map and simple list is enough fo rCR2
         */
        val processingVertices = IdentityHashMap<Vertex, ProcessingVertex>()


        val submitFuture = CompletableFuture<PayloadType>()

        /**
         * Will be completed on payload submission to processor chain
         */
        val startPointTransitionFuture = submitFuture.thenApplyAsync { payload ->
            TransitionPayloadContext(payload = payload)
        }

        /**
         * Will be completed with payload when terminal graph state would be reached.
         */
        val executionResultFuture = CompletableFuture<PayloadType>()

        /**
         * Init Processing Vertices based on graph model vertices
         *
         */
        glReactorGraph.vertices.forEach { vx ->
            val pvx = ProcessingVertex(vertex = vx)

            /**
             * Populate Routers
             */
            if (vx.router != null) {
                /**
                 * Router does not uses {@code {@link ProcessingVertex#handlingFeature}
                 * Insure that it never invoked during execution.
                 */
                pvx.handlingFeature.completeExceptionally(IllegalStateException(
                        "Processing vertex of router ${vx.name} should not use handlingFeature."))
            }

            /**
             * Populate MergePoints transitions
             */
            if (vx.transitions.isNotEmpty()) {
                //TODO: replace copy with delegation since outgoingTransitions is immutable
                pvx.outgoingTransitions.addAll(vx.transitions)
            }

            processingVertices[vx] = pvx
        }

        /**
         * Populate start point transition
         */
        for (vx in glReactorGraph.startPoint) {
            val pvx = processingVertices[vx]!!

//            if (vx.router != null) {
//                /**
//                 * //TODO check, probably this conversion will ot be used in CR2 model
//                 * In case of Router, transition from start point is being converted
//                 * to a {@link MergePayloadContext}
//                 */
//                pvx.incomingMergingFlows.add(TransitionFuture(
//                        startPointTransitionFuture.thenApplyAsync { transitionPayloadContext ->
//                            MergePayloadContext(
//                                    isDeadTransition = transitionPayloadContext.isDeadTransition,
//                                    isTerminal = transitionPayloadContext.isTerminal,
//                                    payload = transitionPayloadContext.payload,
//                                    mergeResult = null)
//                        }))
//            } else {
//                pvx.incomingHandlingFlows.add(TransitionFuture(startPointTransitionFuture))
//            }

            pvx.incomingHandlingFlows.add(TransitionFuture(startPointTransitionFuture))
        }


        /**
         * Populate outgoing flows
         */
        processingVertices.values.asIterable().filter { it.isMergerable }.forEach { mergerablePvx ->

            /**
             * Completion of Mergerable feature triggers completion of outgoing transitions.
             */

            val mergingFeature = mergerablePvx.mergingFeature

            for (transition in mergerablePvx.outgoingTransitions) {

                /**
                 * Terminal merge point vertex handled synchronously with merging process
                 * Skip that kind of transition during pre-processing.
                 */
                if (!transition.isComplete) {
                    /**
                     * Populate outgoing handleBy flows
                     * activates when Mergerable feature is completed
                     */
                    if (transition.handleBy != null) {
                        val target = transition.handleBy

                        processingVertices[target]!!.incomingHandlingFlows.add(TransitionFuture(
                                mergingFeature.thenApplyAsync { context ->

                                    if (context.isTerminal) {
                                        TransitionPayloadContext(isTerminal = true)

                                    } else if (context.isDeadTransition) {
                                        TransitionPayloadContext(isDeadTransition = true)

                                    } else if (transition.isOnAny
                                            || transition.mergeStatuses.contains(context.mergeResult)) {

                                        TransitionPayloadContext(payload = context.payload)

                                    } else {
                                        TransitionPayloadContext(
                                                payload = context.payload,
                                                isDeadTransition = true)
                                    }

                                }.exceptionally { exc ->
                                            log.error(exc) {
                                                """
                                                Failed to activate handleBy transition.
                                                Mark transition as dead.
                                                Transition from ${mergerablePvx.vertex.name} to ${target?.name}.
                                                Transition: $transition
                                                """
                                            }

                                            TransitionPayloadContext(isDeadTransition = true)
                                        }
                        ))

                    }

                    /**
                     * Populate outgoing mergeBy flows
                     */
                    if (transition.mergeBy != null) {
                        val target = transition.mergeBy

                        processingVertices[target]!!.incomingMergingFlows.add(TransitionFuture(
                                mergingFeature.thenApplyAsync { context ->
                                    if (context.isTerminal) {
                                        MergePayloadContext(isTerminal = true)

                                    } else if (context.isDeadTransition) {
                                        MergePayloadContext(isDeadTransition = true)

                                    } else if (transition.isOnAny ||
                                            transition.mergeStatuses.contains(context.mergeResult)) {

                                        MergePayloadContext(
                                                payload = context.payload,
                                                mergeResult = context.mergeResult)
                                    } else {
                                        MergePayloadContext(isDeadTransition = true)
                                    }
                                }.exceptionally { exc ->
                                            log.error(exc) {
                                                """
                                                        Failed to activate mergeBy transition.
                                                        Mark transition as dead.
                                                        Transition from ${mergerablePvx.vertex.name} to ${target?.name}.
                                                        Transition: $transition
                                                        """
                                            }

                                            MergePayloadContext(isDeadTransition = true)
                                        }
                        ))

                    }
                }
            }
        }

        //TODO FIX!! allow detached processor to read data from payload and only after that execute merge point
        // down the flow, so merge point and detached processor would not run concurrently

        /**
         * Join incoming handling flows to single handling invocation
         * All incoming handling transitions should complete.
         * One of them should carry payload.
         * Other transitions should complete with isDead flag.
         */
        for ((vx, pvx) in processingVertices) {

            if (pvx.incomingHandlingFlows.size <= 0) {
                throw IllegalArgumentException("""
                        Invalid graph configuration.
                        Vertex ${vx.name} does not have incoming handling flows.
                        Probably missing `.handleBy(${vx.name})` transition that targets this vertex in configuration.
                        """)
            }

            /**
             * First we should wait for all incoming handleBy transition to complete
             */
            CompletableFuture.allOf(
                    *pvx.incomingHandlingFlows.asIterable().map { it.feature }.toTypedArray()

            ).thenRunAsync {

                val incomingFlows: List<TransitionPayloadContext> = pvx
                        .incomingHandlingFlows
                        .stream()
                        .map { future ->
                            try {
                                /**
                                 * Future should be already complete.
                                 */
                                if (!future.feature.isDone()) {
                                    val resultException = Exception("""
                                        Illegal graph execution state.
                                        Future is not completed. Vertex: ${vx.name}
                                        """)

                                    log.error(resultException) {}
                                    executionResultFuture.completeExceptionally(resultException)

                                    INVALID_TRANSITION_PAYLOAD_CONTEXT
                                } else {
                                    future.feature.get()
                                }
                            } catch (exc: Exception) {
                                val resultException = Exception("""
                                        Failed to get incoming processor flow future result
                                        for vertex: ${vx.name}
                                        """)
                                log.error(resultException) {}
                                executionResultFuture.completeExceptionally(resultException)

                                INVALID_TRANSITION_PAYLOAD_CONTEXT
                            }
                        }
                        .collect(Collectors.toList())

                if (incomingFlows.stream().anyMatch { context -> context === INVALID_TRANSITION_PAYLOAD_CONTEXT }) {
                    /**
                     * Invalid graph execution state
                     * Mark as terminal all outgoing flows from vertex
                     */
                    pvx.handlingFeature.complete(HandlePayloadContext(isTerminal = true))

                } else if (incomingFlows.stream().anyMatch(TransitionPayloadContext::isTerminal)) {
                    /**
                     * Terminal state reached.
                     * Mark as terminal all outgoing flows from vertex
                     */
                    pvx.handlingFeature.complete(HandlePayloadContext(isTerminal = true))

                } else {
                    val activeIncomingFlows: List<TransitionPayloadContext> = incomingFlows
                            .stream()
                            .filter { context -> !context.isDeadTransition }
                            .collect(Collectors.toList())

                    if (activeIncomingFlows.isEmpty()) {
                        /**
                         * There is no active incoming flow for given vertex.
                         * Vertex will not be invoked.
                         * All outgoing flows from vertex will be marked as dead.
                         */
                        pvx.handlingFeature.complete(HandlePayloadContext(isDeadTransition = true))

                    } else {
                        if (activeIncomingFlows.size > 1) {

                            /**
                             * Illegal graph state. Too many active incoming flows.
                             * Mark as terminal all outgoing flows
                             * Complete graph with exception
                             */
                            val tooManyActiveIncomingFlowsExc = Exception("""
                                    There is more than one active incoming flow for vertex ${vx.name}.
                                    Reactor can not determinate from which of transitions to take payload.
                                    Possible loss of computation results.
                                    Possible concurrent modifications of payload.
                                    """)

                            executionResultFuture.completeExceptionally(tooManyActiveIncomingFlowsExc)
                            pvx.handlingFeature.complete(HandlePayloadContext(isTerminal = true)

                        } else {

                            handle(pvx, activeIncomingFlows[0], executionResultFuture)
                        }
                    }
                }
            }
            )
            .exceptionally(throwable -> {
                log.error("Join incoming processor flows failed.", throwable);
                return null;
            });

        });//processingVertices

        /**
         * Join incoming merge flows and processor handing future with single merging invocation
         */
        processingVertices.forEach((processor, vertex) -> {

            List<CompletableFuture> incomingFlows = new ArrayList<>();
            vertex.getincomingMergingFlows()
                    .stream()
                    .map(TransitionFuture::getFuture)
                    .forEach(incomingFlows::add);

            if (vertex.getProcessingItemInfo().getProcessingItemType() !=
                    CRReactorGraph.ProcessingItemType.MERGE_POINT) {
                /**
                 * Ignore processor future for detached merge point
                 * And use it for all other cases
                 */
                incomingFlows.add(vertex.gethandlingFeature());
            }

            CompletableFuture.allOf(incomingFlows.toArray(new CompletableFuture [incomingFlows.size()]))
                    .thenRunAsync(() -> {

            /**
             * Processor result, could be INVALID_HANDLE_PAYLOAD_CONTEXT in case of exception
             * Could be NULL in case of detached merge point
             */
            HandlePayloadContext handlePayloadContext = null;

            if (vertex.getProcessingItemInfo().getProcessingItemType()
                    != CRReactorGraph.ProcessingItemType.MERGE_POINT) {

                handlePayloadContext = Optional.of(vertex.gethandlingFeature())
                        .map(future -> {
                    try {
                        if (!future.isDone()) {
                            RuntimeException resultException = new RuntimeException(String.format(
                                    "Illegal graph execution state. Processor future" +
                                            " is not completed. Processor %s",
                                    vertex.getProcessingItem().getDebugName()));
                            log.error(resultException.getMessage(), resultException);
                            executionResultFuture.completeExceptionally(resultException);

                            return INVALID_HANDLE_PAYLOAD_CONTEXT;
                        } else {
                            return future.get();
                        }
                    } catch (Exception exc) {
                        RuntimeException resultException = new RuntimeException(String.format(
                                "Failed to get processor future result for processor: %s",
                                vertex.getProcessingItem().getDebugName()), exc);

                        log.error(resultException.getMessage(), resultException);
                        executionResultFuture.completeExceptionally(resultException);

                        return INVALID_HANDLE_PAYLOAD_CONTEXT;
                    }
                })
                .orElse(INVALID_HANDLE_PAYLOAD_CONTEXT);

                if (handlePayloadContext == INVALID_HANDLE_PAYLOAD_CONTEXT) {
                    /**
                     * Failed to get processor result.
                     * Merging will not be applied to payload.
                     * All outgoing flows from merge point will be marked as terminal.
                     * executionResult completed by exception
                     */
                    vertex.getmergingFeature().complete(new MergePayloadContext ().setTerminal(true));
                    return;

                } else if (handlePayloadContext.isTerminal()) {
                    /**
                     * Processor was marked as terminal during flow by terminal transition.
                     * Merging will not be applied to payload.
                     * All outgoing flows from merge point will be marked as terminal.
                     */
                    vertex.getmergingFeature().complete(new MergePayloadContext ().setTerminal(true));
                    return;

                } else if (handlePayloadContext.isDeadTransition()) {
                    /**
                     * Processor was disabled during flow by dead transition.
                     * Merging will not be applied to payload.
                     * All outgoing flows from merge point will be marked as dead.
                     */
                    vertex.getmergingFeature().complete(new MergePayloadContext ().setDeadTransition(true));
                    return;
                }
            }

            /**
             * Incoming merge flows, could be empty for processors Merge Point
             */
            List<MergePayloadContext> incomingMergingFlows = vertex . getincomingMergingFlows ().stream()
                    .map(future -> {
            try {
                if (!future.getFuture().isDone()) {

                    RuntimeException resultException = new RuntimeException(String.format(
                            "Illegal graph execution state. Incoming merge future" +
                                    " is not complete." +
                                    " ProcessingVertex: %s", vertex));
                    log.error(resultException.getMessage(), resultException);
                    executionResultFuture.completeExceptionally(resultException);
                    return INVALID_MERGE_PAYLOAD_CONTEXT;
                } else {
                    return future.getFuture().get();
                }
            } catch (Exception exc) {
                RuntimeException resultException = new RuntimeException(String.format(
                        "Failed to get incoming merge flow future result for processor: %s",
                        vertex.getProcessingItem().getDebugName()), exc);

                log.error(resultException.getMessage(), resultException);
                executionResultFuture.completeExceptionally(resultException);
                return INVALID_MERGE_PAYLOAD_CONTEXT;
            }
        }).collect(Collectors.toList());

            if (incomingMergingFlows.stream().anyMatch(context -> context == INVALID_MERGE_PAYLOAD_CONTEXT)) {
            /**
             * Exception during merging
             * Mark as terminal all outgoing flows from merge point
             */
            vertex.getmergingFeature().complete(new MergePayloadContext ().setTerminal(true));

        } else if (incomingMergingFlows.stream().anyMatch(MergePayloadContext::isTerminal)) {
            /**
             * Terminal state reached.
             * Mark as terminal all outgoing flows from merge point
             */
            vertex.getmergingFeature().complete(new MergePayloadContext ().setTerminal(true));

        } else {

            final List < MergePayloadContext > activeincomingMergingFlows = incomingMergingFlows . stream ()
                    .filter(context ->!context.isDeadTransition())
            .collect(Collectors.toList());

            if (vertex.getProcessingItemInfo().getProcessingItemType()
                    == CRReactorGraph.ProcessingItemType.MERGE_POINT) {
                /**
                 * Detached merge point
                 */
                if (activeincomingMergingFlows.size() == 0) {

                    /**
                     * Check that there are at least one incoming transition that marked as dead
                     */
                    if (incomingMergingFlows.stream().anyMatch(MergePayloadContext::isDeadTransition)) {
                        /**
                         * Detached MergePoint marked as Dead, because there are no active incoming
                         * flows and there is at least one incoming dead transition
                         * Mark as dead all outgoing flows from merge point
                         */
                        vertex.getmergingFeature().complete(
                                new MergePayloadContext ()
                                        .setDeadTransition(true));
                    } else {
                        throw new IllegalStateException (String.format(
                                "There is no incoming merge flows for detached merge point %s." +
                                        " At least dead incoming transition expected.",
                                vertex.getProcessingItem().getDebugName()));
                    }

                } else {
                    if (activeincomingMergingFlows.size() > 1) {
                        /**
                         * Illegal graph state. Too many active incoming flows.
                         * Mark as terminal all outgoing flows from merge point
                         * Complete graph with exception
                         */
                        Exception tooManyActiveIncomingFlowsExc = new Exception(String.format(
                                "There is more than one active incoming flow for routing point %s." +
                                        " Reactor can not determinate from which of transitions take" +
                                        " payload." +
                                        " Possible loss of computation results." +
                                        " Possible concurrent modifications of payload.",
                                vertex.getProcessingItem().getDebugName()));

                        executionResultFuture.completeExceptionally(tooManyActiveIncomingFlowsExc);
                        vertex.getmergingFeature().complete(
                                new MergePayloadContext ()
                                        .setTerminal(true));

                    } else {
                        /**
                         * Single active incoming merge flow
                         */
                        merge(vertex,
                                Optional.empty(),
                                activeincomingMergingFlows.get(0).getPayload(),
                                executionResultFuture);
                    }
                }
            } else {
                /**
                 * Processors MergePoint
                 */
                if (incomingMergingFlows.size() == 0) {
                    /**
                     * No incoming merge flows, only one flow from processors handle
                     */
                    merge(vertex,
                            handlePayloadContext.getProcessorResult(),
                            handlePayloadContext.getPayload(),
                            executionResultFuture);
                } else {
                    /**
                     * Incoming merge flows exists. But some of them can be marked as dead.
                     */
                    if (activeincomingMergingFlows.size() == 0) {
                        /**
                         * There is no active incoming merge flow for given merge point.
                         * Mark merge point as dead.
                         */
                        vertex.getmergingFeature().complete(new MergePayloadContext ()

                                .setDeadTransition(true));

                    } else {

                        if (activeincomingMergingFlows.size() > 1) {
                            /**
                             * Illegal graph state. Too many active incoming flows.
                             * Mark as terminal all outgoing flows from merge point
                             * Complete graph with exception
                             */
                            Exception tooManyActiveIncomingFlowsExc = new Exception(String.format(
                                    "There is more than one active incoming flow for merge point for" +
                                            " processor %s." +
                                            " Reactor can not determinate from which of transitions" +
                                            " take payload." +
                                            " Possible loss of computation results." +
                                            " Possible concurrent modifications of payload.",
                                    vertex.getProcessingItem().getDebugName()));

                            executionResultFuture.completeExceptionally(tooManyActiveIncomingFlowsExc);
                            vertex.getmergingFeature().complete(
                                    new MergePayloadContext ()
                                            .setTerminal(true));

                        } else {

                            merge(vertex,
                                    handlePayloadContext.getProcessorResult(),
                                    activeincomingMergingFlows.get(0).getPayload(),
                                    executionResultFuture);
                        }
                    }
                }

            }
        }
        })
            .exceptionally(throwable -> {
            log.error("Joining incoming merge flows failed.", throwable);
            return null;
        });

        });//processingVertices

        /**
         * Handle terminal vertices.
         * When execution reaches 'complete' vertex all transitions should be marked dead and complete.
         */
        executionResultFuture.thenRunAsync(() -> {
            processingVertices.entrySet().stream()
                    .map(Map.Entry::getValue)
                    .map(ProcessingVertex::getincomingHandlingFlows)
                    .flatMap(Collection::stream)
                    .map(TransitionFuture::getFuture)
                    .forEach(future -> future.complete(new TransitionPayloadContext().setDeadTransition(true)));

            processingVertices.entrySet().stream()
                    .map(Map.Entry::getValue)
                    .map(ProcessingVertex::getincomingHandlingFlows)
                    .flatMap(Collection::stream)
                    .map(TransitionFuture::getFuture)
                    .forEach(future -> future.complete(new TransitionPayloadContext().setDeadTransition(true)));
        }).exceptionally(throwable -> {
            log.error("Marking transitions as dead is failed.", throwable);
            return null;
        });

        /**
         * Collect chain execution processor handling futures
         * Processors futures holds handle invocation result or dead context.
         * Exception is an detached merge point which handlingFeature is not used
         * Then all processors futures completes chainExecutionFuture completes too.
         */
        CompletableFuture<Void> chainExecutionFuture = CompletableFuture . allOf (
                processingVertices.entrySet().stream()
                        .map(Map.Entry::getValue)
                        //detached merge point does not have processor future
                        .filter(vertex -> vertex.getProcessingItemInfo().getProcessingItemType() !=
        CRReactorGraph.ProcessingItemType.MERGE_POINT)
        .map(ProcessingVertex::gethandlingFeature)
                .toArray(CompletableFuture[]::new)
        );

        return ReactorGraphExecution.< PayloadType > builder ()
                .resultFuture(executionResultFuture)
                .submitFuture(submitFuture)
                .chainExecutionFuture(chainExecutionFuture)
                .debugProcessingVertexGraphState(debugProcessingVertexGraphState ? processingVertices . values () : null)
        .build();
    }

    private fun invokeHandlingMethod(
            pvx: ProcessingVertex,
            payload: Any?): CompletableFuture<Any?> {

        when (pvx.vertex) {
        //handler
            handler != null -> invokeProcessorHandlingMethod(processorInfo, processingItem, payload);
            subgraph != null -> invokeSubgraphHandlingMethod(processorInfo, payload);
            router != null -> TODO;

            else throw IllegalStateException (
                String
                .format("Processing item %s of type %s not supported",
                    processingItem.getDebugName(),
                    processorInfo.getProcessingItemType())
                );
        }
    }

    private fun invokeSubgraphHandlingMethod(pvx: ProcessingVertex, payload: Any?): CompletableFuture<Any?> {
        val subgraphPayload = pvx.vertex.subgraphPayloadBuilder.buildPayload(payload)

        return try {
            subgraphRunner(subgraphPayload)
        } catch (exc: Exception) {
            val result = CompletableFuture<Any?>()
            result.completeExceptionally(
                    IllegalArgumentException(
                            """
                            Exception during subgraph launching for vertex ${pvx.vertex.name}.
                            Payload: ${debugSerializer.dumpObject(payload)}
                            """,
                            exc))
            result
        }
    }

    private fun invokeProcessorHandlingMethod(pvx: ProcessingVertex, payload: Any?): CompletableFuture<Any?> {

        return try {
            pvx.vertex.handler.handle(payload)
        } catch (exc: Exception) {
            val result = CompletableFuture<Any?>()
            result.completeExceptionally(
                    IllegalArgumentException("""
                            Exception during handler invocation for vertex ${pvx.vertex.name}.
                            Payload: ${debugSerializer.dumpObject(payload)}
                            """,
                            exc))
            result
        }
    }


    private fun <PayloadType> handle(
            pvx: ProcessingVertex,
            payloadContext: TransitionPayloadContext,
            executionResultFuture: CompletableFuture<PayloadType>) {

        val vx = pvx.vertex
        val payload = payloadContext.payload

        val handleCall = profiler.profiledCall("${ProfilerNames.PROCESSOR_HANDLE}${vx.name}").start()

        val isTraceablePayload = tracer.isTraceable(payload)
        val handleTracingMarker =
                if (isTraceablePayload) {
                    tracer.beforeHandle(vx.name, payload)
                } else {
                    null
                }
        val handleTracingIdentity =
                if (isTraceablePayload) {
                    vx.name
                } else {
                    null
                }
        val handlingResult: CompletableFuture<Any?>? = null

        val payloadSnapshot: ImmutabilityChecker.Snapshot? = null

        try {
            if (immutabilityControlLevel != ImmutabilityControlLevel.NO_CONTROL) {
                /**
                 * Invoke handling with immutability check.
                 */
                payloadSnapshot = immutabilityChecker.takeSnapshot(payload)

                handlingResult = invokeHandlingMethod(pvx, payload)

            } else {
                /**
                 * Invoke handling without immutability check.
                 */
                payloadSnapshot = null

                handlingResult = invokeHandlingMethod(pvx, payload)
            }
        } catch (handlingException: Exception) {
            val exc = RuntimeException(
                    """
                    Failed handling by veretx ${vx.name} for payload ${debugSerializer.dumpObject(payload)}.
                    Handling method raised exception: $handlingException.
                    """,
                    handlingException)

            log.error(exc) {}
            executionResultFuture.completeExceptionally(exc)
            pvx.handlingFeature.complete(HandlePayloadContext(isTerminal = true))
            return
        }

        if (handlingResult == null) {
            val exc = RuntimeException(
                    """
                    Failed handling by vertex ${vx.name} for payload ${debugSerializer.dumpObject(payload)}.
                    Handling method returned NULL.
                    Instance of CompletableFuture expected.
                    """)

            log.error(exc){}
            executionResultFuture.completeExceptionally(exc)
            pvx.handlingFeature.complete(HandlePayloadContext(isTerminal = true))
            return
        }

        handlingResult.handleAsync{result, throwable ->
            handleCall.stop()

            if (isTraceablePayload) {
                tracer.afterHandle(handleTracingMarker, handleTracingIdentity, result, throwable)
            }

            if (controlLevel != ImmutabilityControlLevel.NO_CONTROL) {

                Optional<String> diff = immutabilityChecker . diff (payloadSnapshot, payload);
                if (diff.isPresent()) {
                    String message = String . format ("Concurrent modification of payload %s detected. Diff: %s.",
                    debugSerializer.dumpObject(payload),
                    diff.get());

                    switch(controlLevel) {
                        case LOG_ERROR :
                        log.error(message);
                        break;
                        case LOG_WARN :
                        log.warn(message);
                        break;
                        case EXCEPTION :
                        RuntimeException immutabilityException = new RuntimeException(message);
                        log.error(message, immutabilityException);

                        if (throwable == null) {
                            log.error(
                                    "Overwriting execution exception {} by immutability check exception {}.",
                                    throwable,
                                    immutabilityException,
                                    throwable);
                        }
                        throwable = immutabilityException;
                        break;
                    }
                }
            }

            if (throwable != null) {
                RuntimeException exc = new RuntimeException(
                        String.format(
                                "Failed handling by processor %s for payload %s %s",
                                processingVertex.getProcessingItem().getDebugName(),
                                payload.getClass(),
                                debugSerializer.dumpObject(payload)),
                        throwable);

                log.error(exc.getMessage(), exc);
                executionResultFuture.completeExceptionally(exc);

                processingVertex.gethandlingFeature().complete(new HandlePayloadContext ().setTerminal(true));
            } else {
                processingVertex.gethandlingFeature().complete(new HandlePayloadContext ()
                        .setPayload(payload)
                        .setProcessorResult(result));
            }
            return null;
        }).exceptionally(exc -> {
            log.error("Failed to execute afterHandle block for {}",
                    Optional.of(processingVertex)
                            .map(ProcessingVertex::getProcessingItem)
                            .map(CRProcessingItem::getDebugName)
                            .orElse("?"), exc);
            return null;
        });
    }

    /**
     * @param processingVertex
     * @param processorResult       empty in case of detached merge point
     * @param payload
     * @param executionResultFuture
     * @param <PayloadType>
     */
    private <PayloadType> void merge(ProcessingVertex processingVertex,
    Object processorResult,
    Object payload,
    CompletableFuture<PayloadType> executionResultFuture)
    {



//-->
        if (vx.router != null) {
            /**
             * Router does not participate in mergeBy transitions.
             * All incomingHandlingFlows is processed with incomingMergingFlows in mergeres section.
             */
            continue
        }





        /**
         * In case of detached merge point processor should not have incoming handling transition.
         */
        if (processorInfo.getProcessingItemType() == CRReactorGraph.ProcessingItemType.MERGE_POINT) {
            throw new IllegalStateException (String.format(
                    "Processor %s is of type %s and should not have any incoming handling transition",
                    processingVertex.getProcessingItem().getDebugName(),
                    processorInfo.getProcessingItemType()));
        }
//<--



        CRReactorGraph.ProcessingItemInfo processorInfo = processingVertex . getProcessingItemInfo ();

        Supplier<Enum> mergerInvocation;

        switch(processorInfo.getProcessingItemType()) {
            case PROCESSOR :
            if (processorInfo.getDescription().getMerger() == null) {
                /**
                 * This Processor does not have merger
                 */
                processingVertex.getmergingFeature().complete(new MergePayloadContext ().setDeadTransition(true));
                return;
            } else {
                mergerInvocation = () -> (Enum) processorInfo.getDescription().getMerger().merge(
                payload,
                processorResult);
            }
            break;
            case SUBGRAPH :
            if (processorInfo.getSubgraphDescription().getMerger() == null) {
                /**
                 * This Subgraph does not have merger
                 */
                processingVertex.getmergingFeature().complete(new MergePayloadContext ().setDeadTransition(true));
                return;
            } else {
                mergerInvocation = () -> (Enum) processorInfo.getSubgraphDescription().getMerger().merge(
                payload,
                processorResult);
            }
            break;
            case MERGE_POINT :
            mergerInvocation = () -> (Enum) processorInfo.getDetachedMergePointDescription()
            .getMerger()
                .merge(payload);
            break;

            default:
            throw new IllegalArgumentException (String.format("Unknown processor type: %s",
                    processorInfo.getProcessingItemType()));
        }


        try {
            ProfiledCall mergeCall = profiler . profiledCall (
                    ProfilerNames.PROCESSOR_MERGE + processingVertex.getProcessingItem().getProfilingName())
                    .start();
            boolean isTraceablePayload = tracer . isTraceable (payload);
            Object mergeTracingMarker = isTraceablePayload ?
            tracer.beforeMerge(processingVertex.getProcessingItem().getIdentity(), payload, processorResult) :
            null;

            Enum mergeStatus = mergerInvocation . get ();

            mergeCall.stop();

            if (isTraceablePayload) {
                tracer.afterMerger(mergeTracingMarker, processingVertex.getProcessingItem().getIdentity(), payload);
            }

            /**
             * Select outgoing transitions that matches mergeStatus
             */
            List<CRReactorGraph.Transition> activeTransitions = processingVertex . getoutgoingTransitions ().stream()
                    .filter(transition -> transition.isOnAny() || transition.getMergeStatuses().contains(mergeStatus))
            .collect(Collectors.toList());

            if (activeTransitions.size() <= 0) {
                throw new IllegalStateException (String.format("Merger function returned %s.%s status." +
                        " But merge point of processor %s does not have matching transition for this status." +
                        " Expected status from merger function one of: %s",
                        mergeStatus.getDeclaringClass(), mergeStatus,
                        processingVertex.getProcessingItem().getDebugName(),
                        processingVertex.getoutgoingTransitions().stream()
                                .map(CRReactorGraph.Transition::getDebugDescription)
                                .collect(Collectors.joining(",", "{", "}"))));
            }

            /**
             * check if this merge point have terminal transitions that matches merge status
             */
            if (activeTransitions.stream().anyMatch(CRReactorGraph.Transition::isComplete)) {

                /**
                 * Handle terminal transition by completing execution result
                 */
                if (!executionResultFuture.complete((PayloadType) payload)) {

                    Object previousResult = null;
                    try {
                        if (executionResultFuture.isDone()) {
                            previousResult = executionResultFuture.get();
                        } else {
                            log.error("Illegal graph execution state." +
                                    " Completion failed for new result," +
                                    " but execution result from previous terminal step is not complete.");
                        }
                    } catch (Exception exc) {
                        log.error("Failed to get completed execution result from previous terminal step.", exc);
                    }

                    log.error("Processing chain was completed by at least two different terminal steps." +
                            " Already completed with result {}." +
                            " New completion result {} in merge point for processor {}",
                            debugSerializer.dumpObject(previousResult),
                            debugSerializer.dumpObject(payload),
                            processingVertex.getProcessingItem().getDebugName());
                }

                /**
                 * Terminal state reached. Execution result completed.
                 * Throw poison pill - terminal context. All following merge points should be deactivated.
                 */
                processingVertex.getmergingFeature().complete(new MergePayloadContext ()
                        .setPayload(null)
                        .setMergeResult(mergeStatus)
                        .setTerminal(true));
            } else {
                /**
                 * There is no terminal state reached after merging.
                 */
                processingVertex.getmergingFeature().complete(new MergePayloadContext ()
                        .setPayload(payload)
                        .setMergeResult(mergeStatus));
            }

        } catch (Exception exc) {
            log.error("Failed to merge payload {} {} by processing item {} for result {}",
                    payload.getClass(),
                    debugSerializer.dumpObject(payload),
                    processingVertex.getProcessingItem().getDebugName(),
                    debugSerializer.dumpObject(processorResult),
                    exc);

            executionResultFuture.completeExceptionally(exc);

            processingVertex.getmergingFeature().complete(new MergePayloadContext ().setDeadTransition(true));
        }
    }
}
