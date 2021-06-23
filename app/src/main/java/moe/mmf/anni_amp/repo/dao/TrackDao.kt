package moe.mmf.anni_amp.repo.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import moe.mmf.anni_amp.repo.entities.Track

@Dao
interface TrackDao {
    @Query("SELECT * FROM track WHERE catalog = :catalog")
    fun getAllTracks(catalog: String): List<Track>?

    @Query("SELECT * FROM track WHERE catalog = :catalog AND track_id = :trackNumber")
    fun getTrack(catalog: String, trackNumber: Int): Track?

    @Insert
    fun insert(track: Track)
}