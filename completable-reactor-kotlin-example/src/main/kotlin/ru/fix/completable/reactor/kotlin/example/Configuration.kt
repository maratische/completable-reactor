package ru.fix.completable.reactor.kotlin.example

import ru.fix.completable.reactor.runtime.ReactorGraph
import ru.fix.completable.reactor.runtime.ReactorGraphBuilder

/**
 * @author Kamil Asfandiyarov
 */
class Configuration {
    val builder = ReactorGraphBuilder()
    val storageFacility = StorageFacility()

    fun purchase() : ReactorGraph<PurchasePayload> {

        val storage = builder.processor()
                .forPayload(PurchasePayload::class.java)
                .passArg { pld -> pld.productId }
                .withHandler { product -> storageFacility.reserveProduct(product) }
                .withoutMerger()
                .buildProcessor()


        return builder.payload(PurchasePayload::class.java)
                .handle(storage)

                .mergePoint(storage)
                .onAny().complete()

                .coordinates()
                .buildGraph();
    }
}