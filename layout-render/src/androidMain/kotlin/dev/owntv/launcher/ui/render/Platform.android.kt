package dev.owntv.launcher.ui.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.request.ImageRequest
import coil3.request.allowHardware
import coil3.toBitmap

actual fun assetUrl(path: String): String = "file:///android_asset/$path"

internal actual fun ImageRequest.Builder.readablePixels(): ImageRequest.Builder = allowHardware(false)

internal actual fun coil3.Image.toImageBitmap(): ImageBitmap = toBitmap().asImageBitmap()
