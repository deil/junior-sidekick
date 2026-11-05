package com.github.uncomplexco.sidekick.adapters.http.webui

import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class WebUserProfile(val email: String)

@RestController
@RequestMapping("/api/web/user")
class UserProfileController {
    @GetMapping("/profile")
    fun profile(@AuthenticationPrincipal jwt: Jwt) =
        WebUserProfile(checkNotNull(jwt.getClaimAsString("email")))
}
