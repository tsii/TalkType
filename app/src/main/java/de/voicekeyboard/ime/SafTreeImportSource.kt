package de.voicekeyboard.ime

import android.content.ContentResolver
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import de.voicekeyboard.model.ImportSource
import java.io.IOException
import java.io.InputStream

/** SAF names are display names, never document ids. Providers may ignore SQL selection. */
internal class SafTreeImportSource(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
    private val manifestPaths: Collection<String> = emptyList(),
) : ImportSource {
    override fun open(relativePath: String): InputStream? {
        val segments = relativePath.split('/')
        require(segments.none { it.isEmpty() || it == "." || it == ".." || '\\' in it })
        val root = DocumentsContract.getTreeDocumentId(treeUri)
        val exact = resolve(root, segments)
        val file = exact ?: if (segments.size > 1) {
            val flat = findChild(root, segments.last()) ?: return null
            // Without the complete manifest, fallback cannot prove uniqueness.
            if (manifestPaths.count { it.substringAfterLast('/') == segments.last() } != 1 ||
                relativePath !in manifestPaths
            ) throw IOException("ambiguous flat basename: ${segments.last()}")
            flat
        } else return null
        if (file.directory) throw IOException("expected file: $relativePath")
        return resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(treeUri, file.id))
            ?: throw IOException("provider returned no stream: $relativePath")
    }

    private data class Document(val id: String, val directory: Boolean)

    private fun resolve(root: String, segments: List<String>): Document? {
        var parent = root
        for ((index, segment) in segments.withIndex()) {
            val child = findChild(parent, segment) ?: return null
            if (index == segments.lastIndex) return child
            if (!child.directory) throw IOException("expected directory: $segment")
            parent = child.id
        }
        return null
    }

    private fun findChild(parent: String, name: String): Document? {
        val columns = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val cursor = resolver.query(
            DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parent),
            columns, Bundle.EMPTY, null,
        ) ?: throw IOException("provider returned no directory listing")
        return cursor.use {
            val idIndex = it.getColumnIndexOrThrow(columns[0])
            val nameIndex = it.getColumnIndexOrThrow(columns[1])
            val mimeIndex = it.getColumnIndexOrThrow(columns[2])
            var found: Document? = null
            while (it.moveToNext()) {
                if (it.getString(nameIndex) != name) continue
                if (found != null) throw IOException("ambiguous display name: $name")
                found = Document(it.getString(idIndex), it.getString(mimeIndex) == DocumentsContract.Document.MIME_TYPE_DIR)
            }
            found
        }
    }
}
