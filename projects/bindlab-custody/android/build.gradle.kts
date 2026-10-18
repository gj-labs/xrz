plugins {
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}

// ExecuTorch runtime: the track team's AAR (android/libs/executorch.aar, or ../libs/) wins;
// otherwise the public Maven build (XNNPACK only, no QNN delegate).
val executorchAar: File? = listOf(file("libs/executorch.aar"), file("../libs/executorch.aar"))
    .firstOrNull { it.exists() }
extra["executorchAar"] = executorchAar
extra["executorchMaven"] = "org.pytorch:executorch-android:1.5.1"
if (executorchAar != null) logger.lifecycle("ExecuTorch: using local AAR $executorchAar")
else logger.lifecycle("ExecuTorch: libs/executorch.aar not found, using Maven ${extra["executorchMaven"]}")
