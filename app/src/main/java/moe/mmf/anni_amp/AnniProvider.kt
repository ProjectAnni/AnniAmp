package moe.mmf.anni_amp

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.database.MatrixCursor.RowBuilder
import android.graphics.Point
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import androidx.room.Room
import com.maxmpz.poweramp.player.TrackProviderConsts
import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.*
import io.ktor.client.features.json.*
import io.ktor.client.request.*
import io.ktor.http.*
import kotlinx.coroutines.runBlocking
import moe.mmf.anni_amp.repo.RepoDatabase
import moe.mmf.anni_amp.repo.entities.Album
import moe.mmf.anni_amp.repo.entities.Track

class AnniProvider : DocumentsProvider() {
    private lateinit var client: HttpClient
    private lateinit var db: RepoDatabase

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
                header("Authorization", Companion.Token)
            }
        }
        this.db = Room.databaseBuilder(
            context!!,
            RepoDatabase::class.java, "anni-db2",
        ).build()
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

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val result = MatrixCursor(resolveRootProjection(projection))

        result.newRow().apply {
            add(DocumentsContract.Root.COLUMN_ROOT_ID, "Anni-Root")
            add(DocumentsContract.Root.COLUMN_TITLE, "Annil")
            add(DocumentsContract.Root.COLUMN_SUMMARY, "Yesterday17's Annil Library")

            add(DocumentsContract.Root.COLUMN_FLAGS, DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD)
            add(DocumentsContract.Root.COLUMN_ICON, R.mipmap.ic_launcher)
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
    ): Cursor {
        return MatrixCursor(resolveDocumentProjection(projection)).apply {
            if (parentDocumentId.equals("annil")) {
                // root
                val response: List<String> = runBlocking {
                    client.get(path = "albums")
                }
                for (catalog in response) {
                    val album = db.albumDao().getAlbum(catalog)
                    if (album != null) {
                        fillAlbumRow(
                            this.newRow(),
                            album,
                            TrackProviderConsts.FLAG_NO_SUBDIRS
                        )
                    }
                }
            } else if (parentDocumentId != null) {
                // parentDocumentId is catalog
                val tracks = db.trackDao().getAllTracks(parentDocumentId.split("/")[1])
                tracks?.forEach {
                    fillURLTrackRow(this.newRow(), it, false)
                }
            }
        }
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        return MatrixCursor(resolveDocumentProjection(projection)).apply {
            if (documentId.equals("annil")) {
                fillFolderRow(this.newRow(), documentId, documentId, TrackProviderConsts.FLAG_HAS_SUBDIRS)
            } else if (!documentId.contains("/")) {
                val album = db.albumDao().getAlbum(documentId)
                if (album != null) {
                    fillAlbumRow(this.newRow(), album, TrackProviderConsts.FLAG_HAS_SUBDIRS)
                }
            } else {
                val splited = documentId.split("/")
                val catalog = splited[1]
                if (splited.size == 2) {
                    // annil/{catalog}
                    val album = db.albumDao().getAlbum(catalog)
                    if (album != null) {
                        fillAlbumRow(this.newRow(), album, TrackProviderConsts.FLAG_HAS_SUBDIRS)
                    }
                } else if (splited.size == 3) {
                    val trackNumber = splited[2].toInt()
                    val track = db.trackDao().getTrack(catalog, trackNumber)
                    if (track != null) {
                        fillURLTrackRow(this.newRow(), track, true)
                    }
                }
            }
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

    private fun fillAlbumRow(row: RowBuilder, album: Album, flags: Int) {
        fillFolderRow(row, "annil/${album.catalog}", album.title, flags)
    }

    private fun fillFolderRow(row: RowBuilder, id: String, name: String, flags: Int) {
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0)
        row.add(
            DocumentsContract.Document.COLUMN_FLAGS,
            DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL
        )
        // If asked to add the subfolders hint, add it
        if (flags != 0) {
            row.add(TrackProviderConsts.COLUMN_FLAGS, flags)
        }
    }

    private fun fillTrackRow(row: RowBuilder, track: Track) {
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, "annil/${track.catalog}/${track.track}")
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, "audio/flac")
        row.add(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            "%02d. %s.flac".format(track.track, track.title)
        )
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0)
    }

    private fun fillURLTrackRow(row: RowBuilder, track: Track, sendMetadata: Boolean) {
        fillTrackRow(row, track)

//        row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL)
        row.add(TrackProviderConsts.COLUMN_URL, TrackProviderConsts.DYNAMIC_URL)
        row.add(MediaStore.Audio.AudioColumns.DURATION, 0)

        if (sendMetadata) {
            val album = db.albumDao().getAlbum(track.catalog)!!
            row.add(MediaStore.MediaColumns.TITLE, track.title)
            row.add(MediaStore.Audio.AudioColumns.ARTIST, track.artist)
            row.add(MediaStore.Audio.AudioColumns.ALBUM, album.title)
            row.add(MediaStore.Audio.AudioColumns.YEAR, album.releaseDate)
            row.add(TrackProviderConsts.COLUMN_ALBUM_ARTIST, album.artist)
            row.add(MediaStore.Audio.AudioColumns.TRACK, track.track)
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

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val res = super.call(method, arg, extras)
        if (res == null) {
            if (TrackProviderConsts.CALL_GET_URL == method) {
                return handleGetUrl(arg, extras)
//            } else if (TrackProviderConsts.CALL_RESCAN == method) {
//                return handleRescan(arg, extras)
//            } else if (TrackProviderConsts.CALL_GET_DIR_METADATA == method) {
//                return handleGetDirMetadata(arg, extras)
            }
        }
        return res
    }

    private fun handleGetUrl(arg: String?, extras: Bundle?): Bundle? {
        require(!TextUtils.isEmpty(arg))

        val res = Bundle()
        val splited = arg!!.split("%2F")
        val url = "${Companion.BaseUrl}/${splited[1]}/${splited[2]}"
        res.putString(TrackProviderConsts.COLUMN_URL, url)
        res.putString(TrackProviderConsts.COLUMN_HEADERS, "Authorization:${Companion.Token}")
        return res
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        return true
    }

    companion object {
        private const val BaseUrl: String = "http://10.0.2.2:3614"
        private const val Token: String =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpYXQiOjAsInR5cGUiOiJ1c2VyIiwidXNlcm5hbWUiOiJ0ZXN0IiwiYWxsb3dTaGFyZSI6dHJ1ZX0.7CH27OBvUnJhKxBdtZbJSXA-JIwQ4MWqI5JsZ46NoKk"
    }
}