package dev.owntv.launcher.actions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ServiceListTest {
    private val remote = "dev.owntv.launcher/.actions.RemoteService"
    private val talkBack = "com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService"

    @Test
    fun `short and full component names are the same service`() {
        assertEquals("dev.owntv.launcher/dev.owntv.launcher.actions.RemoteService", ServiceList.normalize(remote))
        assertEquals(talkBack, ServiceList.normalize(talkBack))
        assertTrue(ServiceList.contains("dev.owntv.launcher/dev.owntv.launcher.actions.RemoteService", remote))
    }

    @Test
    fun `empty and null settings parse to nothing`() {
        assertEquals(emptyList(), ServiceList.parse(null))
        assertEquals(emptyList(), ServiceList.parse(""))
        assertEquals(emptyList(), ServiceList.parse("null"))
        assertEquals(listOf(ServiceList.normalize(remote)), ServiceList.parse(":$remote::$remote"))
    }

    @Test
    fun `turning a service on or off keeps the others`() {
        val on = ServiceList.with(talkBack, remote, enabled = true)
        assertEquals("$talkBack:dev.owntv.launcher/dev.owntv.launcher.actions.RemoteService", on)
        assertEquals(on, ServiceList.with(on, remote, enabled = true))
        assertEquals(talkBack, ServiceList.with(on, remote, enabled = false))
        assertEquals("", ServiceList.with(remote, remote, enabled = false))
        assertFalse(ServiceList.contains(talkBack, remote))
    }
}
