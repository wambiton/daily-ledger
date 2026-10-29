package com.personal.ledger

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.personal.ledger.data.*
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class AutoEntryNotificationTest {
    @Test fun pendingNotificationHasDistinctActionsAndPrivatePublicVersion() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val c=PaymentCandidate("test-notification","demo.db","demo","模拟账本","支付宝","2026-10-26",850,"expense","模拟商户","备注",false,"pending","请确认")
        AutoCapture.notify(context,c)
        val n=context.getSystemService(NotificationManager::class.java).activeNotifications.single {it.tag==c.id}.notification
        assertEquals(Notification.VISIBILITY_PRIVATE,n.visibility)
        assertEquals("解锁后查看记账消息",n.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(n.publicVersion.actions.isNullOrEmpty())
        assertEquals(3,n.actions.size)
        assertEquals(3,n.actions.map {it.actionIntent}.distinct().size)
        assertEquals(AutoEntryActions.NAME,n.actions[1].remoteInputs.single().resultKey)
        assertNotEquals(n.actions[0].actionIntent,AutoEntryActions.button(context,"another","confirm","确认").actionIntent)
        AutoCapture.notify(context,c.copy(status="booked"))
        val done=context.getSystemService(NotificationManager::class.java).activeNotifications.single {it.tag==c.id}.notification
        assertTrue(done.actions.isNullOrEmpty())
    }
}
