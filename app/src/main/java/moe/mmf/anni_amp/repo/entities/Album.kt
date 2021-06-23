package moe.mmf.anni_amp.repo.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Album(
    @PrimaryKey(autoGenerate = true) val id: Int,
    @ColumnInfo val title: String,
    @ColumnInfo val catalog: String,
    @ColumnInfo val artist: String,
    @ColumnInfo(name = "date") val releaseDate: String,
    @ColumnInfo(name = "parent_album") val parentAlbum: String?,
)