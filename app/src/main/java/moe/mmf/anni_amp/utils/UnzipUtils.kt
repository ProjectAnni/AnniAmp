package moe.mmf.anni_amp.utils

// Edited from https://gist.github.com/NitinPraksash9911/dea21ec4b8ae7df068f8f891187b6d1e
import java.io.*
import java.util.zip.ZipFile

/**
 * UnzipUtils class extracts files and sub-directories of a standard zip file to
 * a destination directory.
 */
object UnzipUtils {
    @Throws(IOException::class)
    fun unzip(zipFilePath: File, destDir: File) {
        if (!destDir.exists()) {
            destDir.mkdir()
        }
        ZipFile(zipFilePath).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                zip.getInputStream(entry).use { input ->
                    val filePath = File(destDir, entry.name)
                    if (!entry.isDirectory) {
                        // if the entry is a file, extracts it
                        extractFile(input, filePath)
                    } else {
                        // if the entry is a directory, make the directory
                        filePath.mkdir()
                    }
                }
            }
        }
    }

    /**
     * Extracts a zip entry (file entry)
     * @param inputStream
     * @param destFilePath
     * @throws IOException
     */
    @Throws(IOException::class)
    private fun extractFile(inputStream: InputStream, destFilePath: File) {
        val bos = BufferedOutputStream(FileOutputStream(destFilePath))
        val bytesIn = ByteArray(BUFFER_SIZE)
        var read: Int
        while (inputStream.read(bytesIn).also { read = it } != -1) {
            bos.write(bytesIn, 0, read)
        }
        bos.close()
    }

    /**
     * Size of the buffer to read/write data
     */
    private const val BUFFER_SIZE = 4096
}