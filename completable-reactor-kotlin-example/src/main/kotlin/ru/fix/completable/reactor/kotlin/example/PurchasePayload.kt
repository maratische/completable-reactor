package ru.fix.completable.reactor.kotlin.example

/**
 * @author Kamil Asfandiyarov
 */
data class PurchasePayload (
        val userId: Long,
        val productId: Long,
        val result: Boolean

)