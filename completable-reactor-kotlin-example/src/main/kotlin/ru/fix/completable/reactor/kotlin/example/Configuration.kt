package ru.fix.completable.reactor.kotlin.example

import ru.fix.completable.reactor.runtime.ReactorGraph
import ru.fix.completable.reactor.runtime.ReactorGraphBuilder

/**
 * @author Kamil Asfandiyarov
 */
class Configuration {

    fun purchase() : ReactorGraph<String> {

        val graphBuilder = ReactorGraphBuilder()

        graphBuilder.payload(PurchasePayload.javaClass)



    }
}