package ru.fix.completable.reactor.runtime.gl

interface GlMergerBuilder<Payload, HandlerResult> {

    fun withMerger(merger: Merger<Payload, HandlerResult>): Vertex

    fun withMerger(title: String, merger: Merger<Payload, HandlerResult>): Vertex

    fun withMerger(title: String,
                   doc: String,
                   merger: Merger<Payload, HandlerResult>): Vertex

    fun withMerger(title: String,
                   docs: Array<String>,
                   merger: Merger<Payload, HandlerResult>): Vertex

    fun withoutMerger(): Vertex
}
