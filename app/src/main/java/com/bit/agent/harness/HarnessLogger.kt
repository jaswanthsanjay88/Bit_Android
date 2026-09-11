package com.bit.agent.harness

/**
 * Logging boundary for the Agent Harness. Abstracts android.util.Log so harness
 * classes remain unit-testable on the plain JVM (Log calls throw "not mocked" there).
 */
interface HarnessLogger {
    fun d(tag: String, message: String)
    fun w(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
}

/** Production logger backed by android.util.Log (Android runtime only). */
class AndroidHarnessLogger : HarnessLogger {
    override fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
    }

    override fun w(tag: String, message: String) {
        android.util.Log.w(tag, message)
    }

    override fun e(tag: String, message: String, throwable: Throwable?) {
        if (throwable != null) android.util.Log.e(tag, message, throwable)
        else android.util.Log.e(tag, message)
    }
}

/** Silent logger used in JVM unit tests and as a safe default. */
object NoOpHarnessLogger : HarnessLogger {
    override fun d(tag: String, message: String) {}
    override fun w(tag: String, message: String) {}
    override fun e(tag: String, message: String, throwable: Throwable?) {}
}
