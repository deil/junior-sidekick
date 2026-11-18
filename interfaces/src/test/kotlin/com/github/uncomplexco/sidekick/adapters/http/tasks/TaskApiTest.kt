package com.github.uncomplexco.sidekick.adapters.http.tasks

import com.github.uncomplexco.sidekick.application.agent.AgentRunResult
import com.github.uncomplexco.sidekick.application.runtime.SidekickCoroutineScope
import com.github.uncomplexco.sidekick.application.stats.AgentUsageStats
import com.github.uncomplexco.sidekick.application.tools.subagents.PublicTaskRuns
import com.github.uncomplexco.sidekick.application.tools.subagents.Subagents
import com.github.uncomplexco.sidekick.testing.TaskApiTestRunner
import com.jayway.jsonpath.JsonPath
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.mock.web.MockServletContext
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.support.TestPropertySourceUtils
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext
import org.springframework.web.servlet.config.annotation.EnableWebMvc

private const val PATH = "/api/internal/tasks"
private const val OWNER_KEY = "owner-key"
private const val ROTATED_KEY = "rotated-key"
private const val OTHER_KEY = "other-key"

@OptIn(ExperimentalCoroutinesApi::class)
class TaskApiTest {
    private lateinit var context: AnnotationConfigWebApplicationContext
    private lateinit var mvc: MockMvc
    private val scope = TestScope()
    private val taskScope = SidekickCoroutineScope(scope)

    @BeforeEach
    fun setup() {
        context =
            AnnotationConfigWebApplicationContext().apply {
                servletContext = MockServletContext()
                TestPropertySourceUtils.addInlinedPropertiesToEnvironment(
                    this,
                    "sidekick.task-api.clients.ci.key-hashes[0]=${hash(OWNER_KEY)}",
                    "sidekick.task-api.clients.ci.key-hashes[1]=${hash(ROTATED_KEY)}",
                    "sidekick.task-api.clients.other.key-hashes[0]=${hash(OTHER_KEY)}",
                )
                beanFactoryRegistration()
                refresh()
            }
        mvc =
            MockMvcBuilders.webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
    }

    @AfterEach
    fun close() {
        taskScope.close()
        scope.runCurrent()
        context.close()
    }

    @Test
    fun `submission returns an internal polling URL before executing the subagent`() {
        val response = submit(OWNER_KEY, """{"prompt":"Find the answer"}""")

        response.andExpect {
            status { isAccepted() }
            jsonPath("$.run_id") { isString() }
        }
        val submitted = response.andReturn().response
        val runId = JsonPath.read<String>(submitted.contentAsString, "$.run_id")
        assertEquals("$PATH/$runId", submitted.getHeader("Location"))
        assertTrue(context.getBean(TaskApiTestRunner::class.java).requests.isEmpty())
    }

    @Test
    fun `queued task is visible to its owner`() {
        val location = submitTask()

        mvc.get(location) { header("Authorization", "Bearer $OWNER_KEY") }
            .andExpect { jsonPath("$.status") { value("queued") } }
    }

    @Test
    fun `running task has no result yet`() {
        val location = submitTask()
        scope.runCurrent()

        mvc.get(location) { header("Authorization", "Bearer $OWNER_KEY") }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("running") }
                jsonPath("$.result") { doesNotExist() }
            }
    }

    @Test
    fun `rotated key can poll a completed task owned by the same client`() {
        val location = submitTask()
        val runner = context.getBean(TaskApiTestRunner::class.java)
        runner.result.complete(AgentRunResult.Completed("42", AgentUsageStats()))
        scope.runCurrent()

        mvc.get(location) { header("Authorization", "Bearer $ROTATED_KEY") }
            .andExpect {
                status { isOk() }
                header { string("Cache-Control", "no-store") }
                jsonPath("$.status") { value("completed") }
                jsonPath("$.result") { value("42") }
            }
        assertEquals(listOf("general" to "Find the answer"), runner.requests)
    }

    @Test
    fun `failed task does not return the runner exception to the client`() {
        val location = submitTask()
        context
            .getBean(TaskApiTestRunner::class.java)
            .result
            .complete(
                AgentRunResult.Failed(IllegalStateException("provider details"), AgentUsageStats())
            )
        scope.runCurrent()

        mvc.get(location) { header("Authorization", "Bearer $OWNER_KEY") }
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("failed") }
                jsonPath("$.error") { value("Task execution failed") }
                jsonPath("$.result") { doesNotExist() }
            }
    }

    @Test
    fun `another client cannot poll a known task ID`() {
        val location = submitTask()

        mvc.get(location) { header("Authorization", "Bearer $OTHER_KEY") }
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `polling requires an API key`() {
        mvc.get("$PATH/${UUID.randomUUID()}").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `submission rejects an invalid key`() {
        submit("unknown-key", """{"prompt":"Find the answer"}""").andExpect {
            status { isUnauthorized() }
        }
    }

    @Test
    fun `submission rejects an unknown subagent type`() {
        submit(OWNER_KEY, """{"prompt":"Find the answer","subagent_type":"unknown"}""").andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `submission rejects a blank prompt`() {
        submit(OWNER_KEY, """{"prompt":" "}""").andExpect { status { isBadRequest() } }
    }

    private fun submit(key: String, body: String) =
        mvc.post(PATH) {
            header("Authorization", "Bearer $key")
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun submitTask(): String =
        checkNotNull(
            submit(OWNER_KEY, """{"prompt":"Find the answer"}""")
                .andExpect { status { isAccepted() } }
                .andReturn()
                .response
                .getHeader("Location")
        )

    private fun AnnotationConfigWebApplicationContext.beanFactoryRegistration() {
        addBeanFactoryPostProcessor {
            it.registerSingleton("sidekickCoroutineScope", taskScope)
        }
        register(Config::class.java)
    }

    private fun hash(key: String): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.toByteArray()))

    @EnableWebMvc
    @EnableWebSecurity
    @Import(TaskApiController::class, TaskApiSecurity::class, PublicTaskRuns::class)
    class Config {
        @Bean fun subagents() = Subagents()

        @Bean fun runner() = TaskApiTestRunner()
    }
}
