package ru.fix.completable.reactor.kotlin.example

import ru.fix.completable.reactor.runtime.ReactorGraph
import ru.fix.completable.reactor.runtime.ReactorGraphBuilder

/**
 * @author Kamil Asfandiyarov
 */
class Configuration {
    val builder = ReactorGraphBuilder(this)
    val storageFacility = StorageFacility()

    val storageProcessor = builder.processor()
            .forPayload(PurchasePayload::class.java)
            .passArg { pld -> pld.productId }
            .withHandler { product -> storageFacility.reserveProduct(product) }
            .withoutMerger()
            .buildProcessor()

    fun purchase() : ReactorGraph<PurchasePayload> {

        return builder.payload(PurchasePayload::class.java)
                .handle(storageProcessor)

                .mergePoint(storageProcessor)
                .onAny().complete()

                .coordinates()
                .buildGraph()
    }
}