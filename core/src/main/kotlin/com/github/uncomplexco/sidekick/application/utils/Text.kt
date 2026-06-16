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
