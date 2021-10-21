package moe.mmf.anni_amp.repo.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import moe.mmf.anni_amp.repo.entities.Album

@Dao
interface AlbumDao {
    @Query("SELECT * FROM album WHERE catalog = :catalog")
    fun getAlbum(catalog: String): Album?

    @Query("SELECT * FROM album")
    fun getAllAlbums(): Array<Album>

    @Insert
    fun insert(album: Album)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(albums: List<Album>)
}