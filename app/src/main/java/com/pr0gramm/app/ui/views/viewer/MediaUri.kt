package com.pr0gramm.app.ui.views.viewer

import android.content.Context
import android.net.Uri

import com.pr0gramm.app.feed.FeedItem
import com.pr0gramm.app.services.UriHelper

/**
 */
data class MediaUri(val id: Long, val baseUri: Uri, val mediaType: MediaUri.MediaType, val delay: Boolean = false) {

    fun withDelay(value: Boolean): MediaUri {
        return copy(delay = value)
    }

    fun withUri(uri: Uri, mediaType: MediaType): MediaUri {
        return copy(baseUri = uri, mediaType = mediaType)
    }

    val isLocal: Boolean
        get() = "file" == baseUri.scheme

    override fun toString(): String {
        return baseUri.toString()
    }

    enum class MediaType {
        IMAGE, VIDEO, GIF
    }

    companion object {

        /**
         * Returns a media uri and guesses the media type from the uri.
         */
        @JvmStatic
        fun of(id: Long, uri: Uri): MediaUri {
            val name = uri.lastPathSegment ?: throw IllegalArgumentException("uri must have a file component")

            var type = MediaType.IMAGE
            if (name.toLowerCase().endsWith(".gif"))
                type = MediaType.GIF

            if (name.toLowerCase().matches(".*\\.(webm|mpe?g|mp4)".toRegex()))
                type = MediaType.VIDEO

            return MediaUri(id, uri, type)
        }

        @JvmStatic
        fun of(id: Long, uri: String): MediaUri {
            return of(id, Uri.parse(uri))
        }

        @JvmStatic
        fun of(context: Context, item: FeedItem): MediaUri {
            return of(item.id, UriHelper.of(context).media(item))
        }
    }
}
