package ru.fix.completable.reactor.kotlin.example

import org.junit.Test
import ru.fix.completable.reactor.runtime.ReactorGraphBuilder

/**
 * @author Kamil Asfandiyarov
 */
class ConfigurationTest {

    @Test
    fun purchase_graph() {
        val purchaseGraph = Configuration().purchase()
        ReactorGraphBuilder.write(purchaseGraph)
    }

}