package com.github.uncomplexco.sidekick.application.utils

fun sanitizePathSegment(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
