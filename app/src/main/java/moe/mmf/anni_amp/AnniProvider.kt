package moe.mmf.anni_amp

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.database.MatrixCursor.RowBuilder
import android.graphics.Point
import android.os.*
import android.provider.DocumentsContract
import android.provider.DocumentsProvider
import android.provider.MediaStore
import android.util.Log
import androidx.room.Room
import com.maxmpz.poweramp.player.TrackProviderConsts
import com.maxmpz.poweramp.player.TrackProviderProto
import com.maxmpz.poweramp.player.TrackProviderProto.TrackProviderProtoClosed
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
import java.io.*
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicReference
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
                host = "annil.mmf.moe"
                url {
                    protocol = URLProtocol.HTTPS
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
                val albums = db.albumDao().getAllAlbums()
                    .filter { album -> response.contains(album.catalog) }
                for (album in albums) {
                    fillAlbumRow(
                        this.newRow(),
                        album,
                        TrackProviderConsts.FLAG_NO_SUBDIRS
                    )
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
            if (documentId == "annil") {
                fillFolderRow(
                    this.newRow(),
                    documentId,
                    documentId,
                    TrackProviderConsts.FLAG_HAS_SUBDIRS
                )
            } else if (!documentId.contains("/")) {
                val album = db.albumDao().getAlbum(documentId)
                if (album != null) {
                    fillAlbumRow(this.newRow(), album, TrackProviderConsts.FLAG_HAS_SUBDIRS)
                }
            } else {
                val split = documentId.split("/")
                val catalog = split[0]
                val trackNumber = split[1].toInt()
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
        row.add(
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.MIME_TYPE_DIR
        )
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

    private fun fillTrackRow(row: RowBuilder, track: Track, sendMetadata: Boolean) {
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, "${track.catalog}/${track.track}")
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, "audio/aac")
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

    override fun openDocumentThumbnail(
        documentId: String?,
        sizeHint: Point?,
        signal: CancellationSignal?
    ): AssetFileDescriptor {
        val catalog = documentId!!.split("/")[0]
        val file = File(context!!.cacheDir, catalog)
        if (!file.exists()) {
            runBlocking {
                client.get<HttpStatement>(path = "${URLEncoder.encode(catalog, "utf-8")}/cover")
                    .execute { saveChannelToFile(it.receive(), file) }
            }
        }
        return AssetFileDescriptor(
            ParcelFileDescriptor.open(
                file,
                ParcelFileDescriptor.MODE_READ_ONLY
            ), 0, 0
        )
    }

    override fun openDocument(
        documentId: String?,
        mode: String?,
        signal: CancellationSignal?
    ): ParcelFileDescriptor? {
        Log.d("anni", "opening $documentId")
        val split = documentId!!.split("/")
        val catalog = split[0]
        val trackId = split[1].toInt()

        val directory = File(context!!.getExternalFilesDir(Environment.DIRECTORY_MUSIC), split[0])
        val file = File(directory, "$trackId.anni.cache")
        if (!file.exists()) {
            directory.mkdirs()
            file.createNewFile()
        }

        var cache = db.cacheDao().getCache(catalog, trackId)
        var startCache = cache?.cached == true
        val size = AtomicReference(cache?.size ?: -1)

        if (file.length() == 0L) {
            thread {
                runBlocking {
                    client.get<HttpStatement>(path = documentId).execute { response ->
                        // if any other requests has started downloading before this request
                        // then skip downloading
                        // else, start download
                        //
                        // if cacheInDb, it file should exist
                        // if file does not exist or length == 0, we need to download it again
                        if (cache == null || file.length() == 0L) {
                            if (cache == null) {
                                // write size into db after first appendBytes
                                val originalSize = response.headers["X-Origin-Size"]!!.toLong()
                                size.set(originalSize)
                                db.cacheDao()
                                    .insert(Cache(0, catalog, trackId, false, originalSize))
                                cache = db.cacheDao().getCache(catalog, trackId)
                                startCache = true
                            }
                            Log.d("anni", "caching $documentId")
                            saveChannelToFile(response.receive(), file)
                            cache!!.size = file.length()
                            size.set(cache!!.size)
                            cache!!.cached = true
                            db.cacheDao().update(cache!!)
                        }
                    }
                }
            }
        }

        Log.d("anni", "cached = ${cache?.cached}, size = $size")
        // file fully cached, use raw fd
        if (cache?.cached == true) {
            Log.d("anni", "opening fd for $documentId")
            return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }

        // wait for audio size
        while (!startCache) {
            Thread.sleep(50)
        }
        Log.d("anni", "opening seekable socket for $documentId")
        return openViaSeekableSocket(file, size, signal)
    }

    private fun openViaSeekableSocket(
        file: File,
        fileLength: AtomicReference<Long>,
        signal: CancellationSignal?
    ): ParcelFileDescriptor? {
        val fds = ParcelFileDescriptor.createSocketPair()

        thread {
            val buf = ByteBuffer.allocateDirect(TrackProviderProto.MAX_DATA_SIZE)
            buf.order(ByteOrder.nativeOrder())
            try {
                FileInputStream(file).use { fis ->
                    val ch = fis.channel
                    TrackProviderProto(fds[1], fileLength.get()).use { proto ->
                        // Send initial header
                        proto.sendHeader()
                        while (true) {
                            if (signal?.isCanceled == true) {
                                break
                            }

                            var len = ch.read(buf)
                            Log.v("anni/openViaSeekableSocket", "read $len bytes")
                            while (len > 0) {
                                // move cursor to start
                                buf.flip()

                                // send buffer read
                                // NOTE: DO NOT SEND EMPTY BUFFER as this will cause premature EOF
                                val seekRequestPos = proto.sendData(buf)
                                // handle possible seek request
                                handleSeekRequest(proto, seekRequestPos, ch, fileLength.get())
                                buf.clear()
                                len = ch.read(buf)
                            }

                            if (fileLength.get() - ch.position() > 1000) {
                                // wait for cache
                                // add some wait here?
                                Log.v(
                                    "anni/openViaSeekableSocket",
                                    "pos = ${ch.position()}, length = $fileLength, waiting for cache..."
                                )
                                continue
                            }

                            // EOF here
                            // handle the last potential seek
                            val seekRequestPos = proto.sendEOFAndWaitForSeekOrClose()
                            if (!handleSeekRequest(proto, seekRequestPos, ch, fileLength.get())) {
                                // no seek request, close socket
                                Log.d(
                                    "anni/openViaSeekableSocket",
                                    "close socket with ${fileLength.get()} bytes read"
                                )
                                break
                            }
                        }
                    }
                }
            } catch (ex: TrackProviderProtoClosed) {
            } catch (th: Throwable) {
            }
        }
        return fds[0]
    }

    // handle seek, return whether seek is needed
    private fun handleSeekRequest(
        proto: TrackProviderProto,
        seekPos: Long,
        ch: FileChannel,
        length: Long
    ): Boolean {
        if (seekPos != TrackProviderProto.INVALID_SEEK_POS) {
            val newPos = seekTrack(ch, seekPos, length)
            proto.sendSeekResult(newPos)
            return true
        }
        return false
    }

    // do seek
    private fun seekTrack(ch: FileChannel, seekPos: Long, length: Long): Long {
        return try {
            if (seekPos >= 0) {
                // seekPos >= 0
                // fileStart --seekPos--> *
                ch.position(seekPos)
            } else {
                // seekPos < 0
                // * <--seekPos-- fileEnd
                ch.position(length + seekPos)
            }
            ch.position()
        } catch (ex: IOException) {
            -1
        }
    }

    // save file
    private suspend fun saveChannelToFile(channel: ByteReadChannel, file: File) {
        while (!channel.isClosedForRead) {
            val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
            while (!packet.isEmpty) {
                val bytes = packet.readBytes()
                file.appendBytes(bytes)
            }
        }
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        return true
    }

    companion object {
        private const val Token: String =
            "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJpYXQiOjAsInR5cGUiOiJ1c2VyIiwidXNlcm5hbWUiOiJ0ZXN0IiwiYWxsb3dTaGFyZSI6dHJ1ZX0.7CH27OBvUnJhKxBdtZbJSXA-JIwQ4MWqI5JsZ46NoKk"
    }
}