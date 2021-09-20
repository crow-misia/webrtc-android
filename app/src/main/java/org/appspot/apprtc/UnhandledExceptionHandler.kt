/*
 *  Copyright 2013 The WebRTC Project Authors. All rights reserved.
 *
 *  Use of this source code is governed by a BSD-style license
 *  that can be found in the LICENSE file in the root of the source
 *  tree. An additional intellectual property rights grant can be found
 *  in the file PATENTS.  All contributing project authors may
 *  be found in the AUTHORS file in the root of the source tree.
 */
package org.appspot.apprtc

import android.content.DialogInterface
import android.util.TypedValue
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import timber.log.Timber
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.system.exitProcess

/**
 * Singleton helper: install a default unhandled exception handler which shows
 * an informative dialog and kills the app.  Useful for apps whose
 * error-handling consists of throwing RuntimeExceptions.
 * NOTE: almost always more useful to
 * Thread.setDefaultUncaughtExceptionHandler() rather than
 * Thread.setUncaughtExceptionHandler(), to apply to background threads as well.
 */
class UnhandledExceptionHandler(private val activity: AppCompatActivity) :
    Thread.UncaughtExceptionHandler {
    override fun uncaughtException(unusedThread: Thread, e: Throwable) {
        activity.runOnUiThread {
            val title = "Fatal error: " + getTopLevelCauseMessage(e)
            val msg = getRecursiveStackTrace(e)
            val errorView = TextView(activity)
            errorView.text = msg
            errorView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 8f)
            val scrollingContainer = ScrollView(activity)
            scrollingContainer.addView(errorView)
            Timber.e("%s\n\n%s\n", title, msg)
            val listener = DialogInterface.OnClickListener { dialog, _ ->
                dialog.dismiss()
                exitProcess(1)
            }
            val builder = AlertDialog.Builder(activity)
            builder.setTitle(title)
                .setView(scrollingContainer)
                .setPositiveButton("Exit", listener)
                .show()
        }
    }

    companion object {
        // Returns the Message attached to the original Cause of `t`.
        private fun getTopLevelCauseMessage(t: Throwable): String? {
            var topLevelCause: Throwable = t
            while (true) {
                val cause = topLevelCause.cause ?: break
                topLevelCause = cause
            }
            return topLevelCause.message
        }

        // Returns a human-readable String of the stacktrace in `t`, recursively
        // through all Causes that led to `t`.
        private fun getRecursiveStackTrace(t: Throwable): String {
            val writer = StringWriter()
            t.printStackTrace(PrintWriter(writer))
            return writer.toString()
        }
    }

}