package moe.mmf.anni_amp.repo

import android.util.Log
import moe.mmf.anni_amp.repo.entities.Album
import moe.mmf.anni_amp.repo.entities.Track
import org.eclipse.jgit.api.Git
import org.tomlj.Toml
import org.tomlj.TomlParseResult
import java.io.File
import kotlin.io.path.Path

class RepoHelper(private var name: String, private var repo: String, root: File) {
    private var root: File = File(root, name)

    fun initialize(db: RepoDatabase) {
        if (root.exists()) {
            return
//            Git.open(root).pull()
        } else {
            Git.cloneRepository()
                .setURI(repo)
                .setDirectory(root)
                .call()
        }
        val albumRoot = File(root, "album")
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
                        val albumReleaseDate = result.getLocalDate("album.date")!!
                        val albumType = result.getString("album.type")!!
                        val albumCatalog = result.getString("album.catalog")!!
                        // insert album
                        db.albumDao()
                            .insert(Album(0, albumTitle, albumCatalog, albumArtist, albumReleaseDate.toString(), null))

                        val discs = result.getArray("discs")!!
                        for (i in 0 until discs.size()) {
                            val disc = discs.getTable(0)
                            val discCatalog = disc.getString("catalog")!!
                            val discTitle = disc.getString("title") { albumTitle }
                            val discArtist = disc.getString("artist") { albumArtist }
                            val discType = disc.getString("type") { albumType }
                            if (discs.size() > 1) {
                                db.albumDao()
                                    .insert(
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
                                db.trackDao().insert(Track(0, discCatalog, j + 1, trackTitle, trackArtist, trackType))
                            }
                        }
                    }
                }
            }
        Log.d("anni", "database construction finished")
    }
}