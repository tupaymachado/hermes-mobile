package com.m57.hermescontrol.data.config

import com.m57.hermescontrol.data.model.PinnedModel
import com.m57.hermescontrol.data.session.GroupRoom
import com.m57.hermescontrol.theme.ThemePreference
import com.m57.hermescontrol.theme.ThemePreset
import kotlinx.serialization.Serializable

@Serializable
data class ServerStoreState(
    /** Legacy host and port retained for one-time migration. */
    val host: String = "127.0.0.1",
    val port: Int = 9119,
    val baseUrl: String? = null,
    val autoReconnect: Boolean = true,
    val themePreference: ThemePreference = ThemePreference.SYSTEM,
    val useDynamicColors: Boolean = true,
    val themePreset: ThemePreset = ThemePreset.DEFAULT,
    val connectionProfiles: List<ConnectionProfile> = emptyList(),
    val selectedProfileId: String? = null,
    val pinnedModels: List<PinnedModel> = emptyList(),
    val wsAuthParam: String = "token",
    val typingEffectEnabled: Boolean = true,
    val typingEffectDelayMs: Int = 30,
    // App display language. "system" = follow device locale; otherwise a BCP-47
    // language code such as "en" or "ko". Applied via ContextWrapper in MainActivity.
    val appLanguage: String = "system",
    // App version the silent update check (issue #867) last completed for.
    // Null / mismatched with BuildConfig.VERSION_NAME → the About tab runs
    // its one-time check again (re-check on app version bump).
    val updateCheckDoneForVersion: String? = null,
    // Latest release tag the silent check (issue #890) last saw. Persisted so
    // a dismissed chat banner can return on a later launch without re-pinging
    // GitHub (the once-per-version guard skips the check by then).
    val lastKnownLatestTag: String? = null,
    // Bot Mode: canonical chat session per SERVER-side Hermes profile
    // (key = profile name, value = session id). Entering a bot reopens this
    // thread instead of creating a new one. Optional with a default, so older
    // stores deserialize unchanged (ignoreUnknownKeys, no migration needed).
    val botChatSessions: Map<String, String> = emptyMap(),
    // Bot Mode P3: group rooms. The 1:N widening of botChatSessions above —
    // each room keeps its own per-member thread map, so a bot in three rooms
    // holds three threads plus its 1:1 chat, independently. Additive with a
    // default, so older stores deserialize unchanged (no migration).
    val groupRooms: List<GroupRoom> = emptyList(),
)
