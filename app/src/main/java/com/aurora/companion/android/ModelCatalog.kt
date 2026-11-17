package com.aurora.companion.android

import android.app.ActivityManager
import android.content.Context

/** A downloadable, third-party GGUF chat model. */
data class ModelOption(
    val id: String,
    val title: String,
    val fileName: String,
    val url: String,
    val sizeBytes: Long,
    /** Minimum total device RAM (GB, as reported by Android) we allow this model on. */
    val minRamGb: Double,
    val note: String,
    val license: String,
)

object ModelCatalog {
    val LLAMA_1B = ModelOption(
        id = "llama-3.2-1b-q4km",
        title = "Llama 3.2 1B Instruct (Q4_K_M)",
        fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        url = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
        sizeBytes = 807_694_464L,
        minRamGb = 3.0,
        note = "Fast and light. Best choice for phones and tablets with 4-6 GB RAM (Xiaomi Pad 5, Galaxy A54 6 GB).",
        license = "Meta Llama 3.2 Community License",
    )
    val LLAMA_3B = ModelOption(
        id = "llama-3.2-3b-q4km",
        title = "Llama 3.2 3B Instruct (Q4_K_M)",
        fileName = "Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        url = "https://huggingface.co/bartowski/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
        sizeBytes = 2_019_377_696L,
        // An "8 GB" phone reports roughly 7.2-7.6 GB total to apps.
        minRamGb = 7.0,
        note = "Smarter but slower and uses about 3 GB RAM while chatting. Only offered on 8 GB+ devices (e.g. Galaxy A54 8 GB).",
        license = "Meta Llama 3.2 Community License",
    )

    val all = listOf(LLAMA_1B, LLAMA_3B)

    fun totalRamGb(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    fun available(ramGb: Double) = all.filter { ramGb >= it.minRamGb }

    /** Always default to 1B: it is responsive everywhere. 3B is an opt-in on 8 GB+. */
    fun recommended(ramGb: Double) = LLAMA_1B
}

fun Long.toNiceSize(): String {
    val gb = this / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format("%.2f GB", gb) else String.format("%.0f MB", this / (1024.0 * 1024.0))
}
