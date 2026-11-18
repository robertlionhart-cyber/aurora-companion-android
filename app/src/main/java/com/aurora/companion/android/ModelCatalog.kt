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
    /** Only offered (and only loadable) while 18+ mode is on (Unlocked edition only). */
    val adultOnly: Boolean = false,
    /** Pinned SHA-256 of the file; checked after download when set. */
    val sha256: String? = null,
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
        // 0.8.4: a "6 GB" phone reports roughly 5.3-5.6 GB total to apps (Galaxy A54 6 GB: 5.3).
        minRamGb = ModelCatalog.MIN_RAM_3B,
        note = "Smarter but 2-3x slower and needs about 3 GB RAM while chatting. May be slow or freeze on 6 GB phones (e.g. Galaxy A54 6 GB); " +
            "best on 8 GB+. If it freezes, the app opens a smaller model next time.",
        license = "Meta Llama 3.2 Community License",
    )

    /** 0.8.6: the Unlocked edition adds its optional models (Edition.extraModels); the Family edition has none. */
    val all = listOf(LLAMA_1B, LLAMA_3B) + Edition.extraModels

    /** 3B-class files (about 2 GB): on 6 GB phones they can run out of memory and freeze. */
    const val HEAVY_BYTES = 1_500_000_000L
    /** RAM (as reported) from which heavy models are trusted to auto-load at startup. */
    const val HEAVY_SAFE_RAM_GB = 7.0

    fun totalRamGb(context: Context): Double {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        return mi.totalMem / (1024.0 * 1024.0 * 1024.0)
    }

    fun available(ramGb: Double, adult: Boolean) = all.filter { ramGb >= it.minRamGb && (adult || !it.adultOnly) }

    fun byFileName(name: String?) = all.firstOrNull { it.fileName == name }

    /** True for a catalog model that needs adult mode (imported .gguf files are the user's own choice). */
    fun isAdultOnly(fileName: String?) = byFileName(fileName)?.adultOnly == true

    /** Always default to 1B: it is responsive everywhere. 3B is an opt-in on 6 GB+ (warned), Qwen 1.5B is the adult pick. */
    fun recommended(ramGb: Double) = LLAMA_1B

    const val MIN_RAM_3B = 5.0
}

fun Long.toNiceSize(): String {
    val gb = this / (1024.0 * 1024.0 * 1024.0)
    return if (gb >= 1.0) String.format("%.2f GB", gb) else String.format("%.0f MB", this / (1024.0 * 1024.0))
}
