package com.github.uncomplexco.sidekick.application.utils

fun escapeXml(text: String) =
    text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

fun xmlTag(
    tag: String,
    text: String,
) = "<$tag>\n$text\n</$tag>"

fun markdownSection(
    title: String,
    text: String,
) = "# $title\n\n$text\n\n"

fun trimStart(
    text: String,
    length: Int,
    prefix: String? = "[older context omitted] ",
): String {
    if (text.length <= length) return text

    return if (prefix != null) {
        prefix.take(length) + text.takeLast((length - prefix.length).coerceAtLeast(0))
    } else {
        text.takeLast(length)
    }
}

fun trimEnd(
    text: String,
    length: Int,
    suffix: String? = "...",
): String {
    if (text.length <= length) return text

    return if (suffix != null) {
        text.take((length - suffix.length).coerceAtLeast(0)) + suffix.takeLast(length)
    } else {
        text.substring(0, length)
    }
}
