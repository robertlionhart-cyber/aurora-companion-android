package com.aurora.companion.android

import android.app.Application
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** aside = written by the app (e.g. a friendly error), never sent back to the model. */
data class ChatMessage(val id: String = UUID.randomUUID().toString(), val text: String, val isUser: Boolean, val time: Long = System.currentTimeMillis(), val aside: Boolean = false)

sealed class Screen {
    object Setup : Screen()
    data class Busy(val title: String, val progress: Float?, val detail: String = "") : Screen()
    object Chat : Screen()
}

data class UiState(
    val screen: Screen = Screen.Setup,
    val ramGb: Double = 0.0,
    val freeStorageBytes: Long = 0,
    val options: List<ModelOption> = emptyList(),
    val selected: ModelOption = ModelCatalog.LLAMA_1B,
    val downloaded: Set<String> = emptySet(),
    val loadedModelName: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val generating: Boolean = false,
    val error: String? = null,
    // Voice (phase 2)
    val voiceReady: Boolean = false,
    val micReady: Boolean = false,
    val voiceStatus: String = "Voice starting...",
    val autoSpeak: Boolean = true,
    val listening: Boolean = false,
    /** Tap-to-talk: recording keeps going until the mic is tapped again. */
    val listenLatched: Boolean = false,
    val transcribing: Boolean = false,
    val speaking: Boolean = false,
    // 0.3: voices + hands-free mic
    val voiceId: String = VoiceCatalog.ROBERT.id,
    val speakerId: Int = 0,
    val numSpeakers: Int = 1,
    val installedVoices: Set<String> = setOf(VoiceCatalog.ROBERT.id),
    val voiceDownload: Float? = null,
    val switchingVoice: Boolean = false,
    val micMode: String = MIC_AUTO,
    val micLevel: Float = 0f,
    val heardSpeech: Boolean = false,
    val notice: String? = null,
    // 0.4: call mode
    val inCall: Boolean = false,
    val callMuted: Boolean = false,
    val callHearing: Boolean = false,
    val callLevel: Float = 0f,
    val bargeIn: Boolean = true,
    val bargeSensitivity: String = "medium",
    // 0.5: companions (avatars)
    val companionId: String = Companions.AURORA.id,
    val companionChosen: Boolean = true,
    val animateAvatar: Boolean = true,
    // 0.6: custom characters
    val characters: List<CompanionProfile> = emptyList(),
    val editor: CharacterEdit? = null,
    val lastSavedCharacter: String? = null,
    // 0.7: reminders
    val reminders: List<Reminder> = emptyList(),
    val speakReminders: Boolean = true,
    /** One-shot: ask for the notification permission (Android 13+) after the first reminder is set. */
    val askNotifPermission: Boolean = false,
    // 0.8: public-domain book library
    val library: LibraryStatus = LibraryStatus(),
    val libraryTask: LibraryTask? = null,
    val bookKnowledge: Boolean = false,
    val bookKnowledgeDefault: Boolean = false,
    // 0.8.2: conversation history (review only)
    val history: List<Conversation> = emptyList(),
    // 0.8.3: running memory + continue
    val memoryOn: Boolean = true,
    val memoryNotes: List<MemoryNote> = emptyList(),
    /** The companion is writing memory notes after a chat (sends wait for it). */
    val memoryBusy: Boolean = false,
    /** Short status while the context is being rebuilt (Continue / recovery). */
    val preparing: String? = null,
) {
    val companion: CompanionProfile get() = characters.firstOrNull { it.id == companionId } ?: Companions.byId(companionId)
}

/** What the character editor opens with (new, edit, duplicate or a reviewed import). */
data class CharacterEdit(
    val id: String?,             // null = new character
    val name: String = "",
    val look: String = "pebbie",
    val personality: String = "",
    val scenario: String = "",
    val greeting: String = "",
    val facts: String = "",      // one per line
    val voiceId: String = VoiceCatalog.ROBERT.id,
    val speakerId: Int = 0,
    val adultFlagged: Boolean = false,
    val source: String = "created",
    val notes: List<String> = emptyList(),
    val createdAt: Long = 0,
)

/** Library download / indexing progress shown on the Library screen. */
data class LibraryTask(val title: String, val progress: Float?, val detail: String = "")

const val MIC_AUTO = "auto"
const val MIC_HOLD = "hold"

class AuroraViewModel(app: Application) : AndroidViewModel(app) {

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val engine: InferenceEngine by lazy { AiChat.getInferenceEngine(app) }
    private val prefs = app.getSharedPreferences("aurora", 0)
    private val modelsDir = File(app.filesDir, "models").apply { mkdirs() }
    private var workJob: Job? = null
    private var genJob: Job? = null

    // ---------- 0.8.3: one model, one user at a time ----------
    /** Serializes everything that uses the llama context: loading, replies, memory passes, rebuilds. */
    private val llm = Mutex()
    /** True when the native context matches the chat on screen (false after a memory pass or an error). */
    @Volatile private var chatValid = false
    private var memJob: Job? = null
    /** Messages before this index were already given to a memory pass. */
    private var memFrom = 0
    /** A memory pass that was interrupted (e.g. you started talking); it's retried with the next one. */
    private var deferredMemory: PendingMemory? = null
    private data class PendingMemory(val companionId: String, val companionName: String, val lines: List<Pair<Boolean, String>>)

    // ---------- voice ----------
    private val voice = VoiceEngine(app)
    private data class Utterance(val text: String, val epoch: Int)
    private val speechQueue = Channel<Utterance>(Channel.UNLIMITED)
    private val pendingSpeech = java.util.concurrent.atomic.AtomicInteger(0)
    private var recordJob: Job? = null

    /** Model waiting for the first-run companion pick, so it is only loaded once with the right persona. */
    private var pendingAutoLoad: File? = null

    private fun voicePrefFor(c: String) = prefs.getString("voice_for_$c", null) ?: prefs.getString("voice_id", VoiceCatalog.ROBERT.id)
    private fun speakerPrefFor(c: String) = prefs.getInt("speaker_for_$c", prefs.getInt("speaker_id", DEFAULT_LIBRITTS_SPEAKER))

    init {
        val chars = runCatching { CharacterStore.loadAll(app) }.getOrElse { Log.e(TAG, "characters load failed", it); emptyList() }
        Companions.custom = chars
        _ui.update { it.copy(characters = chars) }
        _ui.update { it.copy(
            companionId = Companions.byId(prefs.getString("companion_id", null)).id,
            companionChosen = prefs.contains("companion_id"),
            animateAvatar = prefs.getBoolean("animate_avatar", true),
        ) }
        _ui.update { it.copy(bargeIn = prefs.getBoolean("barge_in", true), bargeSensitivity = prefs.getString("barge_sens", "medium") ?: "medium") }
        _ui.update { it.copy(memoryOn = prefs.getBoolean("memory_on", true)) }
        val savedSpeaker = speakerPrefFor(_ui.value.companionId)
        voice.speakerId = savedSpeaker
        _ui.update {
            it.copy(
                autoSpeak = prefs.getBoolean("auto_speak", true),
                micMode = prefs.getString("mic_mode", MIC_AUTO) ?: MIC_AUTO,
                speakerId = savedSpeaker,
                installedVoices = installedVoiceIds(),
            )
        }
        // Load voice models in the background (fully offline).
        viewModelScope.launch(Dispatchers.Default) {
            var v = VoiceCatalog.byId(voicePrefFor(_ui.value.companionId))
            if (!VoiceCatalog.isInstalled(app, v)) v = VoiceCatalog.ROBERT
            try {
                try { voice.initTts(v) } catch (e: Throwable) {
                    if (v.builtIn) throw e
                    Log.e(TAG, "voice ${v.id} failed, falling back to Robert", e)
                    v = VoiceCatalog.ROBERT; voice.initTts(v)
                }
                _ui.update { it.copy(voiceReady = true, voiceId = v.id, numSpeakers = voice.numSpeakers(), voiceStatus = "${shortName(v)} voice ready") }
            } catch (e: Throwable) {
                Log.e(TAG, "TTS init failed", e)
                _ui.update { it.copy(voiceStatus = "Voice unavailable: ${e.message}") }
            }
            try {
                voice.initAsr()
                _ui.update { it.copy(micReady = true) }
            } catch (e: Throwable) {
                Log.e(TAG, "ASR init failed", e)
                _ui.update { it.copy(voiceStatus = "Speech recognition unavailable: ${e.message}") }
            }
            try { voice.initVad() } catch (e: Throwable) {
                Log.e(TAG, "VAD init failed", e)
                _ui.update { it.copy(micMode = MIC_HOLD) }   // hands-free needs the VAD
            }
        }
        // Single speech worker: synthesizes + plays sentences in order.
        viewModelScope.launch(Dispatchers.IO) {
            for (u in speechQueue) {
                try {
                    if (u.epoch == voice.currentEpoch()) voice.speakBlocking(u.text, u.epoch)
                } catch (e: Throwable) {
                    Log.e(TAG, "speak failed", e)
                } finally {
                    if (pendingSpeech.decrementAndGet() <= 0) {
                        pendingSpeech.set(0)
                        _ui.update { it.copy(speaking = false) }
                    }
                }
            }
        }
        // Reminders: load, keep in sync with the alarm receiver, re-arm (a force-stop clears alarms), speak when they fire.
        _ui.update { it.copy(speakReminders = prefs.getBoolean("speak_reminders", true)) }
        viewModelScope.launch { ReminderStore.version.collect { reloadReminders() } }
        viewModelScope.launch(Dispatchers.IO) { runCatching { ReminderAlarms.ensureChannel(app); ReminderAlarms.rescheduleAll(app) } }
        viewModelScope.launch { ReminderBus.fired.collect { onReminderFired(it.reminder) } }
        // Book library (0.8): "Knowledge from books" defaults on only with >= 6 GB RAM, like the desktop.
        val ramNow = ModelCatalog.totalRamGb(app)
        val bkDefault = ramNow >= BOOK_KNOWLEDGE_MIN_RAM_GB
        _ui.update { it.copy(bookKnowledgeDefault = bkDefault, bookKnowledge = prefs.getBoolean("book_knowledge", bkDefault)) }
        refreshLibrary()
        refreshDevice()
        // Auto-load the last used model if it is still on the device.
        prefs.getString("last_model_file", null)?.let { name ->
            val f = File(modelsDir, name)
            if (f.isFile && f.length() > 0) { if (_ui.value.companionChosen) loadModel(f) else pendingAutoLoad = f }
        }
    }

    fun refreshDevice() {
        val ctx = getApplication<Application>()
        val ram = ModelCatalog.totalRamGb(ctx)
        val free = StatFs(ctx.filesDir.path).availableBytes
        val done = ModelCatalog.all.filter { File(modelsDir, it.fileName).let { f -> f.isFile && f.length() == it.sizeBytes } }
            .map { it.id }.toSet()
        _ui.update {
            it.copy(
                ramGb = ram,
                freeStorageBytes = free,
                options = ModelCatalog.available(ram),
                selected = if (it.selected in ModelCatalog.available(ram)) it.selected else ModelCatalog.recommended(ram),
                downloaded = done,
            )
        }
    }

    fun select(option: ModelOption) = _ui.update { it.copy(selected = option, error = null) }
    fun dismissError() = _ui.update { it.copy(error = null) }

    /** Downloads the selected model (user already consented in the UI), resuming if partial. */
    fun downloadSelected() {
        val opt = _ui.value.selected
        val target = File(modelsDir, opt.fileName)
        if (target.isFile && target.length() == opt.sizeBytes) { loadModel(target); return }
        if (_ui.value.freeStorageBytes < opt.sizeBytes + 200L * 1024 * 1024 - (File(modelsDir, opt.fileName + ".part").length())) {
            _ui.update { it.copy(error = "Not enough free storage. Need about ${opt.sizeBytes.toNiceSize()} free.") }
            return
        }
        workJob = viewModelScope.launch {
            val part = File(modelsDir, opt.fileName + ".part")
            try {
                withContext(Dispatchers.IO) {
                    var url = URL(opt.url)
                    var conn: HttpURLConnection
                    var redirects = 0
                    while (true) {
                        conn = url.openConnection() as HttpURLConnection
                        conn.instanceFollowRedirects = false
                        conn.connectTimeout = 20_000
                        conn.readTimeout = 60_000
                        conn.setRequestProperty("User-Agent", "AuroraCompanion-Android/0.1")
                        if (part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
                        val code = conn.responseCode
                        if (code in 300..399 && redirects < 8) {
                            url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); redirects++; continue
                        }
                        break
                    }
                    val code = conn.responseCode
                    val resume = code == 206
                    if (code != 200 && code != 206) throw IllegalStateException("Server returned HTTP $code")
                    if (!resume && part.exists()) part.delete()
                    RandomAccessFile(part, "rw").use { raf ->
                        raf.seek(if (resume) part.length() else 0)
                        var have = raf.filePointer
                        val buf = ByteArray(256 * 1024)
                        var lastUi = 0L
                        conn.inputStream.use { input ->
                            while (isActive) {
                                val n = input.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                have += n
                                val now = System.currentTimeMillis()
                                if (now - lastUi > 250) {
                                    lastUi = now
                                    _ui.update {
                                        it.copy(screen = Screen.Busy(
                                            "Downloading ${opt.title}",
                                            have.toFloat() / opt.sizeBytes,
                                            "${have.toNiceSize()} of ${opt.sizeBytes.toNiceSize()}  -  keep the app open, Wi-Fi recommended"))
                                    }
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    if (part.length() != opt.sizeBytes) throw IllegalStateException("Download incomplete (${part.length()} of ${opt.sizeBytes} bytes). Tap download again to resume.")
                    if (!part.renameTo(target)) throw IllegalStateException("Could not finalize the model file")
                }
                refreshDevice()
                loadModel(target)
            } catch (e: Exception) {
                Log.e(TAG, "download failed", e)
                _ui.update { it.copy(screen = Screen.Setup, error = "Download stopped: ${e.message}") }
            }
        }
        _ui.update { it.copy(screen = Screen.Busy("Starting download...", 0f)) }
    }

    fun cancelWork() {
        workJob?.cancel()
        _ui.update { it.copy(screen = Screen.Setup) }
    }

    /** Copies a user-picked .gguf (e.g. from Downloads) into private storage, then loads it. */
    fun importGguf(uri: Uri) {
        val ctx = getApplication<Application>()
        workJob = viewModelScope.launch {
            try {
                var name = "imported-${System.currentTimeMillis()}.gguf"
                var size = -1L
                ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val si = c.getColumnIndex(OpenableColumns.SIZE)
                        if (ni >= 0) c.getString(ni)?.let { name = it }
                        if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                    }
                }
                if (!name.endsWith(".gguf", ignoreCase = true)) throw IllegalArgumentException("Please pick a .gguf model file")
                val target = File(modelsDir, name)
                withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        FileOutputStream(target).use { out ->
                            val buf = ByteArray(256 * 1024)
                            var have = 0L
                            var lastUi = 0L
                            while (isActive) {
                                val n = input.read(buf); if (n < 0) break
                                out.write(buf, 0, n); have += n
                                val now = System.currentTimeMillis()
                                if (now - lastUi > 250) {
                                    lastUi = now
                                    _ui.update { it.copy(screen = Screen.Busy("Importing $name",
                                        if (size > 0) have.toFloat() / size else null, have.toNiceSize())) }
                                }
                            }
                        }
                    }
                }
                refreshDevice()
                loadModel(target)
            } catch (e: Exception) {
                Log.e(TAG, "import failed", e)
                _ui.update { it.copy(screen = Screen.Setup, error = "Import failed: ${e.message}") }
            }
        }
    }

    /**
     * Loads the model with the companion's persona (+ memory). With [resume], the saved messages are put back on
     * screen and into the model's context instead of a fresh greeting (Continue from History).
     */
    private fun loadModel(file: File, resume: List<ChatMessage>? = null) {
        workJob = viewModelScope.launch {
            val comp = _ui.value.companion
            _ui.update { it.copy(screen = Screen.Busy("Waking up ${comp.name}...", null, "Loading ${file.name} into memory")) }
            try {
                llm.withLock {
                    chatValid = false
                    val st = withTimeoutOrNull(30_000) {
                        engine.state.first { it is InferenceEngine.State.Initialized || it.isModelReadyish() || it is InferenceEngine.State.Error }
                    } ?: throw IllegalStateException("The AI engine is busy (${engine.state.value.javaClass.simpleName})")
                    if (st !is InferenceEngine.State.Initialized) engine.cleanUp()
                    engine.loadModel(file.path)
                    val sys = fullSystemPrompt(comp)
                    if (resume == null) engine.setSystemPrompt(sys)
                    else engine.loadConversation(sys, contextTurns(resume, REBUILD_TOKEN_BUDGET))
                    chatValid = true
                }
                prefs.edit().putString("last_model_file", file.name).apply()
                if (resume == null) {
                    saveConversation()
                    _ui.update {
                        it.copy(screen = Screen.Chat, loadedModelName = file.name,
                            messages = listOf(ChatMessage(text = comp.greeting, isUser = false)))
                    }
                    memFrom = 0
                    beginConversation(HistoryStore.MODE_CHAT, 0)
                } else {
                    _ui.update { it.copy(screen = Screen.Chat, loadedModelName = file.name, messages = resume) }
                    memFrom = resume.size
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "load failed", e)
                runCatching { engine.cleanUp() }
                _ui.update { it.copy(screen = Screen.Setup, error = "Could not load the model: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /** The persona plus the compact running-memory block (about 300 tokens at most). */
    private fun fullSystemPrompt(comp: CompanionProfile): String {
        val base = comp.systemPrompt()
        if (!_ui.value.memoryOn) return base
        val notes = runCatching { MemoryStore.load(getApplication(), comp.id) }.getOrDefault(emptyList())
        return base + MemoryStore.promptBlock(notes)
    }

    /** The newest messages that fit in [budget] tokens, oldest first, for rebuilding the context. */
    private suspend fun contextTurns(msgs: List<ChatMessage>, budget: Int): List<Pair<Boolean, String>> {
        val usable = msgs.filter { !it.aside && it.text.isNotBlank() && it.text != NO_REPLY }
        val picked = ArrayList<Pair<Boolean, String>>()
        var used = 0
        for (m in usable.asReversed()) {
            val t = m.text.take(MAX_PROMPT_CHARS)
            val cost = engine.countTokens(t) + 8
            if (used + cost > budget) break
            picked += m.isUser to t
            used += cost
        }
        picked.reverse()
        // Start on a user turn when possible (a leading greeting is fine, a dangling reply fragment isn't useful).
        while (picked.size > 1 && !picked.first().first && !picked[1].first) picked.removeAt(0)
        return picked
    }

    /** Rebuilds the model's context from [msgs] (the chat on screen, without the message being answered). */
    private suspend fun rebuildChat(msgs: List<ChatMessage>) {
        chatValid = false
        engine.loadConversation(fullSystemPrompt(_ui.value.companion), contextTurns(msgs, REBUILD_TOKEN_BUDGET))
        chatValid = true
    }

    /** Last resort: reload the model file (keeps the chat on screen). */
    private suspend fun reloadModelKeepingChat(msgs: List<ChatMessage>) {
        val name = _ui.value.loadedModelName ?: throw IllegalStateException("No model loaded")
        chatValid = false
        runCatching { engine.cleanUp() }
        engine.loadModel(File(modelsDir, name).path)
        engine.loadConversation(fullSystemPrompt(_ui.value.companion), contextTurns(msgs, REBUILD_TOKEN_BUDGET))
        chatValid = true
    }

    private suspend fun awaitEngineReady(): Boolean = withTimeoutOrNull(10_000) {
        engine.state.first { it is InferenceEngine.State.ModelReady || it is InferenceEngine.State.Error }
    } != null

    private fun InferenceEngine.State.isModelReadyish() =
        this is InferenceEngine.State.ModelReady

    fun send(text: String) {
        val msg = text.trim()
        if (msg.isEmpty() || _ui.value.generating || _ui.value.screen != Screen.Chat) return
        // You come first: an unfinished memory pass is paused and retried later.
        memJob?.let { if (it.isActive) it.cancel() }
        val userMsg = ChatMessage(text = msg, isUser = true)
        val reply = ChatMessage(text = "", isUser = false)
        _ui.update { it.copy(messages = it.messages + userMsg + reply, generating = true) }
        // Reminders by chat / voice (desktop: try_complete / try_parse_reminder_command / user_asks_schedule).
        // The llama.cpp system prompt is fixed after load, so the app's note rides along with this one user turn.
        val note = runCatching { reminderChat(msg) }.getOrNull()
        val useBooks = note == null && _ui.value.bookKnowledge && _ui.value.libraryTask == null && _ui.value.library.indexedBooks > 0
        genJob = viewModelScope.launch {
            val sb = StringBuilder()
            val splitter = SentenceSplitter()
            val speakEpoch = voice.currentEpoch()
            try {
                // Book library (0.8): up to 3 short passages (~1,200 chars) ride along in the same hidden note.
                val books = if (!useBooks) null else withContext(Dispatchers.IO) {
                    runCatching { Library.retrieveForChat(getApplication(), msg, BOOK_NOTE_BUDGET) }
                        .onFailure { Log.e(TAG, "book retrieval failed", it) }.getOrNull()
                }
                if (books != null) _ui.update { it.copy(notice = "\uD83D\uDCDA From the library: " + books.second.joinToString(", ")) }
                val hidden = note ?: books?.first
                val prompt = (if (hidden != null) "(App note, not said by the user: $hidden)\n\n$msg" else msg).take(MAX_PROMPT_CHARS)
                llm.withLock { runTurn(prompt, userMsg.id, reply.id, sb, splitter, speakEpoch) }
            } finally {
                if ((_ui.value.autoSpeak || _ui.value.inCall) && speakEpoch == voice.currentEpoch()) splitter.flush()?.let { enqueueSpeech(it, speakEpoch) }
                if (sb.isEmpty()) _ui.update { s -> s.copy(messages = s.messages.map { if (it.id == reply.id && it.text.isEmpty()) it.copy(text = NO_REPLY, aside = true) else it }) }
                _ui.update { it.copy(generating = false, preparing = null) }
                saveConversation()
            }
        }
    }

    /** Messages that are already in (or belong in) the model's context: everything before [userMsgId]. */
    private fun historyBefore(userMsgId: String): List<ChatMessage> {
        val m = _ui.value.messages
        val i = m.indexOfFirst { it.id == userMsgId }
        return if (i < 0) m else m.subList(0, i).toList()
    }

    /**
     * One reply, robustly (0.8.3):
     * - the prompt + reply always fit the 4,096-token context: old whole turns are dropped first;
     * - if llama.cpp fails, the context is rebuilt (or the model reloaded) and the turn retried once;
     * - if it still fails, a friendly message replaces the raw error.
     */
    private suspend fun runTurn(prompt: String, userMsgId: String, replyId: String, sb: StringBuilder, splitter: SentenceSplitter, speakEpoch: Int) {
        var attempt = 0
        while (true) {
            try {
                if (!awaitEngineReady()) throw IllegalStateException("engine busy: ${engine.state.value.javaClass.simpleName}")
                if (!chatValid) {
                    _ui.update { it.copy(preparing = "Getting ready...") }
                    rebuildChat(historyBefore(userMsgId))
                    _ui.update { it.copy(preparing = null) }
                }
                makeRoom(prompt)
                engine.sendUserPrompt(prompt, MAX_REPLY_TOKENS).collect { token ->
                    sb.append(token)
                    if (_ui.value.autoSpeak || _ui.value.inCall) splitter.push(token).forEach { enqueueSpeech(it, speakEpoch) }
                    val t = sb.toString()
                    _ui.update { s -> s.copy(messages = s.messages.map { if (it.id == replyId) it.copy(text = t) else it }) }
                }
                return
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "reply failed (attempt ${attempt + 1}): ${e.javaClass.simpleName}: ${e.message}", e)
                chatValid = false
                val tooLong = e is InferenceEngine.ContextFullException
                if (attempt == 0 && sb.isEmpty() && !tooLong) {
                    attempt++
                    _ui.update { it.copy(preparing = "One moment...") }
                    try {
                        try { rebuildChat(historyBefore(userMsgId)) }
                        catch (r: kotlinx.coroutines.CancellationException) { throw r }
                        catch (r: Throwable) {
                            Log.e(TAG, "context rebuild failed, reloading the model", r)
                            reloadModelKeepingChat(historyBefore(userMsgId))
                        }
                        continue
                    } catch (r: kotlinx.coroutines.CancellationException) {
                        throw r
                    } catch (r: Throwable) {
                        Log.e(TAG, "recovery failed", r)
                    } finally {
                        _ui.update { it.copy(preparing = null) }
                    }
                }
                val friendly = if (tooLong) "That one's a bit too long for me to take in at once. Could you say it in a shorter way?"
                    else "Sorry, I lost my train of thought for a second. Could you say that again?"
                if (sb.isEmpty()) {
                    _ui.update { s -> s.copy(messages = s.messages.map { if (it.id == replyId) it.copy(text = friendly, aside = true) else it }) }
                    if (_ui.value.autoSpeak || _ui.value.inCall) enqueueSpeech(friendly, speakEpoch)
                    sb.append(friendly)
                }
                return
            }
        }
    }

    /** Drops the oldest whole turns if the prompt plus a full reply wouldn't fit in the context. */
    private suspend fun makeRoom(prompt: String) {
        val need = engine.countTokens(prompt) + 24
        val limit = InferenceEngine.CONTEXT_SIZE - MAX_REPLY_TOKENS - 64
        val used = engine.contextUsed()
        if (used + need > limit) {
            val freed = engine.trimOldestTurns(used + need - limit + TRIM_SLACK_TOKENS)
            Log.i(TAG, "context: used $used + prompt $need > $limit, dropped $freed tokens of old turns")
        }
        if (engine.contextUsed() + need > InferenceEngine.CONTEXT_SIZE - 64) throw InferenceEngine.ContextFullException()
    }

    fun stop() { genJob?.cancel() }

    private fun enqueueSpeech(text: String, epoch: Int) {
        if (!voice.ttsReady) return
        val clean = VoiceEngine.cleanForSpeech(text)
        if (clean.isEmpty()) return
        pendingSpeech.incrementAndGet()
        _ui.update { it.copy(speaking = true) }
        speechQueue.trySend(Utterance(clean, epoch))
    }

    /** Speaker button: speak a single message on demand. */
    fun speakMessage(text: String) {
        stopSpeaking()
        val epoch = voice.currentEpoch()
        val sp = SentenceSplitter()
        sp.push(text + " ").forEach { enqueueSpeech(it, epoch) }
        sp.flush()?.let { enqueueSpeech(it, epoch) }
    }

    fun testVoice() {
        if (!voice.ttsReady) { _ui.update { it.copy(error = "The voice is still loading. Try again in a few seconds.") }; return }
        speakMessage("Hi Robert! This is ${_ui.value.companion.name} testing my voice. If you can hear me, the speaker is working.")
    }

    fun stopSpeaking() {
        voice.stopSpeaking()
        _ui.update { it.copy(speaking = false) }
    }

    fun toggleAutoSpeak() {
        val v = !_ui.value.autoSpeak
        prefs.edit().putBoolean("auto_speak", v).apply()
        if (!v) stopSpeaking()
        _ui.update { it.copy(autoSpeak = v) }
    }

    // ---------- call mode (0.4) ----------
    private val call = CallEngine(app)

    fun setBargeIn(on: Boolean) {
        prefs.edit().putBoolean("barge_in", on).apply()
        _ui.update { it.copy(bargeIn = on) }
        call.settings = CallEngine.Settings(on, _ui.value.bargeSensitivity)
    }

    fun setBargeSensitivity(sens: String) {
        prefs.edit().putString("barge_sens", sens).apply()
        _ui.update { it.copy(bargeSensitivity = sens) }
        call.settings = CallEngine.Settings(_ui.value.bargeIn, sens)
    }

    /** Start a phone-call style conversation (RECORD_AUDIO already granted by the UI). */
    fun startCall() {
        if (_ui.value.inCall) return
        if (!voice.asrReady || !voice.ttsReady) { _ui.update { it.copy(error = "Voice is still loading. Try again in a few seconds.") }; return }
        stopSpeaking(); voice.stopRecording()
        call.settings = CallEngine.Settings(_ui.value.bargeIn, _ui.value.bargeSensitivity)
        call.muted = false
        try {
            val session = call.start(object : CallEngine.Listener {
                override fun isAuroraBusy() = _ui.value.speaking || _ui.value.generating || _ui.value.transcribing
                override fun isAuroraSpeaking() = _ui.value.speaking
                override fun onBargeIn() {
                    voice.stopSpeaking()
                    _ui.update { it.copy(speaking = false) }
                    genJob?.cancel()
                }
                override fun onSpeechStart() = _ui.update { it.copy(callHearing = true) }
                override fun onLevel(level: Float) = _ui.update { it.copy(callLevel = level) }
                override fun onError(msg: String) {
                    _ui.update { it.copy(error = "Call stopped: $msg") }
                    viewModelScope.launch { endCall() }
                }
                override fun onUtterance(samples: FloatArray) {
                    _ui.update { it.copy(callHearing = false, transcribing = true) }
                    viewModelScope.launch {
                        val text = withContext(Dispatchers.Default) { voice.transcribe(samples) }
                        _ui.update { it.copy(transcribing = false) }
                        if (text.isBlank() || !_ui.value.inCall) return@launch
                        genJob?.let { if (it.isActive) { it.cancel(); it.join() } }
                        send(text)
                    }
                }
            })
            voice.setCallAudio(session)
            // History: the typed part so far is closed and the call gets its own entry (the chat log on screen stays one list).
            saveConversation()
            beginConversation(HistoryStore.MODE_CALL, _ui.value.messages.size)
            _ui.update { it.copy(inCall = true, callMuted = false, callHearing = false, callLevel = 0f) }
            if (_ui.value.messages.size <= 1) speakMessage(_ui.value.companion.callGreeting)
        } catch (e: Throwable) {
            Log.e(TAG, "startCall failed", e)
            call.stop(); voice.setCallAudio(0)
            _ui.update { it.copy(error = "Couldn't start the call: ${e.message}") }
        }
    }

    fun toggleMute() {
        val m = !_ui.value.callMuted
        call.muted = m
        _ui.update { it.copy(callMuted = m, callHearing = false) }
    }

    fun endCall() {
        if (!_ui.value.inCall && !call.isRunning) return
        stopSpeaking()
        call.stop()
        voice.setCallAudio(0)
        _ui.update { it.copy(inCall = false, callMuted = false, callHearing = false, callLevel = 0f) }
        saveConversation()
        beginConversation(HistoryStore.MODE_CHAT, _ui.value.messages.size)
        queueMemory(capturePendingMemory())   // after each call, the companion writes its memory notes
    }

    // ---------- conversation history (0.8.2) ----------
    private var convId: String = HistoryStore.newId()
    private var convFrom = 0
    private var convMode = HistoryStore.MODE_CHAT
    private var convStart = System.currentTimeMillis()
    private var convCompanion: CompanionProfile? = null   // who it was with (the selection may change before it is saved)

    private fun beginConversation(mode: String, fromIndex: Int) {
        convId = HistoryStore.newId(); convMode = mode; convFrom = fromIndex; convStart = System.currentTimeMillis()
        convCompanion = _ui.value.companion
    }

    /** Writes the current conversation (messages since it began) if the user said anything in it. */
    private fun saveConversation(sync: Boolean = false) {
        val st = _ui.value
        val msgs = st.messages.drop(convFrom).filter { it.text.isNotBlank() && !(st.generating && it === st.messages.lastOrNull()) }
        if (msgs.none { it.isUser }) return
        val comp = convCompanion ?: st.companion
        val start = minOf(convStart, msgs.first().time)
        val c = Conversation(convId, comp.id, comp.name, convMode, start, maxOf(msgs.last().time, start),
            msgs.map { HistMsg(it.isUser, it.text, it.time) })
        val ctx = getApplication<Application>()
        val work = { runCatching { HistoryStore.save(ctx, c) }.onFailure { Log.e(TAG, "history save failed", it) }; Unit }
        if (sync) work() else viewModelScope.launch(Dispatchers.IO) { work() }
    }

    // ---------- 0.8.3: Continue a saved conversation ----------
    fun continueConversation(c: Conversation) {
        val st = _ui.value
        val modelName = st.loadedModelName
        if (modelName == null) { _ui.update { it.copy(error = "Load a chat model first, then continue the conversation.") }; return }
        if (st.inCall) endCall()
        stop()
        stopSpeaking()
        val pending = capturePendingMemory()
        saveConversation()
        val msgs = c.messages.filter { it.text.isNotBlank() }.map { ChatMessage(text = it.text, isUser = it.user, time = it.time) }
        val switching = c.companionId != st.companionId && (st.characters.any { it.id == c.companionId } || Companions.byId(c.companionId).id == c.companionId)
        if (switching) applyCompanion(c.companionId)
        // Keep appending to the same saved conversation.
        convId = c.id; convMode = c.mode; convStart = c.start; convFrom = 0
        convCompanion = _ui.value.companion
        memFrom = msgs.size
        if (switching) {
            loadModel(File(modelsDir, modelName), resume = msgs)
        } else {
            _ui.update { it.copy(messages = msgs, preparing = "Picking up where you left off...") }
            workJob = viewModelScope.launch {
                try {
                    llm.withLock {
                        if (!awaitEngineReady()) throw IllegalStateException("engine busy")
                        try { rebuildChat(msgs) } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (e: Throwable) { Log.e(TAG, "continue: rebuild failed, reloading", e); reloadModelKeepingChat(msgs) }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) { throw e
                } catch (e: Throwable) {
                    Log.e(TAG, "continue failed", e)
                    chatValid = false
                } finally { _ui.update { it.copy(preparing = null) } }
            }
        }
        _ui.update { it.copy(notice = "Picked up your conversation with ${c.companionName}") }
        queueMemory(pending)
    }

    // ---------- 0.8.3: running memory ----------
    /** Takes the messages not yet given to a memory pass (needs at least 2 things you said). */
    private fun capturePendingMemory(): PendingMemory? {
        val st = _ui.value
        val msgs = st.messages.drop(memFrom).filter { !it.aside && it.text.isNotBlank() && it.text != NO_REPLY }
        memFrom = st.messages.size
        if (!st.memoryOn) return null
        val comp = convCompanion ?: st.companion
        var lines = msgs.map { it.isUser to it.text }
        val prev = deferredMemory
        if (prev != null && prev.companionId == comp.id) { lines = prev.lines + lines; deferredMemory = null }
        if (lines.count { it.first } < 2) {
            if (lines.isNotEmpty()) deferredMemory = PendingMemory(comp.id, comp.name, lines.takeLast(MEMORY_MAX_LINES))
            return null
        }
        return PendingMemory(comp.id, comp.name, lines.takeLast(MEMORY_MAX_LINES))
    }

    private fun queueMemory(p: PendingMemory?) {
        if (p == null) return
        val prevJob = memJob
        val load = workJob
        memJob = viewModelScope.launch {
            load?.join()
            prevJob?.join()
            writeMemoryNotes(p)
        }
    }

    /** The model reads the chat and writes short notes; the chat context is saved and restored around it. */
    private suspend fun writeMemoryNotes(p: PendingMemory) {
        if (_ui.value.loadedModelName == null) { deferredMemory = p; return }
        llm.withLock {
            _ui.update { it.copy(memoryBusy = true) }
            var saved = false
            try {
                if (!awaitEngineReady()) { deferredMemory = p; return@withLock }
                saved = chatValid && engine.saveChatState()
                chatValid = false
                val known = withContext(Dispatchers.IO) { MemoryStore.load(getApplication(), p.companionId) }
                engine.loadConversation(MemoryStore.EXTRACT_SYSTEM, emptyList())
                var lines = p.lines
                var prompt = MemoryStore.extractPrompt(p.companionName, lines, known)
                while (lines.size > 2 && engine.countTokens(prompt) > MEMORY_PROMPT_TOKENS) {
                    lines = lines.drop(2)
                    prompt = MemoryStore.extractPrompt(p.companionName, lines, known)
                }
                val out = StringBuilder()
                engine.sendUserPrompt(prompt, MEMORY_REPLY_TOKENS).collect { out.append(it) }
                val (facts, summary) = MemoryStore.parse(out.toString())
                Log.i(TAG, "memory pass for ${p.companionId}: ${facts.size} facts, summary=${summary != null}")
                val added = withContext(Dispatchers.IO) { MemoryStore.merge(getApplication(), p.companionId, facts, summary) }
                if (p.companionId == _ui.value.companionId) refreshMemoryNotes()
                if (added > 0) Log.i(TAG, "memory: $added new notes")
            } catch (e: kotlinx.coroutines.CancellationException) {
                deferredMemory = p
                throw e
            } catch (e: Throwable) {
                Log.e(TAG, "memory pass failed", e)
            } finally {
                withContext(NonCancellable) {
                    if (saved && runCatching { engine.restoreChatState() }.getOrDefault(false)) chatValid = true
                    _ui.update { it.copy(memoryBusy = false) }
                }
            }
        }
    }

    /** Called when the app goes to the background: a good quiet moment for a memory pass. */
    fun onAppBackground() {
        val st = _ui.value
        if (st.screen != Screen.Chat || st.generating || st.inCall || st.loadedModelName == null) return
        queueMemory(capturePendingMemory())
    }

    fun refreshMemoryNotes() {
        val id = _ui.value.companionId
        viewModelScope.launch(Dispatchers.IO) {
            val notes = MemoryStore.load(getApplication(), id)
            _ui.update { it.copy(memoryNotes = notes) }
        }
    }

    fun setMemoryOn(on: Boolean) {
        prefs.edit().putBoolean("memory_on", on).apply()
        _ui.update { it.copy(memoryOn = on) }
        if (!on) deferredMemory = null
    }

    fun addMemoryNote(text: String) {
        val t = text.trim().take(300)
        if (t.isEmpty()) return
        val id = _ui.value.companionId
        viewModelScope.launch(Dispatchers.IO) {
            val notes = MemoryStore.update(getApplication(), id) { it.add(MemoryNote(text = t, kind = KIND_MANUAL)) }
            _ui.update { it.copy(memoryNotes = notes) }
        }
    }

    fun editMemoryNote(noteId: String, text: String) {
        val t = text.trim().take(300)
        val id = _ui.value.companionId
        viewModelScope.launch(Dispatchers.IO) {
            val notes = MemoryStore.update(getApplication(), id) { list ->
                val i = list.indexOfFirst { it.id == noteId }
                if (i >= 0) { if (t.isEmpty()) list.removeAt(i) else list[i] = list[i].copy(text = t, updated = System.currentTimeMillis()) }
            }
            _ui.update { it.copy(memoryNotes = notes) }
        }
    }

    fun deleteMemoryNote(noteId: String) {
        val id = _ui.value.companionId
        viewModelScope.launch(Dispatchers.IO) {
            val notes = MemoryStore.update(getApplication(), id) { list -> list.removeAll { it.id == noteId } }
            _ui.update { it.copy(memoryNotes = notes) }
        }
    }

    fun wipeMemory() {
        val id = _ui.value.companionId
        deferredMemory = null
        viewModelScope.launch(Dispatchers.IO) {
            MemoryStore.wipe(getApplication(), id)
            _ui.update { it.copy(memoryNotes = emptyList(), notice = "Memory cleared") }
        }
    }

    fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = runCatching { HistoryStore.loadAll(getApplication()) }.getOrDefault(emptyList())
            _ui.update { it.copy(history = list) }
        }
    }

    fun deleteConversation(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            HistoryStore.delete(getApplication(), id)
            if (id == convId) beginConversation(convMode, _ui.value.messages.size)
            loadHistory()
        }
    }

    fun exportConversation(c: Conversation, uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(HistoryStore.toTxt(c).toByteArray()) } }
                _ui.update { it.copy(notice = "Conversation saved") }
            } catch (e: Throwable) { _ui.update { it.copy(error = "Export failed: ${e.message}") } }
        }
    }

    /** Writes the transcript to the app cache for the share sheet (served by FileProvider). */
    fun conversationShareFile(c: Conversation): File? = try {
        val dir = File(getApplication<Application>().cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        File(dir, HistoryStore.fileName(c)).apply { writeText(HistoryStore.toTxt(c)) }
    } catch (e: Throwable) { _ui.update { it.copy(error = "Couldn't prepare the file to share: ${e.message}") }; null }

    private fun shortName(v: VoiceOption) = if (v.builtIn) "Robert" else "LibriTTS"
    private fun installedVoiceIds() = VoiceCatalog.all.filter { VoiceCatalog.isInstalled(getApplication(), it) }.map { it.id }.toSet()

    fun dismissNotice() = _ui.update { it.copy(notice = null) }

    fun setMicMode(mode: String) {
        prefs.edit().putString("mic_mode", mode).apply()
        _ui.update { it.copy(micMode = mode) }
    }

    /** Hands-free: one tap starts, stops by itself when you stop talking (Silero VAD), then sends. */
    fun startAutoListening() {
        if (!voice.asrReady || !voice.vadReady || _ui.value.listening || _ui.value.transcribing || _ui.value.generating) return
        stopSpeaking()   // never record Aurora's own voice
        _ui.update { it.copy(listening = true, listenLatched = true, micLevel = 0f, heardSpeech = false) }
        recordJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                var lastUi = 0L
                val (result, samples) = voice.recordAuto(onLevel = { level, heard ->
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 90) { lastUi = now; _ui.update { it.copy(micLevel = level, heardSpeech = heard) } }
                })
                _ui.update { it.copy(listening = false, listenLatched = false, micLevel = 0f, heardSpeech = false,
                    transcribing = result == VoiceEngine.AutoResult.SPEECH) }
                when (result) {
                    VoiceEngine.AutoResult.SPEECH -> {
                        val text = voice.transcribe(samples)
                        _ui.update { it.copy(transcribing = false) }
                        withContext(Dispatchers.Main) {
                            if (text.isNotBlank()) send(text) else _ui.update { it.copy(notice = "I couldn't make out the words. Try again a little closer to the mic.") }
                        }
                    }
                    VoiceEngine.AutoResult.NO_SPEECH -> _ui.update { it.copy(notice = "I didn't hear anything, so I stopped listening.") }
                    VoiceEngine.AutoResult.CANCELLED -> {}
                }
            } catch (e: Throwable) {
                Log.e(TAG, "auto listening failed", e)
                _ui.update { it.copy(listening = false, listenLatched = false, transcribing = false, error = "Microphone problem: ${e.message}") }
            }
        }
    }

    // ---------- voices ----------
    fun selectVoice(id: String) {
        val v = VoiceCatalog.byId(id)
        if (!VoiceCatalog.isInstalled(getApplication(), v) || _ui.value.switchingVoice) return
        stopSpeaking()
        _ui.update { it.copy(switchingVoice = true, voiceStatus = "Loading ${shortName(v)} voice...") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                voice.initTts(v)
                prefs.edit().putString("voice_id", v.id).putString("voice_for_${_ui.value.companionId}", v.id).apply()
                _ui.update { it.copy(voiceId = v.id, numSpeakers = voice.numSpeakers(), voiceReady = true, voiceStatus = "${shortName(v)} voice ready") }
            } catch (e: Throwable) {
                Log.e(TAG, "switch voice failed", e)
                _ui.update { it.copy(error = "Couldn't load that voice: ${e.message}", voiceStatus = "${shortName(VoiceCatalog.byId(voice.voiceId))} voice ready") }
            } finally {
                _ui.update { it.copy(switchingVoice = false) }
            }
        }
    }

    // ---------- companions (0.5) ----------
    fun setAnimateAvatar(on: Boolean) {
        prefs.edit().putBoolean("animate_avatar", on).apply()
        _ui.update { it.copy(animateAvatar = on) }
    }

    /**
     * Pick a companion. The persona is the model's system prompt, which can only be set right after loading,
     * so switching reloads the model and starts a fresh chat. Also applies that companion's saved voice.
     */
    fun selectCompanion(id: String) {
        if (!applyCompanion(id)) return
        val pending = pendingAutoLoad
        if (pending != null) { pendingAutoLoad = null; loadModel(pending); return }
        if (_ui.value.screen == Screen.Chat) {
            stop()
            val memPending = capturePendingMemory()
            _ui.value.loadedModelName?.let { loadModel(File(modelsDir, it)) }
            queueMemory(memPending)
        }
    }

    /** Makes [id] the current companion (prefs + its voice). Returns true if it changed. Doesn't reload the model. */
    private fun applyCompanion(id: String): Boolean {
        val c = Companions.byId(id)
        val changed = c.id != _ui.value.companionId || !_ui.value.companionChosen
        prefs.edit().putString("companion_id", c.id).apply()
        _ui.update { it.copy(companionId = c.id, companionChosen = true, memoryNotes = emptyList()) }
        if (!changed) return false
        if (_ui.value.inCall) endCall()
        stopSpeaking()
        // Per-companion voice (Robert's voice unless changed in Settings while this companion is selected).
        val wantVoice = VoiceCatalog.byId(voicePrefFor(c.id))
        val sp = speakerPrefFor(c.id)
        voice.speakerId = sp
        _ui.update { it.copy(speakerId = sp) }
        if (wantVoice.id != _ui.value.voiceId && VoiceCatalog.isInstalled(getApplication(), wantVoice) && _ui.value.voiceReady) selectVoice(wantVoice.id)
        return true
    }

    // ---------- custom characters (0.6) ----------
    private fun refreshCharacters() {
        val chars = CharacterStore.loadAll(getApplication())
        Companions.custom = chars
        _ui.update { it.copy(characters = chars) }
    }

    fun newCharacter() = _ui.update { it.copy(editor = CharacterEdit(id = null)) }

    fun editCharacter(id: String) {
        val c = _ui.value.characters.firstOrNull { it.id == id } ?: return
        _ui.update { it.copy(editor = editFrom(c, keepId = true)) }
    }

    /** Duplicate: built-ins can be copied too, as a starting point for your own. */
    fun duplicateCharacter(id: String) {
        val c = _ui.value.characters.firstOrNull { it.id == id } ?: Companions.byId(id)
        val e = editFrom(c, keepId = false)
        _ui.update { it.copy(editor = e.copy(name = (c.name + " (copy)").take(40), source = if (c.custom) c.source else "copy of ${c.name}")) }
    }

    private fun editFrom(c: CompanionProfile, keepId: Boolean) = CharacterEdit(
        id = if (keepId) c.id else null, name = c.name, look = c.look, personality = c.personality, scenario = c.scenario,
        greeting = c.greeting, facts = c.facts.joinToString("\n"),
        voiceId = voicePrefFor(c.id) ?: VoiceCatalog.ROBERT.id, speakerId = speakerPrefFor(c.id),
        adultFlagged = c.adultFlagged, source = c.source, createdAt = if (keepId) c.createdAt else 0,
    )

    fun closeEditor() = _ui.update { it.copy(editor = null) }

    /** Reads a picked file (SAF) and opens it in the editor for review. Nothing is saved until you tap Save. */
    fun importCharacter(uri: Uri) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                val (name, bytes) = withContext(Dispatchers.IO) {
                    var n = "imported"
                    var size = -1L
                    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if (ni >= 0) c.getString(ni)?.let { n = it }
                            val si = c.getColumnIndex(OpenableColumns.SIZE); if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
                        }
                    }
                    if (size > CharacterStore.MAX_IMPORT_BYTES) throw IllegalArgumentException("That file is too big for a character (over 8 MB).")
                    val b = ctx.contentResolver.openInputStream(uri)!!.use { input ->
                        val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(64 * 1024); var total = 0
                        while (true) { val r = input.read(buf); if (r < 0) break; total += r
                            if (total > CharacterStore.MAX_IMPORT_BYTES) throw IllegalArgumentException("That file is too big for a character (over 8 MB).")
                            out.write(buf, 0, r) }
                        out.toByteArray()
                    }
                    n to b
                }
                val d = withContext(Dispatchers.Default) { CharacterStore.parse(bytes, name) }
                val notes = d.notes + if (d.adultFlagged) listOf("This looks like it was written for adult content. Adult content needs the upcoming 18+ mode, so for now the character is kept family-friendly by a safety rule.") else emptyList()
                _ui.update { it.copy(editor = CharacterEdit(id = null, name = d.name, look = d.look, personality = d.personality,
                    scenario = d.scenario, greeting = d.greeting, facts = d.facts.joinToString("\n"), adultFlagged = d.adultFlagged,
                    source = d.source, notes = listOf("Imported from: ${d.source} ($name)") + notes)) }
            } catch (e: Throwable) {
                Log.e(TAG, "import character failed", e)
                _ui.update { it.copy(error = "Couldn't import that file: ${e.message ?: e.javaClass.simpleName}\n\nSupported: Aurora companion pack (.zip), Aurora profile (.json), character cards (TavernAI / SillyTavern .json or .png), or plain personality text (.txt / .md).") }
            }
        }
    }

    fun saveCharacter(e: CharacterEdit) {
        val ctx = getApplication<Application>()
        val id = e.id ?: CharacterStore.newId()
        val old = _ui.value.characters.firstOrNull { it.id == id }
        val c = Companions.makeCustom(
            id = id, name = e.name.take(40), look = e.look, personality = e.personality.trim(), greeting = e.greeting,
            scenario = e.scenario.trim(), facts = e.facts.lines().map { it.trim().removePrefix("- ").trim() }.filter { it.length > 2 }.take(CompanionProfile.MAX_FACTS),
            voiceId = e.voiceId, speakerId = e.speakerId, adultFlagged = e.adultFlagged, source = e.source,
            createdAt = if (e.createdAt > 0) e.createdAt else System.currentTimeMillis(),
        )
        try { CharacterStore.save(ctx, c) } catch (t: Throwable) {
            _ui.update { it.copy(error = "Couldn't save the character: ${t.message}") }; return
        }
        prefs.edit().putString("voice_for_$id", e.voiceId).putInt("speaker_for_$id", e.speakerId).apply()
        refreshCharacters()
        _ui.update { it.copy(editor = null, lastSavedCharacter = id, notice = "Saved ${c.name}") }
        if (_ui.value.companionId == id && _ui.value.companionChosen) {
            // Apply the voice now, and reload the AI if the personality changed.
            voice.speakerId = e.speakerId; _ui.update { it.copy(speakerId = e.speakerId) }
            if (e.voiceId != _ui.value.voiceId && VoiceCatalog.isInstalled(ctx, VoiceCatalog.byId(e.voiceId))) selectVoice(e.voiceId)
            val changed = old == null || old.systemPrompt() != c.systemPrompt() || old.greeting != c.greeting
            if (changed && _ui.value.screen == Screen.Chat) { stop(); stopSpeaking(); _ui.value.loadedModelName?.let { loadModel(File(modelsDir, it)) } }
        }
    }

    fun deleteCharacter(id: String) {
        val c = _ui.value.characters.firstOrNull { it.id == id } ?: return
        CharacterStore.delete(getApplication(), id)
        prefs.edit().remove("voice_for_$id").remove("speaker_for_$id").apply()
        val wasCurrent = _ui.value.companionId == id
        refreshCharacters()
        runCatching { MemoryStore.wipe(getApplication(), id) }
        _ui.update { it.copy(editor = null, notice = "Deleted ${c.name}") }
        if (wasCurrent) selectCompanion(Companions.look(c.look).id)
    }

    private fun exportBytes(id: String, card: Boolean): Pair<String, ByteArray>? {
        val c = _ui.value.characters.firstOrNull { it.id == id } ?: return null
        val base = CharacterStore.safeFileName(c.name)
        return if (card) "${base}_card.json" to CharacterStore.exportCard(c)
        else "${base}_aurora_pack.zip" to CharacterStore.exportPack(c, voicePrefFor(id), speakerPrefFor(id))
    }

    fun exportFileName(id: String, card: Boolean): String {
        val c = _ui.value.characters.firstOrNull { it.id == id } ?: return "companion"
        val base = CharacterStore.safeFileName(c.name)
        return if (card) "${base}_card.json" else "${base}_aurora_pack.zip"
    }

    /** Export to a place the user picked with the system "Save as" screen. */
    fun exportCharacter(id: String, uri: Uri, card: Boolean) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            try {
                val (_, bytes) = exportBytes(id, card) ?: return@launch
                withContext(Dispatchers.IO) { ctx.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }
                _ui.update { it.copy(notice = if (card) "Character card saved" else "Companion pack saved") }
            } catch (e: Throwable) {
                _ui.update { it.copy(error = "Export failed: ${e.message}") }
            }
        }
    }

    /** Writes the pack to the app cache for the Android share sheet (served by FileProvider). */
    fun shareFile(id: String): File? = try {
        val (name, bytes) = exportBytes(id, card = false) ?: throw IllegalStateException("not found")
        val dir = File(getApplication<Application>().cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        File(dir, name).apply { writeBytes(bytes) }
    } catch (e: Throwable) {
        _ui.update { it.copy(error = "Couldn't prepare the file to share: ${e.message}") }; null
    }

    // ---------- book library (0.8) ----------
    private var libraryJob: Job? = null

    fun refreshLibrary() {
        viewModelScope.launch(Dispatchers.IO) {
            val st = runCatching { Library.status(getApplication()) }.onFailure { Log.e(TAG, "library status", it) }.getOrNull() ?: return@launch
            _ui.update { it.copy(library = st) }
        }
    }

    fun setBookKnowledge(on: Boolean) {
        prefs.edit().putBoolean("book_knowledge", on).apply()
        _ui.update { it.copy(bookKnowledge = on) }
    }

    /** Downloads the missing books (verified), then builds the search index. Resumes where it stopped. */
    fun downloadLibrary() {
        if (libraryJob?.isActive == true) return
        val ctx = getApplication<Application>()
        val st = _ui.value.library
        val need = st.totalBytes - st.downloadedBytes
        if (StatFs(ctx.filesDir.path).availableBytes < need + 120L * 1024 * 1024) {
            _ui.update { it.copy(error = "Not enough free storage. The library needs about ${(need + 60L * 1024 * 1024).toNiceSize()} (books + index) plus some room to spare.") }
            return
        }
        libraryJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _ui.update { it.copy(libraryTask = LibraryTask("Downloading books", 0f, "Starting...")) }
                var lastUi = 0L
                Library.downloadAll(ctx) { done, total, title ->
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 250 || done == total) {
                        lastUi = now
                        _ui.update { it.copy(libraryTask = LibraryTask("Downloading books", done.toFloat() / total.coerceAtLeast(1),
                            "${done.toNiceSize()} of ${total.toNiceSize()}" + if (title.isNotEmpty()) "  -  $title" else "")) }
                    }
                }
                _ui.update { it.copy(library = Library.status(ctx)) }
                buildIndex(ctx)
            } catch (e: Throwable) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "library download failed", e)
                    _ui.update { it.copy(error = "Library download stopped: ${e.message}. Tap Download again to resume.") }
                }
            } finally {
                val st = runCatching { Library.status(ctx) }.getOrNull()
                _ui.update { it.copy(libraryTask = null, library = st ?: it.library) }
            }
        }
    }

    /** Builds (or finishes) the on-device index for books already downloaded. */
    fun indexLibrary() {
        if (libraryJob?.isActive == true) return
        val ctx = getApplication<Application>()
        libraryJob = viewModelScope.launch(Dispatchers.IO) {
            try { buildIndex(ctx) } catch (e: Throwable) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "library index failed", e)
                    _ui.update { it.copy(error = "Indexing stopped: ${e.message}. Tap Build index to continue.") }
                }
            } finally {
                val st = runCatching { Library.status(ctx) }.getOrNull()
                _ui.update { it.copy(libraryTask = null, library = st ?: it.library) }
            }
        }
    }

    private suspend fun buildIndex(ctx: Application) {
        _ui.update { it.copy(libraryTask = LibraryTask("Building the search index", 0f, "Starting...")) }
        val t0 = System.currentTimeMillis()
        Library.indexAll(ctx) { done, total, title ->
            _ui.update { it.copy(libraryTask = LibraryTask("Building the search index", done.toFloat() / total.coerceAtLeast(1),
                if (title.isNotEmpty()) "Indexing $title" else "Finishing...")) }
        }
        Log.i(TAG, "library index built in ${System.currentTimeMillis() - t0} ms")
        val st = Library.status(ctx)
        _ui.update { it.copy(library = st, notice = "\uD83D\uDCDA Library ready: ${st.indexedBooks} books") }
    }

    fun cancelLibraryTask() { libraryJob?.cancel() }

    fun deleteLibrary() {
        libraryJob?.cancel()
        viewModelScope.launch(Dispatchers.IO) {
            libraryJob?.join()
            runCatching { Library.deleteAll(getApplication()) }
            refreshLibrary()
        }
    }

    suspend fun searchBooks(query: String): List<Passage> = withContext(Dispatchers.IO) {
        runCatching { Library.search(getApplication(), Library.keywords(query), 30) }.getOrDefault(emptyList())
    }

    suspend fun readerText(book: BookInfo): Library.ReaderText? = withContext(Dispatchers.IO) {
        runCatching { Library.readerText(getApplication(), book) }.onFailure { Log.e(TAG, "reader", it) }.getOrNull()
    }

    fun readerPos(file: String) = prefs.getInt("reader_pos_$file", 0)
    fun saveReaderPos(file: String, pos: Int) = prefs.edit().putInt("reader_pos_$file", pos).apply()
    fun readerFont() = prefs.getInt("reader_font", 18)
    fun saveReaderFont(sp: Int) = prefs.edit().putInt("reader_font", sp).apply()

    // ---------- reminders (0.7) ----------
    private fun reloadReminders() {
        val now = java.time.LocalDateTime.now()
        val list = ReminderStore.load(getApplication()).sortedWith(compareBy<Reminder> { it.done }.thenBy { it.fireAt ?: it.due ?: now.plusYears(50) })
        _ui.update { it.copy(reminders = list) }
    }

    private fun reminderChat(msg: String): String? {
        val ctx = getApplication<Application>()
        val open = ReminderStore.load(ctx).filter { it.active }
        if (ReminderParser.isDoneCommand(msg)) {
            if (open.isEmpty()) return "The user has no open reminders right now. Tell them briefly, in character."
            val r = ReminderParser.matchOpen(msg, open) ?: return null
            ReminderAlarms.done(ctx, r.id)
            _ui.update { it.copy(notice = "Checked off: ${r.text}") }
            return "I checked off the reminder \"${r.text}\". Confirm this briefly in character."
        }
        ReminderParser.parseCommand(msg)?.let { p ->
            val r = addReminderInternal(p.task, p.due, p.repeat, "chat")
            val whenTxt = ReminderParser.formatWhen(r.due, r.repeat)
            _ui.update { it.copy(notice = "\u23F0 Reminder set: ${r.text} - $whenTxt") }
            return if (r.due != null) "I just saved a reminder for ${whenTxt}: \"${r.text}\". I will notify the user then. Confirm it briefly in character and mention the time. Do not invent other reminders."
            else "I just added \"${r.text}\" to the user's reminder list, with no set time (they can ask with a time, like 'at 5pm'). Confirm briefly in character."
        }
        if (ReminderParser.asksSchedule(msg)) {
            val lines = open.take(12).joinToString("\n") { "- ${it.text} (${ReminderParser.formatWhen(it.fireAt ?: it.due, it.repeat)})" }
            return "The user's open reminders are:\n${lines.ifEmpty { "(none)" }}\nAnswer from this list only. If it is empty, say nothing is scheduled."
        }
        return null
    }

    private fun addReminderInternal(text: String, due: java.time.LocalDateTime?, repeat: String?, source: String): Reminder {
        val ctx = getApplication<Application>()
        val r = Reminder(id = ReminderStore.newId(), text = text.trim().take(240), due = due, repeat = repeat, source = source, companion = _ui.value.companionId)
        ReminderStore.update(ctx) { it.add(r) }
        if (r.fireAt != null) ReminderAlarms.schedule(ctx, r)
        if (!ReminderAlarms.canNotify(ctx)) _ui.update { it.copy(askNotifPermission = true) }
        return r
    }

    /** Reminders screen add / edit. [whenText] is natural language ("tomorrow at 9"); blank keeps the old time when editing. */
    fun saveReminder(id: String?, text: String, whenText: String, repeatChoice: String?): Boolean {
        val ctx = getApplication<Application>()
        if (text.isBlank()) { _ui.update { it.copy(error = "Type what to remind you about.") }; return false }
        val now = java.time.LocalDateTime.now()
        val old = id?.let { ReminderStore.get(ctx, it) }
        val w = if (whenText.isBlank()) null else ReminderParser.parseWhen(whenText, now)
        if (whenText.isNotBlank() && w?.due == null) {
            _ui.update { it.copy(error = "I couldn't understand \"$whenText\". Try things like: in 20 minutes, at 5pm, tomorrow at 9, friday at noon.") }; return false
        }
        var due = w?.due ?: old?.due
        var repeat = repeatChoice ?: w?.repeat
        if (repeat != null) {
            val t = due?.toLocalTime() ?: java.time.LocalTime.of(9, 0)
            if (repeat == "weekly") repeat = "weekly:" + (due?.dayOfWeek ?: now.dayOfWeek).name
            due = ReminderParser.nextOccurrence(repeat, t, now.minusSeconds(1))
        }
        if (old == null) {
            addReminderInternal(text, due, repeat, "settings")
        } else {
            var upd: Reminder? = null
            ReminderStore.update(ctx) { items ->
                val i = items.indexOfFirst { it.id == old.id }
                if (i >= 0) { upd = items[i].copy(text = text.trim().take(240), due = due, repeat = repeat, notified = false, snoozeUntil = null, done = false); items[i] = upd!! }
            }
            upd?.let { ReminderAlarms.schedule(ctx, it) }
        }
        return true
    }

    fun previewWhen(whenText: String): String {
        if (whenText.isBlank()) return ""
        val w = ReminderParser.parseWhen(whenText)
        return if (w.due == null) "Not understood yet" else ReminderParser.formatWhen(w.due, w.repeat)
    }

    fun deleteReminder(id: String) {
        val ctx = getApplication<Application>()
        ReminderAlarms.cancel(ctx, id)
        ReminderStore.update(ctx) { items -> items.removeAll { it.id == id } }
    }

    fun snoozeReminder(id: String) {
        viewModelScope.launch(Dispatchers.IO) { ReminderAlarms.snooze(getApplication(), id) }
        _ui.update { it.copy(notice = "Snoozed for ${ReminderAlarms.SNOOZE_MINUTES} minutes") }
    }

    fun doneReminder(id: String) { viewModelScope.launch(Dispatchers.IO) { ReminderAlarms.done(getApplication(), id) } }

    fun clearCompletedReminders() = ReminderStore.update(getApplication()) { items -> items.removeAll { it.done } }

    fun setSpeakReminders(on: Boolean) {
        prefs.edit().putBoolean("speak_reminders", on).apply()
        _ui.update { it.copy(speakReminders = on) }
    }

    fun notifPermissionAsked() = _ui.update { it.copy(askNotifPermission = false) }

    /** App is open when a reminder goes off: say it in the current companion's voice and drop it into the chat. */
    private fun onReminderFired(r: Reminder) {
        if (!ReminderBus.appVisible) return
        val phrase = "Hey, reminder: ${r.text}. Want me to check it off when you're done?"
        if (_ui.value.screen == Screen.Chat) _ui.update { it.copy(messages = it.messages + ChatMessage(text = "\u23F0 $phrase", isUser = false)) }
        if (_ui.value.speakReminders && voice.ttsReady) {
            if (_ui.value.inCall) enqueueSpeech(phrase, voice.currentEpoch()) else speakMessage(phrase)
        }
    }

    fun setSpeaker(id: Int) {
        voice.speakerId = id
        prefs.edit().putInt("speaker_id", id).putInt("speaker_for_${_ui.value.companionId}", id).apply()
        _ui.update { it.copy(speakerId = id) }
    }

    private var voiceDlJob: Job? = null

    /** Optional LibriTTS download (user already consented in Settings). Checksums pinned like the desktop installer. */
    fun downloadVoice(id: String) {
        val v = VoiceCatalog.byId(id)
        if (v.builtIn || voiceDlJob?.isActive == true) return
        val ctx = getApplication<Application>()
        if (StatFs(ctx.filesDir.path).availableBytes < v.modelBytes + 100L * 1024 * 1024) {
            _ui.update { it.copy(error = "Not enough free storage for this voice (needs about ${v.modelBytes.toNiceSize()}).") }; return
        }
        _ui.update { it.copy(voiceDownload = 0f) }
        voiceDlJob = viewModelScope.launch(Dispatchers.IO) {
            val d = VoiceCatalog.dir(ctx, v)
            try {
                d.mkdirs(); File(d, ".ready").delete()
                val json = File(d, VoiceCatalog.JSON_FILE)
                val model = File(d, VoiceCatalog.MODEL_FILE)
                httpDownload(v.jsonUrl, json, null) {}
                check(sha256(json) == v.jsonSha256) { "voice settings file failed its checksum" }
                httpDownload(v.modelUrl, model, v.modelBytes) { f -> _ui.update { it.copy(voiceDownload = f) } }
                check(sha256(model) == v.modelSha256) { "voice file failed its checksum" }
                VoiceCatalog.prepareForSherpa(ctx, v)
                _ui.update { it.copy(voiceDownload = null, installedVoices = installedVoiceIds()) }
                withContext(Dispatchers.Main) { selectVoice(v.id) }
            } catch (e: Throwable) {
                Log.e(TAG, "voice download failed", e)
                if (e !is kotlinx.coroutines.CancellationException) d.deleteRecursively()
                _ui.update { it.copy(voiceDownload = null, installedVoices = installedVoiceIds(),
                    error = if (e is kotlinx.coroutines.CancellationException) null else "Voice download stopped: ${e.message}") }
            }
        }
    }

    fun cancelVoiceDownload() { voiceDlJob?.cancel() }

    fun deleteVoice(id: String) {
        val v = VoiceCatalog.byId(id)
        if (v.builtIn) return
        if (_ui.value.voiceId == v.id) selectVoice(VoiceCatalog.ROBERT.id)
        viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(1500)   // let the switch release the old voice first
            VoiceCatalog.dir(getApplication(), v).deleteRecursively()
            _ui.update { it.copy(installedVoices = installedVoiceIds()) }
        }
    }

    private fun sha256(f: File): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Simple resumable HTTPS download with manual redirect handling (Hugging Face redirects to its CDN). */
    private suspend fun httpDownload(urlStr: String, dest: File, expected: Long?, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val part = File(dest.path + ".part")
        var url = URL(urlStr)
        var conn: HttpURLConnection
        var redirects = 0
        while (true) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 20_000; conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", "AuroraCompanion-Android/0.3")
            if (part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
            val code = conn.responseCode
            if (code in 300..399 && redirects < 8) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); redirects++; continue }
            break
        }
        val code = conn.responseCode
        if (code != 200 && code != 206) throw IllegalStateException("Server returned HTTP $code")
        val resume = code == 206
        RandomAccessFile(part, "rw").use { raf ->
            if (!resume) raf.setLength(0)
            raf.seek(raf.length())
            var have = raf.filePointer
            val buf = ByteArray(256 * 1024)
            var lastUi = 0L
            conn.inputStream.use { input ->
                while (isActive) {
                    val n = input.read(buf); if (n < 0) break
                    raf.write(buf, 0, n); have += n
                    val now = System.currentTimeMillis()
                    if (expected != null && now - lastUi > 250) { lastUi = now; onProgress(have.toFloat() / expected) }
                }
            }
        }
        conn.disconnect()
        if (!isActive) throw kotlinx.coroutines.CancellationException()
        if (expected != null && part.length() != expected) throw IllegalStateException("download incomplete, tap Download again to resume")
        dest.delete()
        if (!part.renameTo(dest)) throw IllegalStateException("could not save the file")
    }

    /** Push-to-talk: start recording (RECORD_AUDIO already granted by the UI). */
    fun startListening() {
        if (!voice.asrReady || _ui.value.listening || _ui.value.transcribing) return
        stopSpeaking()
        _ui.update { it.copy(listening = true) }
        recordJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val samples = voice.recordBlocking(maxSeconds = 30)
                _ui.update { it.copy(listening = false, listenLatched = false, transcribing = true) }
                val text = if (samples.size < VoiceEngine.MIC_RATE / 4) "" else voice.transcribe(samples)
                _ui.update { it.copy(transcribing = false) }
                withContext(Dispatchers.Main) {
                    if (text.isNotBlank()) send(text)
                    else _ui.update { it.copy(error = "I didn't catch that. Hold the mic while you talk and let go, or tap it once, talk, then tap again.") }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "listening failed", e)
                _ui.update { it.copy(listening = false, listenLatched = false, transcribing = false, error = "Microphone problem: ${e.message}") }
            }
        }
    }

    fun stopListening() = voice.stopRecording()

    /** A quick tap keeps the mic open (tap-to-talk) until the next tap. */
    fun latchListening() { if (_ui.value.listening) _ui.update { it.copy(listenLatched = true) } }

    /** Fresh conversation: reload the model so the context is cleared. */
    fun newChat() {
        stop()
        stopSpeaking()
        val pending = capturePendingMemory()
        _ui.value.loadedModelName?.let { loadModel(File(modelsDir, it)) }
        queueMemory(pending)
    }

    fun backToSetup() {
        stop()
        stopSpeaking()
        // No model to write notes with after this; keep them for the next pass.
        capturePendingMemory()?.let { deferredMemory = it }
        memJob?.cancel()
        chatValid = false
        runCatching { engine.cleanUp() }
        refreshDevice()
        saveConversation()
        _ui.update { it.copy(screen = Screen.Setup, loadedModelName = null, messages = emptyList()) }
        beginConversation(HistoryStore.MODE_CHAT, 0)
    }

    fun deleteModel(option: ModelOption) {
        File(modelsDir, option.fileName).delete()
        File(modelsDir, option.fileName + ".part").delete()
        refreshDevice()
    }

    override fun onCleared() {
        runCatching { saveConversation(sync = true) }
        memJob?.cancel()
        runCatching { call.release() }
        runCatching { voice.release() }
        speechQueue.close()
        runCatching { engine.destroy() }
        super.onCleared()
    }

    companion object {
        private const val TAG = "AuroraVM"
        private const val MAX_REPLY_TOKENS = 384
        /** Context rebuilds (Continue, recovery, after a memory pass) keep about this many tokens of recent chat. */
        private const val REBUILD_TOKEN_BUDGET = 1400
        /** Extra room freed when old turns are dropped, so it doesn't happen on every message. */
        private const val TRIM_SLACK_TOKENS = 600
        private const val MAX_PROMPT_CHARS = 6000
        private const val MEMORY_MAX_LINES = 40
        private const val MEMORY_PROMPT_TOKENS = 2200
        private const val MEMORY_REPLY_TOKENS = 200
        const val NO_REPLY = "(no reply)"
        const val BOOK_KNOWLEDGE_MIN_RAM_GB = 6.0
        const val BOOK_NOTE_BUDGET = 1200
        const val DEFAULT_LIBRITTS_SPEAKER = 0
        // Personas live in Companions.kt (family-friendly). Adult mode is a later phase, gated behind an 18+ confirmation.
    }
}
