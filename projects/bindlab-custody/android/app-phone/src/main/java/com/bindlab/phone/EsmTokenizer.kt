package com.bindlab.phone

/** ESM-2 alphabet, identical to ml/common.py (<cls> residues <eos> then <pad> to 256). */
object EsmTokenizer {
    val VOCAB = listOf(
        "<cls>", "<pad>", "<eos>", "<unk>",
        "L", "A", "G", "V", "S", "E", "R", "T", "I", "D", "P", "K",
        "Q", "N", "F", "Y", "M", "H", "W", "C", "X", "B", "U", "Z", "O",
        ".", "-", "<null_1>", "<mask>",
    )
    const val SEQ_LEN = 256
    const val CLS = 0L
    const val PAD = 1L
    const val EOS = 2L
    const val UNK = 3L
    val MAX_RESIDUES = SEQ_LEN - 2

    private val ids: Map<Char, Long> = VOCAB.withIndex()
        .filter { it.value.length == 1 }.associate { it.value[0] to it.index.toLong() }

    fun clean(seq: String) = seq.uppercase().filterNot { it.isWhitespace() }

    fun encode(seq: String, seqLen: Int = SEQ_LEN): LongArray {
        val residues = clean(seq).take(seqLen - 2)
        val out = LongArray(seqLen) { PAD }
        out[0] = CLS
        residues.forEachIndexed { i, c -> out[i + 1] = ids[c] ?: UNK }
        out[residues.length + 1] = EOS
        return out
    }
}
