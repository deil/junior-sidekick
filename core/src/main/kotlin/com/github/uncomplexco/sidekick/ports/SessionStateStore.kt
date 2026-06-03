package com.github.uncomplexco.sidekick.ports

import com.github.uncomplexco.sidekick.application.session.SessionId
import com.github.uncomplexco.sidekick.application.session.SessionState

interface SessionStateStore {
    fun exists(id: SessionId): Boolean

    fun load(id: SessionId): SessionState

    fun save(
        id: SessionId,
        state: SessionState,
    )

    suspend fun <T> withSessionLock(
        id: SessionId,
        block: suspend () -> T,
    ): T
}
