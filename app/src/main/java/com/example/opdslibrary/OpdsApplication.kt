package com.example.opdslibrary

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.example.opdslibrary.utils.CachedImageInterceptor

/**
 * Custom Application class to configure Coil with image caching
 */
class OpdsApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .components {
                add(CachedImageInterceptor(this@OpdsApplication))
            }
            .respectCacheHeaders(false) // We manage our own cache validation
            .build()
    }
}
