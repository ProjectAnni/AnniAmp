package moe.mmf.anni_amp.repo.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
data class Cache(
    @PrimaryKey(autoGenerate = true) val id: Int,
    @ColumnInfo val catalog: String,
    @ColumnInfo(name = "track_id") val trackId: Int,
    @ColumnInfo var cached: Boolean,
    @ColumnInfo var size: Long,
)