package com.quicklock.applock.util

import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Icons are decoded lazily off the main thread and cached, which keeps long app lists scrolling smoothly. */
object AppIcons {
    private val cache = LruCache<String, ImageBitmap>(180)

    fun peek(pkg: String): ImageBitmap? = cache.get(pkg)

    suspend fun load(pm: PackageManager, pkg: String): ImageBitmap? {
        cache.get(pkg)?.let { return it }
        val bitmap = withContext(Dispatchers.Default) {
            runCatching { pm.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
        }
        if (bitmap != null) cache.put(pkg, bitmap)
        return bitmap
    }
}

@Composable
fun AppIcon(pkg: String, size: Dp, modifier: Modifier = Modifier) {
    val pm = LocalContext.current.packageManager
    val icon by produceState(AppIcons.peek(pkg), pkg) { value = AppIcons.load(pm, pkg) }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap = bitmap, contentDescription = null, modifier = modifier.size(size))
    } else {
        androidx.compose.foundation.layout.Box(modifier.size(size))
    }
}
