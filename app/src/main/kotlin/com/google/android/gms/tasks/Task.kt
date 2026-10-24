package com.google.android.gms.tasks

fun interface OnSuccessListener<in TResult> {
    fun onSuccess(result: TResult)
}

fun interface OnFailureListener {
    fun onFailure(e: Exception)
}

fun interface OnCanceledListener {
    fun onCanceled()
}

/**
 * The gms Task as ScreenReader uses it: one result or one failure, delivered to every listener
 * whether it was added before or after. A listener runs on the thread that completes the task, or
 * at once on its own thread if the task is already complete; gms posts them to the main thread,
 * and the coroutine that waits on them resumes on its own dispatcher either way.
 *
 * Nothing here cancels a task, so canceled listeners never run.
 */
class Task<TResult> internal constructor() {
    private var outcome: Result<TResult>? = null
    private val waiting = mutableListOf<(Result<TResult>) -> Unit>()

    fun addOnSuccessListener(listener: OnSuccessListener<TResult>): Task<TResult> =
        whenComplete { it.onSuccess(listener::onSuccess) }

    fun addOnFailureListener(listener: OnFailureListener): Task<TResult> =
        whenComplete { it.onFailure { e -> listener.onFailure(e as Exception) } }

    @Suppress("UNUSED_PARAMETER")
    fun addOnCanceledListener(listener: OnCanceledListener): Task<TResult> = this

    internal fun setResult(value: TResult) = finish(Result.success(value))

    internal fun setException(e: Exception) = finish(Result.failure(e))

    private fun whenComplete(action: (Result<TResult>) -> Unit): Task<TResult> {
        val done = synchronized(this) { outcome.also { if (it == null) waiting += action } }
        done?.let(action)
        return this
    }

    private fun finish(result: Result<TResult>) {
        val listeners = synchronized(this) {
            check(outcome == null) { "Task is already complete" }
            outcome = result
            waiting.toList().also { waiting.clear() }
        }
        listeners.forEach { it(result) }
    }
}
