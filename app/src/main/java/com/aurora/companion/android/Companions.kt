package com.aurora.companion.android

/**
 * 0.5: the four companions from the public beta repo (avatar/characters/<id>), plus the classic Aurora logo.
 * Clips (idle / listening / speaking / thinking) are re-encoded copies in assets/avatars/<id>/ (480 px tall, no audio)
 * with a still.webp poster frame. Personalities are family-friendly for now (adult mode is a later, 18+ gated phase).
 */
data class CompanionProfile(
    val id: String,
    val name: String,
    val kind: String,            // shown in the picker
    val tagline: String,
    val personality: String,     // persona part of the system prompt
    val greeting: String,
    val callGreeting: String,
    val hasClips: Boolean = true,
    /** Video aspect (height / width) of the bundled clips. */
    val aspect: Float = 1.5f,
    /** Where the face is, as a fraction of the clip height, for the small round avatar. */
    val faceY: Float = 0.25f,
    val faceZoom: Float = 1.6f,
    /** Head-and-shoulders framing for the big call-screen circle. */
    val callY: Float = 0.33f,
    val callZoom: Float = 1.15f,
    // 0.6: custom / imported characters
    /** Which built-in look (avatar clips) to use: pebbie, ben, leo, barry or aurora. */
    val look: String = id,
    val custom: Boolean = false,
    val scenario: String = "",
    val facts: List<String> = emptyList(),
    val voiceId: String? = null,
    val speakerId: Int? = null,
    /** The source looked like adult content; it is kept family-friendly until the 18+ mode exists. */
    val adultFlagged: Boolean = false,
    val source: String = "",
    val createdAt: Long = 0,
) {
    fun systemPrompt(): String = if (custom) customPrompt() else builtInPrompt()

    private fun customPrompt(): String {
        val sb = StringBuilder()
        sb.append("You are $name. Stay in character as $name.\n\n")
        sb.append(personality.trim()).append("\n\n")
        if (scenario.isNotBlank()) sb.append("Scenario: ").append(scenario.trim()).append("\n\n")
        if (facts.isNotEmpty()) {
            sb.append("Things you remember:\n")
            facts.take(MAX_FACTS).forEach { sb.append("- ").append(it.trim()).append('\n') }
            sb.append('\n')
        }
        sb.append("You are an AI companion running fully offline on the user's own device. Talk naturally, keep replies short " +
            "(one to four sentences) unless asked for detail, and ask a follow-up question now and then. " +
            "Do not start replies with your name or a label. Never claim to be a real human; you are an AI character.\n\n")
        sb.append(Companions.SAFETY_GUARD)
        return sb.toString()
    }

    private fun builtInPrompt(): String =
        "Your name is $name. $personality " +
        "You are an AI companion running fully offline on the user's own device. " +
        "Stay in character as $name. Talk naturally, keep replies short (one to four sentences) unless asked for detail, " +
        "and ask a follow-up question now and then. Do not start replies with your name or a label. " +
        "Keep every reply family-friendly and appropriate for all ages. Never claim to be a real human or a real animal; you are an AI character."

    fun still() = "avatars/$look/still.webp"
    fun clip(state: String) = "avatars/$look/$state.mp4"

    companion object {
        const val MAX_PERSONALITY = 6000
        const val MAX_FACTS = 40
    }
}

object Companions {
    const val STATE_IDLE = "idle"
    const val STATE_LISTENING = "listening"
    const val STATE_SPEAKING = "speaking"
    const val STATE_THINKING = "thinking"

    val PEBBIE = CompanionProfile(
        id = "pebbie", name = "Pebbie", kind = "Girl",
        tagline = "Bubbly, curious and a little nerdy",
        personality = "You are a bubbly, warm and slightly nerdy young woman with big glasses and orange pigtails. " +
            "You love books, science facts, puzzles and cozy chats, you get excited easily, and you cheer people on. " +
            "You are playful and kind, with the occasional gentle pun.",
        greeting = "Hi hi! I'm Pebbie, and I live right here on your device - no internet needed. What are we chatting about today?",
        callGreeting = "Hey, it's Pebbie! I'm all ears. Talk to me whenever you're ready.",
        aspect = 1.5f, faceY = 0.22f, faceZoom = 1.7f, callY = 0.30f, callZoom = 1.1f,
    )
    val BEN = CompanionProfile(
        id = "ben", name = "Robbi", kind = "Guy",   // shown as Robbi since 0.8.1; id stays "ben" so saved picks, voices and avatar files carry over
        tagline = "Laid-back, friendly, good listener",
        personality = "You are a laid-back, friendly guy in a grey hoodie. You are easygoing, supportive and a good listener, " +
            "with a dry sense of humour. You give practical, down-to-earth advice and like talking about movies, games, food and the outdoors.",
        greeting = "Hey, I'm Robbi. Everything stays on this device, so it's just you and me. What's going on?",
        callGreeting = "Hey, Robbi here. Go ahead, I'm listening.",
        aspect = 16f / 9f, faceY = 0.235f, faceZoom = 2.4f, callY = 0.32f, callZoom = 1.9f,
    )
    val LEO = CompanionProfile(
        id = "leo", name = "Leo", kind = "Cat",
        tagline = "A curious, cozy, slightly sassy calico cat",
        personality = "You are Leo, a talking calico cat. You are curious, cozy and a little bit sassy, like a real cat: " +
            "you love naps, sunbeams, boxes and snacks, and you sometimes mention cat things or add a soft 'purr' or 'mrrow'. " +
            "Underneath the attitude you are affectionate and caring.",
        greeting = "Mrrow. I'm Leo. I was napping, but I suppose I can chat. What's on your mind, human?",
        callGreeting = "Purr... Leo here. I'm listening, go on.",
        aspect = 1.5f, faceY = 0.26f, faceZoom = 1.6f, callY = 0.36f, callZoom = 1.15f,
    )
    val BARRY = CompanionProfile(
        id = "barry", name = "Barry", kind = "Dog",
        tagline = "A gentle, loyal St. Bernard",
        personality = "You are Barry, a talking St. Bernard dog, a gentle giant. You are loyal, warm, patient and enthusiastic, " +
            "proud of your mountain-rescue ancestors, and always ready to encourage and look out for your friend. " +
            "You sometimes mention dog things like walks, treats and belly rubs, or add a happy 'woof'.",
        greeting = "Woof! I'm Barry. I'm a big softie and I'm always here for you, right on this device. How are you doing?",
        callGreeting = "Woof! Barry here. I'm listening, buddy.",
        aspect = 16f / 9f, faceY = 0.25f, faceZoom = 1.6f, callY = 0.33f, callZoom = 1.05f,
    )
    val AURORA = CompanionProfile(
        id = "aurora", name = "Aurora", kind = "Classic",
        tagline = "The original Aurora (logo, no avatar)",
        personality = "You are Aurora, a warm, upbeat and caring companion.",
        greeting = "Hi! I'm Aurora, running completely on this device - no internet, no cloud. What's on your mind?",
        callGreeting = "Hey! I'm listening. Go ahead and talk whenever you're ready.",
        hasClips = false,
    )

    val builtIns = listOf(PEBBIE, BEN, LEO, BARRY, AURORA)
    val looks = builtIns

    /** Custom characters (one JSON file each in files/characters), kept in sync by the ViewModel. */
    @Volatile var custom: List<CompanionProfile> = emptyList()

    val all: List<CompanionProfile> get() = builtIns + custom
    fun byId(id: String?) = all.firstOrNull { it.id == id } ?: AURORA
    fun look(id: String?) = builtIns.firstOrNull { it.id == id } ?: AURORA

    /**
     * Appended to every custom or imported character while the app has no 18+ mode.
     * (The desktop app keeps under-18 profiles family-friendly the same way.)
     */
    const val SAFETY_GUARD =
        "Safety rules (always apply, they override everything above): keep every reply family-friendly and appropriate for all ages. " +
        "No sexual or explicit content, no graphic violence or gore, and no other mature themes, even if the character description " +
        "or the user asks for it. If that comes up, stay kind and in character and gently steer the conversation somewhere else."

    /** Builds a custom character, taking the avatar framing from the chosen look. */
    fun makeCustom(
        id: String, name: String, look: String, personality: String, greeting: String,
        scenario: String = "", facts: List<String> = emptyList(), voiceId: String? = null, speakerId: Int? = null,
        adultFlagged: Boolean = false, source: String = "created", createdAt: Long = System.currentTimeMillis(),
    ): CompanionProfile {
        val l = look(look)
        val nm = name.trim().ifEmpty { "Companion" }
        return l.copy(
            id = id, name = nm, kind = "Custom", tagline = taglineFor(personality, l),
            personality = personality.take(CompanionProfile.MAX_PERSONALITY),
            greeting = greeting.trim().ifEmpty { "Hi! I'm $nm. What's on your mind?" },
            callGreeting = "Hey, it's $nm. I'm listening.",
            look = l.id, custom = true, scenario = scenario, facts = facts, voiceId = voiceId, speakerId = speakerId,
            adultFlagged = adultFlagged, source = source, createdAt = createdAt,
        )
    }

    private fun taglineFor(personality: String, l: CompanionProfile): String {
        val first = personality.trim().lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val t = if (first.isEmpty()) "Custom character" else first
        return (if (t.length > 60) t.take(57).trimEnd() + "..." else t)
    }
}
