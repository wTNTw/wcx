package com.Johnny.wcx.loader.startup

/** Observed startup of WCX in this process, not a scan of installed frameworks. */
internal object ModuleLoadStatus {
    enum class State { NOT_OBSERVED, WAITING_FOR_HOST, INITIALIZING, LOADED, FAILED }

    enum class FailureStage(val expectedState: State) {
        REGISTER_LIFECYCLE(State.WAITING_FOR_HOST),
        INITIALIZE_MODULE(State.INITIALIZING),
    }

    data class Failure(val stage: FailureStage, val reason: String)

    data class Snapshot(
        val state: State = State.NOT_OBSERVED,
        val entryPoint: String? = null,
        val framework: String? = null,
        val failure: Failure? = null,
    )

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    @Synchronized
    fun waitingForHost(entryPoint: String, framework: String?) {
        if (snapshot.state != State.NOT_OBSERVED) return
        snapshot = Snapshot(State.WAITING_FOR_HOST, entryPoint, framework)
    }

    @Synchronized
    fun beginInitialization(): Boolean {
        if (snapshot.state != State.WAITING_FOR_HOST) return false
        snapshot = snapshot.copy(state = State.INITIALIZING)
        return true
    }

    @Synchronized
    fun loaded() {
        if (snapshot.state != State.INITIALIZING) return
        snapshot = snapshot.copy(state = State.LOADED)
    }

    @Synchronized
    fun failed(stage: FailureStage, error: Throwable) {
        if (snapshot.state != stage.expectedState) return
        val type = error.javaClass.simpleName.ifBlank { error.javaClass.name }
        val message = error.message?.lineSequence()?.firstOrNull()?.trim()?.take(240)
        val reason = if (message.isNullOrEmpty()) type else "$type: $message"
        snapshot = snapshot.copy(state = State.FAILED, failure = Failure(stage, reason))
    }
}
