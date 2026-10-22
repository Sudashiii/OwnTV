package dev.owntv.launcher.ui.render

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import coil3.request.ImageRequest
import coil3.toBitmap

/** The web preview serves layout assets next to the page. */
actual fun assetUrl(path: String): String = "assets/$path"

internal actual fun ImageRequest.Builder.readablePixels(): ImageRequest.Builder = this

internal actual fun coil3.Image.toImageBitmap(): ImageBitmap = toBitmap().asComposeImageBitmap()
