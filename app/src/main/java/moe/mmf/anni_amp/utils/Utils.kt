package moe.mmf.anni_amp

import io.ktor.utils.io.*
import io.ktor.utils.io.core.*
import java.io.File

// save file
suspend fun saveChannelToFile(channel: ByteReadChannel, file: File) {
    while (!channel.isClosedForRead) {
        val packet = channel.readRemaining(DEFAULT_BUFFER_SIZE.toLong())
        while (!packet.isEmpty) {
            val bytes = packet.readBytes()
            file.appendBytes(bytes)
        }
    }
}
