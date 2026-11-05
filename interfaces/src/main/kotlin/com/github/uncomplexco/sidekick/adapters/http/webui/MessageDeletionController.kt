package com.github.uncomplexco.sidekick.adapters.http.webui

import com.fasterxml.jackson.annotation.JsonProperty
import com.github.uncomplexco.sidekick.adapters.slack.DeleteSlackMessageUsecase
import com.github.uncomplexco.sidekick.adapters.slack.DeleteSlackMessageUsecase.Result
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

data class DeleteMessageRequest(@get:JsonProperty("message_url") val messageUrl: String)

data class DeletedMessage(val status: String = "deleted")

@RestController
@RequestMapping("/api/web/user/messages")
class MessageDeletionController(private val deleteMessage: DeleteSlackMessageUsecase) {
    @PostMapping("/delete")
    fun delete(
        @RequestBody request: DeleteMessageRequest,
        @AuthenticationPrincipal jwt: Jwt,
    ): ResponseEntity<DeletedMessage> {
        val link =
            SlackMessageLink.parse(request.messageUrl)
                ?: throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Paste a Slack message link",
                )
        val command =
            DeleteSlackMessageUsecase.Command(
                link.channelId,
                link.timestamp,
                checkNotNull(jwt.getClaimAsString("email")),
            )

        return when (deleteMessage.execute(command)) {
            Result.Deleted ->
                ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(DeletedMessage())
            Result.NotFound ->
                throw ResponseStatusException(HttpStatus.NOT_FOUND, "Message or channel not found")
            Result.NotAllowed ->
                throw ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "The bot cannot delete this message",
                )
            Result.RateLimited ->
                throw ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Slack rate limit reached",
                )
            Result.Unavailable ->
                throw ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Slack deletion is unavailable",
                )
        }
    }
}
