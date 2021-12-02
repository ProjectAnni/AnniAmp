package moe.mmf.anni_amp.repo

import android.util.Log
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import kotlinx.coroutines.runBlocking
import moe.mmf.anni_amp.repo.entities.Album
import moe.mmf.anni_amp.repo.entities.Track
import moe.mmf.anni_amp.saveChannelToFile
import moe.mmf.anni_amp.utils.UnzipUtils
import org.tomlj.Toml
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import java.io.File
import java.time.LocalDate
import kotlin.io.path.Path

class RepoHelper(name: String, private var repo: String, private var root: File) {
    private var rootFolder: File = File(root, "$name-master")
    private var rootFile: File = File(root, "repo.zip")

    fun needInitialize(): Boolean {
        return !rootFolder.exists()
    }

    fun initialize(db: RepoDatabase) {
        runBlocking {
            HttpClient().get<HttpStatement>("$repo/archive/refs/heads/master.zip")
                .execute {
                    saveChannelToFile(it.receive(), rootFile)
                }
        }
        UnzipUtils.unzip(rootFile, root)

        val albumRoot = File(rootFolder, "album")
        val albumList = mutableListOf<Album>()
        val tracksList = mutableListOf<Track>()
        albumRoot.walkTopDown()
            .maxDepth(1)
            .forEach {
                // ignore root
                if (it != albumRoot) {
                    val result: TomlParseResult = Toml.parse(Path(it.path))
                    if (!result.hasErrors()) {
                        // only apply albums with no error
                        val albumTitle = result.getString("album.title")!!
                        val albumArtist = result.getString("album.artist")!!
                        val albumType = result.getString("album.type")!!
                        val albumCatalog = result.getString("album.catalog")!!

                        var albumReleaseDate = result.get("album.date")!!
                        when (albumReleaseDate) {
                            is String -> {}
                            is TomlTable -> {
                                val year = albumReleaseDate.getLong("year")!!
                                var month = albumReleaseDate.getLong("month")
                                if (month == null) {
                                    month = 0
                                }
                                var day = albumReleaseDate.getLong("day")
                                if (day == null) {
                                    day = 0
                                }
                                albumReleaseDate = "${year}-${month}-${day}"
                            }
                            is LocalDate -> {
                                albumReleaseDate = albumReleaseDate.toString()
                            }
                            else -> {
                                // fallback, should not happen
                                albumReleaseDate = "2021"
                            }
                        }

                        // insert album
                        albumList.add(
                            Album(
                                0,
                                albumTitle,
                                albumCatalog,
                                albumArtist,
                                albumReleaseDate.toString(),
                                null
                            )
                        )
                        val discs = result.getArray("discs")!!
                        for (i in 0 until discs.size()) {
                            val disc = discs.getTable(i)
                            val discCatalog = disc.getString("catalog")!!
                            val discTitle = disc.getString("title") { albumTitle }
                            val discArtist = disc.getString("artist") { albumArtist }
                            val discType = disc.getString("type") { albumType }
                            if (discs.size() > 1) {
                                albumList.add(
                                    Album(
                                        0,
                                        discTitle,
                                        discCatalog,
                                        discArtist,
                                        albumReleaseDate.toString(),
                                        albumCatalog
                                    )
                                )
                            }

                            val tracks = disc.getArray("tracks")!!
                            for (j in 0 until tracks.size()) {
                                val track = tracks.getTable(j)
                                val trackTitle = track.getString("title")!!
                                val trackArtist = track.getString("artist") { discArtist }
                                val trackType = track.getString("type") { discType }
                                tracksList.add(
                                    Track(
                                        0,
                                        discCatalog,
                                        j + 1,
                                        trackTitle,
                                        trackArtist,
                                        trackType
                                    )
                                )
                            }
                        }
                    }
                }
            }
        db.albumDao().insertAll(albumList)
        db.trackDao().insertAll(tracksList)
        Log.d("anni", "database construction finished")
    }
}
