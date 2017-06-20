package ru.fix.completable.reactor.kotlin.example

import org.slf4j.LoggerFactory
import ru.fix.completable.reactor.api.Reactored
import java.util.concurrent.CompletableFuture

/**
 * @author Kamil Asfandiyarov
 */
class StorageFacility {
    companion object{
        val log = LoggerFactory.getLogger(StorageFacility.javaClass)
    }

    @Reactored("Reserves given product if it is is available on the storage")
    fun reserveProduct(productId: Long): CompletableFuture<Boolean>{

        log.info("Check availability for {} and reserve it.", productId)
        return CompletableFuture.completedFuture(true)
    }
}

