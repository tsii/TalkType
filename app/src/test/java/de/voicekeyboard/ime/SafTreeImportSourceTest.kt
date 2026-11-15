package de.voicekeyboard.ime

import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SafTreeImportSourceTest {
    private lateinit var provider: FakeDocumentsProvider
    private val tree = DocumentsContract.buildTreeDocumentUri("import.test", "root")

    @Before fun setup() {
        provider = FakeDocumentsProvider()
        provider.attachInfo(RuntimeEnvironment.getApplication(), ProviderInfo().apply {
            authority = "import.test"
            exported = true
            grantUriPermissions = true
            readPermission = "android.permission.MANAGE_DOCUMENTS"
            writePermission = "android.permission.MANAGE_DOCUMENTS"
        })
        ShadowContentResolver.registerProviderInternal("import.test", provider)
    }

    private fun source(vararg paths: String) = SafTreeImportSource(
        RuntimeEnvironment.getApplication().contentResolver, tree, paths.toList(),
    )

    @Test fun `filters display names locally when provider ignores selection`() {
        provider.file("root", "unrelated", "wrong")
        provider.file("root", "model.bin", "correct")
        assertEquals("correct", source("model.bin").open("model.bin")!!.bufferedReader().use { it.readText() })
        assertNull(source("absent.bin").open("absent.bin"))
    }

    @Test fun `nested exact path wins over flat basename`() {
        provider.file("root", "model.bin", "flat")
        val nested = provider.directory("root", "shared")
        provider.file(nested, "model.bin", "nested")
        assertEquals("nested", source("shared/model.bin").open("shared/model.bin")!!.bufferedReader().use { it.readText() })
    }

    @Test fun `unique flat basename is accepted when nested file absent`() {
        provider.file("root", "model.bin", "flat")
        assertEquals("flat", source("shared/model.bin").open("shared/model.bin")!!.bufferedReader().use { it.readText() })
    }

    @Test fun `flat fallback refuses manifest basename collision`() {
        provider.file("root", "model.bin", "flat")
        assertThrows(IOException::class.java) {
            source("a/model.bin", "b/model.bin").open("a/model.bin")
        }
    }

    @Test fun `duplicate provider display names are not chosen arbitrarily`() {
        provider.file("root", "model.bin", "one")
        provider.file("root", "model.bin", "two")
        assertThrows(IOException::class.java) { source("model.bin").open("model.bin") }
    }

    @Test fun `null query is IO failure not missing`() {
        provider.nullQuery = true
        assertThrows(IOException::class.java) { source("model.bin").open("model.bin") }
    }

    @Test fun `directory cannot masquerade as a file`() {
        provider.directory("root", "model.bin")
        assertThrows(IOException::class.java) { source("model.bin").open("model.bin") }
    }

    @Test fun `file cannot masquerade as a nested directory`() {
        provider.file("root", "shared", "not a directory")
        assertThrows(IOException::class.java) { source("shared/model.bin").open("shared/model.bin") }
    }

    @Test fun `unreadable exact file does not fall back to flat file`() {
        provider.file("root", "model.bin", "flat")
        val nested = provider.directory("root", "shared")
        provider.file(nested, "model.bin", "nested", unreadable = true)
        assertThrows(IOException::class.java) { source("shared/model.bin").open("shared/model.bin") }
    }

    class FakeDocumentsProvider : DocumentsProvider() {
        private data class Entry(val id: String, val parent: String, val name: String, val mime: String, val body: String, val unreadable: Boolean)
        private val entries = mutableListOf<Entry>()
        var nullQuery = false
        override fun onCreate() = true
        override fun isChildDocument(parentDocumentId: String, documentId: String): Boolean {
            var current = documentId
            while (current != "root") {
                current = entries.find { it.id == current }?.parent ?: return false
                if (current == parentDocumentId) return true
            }
            return false
        }
        fun directory(parent: String, name: String): String = add(parent, name, DocumentsContract.Document.MIME_TYPE_DIR, "", false)
        fun file(parent: String, name: String, body: String, unreadable: Boolean = false): String = add(parent, name, "application/octet-stream", body, unreadable)
        private fun add(parent: String, name: String, mime: String, body: String, unreadable: Boolean): String {
            val id = "opaque-${entries.size}"
            entries.add(Entry(id, parent, name, mime, body, unreadable))
            return id
        }
        override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(arrayOf("root_id"))
        override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor = cursor(projection, entries.filter { it.id == documentId })
        override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor? {
            if (nullQuery) return null
            return cursor(projection, entries.filter { it.parent == parentDocumentId })
        }
        private fun cursor(projection: Array<out String>?, rows: List<Entry>): Cursor {
            val columns = projection ?: arrayOf("document_id", "_display_name", "mime_type")
            return MatrixCursor(columns).apply {
                rows.forEach { e -> addRow(columns.map { column -> when (column) {
                    "document_id" -> e.id
                    "_display_name" -> e.name
                    "mime_type" -> e.mime
                    else -> null
                } }.toTypedArray()) }
            }
        }
        override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
            val entry = entries.single { it.id == documentId }
            if (entry.unreadable) throw java.io.FileNotFoundException("permission revoked")
            val file = File.createTempFile("import-provider", ".bin", context!!.cacheDir)
            file.writeText(entry.body)
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }
}
