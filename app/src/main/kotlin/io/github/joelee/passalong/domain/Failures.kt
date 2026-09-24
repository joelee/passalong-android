package io.github.joelee.passalong.domain

import kotlinx.coroutines.CancellationException

/**
 * One line about a failure for the status line or the error field: the message when there is
 * one, otherwise the exception's name, since a blank status would hide that anything went wrong.
 */
fun Throwable.describe(): String = message?.takeIf { it.isNotBlank() } ?: toString()

/**
 * Throws a [CancellationException] on, since it is how a coroutine is stopped, not a failure to
 * report; returns any other throwable for reporting.
 */
fun Throwable.rethrowCancellation(): Throwable {
    if (this is CancellationException) throw this
    return this
}
