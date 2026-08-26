package com.m57.hermescontrol.ui.common

import androidx.lifecycle.ViewModel
import com.m57.hermescontrol.data.remote.NetworkResult
import com.m57.hermescontrol.data.ws.ChangeEventHub
import com.m57.hermescontrol.data.ws.ChangeEvents
import com.m57.hermescontrol.data.ws.WsEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Regression guard for issue #784: `refreshOnChange` must react to hub events
 * WITHOUT touching the HermesWsClient singleton — constructing a ViewModel
 * that wires it must be safe in a plain unit-test JVM (no mockkObject of
 * HermesWsClient, no static mocks). This used to poison the whole suite with
 * ExceptionInInitializerError at MockKStub.kt:106.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshOnChangeTest {
    private val testDispatcher = StandardTestDispatcher()

    private class TestViewModel : ViewModel() {
        val state = MutableStateFlow("initial")

        init {
            refreshOnChange(
                eventType = ChangeEvents.CRON,
                apiCall = { NetworkResult.Success("refreshed") },
                onSuccess = { state.value = it },
            )
        }
    }

    /** Multi-type variant: one collector, several backend signatures. */
    private class MultiTypeViewModel : ViewModel() {
        val refreshes = MutableStateFlow(0)

        init {
            refreshOnChange(
                eventTypes = setOf(ChangeEvents.SESSIONS, ChangeEvents.GATEWAY),
                apiCall = { NetworkResult.Success(Unit) },
                onSuccess = { refreshes.value += 1 },
            )
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testRefreshOnChange_updatesOnMatchingEvent() =
        runTest(testDispatcher) {
            val viewModel = TestViewModel()
            // Let the init coroutine run so the collector actually subscribes
            // before the event fires (replay=0 — events before subscription
            // are dropped, matching production where VMs subscribe at
            // construction and events arrive afterwards).
            advanceUntilIdle()
            ChangeEventHub.emit(WsEvent.ChangeEvent(ChangeEvents.CRON))
            advanceUntilIdle()
            assertEquals("refreshed", viewModel.state.value)
        }

    @Test
    fun testRefreshOnChange_multiType_refreshesOnEitherType() =
        runTest(testDispatcher) {
            // Bot Mode Fase 4: the roster refreshes on sessions.changed (last
            // message) AND gateway.changed (presence), off ONE collector.
            val viewModel = MultiTypeViewModel()
            advanceUntilIdle()
            ChangeEventHub.emit(WsEvent.ChangeEvent(ChangeEvents.SESSIONS))
            advanceUntilIdle()
            ChangeEventHub.emit(WsEvent.ChangeEvent(ChangeEvents.GATEWAY))
            advanceUntilIdle()
            assertEquals(2, viewModel.refreshes.value)
        }

    @Test
    fun testRefreshOnChange_multiType_ignoresUnlistedTypes() =
        runTest(testDispatcher) {
            // A backend that only emits a subset degrades to that subset — it
            // never refreshes on something nobody asked for.
            val viewModel = MultiTypeViewModel()
            advanceUntilIdle()
            ChangeEventHub.emit(WsEvent.ChangeEvent(ChangeEvents.CRON))
            advanceUntilIdle()
            assertEquals(0, viewModel.refreshes.value)
        }

    @Test
    fun testRefreshOnChange_ignoresOtherEventTypes() =
        runTest(testDispatcher) {
            val viewModel = TestViewModel()
            advanceUntilIdle()
            ChangeEventHub.emit(WsEvent.ChangeEvent(ChangeEvents.SESSIONS))
            advanceUntilIdle()
            assertEquals("initial", viewModel.state.value)
        }
}
