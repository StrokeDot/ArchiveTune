/*
 * ArchiveTune (2026)
 * © Rukamori — github.com/rukamori
 * GPL-3.0 License | Contributors: see git history
 * Do not remove or alter this notice. - Per GPL-3.0 Section 4 & Section 5
 */

package moe.rukamori.archivetune.localmedia

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.AtomicFile
import androidx.core.content.FileProvider
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.MetadataRetriever
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.withTimeout
import moe.rukamori.archivetune.db.entities.FormatEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
    private const val ReplacementDirectory = "local_replacements"
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

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    @Suppress("DEPRECATION")
    suspend fun replace(
        context: Context,
        database: MusicDatabase,
        mediaId: String,
        replacement: Uri,
    ): String =
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                loadBlocking(context)
                require(mediaId.isLocalMediaId() && replacement.scheme == "content")
                val track = requireNotNull(database.song(mediaId).first()?.song?.takeIf { it.isLocal })
                val resolver = context.contentResolver
                val previousBindings = bindings.value
                val previousSource = previousBindings[mediaId]
                val readGrant = Intent.FLAG_GRANT_READ_URI_PERMISSION
                var tookGrant = false
                var copiedFile: File? = null
                try {
                    val alreadyGranted = resolver.persistedUriPermissions.any { it.uri == replacement && it.isReadPermission }
                    val source =
                        if (alreadyGranted) {
                            replacement
                        } else {
                            val persisted = try {
                                resolver.takePersistableUriPermission(replacement, readGrant)
                                tookGrant = true
                                true
                            } catch (_: SecurityException) {
                                false
                            }
                            if (persisted) {
                                replacement
                            } else {
                                val directory = File(context.filesDir, ReplacementDirectory)
                                directory.mkdirs()
                                val file = File.createTempFile("track-", ".media", directory)
                                copiedFile = file
                                val cancellation = currentCoroutineContext()
                                val input = resolver.openInputStream(replacement) ?: throw FileNotFoundException()
                                input.use {
                                    file.outputStream().use { output ->
                                        val buffer = ByteArray(64 * 1024)
                                        while (true) {
                                            cancellation.ensureActive()
                                            val read = input.read(buffer)
                                            if (read < 0) break
                                            output.write(buffer, 0, read)
                                        }
                                    }
                                }
                                FileProvider.getUriForFile(context, "${context.packageName}.FileProvider", file)
                            }
                        }
                    val (audioFormat, durationUs) = withTimeout(30_000) {
                        MetadataRetriever.Builder(context, MediaItem.fromUri(source)).build().use { retriever ->
                            val tracks = retriever.retrieveTrackGroups().await()
                            val audio = (0 until tracks.length).asSequence()
                                .map(tracks::get)
                                .firstOrNull { it.type == C.TRACK_TYPE_AUDIO }
                                ?.getFormat(0)
                                ?: throw IOException("Replacement has no audio track")
                            audio to retriever.retrieveDurationUs().await()
                        }
                    }
                    val contentLength =
                        copiedFile?.length()
                            ?: runCatching { resolver.openAssetFileDescriptor(source, "r")?.use { it.length } }.getOrNull()
                            ?: -1L
                    val durationSeconds =
                        if (durationUs >= 0) {
                            (durationUs / 1_000_000).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                        } else {
                            -1
                        }
                    val mimeType =
                        runCatching { resolver.getType(replacement) }.getOrNull()
                            ?.takeIf(SupportedLocalAudio::isSupportedMimeType) ?: "audio/*"
                    withContext(NonCancellable) {
                        database.withTransaction {
                            require(updateLocalReplacementDuration(mediaId, durationSeconds) == 1)
                            upsert(
                                FormatEntity(
                                    id = mediaId,
                                    itag = 0,
                                    mimeType = mimeType,
                                    codecs = audioFormat.codecs ?: audioFormat.sampleMimeType.orEmpty(),
                                    bitrate = audioFormat.bitrate,
                                    sampleRate = audioFormat.sampleRate.takeIf { it > 0 },
                                    contentLength = contentLength,
                                    loudnessDb = null,
                                    playbackUrl = null,
                                ),
                            )
                            persistBindings(context, previousBindings + (mediaId to source.toString()))
                        }
                        LocalFileAvailability.markAvailable(context, mediaId)
                        if (previousSource != source.toString()) {
                            runCatching { deleteManagedCopy(context, previousSource) }
                        }
                    }
                } catch (failure: Exception) {
                    if (bindings.value !== previousBindings) {
                        runCatching { persistBindings(context, previousBindings) }
                    }
                    copiedFile?.delete()
                    if (tookGrant && replacement.toString() !in previousBindings.values) {
                        runCatching { resolver.releasePersistableUriPermission(replacement, readGrant) }
                    }
                    throw failure
                }
                track.title
            }
        }

    suspend fun remove(context: Context, database: MusicDatabase, mediaId: String) {
        withContext(Dispatchers.IO) {
            mutationMutex.withLock {
                loadBlocking(context)
                require(mediaId in LocalFileAvailability.missingIds.value)
                val previousSource = bindings.value[mediaId]
                if (previousSource != null) {
                    persistBindings(context, bindings.value - mediaId)
                }
                database.removeLocalSong(mediaId)
                LocalFileAvailability.markAvailable(context, mediaId)
                deleteManagedCopy(context, previousSource)
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

    private fun deleteManagedCopy(context: Context, source: String?) {
        if (source == null || source in bindings.value.values) return
        val uri = Uri.parse(source)
        if (uri.authority != "${context.packageName}.FileProvider") return
        val segments = uri.pathSegments
        if (segments.size == 3 && segments[0] == "files" && segments[1] == ReplacementDirectory) {
            val name = segments[2]
            if (name.matches(Regex("track-[0-9]+[.]media"))) {
                File(File(context.filesDir, ReplacementDirectory), name).delete()
            }
        }
    }

    private fun bindingFile(context: Context) = AtomicFile(File(context.filesDir, FileName))
}
