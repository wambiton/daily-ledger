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

    @Test fun suggestionDetailsUpdateWithoutLeakingIntoPublicNotification() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val c=PaymentCandidate("details","demo.db","demo","模拟账本","招商银行短信","2026-10-26",850,"expense","模拟商户","备注",false,"pending","首次确认",channel="支付宝",suggestedTitle="早餐",transactionAt=1792971000000)
        val manager=context.getSystemService(NotificationManager::class.java)
        AutoCapture.notify(context,c,"餐饮")
        val n=manager.activeNotifications.single {it.tag==c.id}.notification
        assertEquals("早餐 · ¥8.50",n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("支付宝 · 餐饮 · 支出"))
        assertFalse(n.publicVersion.extras.toString().contains("早餐"))
        assertFalse(n.publicVersion.extras.toString().contains("8.50"))
        AutoCapture.notify(context,c.copy(suggestedTitle="午餐"),"餐饮")
        assertEquals("午餐 · ¥8.50",manager.activeNotifications.single {it.tag==c.id}.notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }
    @Test fun incompleteAmountsAreNotPresentedAsZero() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val c=PaymentCandidate("missing","demo.db","demo","模拟账本","微信","2026-10-26",null,null,"","",false,"pending","请核对")
        AutoCapture.notify(context,c)
        val n=context.getSystemService(NotificationManager::class.java).activeNotifications.single {it.tag==c.id}.notification
        assertEquals("待确认账目 · 金额待核对",n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertTrue(n.extras.getCharSequence(Notification.EXTRA_TEXT).toString().contains("分类待确认 · 收支待核对 · 时间待核对"))
    }

    @Test fun savedNotificationsRouteRevokeAndHideActionsOnPublicVersion() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val manager=context.getSystemService(NotificationManager::class.java)
        val c=PaymentCandidate("saved","demo.db","a","模拟账本","支付宝","2026-10-26",850,"expense","商户","",false,"booked","",entryId="entry")
        for(status in listOf("booked","merged")) {
            AutoCapture.notify(context,c.copy(status=status))
            val n=manager.activeNotifications.single {it.tag==c.id}.notification
            assertEquals(1,n.actions.size)
            assertEquals("撤销入账",n.actions.single().title.toString())
            assertEquals(AutoEntryActions.button(context,c.id,"revoke","撤销入账").actionIntent,n.actions.single().actionIntent)
            assertNotEquals(AutoEntryActions.button(context,"other","revoke","撤销入账").actionIntent,n.actions.single().actionIntent)
            assertTrue(n.actions.single().remoteInputs.isNullOrEmpty())
            assertEquals(Notification.VISIBILITY_PRIVATE,n.visibility)
            assertTrue(n.publicVersion.actions.isNullOrEmpty())
        }
        AutoCapture.notify(context,c.copy(status="revoked"))
        assertTrue(manager.activeNotifications.none {it.tag==c.id})
    }

    @Test fun resultSwitchClearsOnlySavedAlertsAndKeepsPendingActions() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val manager=context.getSystemService(NotificationManager::class.java)
        val pending=PaymentCandidate("review-setting","demo.db","a","模拟","支付宝","2026-10-26",850,"expense","商户","",false,"pending","")
        val saved=pending.copy(id="saved-setting",status="booked",entryId="entry")
        try {
            AutoCapture.setResultNotifications(context,true)
            AutoCapture.notify(context,pending);AutoCapture.notify(context,saved)
            assertEquals(NotificationManager.IMPORTANCE_LOW,manager.getNotificationChannel(AutoCapture.RESULT_CHANNEL).importance)
            assertEquals(AutoCapture.RESULT_CHANNEL,manager.activeNotifications.single {it.tag==saved.id}.notification.channelId)
            AutoCapture.setResultNotifications(context,false)
            assertTrue(manager.activeNotifications.none {it.tag==saved.id})
            assertEquals(3,manager.activeNotifications.single {it.tag==pending.id}.notification.actions.size)
            AutoCapture.notify(context,saved.copy(status="merged"))
            assertTrue(manager.activeNotifications.none {it.tag==saved.id})
            AutoCapture.notify(context,pending.copy(suggestedTitle="早餐"))
            assertEquals(3,manager.activeNotifications.single {it.tag==pending.id}.notification.actions.size)
            AutoCapture.setResultNotifications(context,true)
            AutoCapture.notify(context,saved)
            assertEquals(1,manager.activeNotifications.single {it.tag==saved.id}.notification.actions.size)
        } finally {AutoCapture.prefs(context).edit().remove("result_notifications").commit()}
    }
    @Test fun disablingResultsDoesNotLeaveAConfirmedCandidateLookingPending() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val manager=context.getSystemService(NotificationManager::class.java)
        val c=PaymentCandidate("transition-setting","demo.db","a","模拟","支付宝","2026-10-26",850,"expense","商户","",false,"pending","")
        try {
            AutoCapture.notify(context,c)
            AutoCapture.setResultNotifications(context,false)
            AutoCapture.notify(context,c.copy(status="booked",entryId="entry"))
            assertTrue(manager.activeNotifications.none {it.tag==c.id})
            assertFalse(AutoCapture.resultNotificationsEnabled(context))
        } finally {AutoCapture.prefs(context).edit().remove("result_notifications").commit()}
    }
}
