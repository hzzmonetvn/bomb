package com.hzzmonet.zkbomb.ui.design

import android.content.Context
import android.graphics.ImageDecoder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val BACKDROP_FILE = "backdrop.img"

/** Longest edge kept after decoding — a 200 MP photo has no business in RAM. */
private const val MAX_EDGE = 2048

@Composable
actual fun rememberBackdropImageState(): BackdropImageState {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imageState = remember { mutableStateOf<ImageBitmap?>(null) }

    // The photo picker: no storage permission, and the user chooses exactly one
    // image rather than granting access to the whole gallery.
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                imageState.value = withContext(Dispatchers.IO) {
                    runCatching {
                        // Copy into app storage: photo-picker URIs are not
                        // persistable, so keeping the URI would lose the
                        // wallpaper on the next launch.
                        val file = File(context.filesDir, BACKDROP_FILE)
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        }
                        decodeBackdrop(file)
                    }.getOrNull()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        val file = File(context.filesDir, BACKDROP_FILE)
        if (file.exists()) {
            imageState.value = withContext(Dispatchers.IO) {
                runCatching { decodeBackdrop(file) }.getOrNull()
            }
        }
    }

    return remember(launcher, context) {
        object : BackdropImageState {
            override val image: ImageBitmap? get() = imageState.value
            override val canPick = true
            override val unavailableReason: String? = null

            override fun pick() {
                launcher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                )
            }

            override fun clear() {
                imageState.value = null
                runCatching { File(context.filesDir, BACKDROP_FILE).delete() }
            }
        }
    }
}

private fun decodeBackdrop(file: File): ImageBitmap {
    val source = ImageDecoder.createSource(file)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        decoder.isMutableRequired = false
        // Software allocation: hardware bitmaps cannot be used as a blur source.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val longest = maxOf(info.size.width, info.size.height)
        if (longest > MAX_EDGE) {
            decoder.setTargetSampleSize(((longest + MAX_EDGE - 1) / MAX_EDGE))
        }
    }
    return bitmap.asImageBitmap()
}
