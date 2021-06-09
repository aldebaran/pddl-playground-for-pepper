package com.softbankrobotics.pddl.pddlplaygroundforpepper.common

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.UiThread
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * Run the given function on the UI thread.
 */
fun postOnUiThread(block: () -> Unit) {
    Handler(Looper.getMainLooper()).post(block)
}

/**
 * Runs the given block in the UI thread, and waits synchronously for completion.
 */
fun <R> runBlockingOnUiThread(block: () -> R): R = runBlocking { onUiThread(block) }

/**
 * Transform a block into a function deferring it to the UI thread.
 */
suspend fun <R> onUiThread(block: () -> R): R {
    return suspendCoroutine {
        fun runBlockAndResume() {
            try {
                it.resume(block())
            } catch (t: Throwable) {
                it.resumeWithException(t)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runBlockAndResume()
        } else {
            postOnUiThread {
                runBlockAndResume()
            }
        }
    }
}

/**
 * Transform a block into a function deferring it to the UI thread.
 */
fun <R> onUiThreadAsync(block: () -> R): Deferred<R> {
    return createAsyncCoroutineScope().async { onUiThread(block) }
}

/**
 * Create a view.
 */
@UiThread
fun createView(layout: Int, context: Context): View {
    return View.inflate(context, layout, null)
}

/**
 * Switch view in a frame layout based on a layout resource.
 */
@UiThread
fun switchView(layout: Int, context: Context, frameLayout: FrameLayout) {
    frameLayout.run {
        removeAllViews()
        addView(createView(layout, context))
    }
}

/**
 * Switch view in a frame layout.
 */
@UiThread
fun switchView(view: View, frameLayout: FrameLayout) {
    frameLayout.run {
        removeAllViews()
        addView(view)
    }
}


/**
 * Switch view in a frame layout, and log an associated name.
 */
@UiThread
fun switchView(view: View, name: String, frameLayout: FrameLayout) {
    Timber.tag("UiUtil").i("Switching view to \"$name\"")
    switchView(view, frameLayout)
}
