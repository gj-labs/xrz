package com.bindlab.phone

import org.json.JSONObject

data class Sequences(val target: String?, val binder: String?)

/**
 * QR payloads we accept:
 *  - JSON {"target": "...", "binder": "..."} (also target_seq / binder_seq)
 *  - FASTA with two records (a record named *binder* is the binder, otherwise order = target, binder)
 *  - "TARGET|BINDER" or "TARGET;BINDER", or two lines
 *  - a single sequence -> target only
 */
object SequenceParser {
    private val residues = Regex("^[A-Za-z]+$")

    fun parse(raw: String): Sequences? {
        val text = raw.trim()
        if (text.startsWith("{")) {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val t = o.optString("target").ifBlank { o.optString("target_seq") }
            val b = o.optString("binder").ifBlank { o.optString("binder_seq") }
            return ok(t, b)
        }
        if (text.startsWith(">")) {
            val recs = mutableListOf<Pair<String, StringBuilder>>()
            text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
                if (line.startsWith(">")) recs += line.drop(1) to StringBuilder() else recs.lastOrNull()?.second?.append(line)
            }
            val binderIdx = recs.indexOfFirst { "binder" in it.first.lowercase() }
            return if (binderIdx >= 0) {
                ok(recs.filterIndexed { i, _ -> i != binderIdx }.firstOrNull()?.second?.toString(), recs[binderIdx].second.toString())
            } else ok(recs.getOrNull(0)?.second?.toString(), recs.getOrNull(1)?.second?.toString())
        }
        val parts = text.split('|', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        return ok(parts.getOrNull(0), parts.getOrNull(1))
    }

    private fun ok(t: String?, b: String?): Sequences? {
        val tt = t?.let(EsmTokenizer::clean)?.takeIf { residues.matches(it) }
        val bb = b?.let(EsmTokenizer::clean)?.takeIf { residues.matches(it) }
        return if (tt == null && bb == null) null else Sequences(tt, bb)
    }

    // Ubiquitin + a ubiquitin-interacting-motif-like peptide: small, fast demo pair.
    const val DEMO_TARGET = "MQIFVKTLTGKTITLEVEPSDTIENVKAKIQDKEGIPPDQQRLIFAGKQLEDGRTLSDYNIQKESTLHLVLRLRGG"
    const val DEMO_BINDER = "GSEEDLQRALALSLAESEAAKRGS"
}
