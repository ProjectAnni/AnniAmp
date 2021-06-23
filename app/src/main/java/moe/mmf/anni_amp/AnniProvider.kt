package moe.mmf.anni_amp

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.database.MatrixCursor.RowBuilder
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import com.maxmpz.poweramp.player.TrackProviderConsts
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.*
import io.ktor.client.features.json.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking

class AnniProvider : DocumentsProvider() {
    private lateinit var client: HttpClient

    override fun onCreate(): Boolean {
        this.client = HttpClient(CIO) {
            install(JsonFeature) {
                serializer = GsonSerializer()
            }
            defaultRequest {
                host = "10.0.2.2"
                port = 3614
                url {
                    protocol = URLProtocol.HTTP
                }
                headers {
                    // TODO: replace debug token
                    this.append(
                        "Authorization",
                        "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpYXQiOjAsInR5cGUiOiJ1c2VyIiwidXNlcm5hbWUiOiJ0ZXN0IiwiYWxsb3dTaGFyZSI6dHJ1ZX0.7CH27OBvUnJhKxBdtZbJSXA-JIwQ4MWqI5JsZ46NoKk"
                    )
                }
            }
        }
        return true
    }

    /////////////////////////////////////////////////////////////////////////
    // ROOT
    private val DEFAULT_ROOT_PROJECTION = arrayOf(
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_SUMMARY,
        DocumentsContract.Root.COLUMN_FLAGS,
        DocumentsContract.Root.COLUMN_ICON,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID
    )

    override fun queryRoots(projection: Array<out String>?): Cursor? {
        val result = MatrixCursor(resolveRootProjection(projection))

        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, "Anni-Root")
            add(DocumentsContract.Root.COLUMN_TITLE, "Annil")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "Yesterday17's Annil Library")

            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
            add(DocumentsContract.Root.COLUMN_ICON, R.drawable.ic_launcher_foreground)
            add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, "annil")
        }

        return result
    }

    private fun resolveRootProjection(projection: Array<out String>?): Array<out String> {
        return projection ?: DEFAULT_ROOT_PROJECTION
    }

    /////////////////////////////////////////////////////////////////////////
    // Provide files in folders
    override fun queryChildDocuments(
        parentDocumentId: String?,
        projection: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        return MatrixCursor(resolveDocumentProjection(projection)).apply {
            if (parentDocumentId.equals("annil")) {
                // root
                val response: List<String> = runBlocking {
                    // TODO: Specify host
                    client.get(path = "albums")
                }
                response.map { fillFolderRow(it, this.newRow(), TrackProviderConsts.FLAG_NO_SUBDIRS) }
            } else {
                // TODO: show tracks from metadata repository
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor? {
        return MatrixCursor(resolveDocumentProjection(projection)).apply {
            fillFolderRow(documentId, this.newRow(), TrackProviderConsts.FLAG_HAS_SUBDIRS)
        }
    }

    private val DEFAULT_DOCUMENT_PROJECTION = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE
    )

    private fun resolveDocumentProjection(projection: Array<out String>?): Array<out String> {
        return projection ?: DEFAULT_DOCUMENT_PROJECTION
    }

    private fun fillFolderRow(documentId: String, row: RowBuilder, flags: Int) {
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId)
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, documentId)
//        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, mApkInstallTime)
        row.add(
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
        )
        // If asked to add the subfolders hint, add it
        if (flags != 0) {
            row.add(TrackProviderConsts.COLUMN_FLAGS, flags)
        }
    }

    override fun openDocumentThumbnail(
        documentId: String?,
        sizeHint: Point?,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        return super.openDocumentThumbnail(documentId, sizeHint, signal)
    }

    override fun openDocument(documentId: String?, mode: String?, signal: CancellationSignal?): ParcelFileDescriptor? {
        return null
    }
}