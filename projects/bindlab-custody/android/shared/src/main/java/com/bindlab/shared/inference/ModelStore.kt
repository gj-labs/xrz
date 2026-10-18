package com.bindlab.shared.inference

import android.content.Context
import java.io.File

/**
 * Where models live. Primary: app-specific external storage, which `adb push` can write to:
 *   /sdcard/Android/data/<applicationId>/files/models/
 * Secondary: internal files/models (via `adb shell run-as <pkg> cp ...`).
 */
object ModelStore {
    fun dirs(context: Context): List<File> = listOfNotNull(
        context.getExternalFilesDir("models"),
        File(context.filesDir, "models"),
    ).onEach { it.mkdirs() }

    fun primaryDir(context: Context): File = dirs(context).first()

    fun file(context: Context, name: String): File? =
        dirs(context).map { File(it, name) }.firstOrNull { it.exists() }

    /**
     * `<base>.pte` if present, else the shards `<base>_shard0.pte`, `<base>_shard1.pte`, ...
     * Returns null when nothing is there ("model missing").
     */
    fun find(context: Context, base: String): List<File>? {
        file(context, "$base.pte")?.let { return listOf(it) }
        for (dir in dirs(context)) {
            val shards = dir.listFiles { f -> f.name.startsWith("${base}_shard") && f.name.endsWith(".pte") }
                ?.sortedBy { it.name.removePrefix("${base}_shard").removeSuffix(".pte").toIntOrNull() ?: 0 }
            if (!shards.isNullOrEmpty()) return shards
        }
        return null
    }

    /** Engine for a model found by [find]: a plain engine for one file, a ShardedEngine for shards. */
    fun engineFor(files: List<File>, factory: () -> InferenceEngine): Pair<InferenceEngine, String> =
        if (files.size == 1) factory() to files[0].absolutePath
        else ShardedEngine(factory, files.map { it.absolutePath }) to files[0].absolutePath
}
