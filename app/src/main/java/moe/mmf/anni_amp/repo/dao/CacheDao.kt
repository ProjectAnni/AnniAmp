package moe.mmf.anni_amp.repo.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import moe.mmf.anni_amp.repo.entities.Cache

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache WHERE catalog = :catalog AND track_id = :trackId")
    fun getCache(catalog: String, trackId: Int): Cache?

    @Insert
    fun insert(cache: Cache)
}