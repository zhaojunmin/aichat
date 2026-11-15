package com.octos.aichat.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportedAppsTest {

    /** Derived defaults must match what earlier versions wrote, or saved settings would shift. */
    @Test fun defaultsAreUnchanged() {
        assertEquals(
            "com.tencent.mm,com.tencent.mobileqq,cn.soulapp.android,com.immomo.vchat,com.immomo.momo",
            AppSettings.DEFAULT_TARGET_PACKAGES,
        )
        assertEquals("com.tencent.mm,com.immomo.momo", AppSettings.DEFAULT_OCR_PACKAGES)
    }

    @Test fun eachAppIsReadOneWay() {
        assertEquals(SupportedApps.ALL.size, SupportedApps.PACKAGES.size) // no duplicates
        assertEquals(setOf(SupportedApps.WECHAT, SupportedApps.MOMO), SupportedApps.OCR_PACKAGES)
        for (pkg in SupportedApps.PACKAGES) {
            val treeRule = SupportedApps.nodeTreeRule(pkg)
            val isOcr = pkg in SupportedApps.OCR_PACKAGES
            assertTrue(pkg, (treeRule != null) != isOcr)
        }
    }

    @Test fun rulesReachTheReaders() {
        assertEquals("content_text", SupportedApps.nodeTreeRule(SupportedApps.SOUL)?.messageId)
        assertEquals("chat_avatar", SupportedApps.nodeTreeRule(SupportedApps.SOUL)?.avatarId)
        assertEquals(
            "tv_chat_content_self",
            SupportedApps.nodeTreeRule(SupportedApps.HERTZ)?.selfId,
        )
        assertEquals("mjn", SupportedApps.nodeTreeRule(SupportedApps.QQ)?.messageId)
        assertEquals("message_tv_layouttextview", SupportedApps.messageBoundsId(SupportedApps.MOMO))
        assertNull(SupportedApps.messageBoundsId(SupportedApps.WECHAT)) // whole-screen OCR
        assertNull(SupportedApps.of("com.example.unknown"))
    }

    @Test fun builtInsAreAlwaysIncluded_evenIfTheUserClearsTheLists() {
        val s = AppSettings(targetPackages = "", ocrPackages = "com.example.extra")
        assertEquals(SupportedApps.PACKAGES, s.targetPackageSet)
        assertEquals(SupportedApps.OCR_PACKAGES + "com.example.extra", s.ocrPackageSet)
    }
}
