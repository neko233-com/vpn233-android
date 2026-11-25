package com.neko233.vpn233

import com.neko233.vpn233.service.LifecycleRequests
import com.neko233.vpn233.service.LifecycleRequests.Command
import com.neko233.vpn233.service.LifecycleRequests.Work
import org.junit.Assert.*
import org.junit.Test

class LifecycleRequestsTest {
    @Test
    fun reloadDuringLoadIsNotLostAndBurstsAreCoalesced() {
        val requests = LifecycleRequests()
        assertTrue(requests.request(Command.START))
        assertEquals(Work.LOAD, requests.next())
        assertFalse(requests.request(Command.RELOAD))
        assertFalse(requests.request(Command.RELOAD))
        assertTrue(requests.hasPendingStart())
        var running = false
        assertFalse(requests.finishLoad { running = true })
        assertFalse(running)
        assertEquals(Work.LOAD, requests.next())
        assertTrue(requests.finishLoad { running = true })
        assertTrue(running)
        assertNull(requests.next())
        assertTrue(requests.request(Command.RELOAD))
    }

    @Test
    fun lastStartOrStopWinsAndStoppedServiceIgnoresReload() {
        val requests = LifecycleRequests()
        assertFalse(requests.request(Command.RELOAD))
        assertTrue(requests.request(Command.START))
        assertFalse(requests.request(Command.STOP))
        assertEquals(Work.STOP, requests.next())
        assertFalse(requests.wantsRunning())
        assertNull(requests.next())
        assertTrue(requests.request(Command.START))
        assertEquals(Work.LOAD, requests.next())
        assertFalse(requests.request(Command.STOP))
        assertFalse(requests.request(Command.RELOAD))
        assertFalse(requests.request(Command.START))
        assertEquals(Work.LOAD, requests.next())
        assertNull(requests.next())
    }
}
