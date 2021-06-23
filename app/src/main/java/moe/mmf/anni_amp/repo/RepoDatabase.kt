package moe.mmf.anni_amp.repo

import androidx.room.Database
import androidx.room.RoomDatabase
import moe.mmf.anni_amp.repo.dao.AlbumDao
import moe.mmf.anni_amp.repo.dao.TrackDao
import moe.mmf.anni_amp.repo.entities.Album
import moe.mmf.anni_amp.repo.entities.Track

@Database(entities = arrayOf(Album::class, Track::class), version = 1, exportSchema = false)
abstract class RepoDatabase : RoomDatabase() {
    abstract fun albumDao(): AlbumDao
    abstract fun trackDao(): TrackDao
}