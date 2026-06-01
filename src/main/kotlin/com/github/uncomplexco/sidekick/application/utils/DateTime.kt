package com.github.uncomplexco.sidekick.application.utils

import java.time.Instant.ofEpochMilli

fun timestamp(millis: Long) = ofEpochMilli(millis).toString()
