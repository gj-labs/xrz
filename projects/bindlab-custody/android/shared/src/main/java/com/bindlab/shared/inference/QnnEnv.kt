package com.bindlab.shared.inference

import android.content.Context
import android.system.Os
import android.util.Log

/**
 * QNN needs to find libQnnHtp*.so (LD_LIBRARY_PATH) and the Hexagon skel libs (ADSP_LIBRARY_PATH).
 * Both live in nativeLibraryDir when shipped in src/main/jniLibs/arm64-v8a.
 */
object QnnEnv {
    private const val SYSTEM_ADSP =
        "/vendor/lib/rfsa/adsp;/vendor/dsp/cdsp;/system/lib/rfsa/adsp;/system/vendor/lib/rfsa/adsp;/dsp"

    fun libDir(context: Context): String = context.applicationInfo.nativeLibraryDir

    fun adspPath(context: Context) = "${libDir(context)};$SYSTEM_ADSP"

    fun ldPath(context: Context): String {
        val existing = System.getenv("LD_LIBRARY_PATH")
        return if (existing.isNullOrEmpty()) libDir(context) else "${libDir(context)}:$existing"
    }

    @Volatile private var applied = false

    fun apply(context: Context) {
        if (applied) return
        try {
            Os.setenv("ADSP_LIBRARY_PATH", adspPath(context), true)
            Os.setenv("LD_LIBRARY_PATH", ldPath(context), true)
            applied = true
        } catch (e: Exception) {
            Log.w("QnnEnv", "setenv failed", e)
        }
    }
}
