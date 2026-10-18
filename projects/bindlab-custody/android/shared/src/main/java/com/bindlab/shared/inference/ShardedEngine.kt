package com.bindlab.shared.inference

/**
 * Runs N .pte shards back to back (3B split for the HTP context limit): the outputs of shard i
 * become the inputs of shard i+1. The first shard gets the caller's inputs.
 */
class ShardedEngine(private val factory: () -> InferenceEngine, private val shardPaths: List<String>) :
    InferenceEngine {
    override val name = "sharded(${shardPaths.size})"
    private val engines = mutableListOf<InferenceEngine>()

    /** [modelPath] is ignored; the shard list given at construction is loaded. */
    override fun load(modelPath: String): Long {
        close()
        var total = 0L
        try {
            for (p in shardPaths) {
                val e = factory()
                engines += e
                total += e.load(p)
            }
        } catch (t: Throwable) {
            close(); throw t
        }
        return total
    }

    override fun run(inputs: List<TensorInput>): List<TensorOutput> {
        var cur = inputs
        var outs: List<TensorOutput> = emptyList()
        for (e in engines) {
            outs = e.run(cur)
            cur = outs.map { TensorInput.F32(it.data, it.shape) }
        }
        return outs
    }

    override fun close() {
        engines.forEach { runCatching { it.close() } }
        engines.clear()
    }
}
