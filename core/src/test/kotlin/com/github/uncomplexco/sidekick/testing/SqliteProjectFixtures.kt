package com.github.uncomplexco.sidekick.testing

import com.github.uncomplexco.sidekick.adapters.sqlite.SqliteConfiguration
import com.github.uncomplexco.sidekick.adapters.sqlite.SqliteProjectsRepository
import com.github.uncomplexco.sidekick.adapters.sqlite.SqliteUsersRepository
import com.github.uncomplexco.sidekick.application.agent.AgentConfig

fun sqliteProjectsRepository(config: AgentConfig): SqliteProjectsRepository {
    val configuration = SqliteConfiguration()
    val source = configuration.sidekickDataSource(config)
    return SqliteProjectsRepository(
        configuration.sidekickJdbcClient(source),
        configuration.sidekickTransactions(source),
    )
}

fun sqliteUsersRepository(config: AgentConfig): SqliteUsersRepository {
    val configuration = SqliteConfiguration()
    val source = configuration.sidekickDataSource(config)
    return SqliteUsersRepository(configuration.sidekickJdbcClient(source))
}
