package ru.fix.completable.reactor.kotlin.example

import org.slf4j.LoggerFactory
import ru.fix.completable.reactor.api.Reactored
import java.util.concurrent.CompletableFuture

/**
 * @author Kamil Asfandiyarov
 */
class Storage{
    companion object{
        val log = LoggerFactory.getLogger(Storage.javaClass)
    }

    @Reactored("Checks that given product is available on the storage")
    fun checkProductAvailability(product: Product): CompletableFuture<Boolean>{

        log.info("Check availability for {}", product)
        return CompletableFuture.completedFuture(true)
    }
}

