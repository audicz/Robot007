package cz.robotcockpit.app.core

sealed interface LocalVoiceIntent {
    data object Stop : LocalVoiceIntent
    data object ComeHere : LocalVoiceIntent
    data object Explore : LocalVoiceIntent
    data class GoToRoom(val room: String) : LocalVoiceIntent
    data class Unknown(val transcript: String) : LocalVoiceIntent
}

/** Deterministic offline Czech intent parser. Safety-critical STOP never uses an LLM. */
class LocalVoiceIntentParser {
    fun parse(transcript: String): LocalVoiceIntent {
        val text = transcript.trim().lowercase()
        if (text.isEmpty()) return LocalVoiceIntent.Unknown(transcript)

        if (listOf("stop", "zastav", "stůj", "stuj", "nouzové zastavení", "nouzove zastaveni").any(text::contains)) {
            return LocalVoiceIntent.Stop
        }
        if (listOf("přijeď ke mně", "prijeď ke mně", "najdi mě", "najdi me").any(text::contains)) {
            return LocalVoiceIntent.ComeHere
        }
        if (listOf("prozkoumej", "zmapuj", "projeď dům", "projed dum").any(text::contains)) {
            return LocalVoiceIntent.Explore
        }
        val room = listOf("kuchyně", "kuchyne", "obývák", "obyvak", "ložnice", "loznice", "chodba")
            .firstOrNull(text::contains)
        if (room != null && listOf("jeď", "jed", "přijeď", "prijeď", "do ").any(text::contains)) {
            return LocalVoiceIntent.GoToRoom(room)
        }
        return LocalVoiceIntent.Unknown(transcript)
    }
}
