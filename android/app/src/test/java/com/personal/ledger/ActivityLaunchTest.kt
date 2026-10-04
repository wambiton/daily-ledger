package com.personal.ledger

import android.view.ViewGroup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Runs onCreate and the Compose host lifecycle; does not replace device installation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ActivityLaunchTest {
    @Test fun mainActivityStartsAndAttachesContent() {
        // Robolectric does not run AndroidX Startup's provider as a real device does.
        androidx.work.WorkManager.initialize(
            androidx.test.core.app.ApplicationProvider.getApplicationContext(),
            androidx.work.Configuration.Builder().build())
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertFalse(activity.isFinishing)
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            assertTrue("Compose content must be attached", content.childCount > 0)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
