package vn.cnldg.oncam.viewer.log

import timber.log.Timber
import vn.cnldg.oncam.shared.LogSink

/** Chuyển log của module shared sang Timber. */
object TimberLogSink : LogSink {
    override fun log(level: LogSink.Level, message: String, error: Throwable?) {
        when (level) {
            LogSink.Level.DEBUG -> Timber.d(error, message)
            LogSink.Level.INFO -> Timber.i(error, message)
            LogSink.Level.WARN -> Timber.w(error, message)
            LogSink.Level.ERROR -> Timber.e(error, message)
        }
    }
}
