package moe.mmf.anni_amp.repo.dao

import androidx.room.*
import moe.mmf.anni_amp.repo.entities.Cache

@Dao
interface CacheDao {
    @Query("SELECT * FROM cache WHERE catalog = :catalog AND track_id = :trackId")
    fun getCache(catalog: String, trackId: Int): Cache?

    @Insert
    fun insert(cache: Cache)

    @Update(onConflict = OnConflictStrategy.REPLACE)
    fun update(cache: Cache)
}