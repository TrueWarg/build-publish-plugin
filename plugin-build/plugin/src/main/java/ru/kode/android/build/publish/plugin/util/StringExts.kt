package ru.kode.android.build.publish.plugin.util

internal fun String.capitalize(): String {
    return this.replaceFirstChar { it.titlecase() }
}
