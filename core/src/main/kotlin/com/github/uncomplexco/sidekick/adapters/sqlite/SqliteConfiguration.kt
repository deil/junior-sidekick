package com.github.uncomplexco.sidekick.adapters.sqlite

import com.github.uncomplexco.sidekick.application.agent.AgentConfig
import org.flywaydb.core.Flyway
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.JdbcTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteDataSource

@Configuration
class SqliteConfiguration {
    @Bean
    fun sidekickDataSource(config: AgentConfig): SQLiteDataSource {
        val settings =
            SQLiteConfig().apply {
                setJournalMode(SQLiteConfig.JournalMode.WAL)
                setSynchronous(SQLiteConfig.SynchronousMode.FULL)
                setBusyTimeout(5000)
            }
        val dataSource =
            SQLiteDataSource(settings).apply {
                url = "jdbc:sqlite:${config.stateDirectoryPath().resolve("sidekick.db")}"
            }

        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/sidekick")
            .table("sidekick_schema_history")
            .load()
            .migrate()

        return dataSource
    }

    @Bean
    fun sidekickJdbcClient(sidekickDataSource: SQLiteDataSource): JdbcClient =
        JdbcClient.create(sidekickDataSource)

    @Bean
    fun sidekickTransactions(sidekickDataSource: SQLiteDataSource): TransactionTemplate =
        TransactionTemplate(JdbcTransactionManager(sidekickDataSource))
}
