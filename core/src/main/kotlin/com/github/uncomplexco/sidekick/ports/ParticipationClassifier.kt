package com.github.uncomplexco.sidekick.ports

import com.github.uncomplexco.sidekick.application.turn.ParticipationClassifierInput
import com.github.uncomplexco.sidekick.application.turn.TurnParticipationDecision

fun interface ParticipationClassifier {
    suspend fun classify(input: ParticipationClassifierInput): TurnParticipationDecision
}
