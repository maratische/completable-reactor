package ru.fix.cpapsm.completable.reactor.runtime.immutability;

/**
 * @author Kamil Asfandiyarov
 */
public enum ImmutabilityControlLevel {
    /**
     * Fastest execution mode.
     * Payload is not copied and no payload modification check is applied.
     */
    NO_CONTROL,
    /**
     * In case payload modification inside processor handler method or outside reactor
     * warning will be logged
     */
    LOG_WARN,
    /**
     * In case payload modification inside processor handler method or outside reactor
     * error will be logged
     */
    LOG_ERROR,
    /**
     * In case payload modification inside processor handler method or outside reactor
     * exception will be raised
     */
    EXCEPTION
}
