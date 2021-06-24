package moe.mmf.anni_amp

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.database.MatrixCursor.RowBuilder
import android.graphics.Point
import android.os.*
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.provider.MediaStore
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.text.TextUtils
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.room.Room
import com.maxmpz.poweramp.player.TrackProviderConsts
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.features.*
import io.ktor.client.features.json.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import kotlinx.coroutines.runBlocking
import moe.mmf.anni_amp.repo.RepoDatabase
import moe.mmf.anni_amp.repo.entities.Album
import moe.mmf.anni_amp.repo.entities.Cache
import moe.mmf.anni_amp.repo.entities.Track
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import kotlin.concurrent.thread

class AnniProvider : DocumentsProvider() {
    private lateinit var client: HttpClient
    private lateinit var db: RepoDatabase

    override fun onCreate(): Boolean {
        this.client = HttpClient(CIO) {
            install(JsonFeature) {
                serializer = GsonSerializer()
            }
            defaultRequest {
//                host = "annil.mmf.moe"
                host = "192.168.114.1"
                port = 3614
                url {
                    protocol = URLProtocol.HTTP
                }
                header("Authorization", Token)
            }
        }
        this.db = Room.databaseBuilder(
            context!!,
            RepoDatabase::class.java, "anni-db",
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
                val tracks = db.trackDao().getAllTracks(parentDocumentId)
                tracks?.forEach {
                    fillTrackRow(this.newRow(), it, false)
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
                val catalog = splited[0]
                val trackNumber = splited[1].toInt()
                val track = db.trackDao().getTrack(catalog, trackNumber)
                if (track != null) {
                    fillTrackRow(this.newRow(), track, true)
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
        fillFolderRow(row, album.catalog, album.title, flags)
    }

    private fun fillFolderRow(row: RowBuilder, id: String, name: String, flags: Int) {
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, id)
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.MIME_TYPE_DIR)
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, name)
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0)
        row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL)
        // If asked to add the subfolders hint, add it
        if (flags != 0) {
            row.add(TrackProviderConsts.COLUMN_FLAGS, flags)
        }
    }

    private fun fillTrackRow(row: RowBuilder, track: Track, sendMetadata: Boolean) {
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, "${track.catalog}/${track.track}")
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, "audio/flac")
        row.add(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            "%02d. %s.flac".format(track.track, track.title)
        )
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, 0)

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

    private fun fillURLTrackRow(row: RowBuilder, track: Track, sendMetadata: Boolean) {
        fillTrackRow(row, track, sendMetadata)

        row.add(DocumentsContract.Document.COLUMN_FLAGS, DocumentsContract.Document.FLAG_SUPPORTS_THUMBNAIL)
        row.add(TrackProviderConsts.COLUMN_URL, TrackProviderConsts.DYNAMIC_URL)
        row.add(MediaStore.Audio.AudioColumns.DURATION, 0)
    }

    override fun openDocumentThumbnail(
        documentId: String?,
        sizeHint: Point?,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val catalog = documentId!!.split("/")[0]
        val file = File(context!!.cacheDir, catalog)
        if (!file.exists()) {
            runBlocking {
                client.get<HttpStatement>(path = "${catalog}/cover").execute { httpResponse ->
                    val channel: ByteReadChannel = httpResponse.receive()
                    while (!channel.isClosedForRead) {
                        val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
                        while (!packet.isEmpty) {
                            val bytes = packet.readBytes()
                            file.appendBytes(bytes)
                        }
                    }
                }
            }
        }
        return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, 0)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun openDocument(documentId: String?, mode: String?, signal: CancellationSignal?): ParcelFileDescriptor? {
        val split = documentId!!.split("/")
        val catalog = split[0]
        val trackId = split[1].toInt()

        val directory = File(context!!.getExternalFilesDir(Environment.DIRECTORY_MUSIC), split[0])
        val file = File(directory, "$trackId.flac")

        val cache = db.cacheDao().getCache(catalog, trackId)
        var size = cache?.size ?: 0
        var requested = file.exists()

        if (!file.exists()) {
            directory.mkdirs()
            thread {
                runBlocking {
                    client.get<HttpStatement>(path = documentId).execute { response ->
                        if (response.status != HttpStatusCode.OK) {
                            //TODO: error handling
                        }

                        // query db again
                        val cacheInDb = db.cacheDao().getCache(catalog, trackId)
                        // if any other requests has started downloading before this request
                        // then skip downloading
                        // else, start download
                        //
                        // if cacheInDb, it file should exist
                        // if file does not exist, we need to download it again
                        if (cacheInDb == null || !file.exists()) {
                            // remove file if exists
                            // this should not happen
                            if (file.exists()) {
                                file.delete()
                            }

                            // download file
                            val channel: ByteReadChannel = response.receive()
                            while (!channel.isClosedForRead) {
                                val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
                                while (!packet.isEmpty) {
                                    val bytes = packet.readBytes()
                                    file.appendBytes(bytes)

                                    if (!requested) {
                                        // write size into db after first appendBytes
                                        size = response.headers["X-Origin-Size"]!!.toLong()
                                        db.cacheDao().insert(Cache(0, catalog, trackId, size))
                                    }
                                    // allow GetSize to read the correct size after bytes appended
                                    requested = true
                                }
                            }
                        } else {
                            // update size with data stored in db
                            size = cacheInDb.size
                            requested = true
                        }
                    }
                }
            }
        }

        val thread = HandlerThread(documentId)
        thread.start()
        val handler = Handler(thread.looper)
        var fis: FileInputStream? = null

        val storage = context?.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        return storage.openProxyFileDescriptor(
            ParcelFileDescriptor.MODE_READ_ONLY,
            object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long {
                    Log.d("anni", "getSize, file = ${file.path}, requested = $requested, size = $size")
                    while (!requested) {
                        Thread.sleep(100)
                    }
                    return size
                }

                @Throws(ErrnoException::class)
                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    try {
                        Log.d("anni", "file = ${file.path}, offset = $offset, size = $size")
                        if (fis == null) {
                            fis = FileInputStream(file)
                        }

                        // wait for file download
                        var len: Long = file.length()
                        // if len >= size, file fully downloaded, ignore
                        // if len < size and length got is less than needed(offset + size)
                        while (len < size && len < offset + size) {
                            Log.d("anni", "file = ${file.path}, length = $len, offset = $offset, size = $size")
                            Thread.sleep(200)
                            len = file.length()
                        }

                        val result = Os.pread(fis!!.fd, data, 0, size, offset)
                        Log.d("anni", "file = ${file.path}, readResult = $result")
                        return result
                    } catch (errno: ErrnoException) {
                        Log.e("anni", "index = $documentId", errno)
                        throw errno
                    } catch (e: Throwable) {
                        // other errors
                        Log.e("anni", "throwable, index = $documentId", e)
                        throw ErrnoException("onRead", OsConstants.EBADF)
                    }
                }

                override fun onRelease() {
                    thread.quitSafely()
                    closeSilently(fis)
                }
            },
            handler
        )
    }

    private fun closeSilently(c: Closeable?) {
        if (c != null) {
            try {
                c.close()
            } catch (ex: IOException) {
            }
        }
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

    private fun handleGetUrl(arg: String?, extras: Bundle?): Bundle {
        require(!TextUtils.isEmpty(arg))

        val res = Bundle()
        val splited = arg!!.split("%2F")
        val catalog = splited[0].split("/").last()
        val trackNumber = splited[1]
        val url = "${Companion.BaseUrl}/${catalog}/${trackNumber}"
        res.putString(TrackProviderConsts.COLUMN_URL, url)
        res.putString(TrackProviderConsts.COLUMN_HEADERS, "Authorization:${Companion.Token}")
        return res
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        return true
    }

    companion object {
        private const val BaseUrl: String = "http://192.168.114.1:3614"
        private const val Token: String =
//            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpYXQiOjE2MTkwNzQ1NDcsInR5cGUiOiJ1c2VyIiwidXNlcm5hbWUiOiJ0ZXN0IiwiYWxsb3dTaGFyZSI6dHJ1ZX0.z7ZsL9nqP1KDZhLjtsUzSn5bhx8-01w2XOVFdazRtVo"
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpYXQiOjAsInR5cGUiOiJ1c2VyIiwidXNlcm5hbWUiOiJ0ZXN0IiwiYWxsb3dTaGFyZSI6dHJ1ZX0.7CH27OBvUnJhKxBdtZbJSXA-JIwQ4MWqI5JsZ46NoKk"
    }
}