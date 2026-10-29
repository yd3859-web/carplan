package com.htb.carplan.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.htb.carplan.shared.data.DevTrip
import com.htb.carplan.shared.data.TripRepository
import com.htb.carplan.shared.model.Place
import com.htb.carplan.shared.model.Stop
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

/** Adds stops to the fixed dev trip. Goes away once 3장 일정 만들기 exists. */
@OptIn(ExperimentalCoroutinesApi::class)
class DevTripViewModel : ViewModel() {
    private val repo = TripRepository()
    private val auth = FirebaseAuth.getInstance()
    private val today = LocalDate.now().toString()
    private val signedIn = MutableStateFlow(auth.currentUser != null)

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    val stops: StateFlow<List<Stop>> = signedIn
        .flatMapLatest { ok ->
            if (ok) repo.observeStops(DevTrip.GROUP_ID, DevTrip.TRIP_ID, today) else emptyFlow()
        }
        .catch { _error.value = it.message }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            // TODO(1장): Google sign-in.
            runCatching { if (auth.currentUser == null) auth.signInAnonymously().await() }
                .onSuccess { signedIn.value = true }
                .onFailure { _error.value = it.message }
        }
    }

    fun addStop(place: Place) {
        val uid = auth.currentUser?.uid ?: return
        viewModelScope.launch {
            runCatching { repo.addStop(DevTrip.GROUP_ID, DevTrip.TRIP_ID, today, Stop(place = place), uid) }
                .onFailure { _error.value = it.message }
        }
    }
}
