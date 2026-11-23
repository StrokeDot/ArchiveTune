/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmedia

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.MusicDatabase
import java.io.FileNotFoundException
import java.io.IOException

object LocalFileAvailability {
    private val unavailable = MutableStateFlow<Set<String>>(emptySet())
    val missingIds = unavailable.asStateFlow()
    private var loaded = false

    @Synchronized
    fun loadBlocking(context: Context) {
        if (loaded) return
        unavailable.value = preferences(context).getStringSet("missing_ids", emptySet()).orEmpty().toSet()
        loaded = true
    }

    suspend fun checkLibrary(context: Context, database: MusicDatabase): Set<String> =
        withContext(Dispatchers.IO) {
            loadBlocking(context)
            LocalMediaUriHeals.loadBlocking(context)
            val sources = LocalMediaUriHeals.sourceUris.value
            val previous = unavailable.value
            val ids = database.localSongIds().toHashSet()
            val results = ids.associateWith { id ->
                ensureActive()
                checkFile(context, (sources[id] ?: id).toUri())
            }
            val unverified = mutableSetOf<String>()
            updateMissing(context) { current ->
                val currentSources = LocalMediaUriHeals.sourceUris.value
                val next = (current - (previous - ids)).toMutableSet()
                results.forEach { (id, status) ->
                    if (sources[id] == currentSources[id]) {
                        when (status) {
                            FileStatus.Missing -> next.add(id)
                            FileStatus.Available -> {
                                if (id in previous) next.remove(id)
                            }
                            FileStatus.Unknown -> unverified.add(id)
                        }
                    }
                }
                next
            }
            unverified
        }

    suspend fun checkAfterPlaybackError(context: Context, id: String) {
        withContext(Dispatchers.IO) {
            LocalMediaUriHeals.loadBlocking(context)
            val source = LocalMediaUriHeals.uriFor(id) ?: id
            if (checkFile(context, source.toUri()) == FileStatus.Missing) {
                updateMissing(context) { current ->
                    if ((LocalMediaUriHeals.uriFor(id) ?: id) == source) current + id else current
                }
            }
        }
    }

    fun markAvailable(context: Context, id: String) = updateMissing(context) { it - id }

    fun canPruneFromScan(context: Context, id: String): Boolean =
        LocalMediaUriHeals.uriFor(id) == null &&
            isIndexedAudio(id.toUri()) &&
            checkFile(context, id.toUri()) == FileStatus.Available

    @Synchronized
    private fun updateMissing(context: Context, update: (Set<String>) -> Set<String>) {
        loadBlocking(context)
        val current = unavailable.value
        val next = update(current)
        if (next != current) {
            preferences(context).edit().putStringSet("missing_ids", next).apply()
            unavailable.value = next
        }
    }

    private fun preferences(context: Context) = context.getSharedPreferences("local_file_integrity", Context.MODE_PRIVATE)

    private fun isIndexedAudio(uri: Uri): Boolean =
        uri.authority == MediaStore.AUTHORITY && uri.pathSegments.getOrNull(1) == "audio"

    private fun checkFile(context: Context, uri: Uri): FileStatus {
        val scheme = uri.scheme
        if (scheme != "content" && scheme != "file" && scheme != "android.resource") {
            return FileStatus.Unknown
        }
        if (isIndexedAudio(uri) && !canReadIndexedAudio(context)) return FileStatus.Unknown
        return try {
            val opened = context.contentResolver.openAssetFileDescriptor(uri, "r")
            if (opened == null) FileStatus.Unknown else opened.use { FileStatus.Available }
        } catch (_: FileNotFoundException) {
            FileStatus.Missing
        } catch (_: SecurityException) {
            FileStatus.Unknown
        } catch (_: IllegalArgumentException) {
            FileStatus.Unknown
        } catch (_: IOException) {
            FileStatus.Unknown
        }
    }

    private fun canReadIndexedAudio(context: Context): Boolean {
        val permission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                Manifest.permission.READ_MEDIA_AUDIO
            } else {
                Manifest.permission.READ_EXTERNAL_STORAGE
            }
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private enum class FileStatus { Available, Missing, Unknown }
}

internal fun PlaybackException.isLocalFileNotFound(): Boolean =
    errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
        generateSequence<Throwable>(this) { it.cause }.take(8).any { it is FileNotFoundException }
