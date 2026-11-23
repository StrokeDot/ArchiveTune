/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmedia

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import android.net.Uri
import android.util.AtomicFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.rukamori.archivetune.db.MusicDatabase
import moe.rukamori.archivetune.utils.isLocalMediaId
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException

object LocalMediaUriHeals {
    private const val FileName = "local_uri_heals.json"
    private val bindings = MutableStateFlow<Map<String, String>>(emptyMap())
    val sourceUris = bindings.asStateFlow()
    private val mutationMutex = Mutex()
    private var loaded = false

    fun uriFor(mediaId: String): String? = bindings.value[mediaId]

    @Synchronized
    fun loadBlocking(context: Context) {
        if (loaded) return
        val loadedBindings =
            try {
                val json = JSONObject(bindingFile(context).readFully().decodeToString())
                json.keys().asSequence().mapNotNull { key ->
                    json.optString(key)
                        .takeIf { key.isLocalMediaId() && it.startsWith("content://") }
                        ?.let { key to it }
                }.toMap()
            } catch (_: FileNotFoundException) {
                emptyMap()
            } catch (error: IOException) {
                Timber.w(error, "Could not read local media replacements")
                emptyMap()
            } catch (error: JSONException) {
                Timber.w(error, "Could not decode local media replacements")
                emptyMap()
            }
        bindings.value = loadedBindings
        loaded = true
    }

    suspend fun replace(
        context: Context,
        database: MusicDatabase,
        mediaId: String,
        replacement: Uri,
    ) {
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                loadBlocking(context)
                require(mediaId.isLocalMediaId() && replacement.scheme == "content")
                require(database.song(mediaId).first()?.song?.isLocal == true)
                require(mediaId in LocalFileAvailability.missingIds.value)
                val resolver = context.contentResolver
                val alreadyGranted = resolver.persistedUriPermissions.any { it.uri == replacement && it.isReadPermission }
                resolver.takePersistableUriPermission(replacement, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                try {
                    val extractor = MediaExtractor()
                    try {
                        extractor.setDataSource(context, replacement, null)
                        require((0 until extractor.trackCount).any {
                            extractor.getTrackFormat(it).getString(android.media.MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                        })
                    } finally {
                        extractor.release()
                    }
                    persistBindings(context, bindings.value + (mediaId to replacement.toString()))
                    LocalFileAvailability.markAvailable(mediaId)
                } catch (failure: Exception) {
                    if (!alreadyGranted) {
                        runCatching {
                            resolver.releasePersistableUriPermission(replacement, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    }
                    throw failure
                }
            }
        }
    }

    suspend fun remove(context: Context, database: MusicDatabase, mediaId: String) {
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                loadBlocking(context)
                require(mediaId in LocalFileAvailability.missingIds.value)
                if (bindings.value.containsKey(mediaId)) {
                    persistBindings(context, bindings.value - mediaId)
                }
                database.removeLocalSong(mediaId)
            }
        }
    }

    private fun persistBindings(context: Context, updated: Map<String, String>) {
        val file = bindingFile(context)
        val output = file.startWrite()
        try {
            output.write(JSONObject(updated).toString().encodeToByteArray())
            file.finishWrite(output)
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
        bindings.value = updated
    }

    private fun bindingFile(context: Context) = AtomicFile(File(context.filesDir, FileName))
}
