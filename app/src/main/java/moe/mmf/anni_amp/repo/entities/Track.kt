package moe.mmf.anni_amp.repo.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Track(
    @PrimaryKey(autoGenerate = true) val id: Int,
    @ColumnInfo val catalog: String,
    @ColumnInfo(name = "track_id") val track: Int,
    @ColumnInfo val title: String,
    @ColumnInfo val artist: String,
    @ColumnInfo val type: String,
)