package app.pact.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FocusStoreTest {
    @Test
    fun urlBarTextBecomesAHost() {
        assertEquals("youtube.com", FocusStore.hostOf("youtube.com"))
        assertEquals("m.youtube.com", FocusStore.hostOf("https://m.youtube.com/watch?v=1"))
        assertEquals("youtube.com", FocusStore.hostOf("www.YouTube.com/shorts"))
        assertEquals("x.com", FocusStore.hostOf("x.com:443/home"))
    }

    @Test
    fun searchTextIsNotAHost() {
        assertNull(FocusStore.hostOf("funny cat videos"))
        assertNull(FocusStore.hostOf(""))
        assertNull(FocusStore.hostOf(null))
    }
}
