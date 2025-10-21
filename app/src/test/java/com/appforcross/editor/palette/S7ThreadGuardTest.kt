package com.appforcross.editor.palette

import android.os.Looper
import com.handmadeapp.logging.Logger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

@RunWith(RobolectricTestRunner::class)
class S7ThreadGuardTest {

    private class FatalLoggerException : RuntimeException()

    @BeforeTest
    fun setUp() {
        Logger.setFatalAction { throw FatalLoggerException() }
    }

    @AfterTest
    fun tearDown() {
        Logger.setFatalAction(null)
    }

    @Test
    fun guardAllowsWorkerThreads() {
        val error = AtomicReference<Throwable?>(null)
        val worker = Thread {
            try {
                S7ThreadGuard.assertBackground("test_worker")
            } catch (t: Throwable) {
                error.set(t)
            }
        }
        worker.start()
        worker.join()
        val failure = error.get()
        if (failure != null) {
            fail("Guard threw on worker thread", failure)
        }
    }

    @Test
    fun guardCrashesOnMainThread() {
        val thrown = AtomicReference<Throwable?>(null)
        val shadowLooper = Shadows.shadowOf(Looper.getMainLooper())
        shadowLooper.runOnUiThread {
            try {
                S7ThreadGuard.assertBackground("test_main")
            } catch (t: Throwable) {
                thrown.set(t)
            }
        }
        val failure = thrown.get()
        assertTrue(failure is FatalLoggerException, "Expected fatal logger exception on main thread")
    }
}
