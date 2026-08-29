package com.m57.hermescontrol.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.m57.hermescontrol.data.remote.ApiClient
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.remote.safeApiCall
import com.m57.hermescontrol.data.ws.ChangeEvents
import com.m57.hermescontrol.ui.common.refreshOnChange
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One offerable bot: the handle typed into the message, plus what it is for. */
data class MentionBot(
    val name: String,
    val description: String? = null,
)

/**
 * Roster behind the composer's @mention autocomplete (§P4).
 *
 * Deliberately NOT `BotsViewModel`: the roster's job is the Bots screen, and it
 * pays 2N+1 requests to fan out last-messages and presence. Autocomplete needs
 * names, so this asks `GET /api/profiles` and nothing else — **one** request,
 * on a screen the user opened to type, not to browse bots.
 *
 * Deliberately NOT state on `ChatViewModel` either: that file is the hot spot
 * §V10 warns about, and a dropdown does not belong in a generations/resume
 * machine.
 *
 * **Failure is silent, by design.** A roster that will not load means no
 * suggestions — the `@` the user typed stays plain text and the message sends
 * exactly as written. Autocomplete is an accelerator; it has no business
 * raising an error over a message the user can finish typing by hand.
 */
class MentionViewModel(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _bots = MutableStateFlow<List<MentionBot>>(emptyList())
    val bots: StateFlow<List<MentionBot>> = _bots.asStateFlow()

    init {
        // A bot created or renamed elsewhere should be mentionable without
        // reopening the chat. Same silent-backstop shape the other Bot Mode
        // screens use; a backend that never emits simply keeps the list from
        // [load].
        refreshOnChange(
            eventType = ChangeEvents.GATEWAY,
            apiCall = { fetchBots() },
            onSuccess = { fetched -> _bots.value = fetched },
        )
    }

    fun load() {
        viewModelScope.launch {
            when (val result = fetchBots()) {
                is NetworkResult.Success -> _bots.value = result.data
                // Swallowed on purpose — see the class comment.
                is NetworkResult.Failure -> Unit
            }
        }
    }

    private suspend fun fetchBots(): NetworkResult<List<MentionBot>> =
        withContext(ioDispatcher) {
            when (val result = safeApiCall { ApiClient.hermesApi.getProfiles() }) {
                is NetworkResult.Success ->
                    NetworkResult.Success(
                        result.data.profiles
                            .orEmpty()
                            .filter { it.name.isNotBlank() }
                            .map { profile ->
                                MentionBot(
                                    name = profile.name,
                                    description = profile.description?.takeIf(String::isNotBlank),
                                )
                            },
                    )

                is NetworkResult.Failure -> result
            }
        }
}
