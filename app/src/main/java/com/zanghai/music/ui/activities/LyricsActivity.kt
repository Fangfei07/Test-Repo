package com.zanghai.music.ui.activities

import android.animation.ValueAnimator
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.collection.LruCache
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.fangfei.lyricview.LyricView
import com.zanghai.music.R
import com.zanghai.music.data.cache.LyricsCacheManager
import com.zanghai.music.data.model.LyricsLine
import com.zanghai.music.data.model.Song
import com.zanghai.music.data.remote.LrcLibApi
import com.zanghai.music.databinding.ActivityLyricsBinding
import com.zanghai.music.service.MusicPlayerService
import com.zanghai.music.service.PlaybackListener
import com.zanghai.music.utils.LrcParser
import com.zanghai.music.utils.LyricsShareHelper
import jp.wasabeef.glide.transformations.BlurTransformation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class LyricsActivity : AppCompatActivity(), PlaybackListener {

    private val HIGHLIGHT_OFFSET_MS = 100L

    private lateinit var binding: ActivityLyricsBinding
    private lateinit var lyricView: LyricView
    private lateinit var lyricsCache: LyricsCacheManager
    private val lrcLibApi = LrcLibApi.create()

    private var allLyricsLines = listOf<LyricsLine>()
    private var currentIndex = -1
    private var musicService: MusicPlayerService? = null
    private var isServiceBound = false
    private var currentSongId: Long = -1
    private var currentSong: Song? = null

    private val memoryCache = LruCache<String, List<LyricsLine>>(20)
    private var isSeeking = false
    private var lyricsJob: Job? = null
    private var blurAnimator: ValueAnimator? = null

    private val uiHandler = Handler(Looper.getMainLooper())

    private val lyricSyncRunnable = object : Runnable {
        override fun run() {
            syncLyricLine()
            uiHandler.postDelayed(this, 100L)
        }
    }

    private val nowPlayingRunnable = object : Runnable {
        override fun run() {
            updateNowPlayingInfo()
            uiHandler.postDelayed(this, 500L)
        }
    }

    private val seekingResetRunnable = Runnable { isSeeking = false }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLyricsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        lyricView = binding.lyricsView
        window.setBackgroundDrawable(null)

        lyricsCache = LyricsCacheManager(this)
        setupSystemUI()
        setupLyricsView()
        setupSelectionUI()

        Intent(this, MusicPlayerService::class.java).also {
            bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
        }

        val artist = intent.getStringExtra("extra_artist") ?: ""
        val title = intent.getStringExtra("extra_title") ?: ""
        loadLyrics(artist, title)
    }

    override fun onResume() {
        super.onResume()
        if (isServiceBound) startSyncLoops()
    }

    override fun onPause() {
        super.onPause()
        stopSyncLoops()
    }

    override fun onDestroy() {
        stopSyncLoops()
        lyricsJob?.cancel()
        blurAnimator?.cancel()
        if (isServiceBound) {
            unbindService(serviceConnection)
            isServiceBound = false
            musicService = null
        }
        super.onDestroy()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            musicService = (service as MusicPlayerService.MusicBinder).getService()
            isServiceBound = true
            musicService?.setListener(this@LyricsActivity)
            startSyncLoops()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            musicService = null
            isServiceBound = false
            stopSyncLoops()
        }
    }

    private fun startSyncLoops() {
        uiHandler.post(lyricSyncRunnable)
        uiHandler.post(nowPlayingRunnable)
    }

    private fun stopSyncLoops() {
        uiHandler.removeCallbacks(lyricSyncRunnable)
        uiHandler.removeCallbacks(nowPlayingRunnable)
    }

    private fun setupSystemUI() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            )
        }
    }

    private fun setupLyricsView() {
        ResourcesCompat.getFont(this, R.font.avenir_semi)?.let { font ->
            lyricView.setCustomFont(font)
        }

        lyricView.setOnSeekListener { timestampMs ->
            isSeeking = true
            uiHandler.removeCallbacks(seekingResetRunnable)
            uiHandler.postDelayed(seekingResetRunnable, 600L)

            musicService?.seekTo(timestampMs.toInt())
            currentIndex = findLineIndexByTime(timestampMs)
        }
    }

    private fun setupSelectionUI() {
        lyricView.setOnSelectionModeChanged { isActive, count ->
            if (isActive) {
                showSelectionUI(count)
            } else {
                hideSelectionUI()
            }
        }

        lyricView.setOnLineSelected { _, _ ->
            val count = lyricView.getSelectedCount()
            binding.tvSelectionCount.text = "$count lirik selected"
        }

        binding.btnShareLyrics.setOnClickListener {
            shareToInstagram()
        }
    }

    private fun showSelectionUI(count: Int) {
        binding.selectionCounterCard.visibility = View.VISIBLE
        binding.tvSelectionCount.text = "$count lirik selected"

        binding.btnShareLyrics.apply {
            visibility = View.VISIBLE
            scaleX = 0.8f
            scaleY = 0.8f
            animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(300)
                .start()
        }
    }

    private fun hideSelectionUI() {
        binding.btnShareLyrics.apply {
            animate()
                .scaleX(0.8f)
                .scaleY(0.8f)
                .setDuration(300)
                .withEndAction {
                    visibility = View.GONE
                }
                .start()
        }

        binding.selectionCounterCard.animate()
            .alpha(0f)
            .setDuration(300)
            .withEndAction {
                binding.selectionCounterCard.visibility = View.GONE
                binding.selectionCounterCard.alpha = 1f
            }
            .start()
    }

    private fun shareToInstagram() {
        val selectedLyrics = lyricView.getSelectedLyrics()
        if (selectedLyrics.isEmpty()) {
            Toast.makeText(this, "No lyrics selected", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val shareImage = LyricsShareHelper.createLyricsImage(
                selectedLyrics,
                artist = currentSong?.artist ?: "",
                title = currentSong?.title ?: ""
            )

            val shareUri = saveTempImage(shareImage)
            if (shareUri == null) {
                Toast.makeText(this, "Failed to create image", Toast.LENGTH_SHORT).show()
                return
            }

            val intent = Intent().apply {
                action = "com.instagram.share.ADD_TO_STORY"
                putExtra("interactive_asset_uri", shareUri)
                type = "image/*"
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            }

            startActivity(intent)
            lyricView.clearSelection()
            Toast.makeText(this, "Shared to Instagram Story!", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Instagram not installed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveTempImage(bitmap: android.graphics.Bitmap): Uri? {
        return try {
            val file = File(cacheDir, "lyrics_share_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { fos ->
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
            }
            FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun syncLyricLine() {
        if (isSeeking) return
        val pos = musicService?.getCurrentPosition()?.toLong() ?: return
        val newIndex = findLineIndexByTime(pos)
        if (newIndex != currentIndex) {
            currentIndex = newIndex
            lyricView.setCurrentLine(currentIndex)
        }
    }

    private fun findLineIndexByTime(position: Long): Int {
        if (allLyricsLines.isEmpty()) return -1

        val adjustedPosition = (position - HIGHLIGHT_OFFSET_MS).coerceAtLeast(0L)

        if (adjustedPosition < allLyricsLines[0].timestamp) return -1

        var left = 0
        var right = allLyricsLines.size - 1
        while (left <= right) {
            val mid = (left + right) / 2
            when {
                adjustedPosition < allLyricsLines[mid].timestamp -> right = mid - 1
                mid == allLyricsLines.size - 1 || adjustedPosition < allLyricsLines[mid + 1].timestamp -> return mid
                else -> left = mid + 1
            }
        }
        return -1
    }

    private fun loadLyrics(artist: String, title: String) {
        val key = buildCacheKey(artist, title)

        memoryCache.get(key)?.let {
            allLyricsLines = it
            showLyrics(it)
            return
        }

        lyricsJob?.cancel()
        binding.progressBar.visibility = View.VISIBLE
        binding.tvError.visibility = View.GONE

        lyricsJob = lifecycleScope.launch {
            val cached = withContext(Dispatchers.IO) { lyricsCache.getLyrics(artist, title) }
            if (cached != null) {
                allLyricsLines = cached
                memoryCache.put(key, cached)
                showLyrics(cached)
                binding.progressBar.visibility = View.GONE
                return@launch
            }

            try {
                val response = withContext(Dispatchers.IO) { lrcLibApi.getLyrics(artist, title) }
                val lrcText = response.syncedLyrics
                if (lrcText != null) {
                    val parsed = LrcParser.parse(lrcText)
                    allLyricsLines = parsed
                    memoryCache.put(key, parsed)
                    withContext(Dispatchers.IO) { lyricsCache.saveLyrics(artist, title, parsed) }
                    showLyrics(parsed)
                } else {
                    showError("Lyrics not found")
                }
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            } finally {
                binding.progressBar.visibility = View.GONE
            }
        }
    }

    private fun showLyrics(lines: List<LyricsLine>) {
        lyricView.setLyrics(lines.map { it.text })
        lyricView.setTimestamps(lines.map { it.timestamp })
        currentIndex = -1
        binding.lyricsView.visibility = View.VISIBLE
    }

    private fun showError(message: String) {
        binding.tvError.visibility = View.VISIBLE
        binding.tvError.text = message
    }

    private fun buildCacheKey(artist: String, title: String) =
        "${artist.trim()}_${title.trim()}".replace(" ", "_").lowercase()

    private fun updateNowPlayingInfo() {
        if (isDestroyed || isFinishing) return
        val song = musicService?.getCurrentSong() ?: return

        currentSong = song
        binding.nowPlayingTitle.text = song.title
        binding.nowPlayingArtist.text = song.artist

        if (song.id != currentSongId) {
            currentSongId = song.id
            loadAlbumArt(song.albumArt)
            loadBlurBackground(song.albumArt)
        }
    }

    private fun loadAlbumArt(uri: Uri?) {
        Glide.with(this)
            .load(uri)
            .centerCrop()
            .transform(CenterCrop(), com.bumptech.glide.load.resource.bitmap.RoundedCorners(24))
            .placeholder(R.drawable.z_hai)
            .into(binding.nowPlayingImage)
    }

    private fun loadBlurBackground(uri: Uri?) {
        blurAnimator?.cancel()

        Glide.with(this)
            .load(uri)
            .override(100, 100)
            .transform(CenterCrop(), BlurTransformation(14, 8))
            .into(object : CustomTarget<Drawable>() {
                override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                    if (isDestroyed || isFinishing) return

                    val dimOverlay = ColorDrawable(Color.parseColor("#4D000000"))
                    val layered = LayerDrawable(arrayOf(resource, dimOverlay))

                    window.setBackgroundDrawable(layered)

                    blurAnimator = ValueAnimator.ofInt(0, 255).apply {
                        duration = 600
                        interpolator = DecelerateInterpolator()
                        addUpdateListener { layered.alpha = it.animatedValue as Int }
                        start()
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {}
            })
    }

    override fun onSongChanged(song: Song) {
        uiHandler.post {
            if (isDestroyed || isFinishing) return@post
            allLyricsLines = emptyList()
            currentIndex = -1
            lyricView.setLyrics(emptyList())
            lyricView.clearSelection()
            binding.tvError.visibility = View.GONE
            loadLyrics(song.artist, song.title)
        }
    }

    override fun onPlaybackStateChanged(isPlaying: Boolean) {
        // no-op
    }

    override fun onProgressUpdated(currentPosition: Int, duration: Int) {
        // no-op
    }
}