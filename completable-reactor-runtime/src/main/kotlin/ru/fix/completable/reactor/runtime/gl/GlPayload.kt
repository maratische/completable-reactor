package ru.fix.completable.reactor.runtime.gl

interface GlPayload<Payload> {
    fun handleBy(vertex: Vertex): GlPayload<Payload>
}
