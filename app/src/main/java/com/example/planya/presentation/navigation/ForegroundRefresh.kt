package com.example.planya.presentation.navigation

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner

// El primer RESUME ya tiene las cargas iniciales. Solo refrescar al regresar.
internal class ForegroundRefreshObserver(private val onReturn: () -> Unit) : LifecycleEventObserver {
    private var resumedOnce = false
    override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
        if (event == Lifecycle.Event.ON_RESUME) {
            if (resumedOnce) onReturn() else resumedOnce = true
        }
    }
}
