package app.codeg.android.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The idea inbox store against a real Preferences DataStore (the ServerStoreTest
 * recipe). 批v160: the multi-select batch delete must drop exactly the named ids
 * in ONE JSON rewrite and keep every other row — including the attachment-bearing
 * drafts a naive "re-encode what's on screen" would clobber.
 */
class IdeaStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
    }

    private fun store(): IdeaStore {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob()).also { scopes += it }
        return IdeaStore(PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("idea-${scopes.size}.pb") })
    }

    private fun idea(id: String, updated: Long) = IdeaEntry(id = id, title = id, updatedAt = updated)

    @Test
    fun `removeIds drops exactly the named rows and keeps the rest`() = runBlocking {
        val store = store()
        store.upsert(idea("a", 3))
        store.upsert(idea("b", 2))
        store.upsert(idea("c", 1))
        // "ghost" doesn't exist — must be ignored, not fatal.
        store.removeIds(listOf("a", "ghost", "c"))
        assertEquals(listOf("b"), store.ideas.first().map { it.id })
    }

    @Test
    fun `removeIds with nothing selected is a no-op`() = runBlocking {
        val store = store()
        store.upsert(idea("a", 1))
        store.removeIds(emptyList())
        assertEquals(listOf("a"), store.ideas.first().map { it.id })
    }
}
