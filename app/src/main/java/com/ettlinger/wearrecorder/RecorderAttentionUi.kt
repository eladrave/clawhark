package com.ettlinger.wearrecorder

/** Display state and button intent are separate: a persisted request is not proof of live audio. */
object RecorderAttentionUi {
    private val activeStates = setOf("RECORDING", "LISTENING", "RECOVERING MIC", "STARTING")
    data class State(val label: String, val reason: String?, val stopAction: Boolean,
        val recordingActive: Boolean)

    fun resolve(requested: Boolean, publishedState: String, serviceState: String?,
        terminalError: String?, recoveryReason: String?): State {
        val error = terminalError?.takeIf { it.isNotBlank() }
        val label = when {
            error != null -> "NEEDS ATTENTION"
            serviceState != null && serviceState != "STOPPED" -> serviceState
            requested && publishedState == "STOPPED" -> "STARTING"
            requested -> publishedState
            publishedState == "NEEDS ATTENTION" -> publishedState
            else -> "STOPPED"
        }
        val reason = error ?: recoveryReason?.takeIf {
            it.isNotBlank() && label in setOf("RECOVERING MIC", "STARTING", "CHECK RECORDER")
        }
        return State(label, reason, requested || serviceState in activeStates, label == "RECORDING")
    }

    fun statusColor(label: String): Int = when (label) {
        "NEEDS ATTENTION", "RECORDING" -> 0xffff6666.toInt()
        "RECOVERING MIC", "CHECK RECORDER", "STARTING" -> 0xffffc857.toInt()
        else -> 0xff71daca.toInt()
    }
}
