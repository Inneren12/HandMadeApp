package com.handmadeapp.ui.importer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ImportActivityS7JobTest {

    @Test
    fun cancelsPreviousJobBeforeStartingNewOne() = runBlocking {
        val controller = Robolectric.buildActivity(ImportActivity::class.java)
            .create().start().resume().visible()
        val activity = controller.get()

        val firstStarted = CompletableDeferred<Unit>()
        val firstCancelled = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()

        activity.launchS7Job("job1") {
            firstStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                if (!firstCancelled.isCompleted) {
                    firstCancelled.complete(Unit)
                }
            }
        }

        firstStarted.await()

        activity.launchS7Job("job2") {
            assertTrue(firstCancelled.isCompleted, "Previous job must cancel before new job starts")
            secondStarted.complete(Unit)
        }

        secondStarted.await()
        firstCancelled.await()
        activity.awaitS7IdleForTest()
        controller.pause().stop().destroy()
    }
}
