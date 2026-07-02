package com.github.uncomplexco.sidekick.application.utils

class Redacted<T>(
    var value: T,
) {
    override fun toString(): String = "<redacted>"
}
