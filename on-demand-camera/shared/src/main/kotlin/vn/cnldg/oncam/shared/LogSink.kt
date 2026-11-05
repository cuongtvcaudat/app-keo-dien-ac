package vn.cnldg.oncam.shared

/**
 * Cổng ghi log cho module shared (JVM thuần, không dùng được Timber trực tiếp).
 * Hai app Android truyền vào một adapter chuyển sang Timber.
 */
fun interface LogSink {
    /** Ghi một dòng log. */
    fun log(level: Level, message: String, error: Throwable?)

    /** Mức log. */
    enum class Level { DEBUG, INFO, WARN, ERROR }

    companion object {
        /** Bỏ qua mọi log. */
        val NONE: LogSink = LogSink { _, _, _ -> }
    }
}

internal fun LogSink.d(message: String) = log(LogSink.Level.DEBUG, message, null)
internal fun LogSink.i(message: String) = log(LogSink.Level.INFO, message, null)
internal fun LogSink.w(message: String, error: Throwable? = null) = log(LogSink.Level.WARN, message, error)
