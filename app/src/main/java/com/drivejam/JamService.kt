package com.drivejam

import android.content.Intent
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.media.MediaBrowserService

/**
 * Music/Audio category requirement: the car's media system talks to us through this session —
 * home-screen media card (title + cover), steering-wheel ⏮ ⏯ ⏭, and voice search ("…한 음악 틀어줘").
 */
class JamService : MediaBrowserService() {
    private lateinit var session: MediaSession
    private val sync: () -> Unit = { publish() }

    override fun onCreate() {
        super.onCreate()
        Jam.init(this)
        session = MediaSession(this, "DriveJam").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() = Jam.play()
                override fun onPause() = Jam.pause()
                override fun onStop() = Jam.pause()
                override fun onSkipToNext() = Jam.next()
                override fun onSkipToPrevious() = Jam.previous()
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = Jam.play()
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    if (query.isNullOrBlank()) Jam.play() else Jam.request(query)
                }
            })
            isActive = true
        }
        sessionToken = session.sessionToken
        Jam.addListener(sync)
        publish()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onDestroy() {
        Jam.removeListener(sync)
        session.release()
        super.onDestroy()
    }

    private fun publish() {
        val t = Jam.track
        if (t != null) {
            session.setMetadata(
                MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, t.title)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, t.artist)
                    .putString(MediaMetadata.METADATA_KEY_GENRE, t.style.label)
                    .putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, CoverArt.of(t, 320))
                    .build()
            )
        }
        val playing = Jam.isPlaying
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackState.ACTION_PLAY_FROM_SEARCH or PlaybackState.ACTION_PLAY_FROM_MEDIA_ID
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    System.currentTimeMillis() - Jam.trackStartedAt, if (playing) 1f else 0f,
                )
                .build()
        )
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?) = BrowserRoot("root", null)

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        val item = MediaBrowser.MediaItem(
            MediaDescription.Builder().setMediaId("jam").setTitle("지금 이 드라이브").setSubtitle("Drive JAM").build(),
            MediaBrowser.MediaItem.FLAG_PLAYABLE,
        )
        result.sendResult(mutableListOf(item))
    }
}
