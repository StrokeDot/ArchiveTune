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
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.media3.common.PlaybackException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.MusicDatabase
import java.io.FileNotFoundException
import java.io.IOException

object LocalFileAvailability {
    private val unavailable = MutableStateFlow<Set<String>>(emptySet())
    val missingIds = unavailable.asStateFlow()

    @OptIn(FlowPreview::class)
    suspend fun observe(context: Context, database: MusicDatabase) {
        withContext(Dispatchers.IO) {
            LocalMediaUriHeals.loadBlocking(context)
            combine(
                database.observeLocalSongIds().distinctUntilChanged(),
                mediaChanges(context),
                LocalMediaUriHeals.sourceUris,
            ) { ids, _, sources -> ids to sources }
                .debounce(400)
                .collectLatest { (ids, sources) ->
                    val previous = unavailable.value
                    val tracked = ids.toHashSet()
                    val missing = buildSet {
                        for (id in ids) {
                            ensureActive()
                            when (checkFile(context, (sources[id] ?: id).toUri())) {
                                FileStatus.Missing -> add(id)
                                FileStatus.Unknown -> if (id in previous) add(id)
                                FileStatus.Available -> Unit
                            }
                        }
                    }
                    if (sources === LocalMediaUriHeals.sourceUris.value) {
                        unavailable.update { current ->
                            missing + (current - tracked) + (current - previous)
                        }
                    }
                }
        }
    }

    suspend fun checkAfterPlaybackError(context: Context, id: String) {
        withContext(Dispatchers.IO) {
            val source = LocalMediaUriHeals.uriFor(id) ?: id
            if (checkFile(context, source.toUri()) == FileStatus.Missing) {
                unavailable.update { current ->
                    if ((LocalMediaUriHeals.uriFor(id) ?: id) == source) current + id else current
                }
            }
        }
    }

    fun markAvailable(id: String) {
        unavailable.update { it - id }
    }

    fun canPruneFromScan(context: Context, id: String): Boolean =
        LocalMediaUriHeals.uriFor(id) == null &&
            isIndexedAudio(id.toUri()) &&
            checkFile(context, id.toUri()) == FileStatus.Available

    private fun mediaChanges(context: Context) = callbackFlow<Unit> {
        val resolver = context.contentResolver
        val observer =
            object : ContentObserver(null) {
                override fun onChange(selfChange: Boolean) {
                    trySend(Unit)
                }
            }
        val registered =
            runCatching {
                resolver.registerContentObserver(MediaStore.AUTHORITY_URI, true, observer)
            }.isSuccess
        trySend(Unit)
        awaitClose { if (registered) resolver.unregisterContentObserver(observer) }
    }

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
