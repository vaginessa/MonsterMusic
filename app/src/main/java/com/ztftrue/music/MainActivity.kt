package com.ztftrue.music

import android.Manifest
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.MediaScannerConnection
import android.media.MediaScannerConnection.MediaScannerConnectionClient
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import android.provider.Settings
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaControllerCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.ztftrue.music.play.ACTION_PlayLIST_CHANGE
import com.ztftrue.music.play.ACTION_TRACKS_DELETE
import com.ztftrue.music.play.ACTION_TRACKS_UPDATE
import com.ztftrue.music.play.EVENT_MEDIA_ITEM_Change
import com.ztftrue.music.play.EVENT_MEDIA_METADATA_Change
import com.ztftrue.music.play.EVENT_SLEEP_TIME_Change
import com.ztftrue.music.play.EVENT_changePlayQueue
import com.ztftrue.music.play.PlayService
import com.ztftrue.music.sqlData.model.MainTab
import com.ztftrue.music.sqlData.model.MusicItem
import com.ztftrue.music.sqlData.model.StorageFolder
import com.ztftrue.music.ui.home.BaseLayout
import com.ztftrue.music.ui.theme.MusicPitchTheme
import com.ztftrue.music.utils.OperateTypeInActivity
import com.ztftrue.music.utils.Utils
import com.ztftrue.music.utils.model.AnyListBase
import com.ztftrue.music.utils.trackManager.PlaylistManager.removeTrackFromM3U
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream


@Suppress("DEPRECATION")
@UnstableApi
class MainActivity : ComponentActivity() {

    private var mediaBrowser: MediaBrowserCompat? = null
    private val musicViewModel: MusicViewModel by viewModels()
    private var jobSeek: Job? = null
    private val scopeMain = CoroutineScope(Dispatchers.Main)
    private var lyricsPath = ""

    val bundle = Bundle()

    @JvmField
    val modifyMediaLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result != null && result.resultCode == RESULT_OK) {
                val resolver: ContentResolver = contentResolver
                val action = bundle.getString("action")
                if (OperateTypeInActivity.DeletePlayList.name == action) {
                    val u = bundle.getParcelable<Uri>("uri")
                    if (u != null) {
                        resolver.delete(u, null, null)
                    }
                } else if (OperateTypeInActivity.InsertTrackToPlaylist.name == action) {
                    val u = bundle.getParcelable<Uri>("uri")
                    val v = bundle.getParcelableArrayList<ContentValues>("values")
                    if (u != null && v != null) {
                        resolver.bulkInsert(u, v.toTypedArray())
                    }
                } else if (OperateTypeInActivity.RenamePlaylist.name == action) {
                    val u = bundle.getParcelable<Uri>("uri")
                    val v = bundle.getParcelable<ContentValues>("values")
                    if (u != null && v != null) {
                        resolver.update(u, v, null, null)
                    }
                } else if (OperateTypeInActivity.RemoveTrackFromPlayList.name == action) {
                    val playListPath = bundle.getString("playListPath", "")
                    val trackIndex = bundle.getInt("trackIndex", -1)
                    if (playListPath.isNotEmpty() && trackIndex != -1) {
                        removeTrackFromM3U(playListPath, trackIndex)
                        MediaScannerConnection.scanFile(
                            this@MainActivity,
                            arrayOf<String>(playListPath),
                            arrayOf("*/*"),
                            object : MediaScannerConnectionClient {
                                override fun onMediaScannerConnected() {}
                                override fun onScanCompleted(path: String, uri: Uri) {
                                    musicViewModel.mediaBrowser?.sendCustomAction(
                                        ACTION_PlayLIST_CHANGE,
                                        null,
                                        object : MediaBrowserCompat.CustomActionCallback() {
                                            override fun onResult(
                                                action: String?,
                                                extras: Bundle?,
                                                resultData: Bundle?
                                            ) {
                                                super.onResult(action, extras, resultData)
                                                if (ACTION_PlayLIST_CHANGE == action) {
                                                    musicViewModel.refreshList.value =
                                                        !musicViewModel.refreshList.value
                                                }
                                            }
                                        }
                                    )
                                }
                            })
                    }
                    return@registerForActivityResult
                } else if (OperateTypeInActivity.RemoveTrackFromStorage.name == action) {
                    val u = bundle.getParcelable<Uri>("uri")
                    val id = bundle.getLong("musicId")
                    if (u != null) {
                        contentResolver.delete(u, null, null)
                        val bundleTemp = Bundle()
                        bundleTemp.putLong("id", id)
                        musicViewModel.mediaBrowser?.sendCustomAction(
                            ACTION_TRACKS_DELETE,
                            bundleTemp,
                            object : MediaBrowserCompat.CustomActionCallback() {
                                override fun onResult(
                                    action: String?,
                                    extras: Bundle?,
                                    resultData: Bundle?
                                ) {
                                    super.onResult(action, extras, resultData)
                                    if (ACTION_TRACKS_DELETE == action) {
                                        musicViewModel.refreshList.value =
                                            !musicViewModel.refreshList.value
                                    }
                                }
                            }
                        )
                    }
                    return@registerForActivityResult
                } else if (OperateTypeInActivity.EditTrackInfo.name == action) {
                    val u = bundle.getParcelable<Uri>("uri")
                    val v = bundle.getParcelable<ContentValues>("value")
                    val id = bundle.getLong("id")
                    if (u != null && v != null) {
                        // TODO this is disable,  because cant  updating real file in storage
//                        values.put(MediaStore.Audio.Media.IS_PENDING, 1)
//                        contentResolver.update(uri, values, null, null)
                        resolver.update(u, v, null, null)
                        val bundleTemp = Bundle()
                        bundleTemp.putLong("id", id)
                        musicViewModel.mediaBrowser?.sendCustomAction(
                            ACTION_TRACKS_UPDATE,
                            bundleTemp,
                            object : MediaBrowserCompat.CustomActionCallback() {
                                override fun onResult(
                                    action: String?,
                                    extras: Bundle?,
                                    resultData: Bundle?
                                ) {
                                    super.onResult(action, extras, resultData)
                                    if (ACTION_TRACKS_UPDATE == action) {
                                        musicViewModel.refreshList.value =
                                            !musicViewModel.refreshList.value
                                    }
                                }
                            }
                        )
                    }

                }

                musicViewModel.mediaBrowser?.sendCustomAction(
                    ACTION_PlayLIST_CHANGE,
                    null,
                    object : MediaBrowserCompat.CustomActionCallback() {
                        override fun onResult(
                            action: String?,
                            extras: Bundle?,
                            resultData: Bundle?
                        ) {
                            super.onResult(action, extras, resultData)
                            if (ACTION_PlayLIST_CHANGE == action) {
                                musicViewModel.refreshList.value = !musicViewModel.refreshList.value
                            }
                        }
                    }
                )
            } else {
                bundle.clear()
            }
        }


    private val filePickerLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                if (result.data != null) {
                    val selectedFileUri: Uri? = result.data!!.data
                    if (selectedFileUri != null) {
                        val cursor = this@MainActivity.contentResolver.query(
                            selectedFileUri,
                            null,
                            null,
                            null,
                            null
                        ) ?: return@registerForActivityResult
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        cursor.moveToFirst()
                        val name = cursor.getString(nameIndex)
                        lyricsPath += if (name.lowercase().endsWith(".lrc")) {
                            "lrc"
                        } else if (name.lowercase().endsWith(".srt")) {
                            "srt"
                        } else if (name.lowercase().endsWith(".vtt")) {
                            "vtt"
                        } else if (name.lowercase().endsWith(".txt")) {
                            "txt"
                        } else {
                            return@registerForActivityResult
                        }
                        val inputStream =
                            this@MainActivity.contentResolver.openInputStream(selectedFileUri)
                        if (inputStream != null) {
                            val outputStream = FileOutputStream(File(lyricsPath))
                            val buffer = ByteArray(1024)
                            var length: Int
                            while (inputStream.read(buffer).also { length = it } > 0) {
                                outputStream.write(buffer, 0, length)
                            }
                            inputStream.close()
                            outputStream.close()
                        }
                        if (musicViewModel.currentPlay.value != null) {
                            musicViewModel.dealLyrics(
                                this@MainActivity,
                                musicViewModel.currentPlay.value!!
                            )
                        }
                        lyricsPath = ""
                    }
                }
            }
        }
    val folderPickerLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == RESULT_OK) {
                val treeUri = result.data?.data
                if (treeUri != null) {
                    contentResolver.takePersistableUriPermission(
                        treeUri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    CoroutineScope(Dispatchers.IO).launch {
                        musicViewModel.getDb(this@MainActivity).StorageFolderDao().insert(
                            StorageFolder(null, treeUri.toString())
                        )
                        musicViewModel.dealLyrics(
                            this@MainActivity,
                            musicViewModel.currentPlay.value!!
                        )
                    }

                }
            }
        }

    private fun openAppSettings() {
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
        intent.setData(Uri.fromParts("package", packageName, null))
        // Check if there is an activity that can handle this intent
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
            finish()
        }
    }


    fun openFilePicker(filePath: String) {
        lyricsPath = filePath
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.type = "*/*" // Specify the MIME type for text files
//        intent.addCategory(Intent.CATEGORY_OPENABLE)
//        intent.type = "text/plain|application/octet-stream";
        filePickerLauncher.launch(intent)
    }

    @OptIn(ExperimentalFoundationApi::class)
    private val audioPermissionRequest = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        if (it) {
            val i = Intent(this@MainActivity, PlayService::class.java)
            startService(i)  // Start the service explicitly
            mediaBrowser = MediaBrowserCompat(
                this,
                ComponentName(this, PlayService::class.java),
                connectionCallbacks,
                null // optional Bundle
            )
            setContent {
                MusicPitchTheme(musicViewModel) {
                    BaseLayout(musicViewModel, this@MainActivity)
                }
                mediaBrowser?.connect()
            }
        } else {
            compatSplashScreen?.setKeepOnScreenCondition { false }
            setContent {
                MusicPitchTheme(musicViewModel) {
                    // A surface container using the 'background' color from the theme
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .fillMaxHeight()
                            .fillMaxWidth()
                            .combinedClickable {
                                openAppSettings()
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = stringResource(R.string.can_t_find_any_audio_file_i_need_permission_click_here_to_open_settings),
                            fontSize = 20.sp,
                            color = Color.Red
                        )
                    }
                }
            }
        }
    }

    private var compatSplashScreen: SplashScreen? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        compatSplashScreen = installSplashScreen()
        compatSplashScreen?.setKeepOnScreenCondition { musicViewModel.mainTabList.isEmpty() }
        Utils.initSettingsData(musicViewModel, this)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                val i = Intent(this@MainActivity, PlayService::class.java)
                startService(i)  // Start the service explicitly
                setContent {
                    MusicPitchTheme(musicViewModel) {
                        BaseLayout(musicViewModel, this@MainActivity)
                    }
                }
            } else {
                audioPermissionRequest.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        } else if (ActivityCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val i = Intent(this@MainActivity, PlayService::class.java)
            startService(i)  // Start the service explicitly
            setContent {
                MusicPitchTheme(musicViewModel) {
                    BaseLayout(musicViewModel, this@MainActivity)
                }
            }
        } else {
            audioPermissionRequest.launch(Manifest.permission.READ_MEDIA_AUDIO)
        }
    }

    public override fun onStart() {
        super.onStart()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                mediaBrowser = MediaBrowserCompat(
                    this,
                    ComponentName(this, PlayService::class.java),
                    connectionCallbacks,
                    null // optional Bundle
                )
                mediaBrowser?.connect()
            }
        } else if (ActivityCompat.checkSelfPermission(
                this@MainActivity, Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            mediaBrowser = MediaBrowserCompat(
                this,
                ComponentName(this, PlayService::class.java),
                connectionCallbacks,
                null // optional Bundle
            )
            mediaBrowser?.connect()
        }
    }

    public override fun onResume() {
        super.onResume()
        getSeek()
        volumeControlStream = AudioManager.STREAM_MUSIC
    }

    override fun onPause() {
        jobSeek?.cancel()
        super.onPause()
    }

    public override fun onStop() {
        MediaControllerCompat.getMediaController(this)?.unregisterCallback(callback)
        musicViewModel.mediaController = null
        mediaBrowser?.disconnect()
        super.onStop()
    }

    override fun onDestroy() {
        // should invoke in onStop()
        musicViewModel.reset()
        super.onDestroy()
    }

    fun getSeek() {
        if (musicViewModel.playStatus.value) {
            jobSeek = scopeMain.launch {
                // i debug it, when i set it while(true),
                // this code is working (when it invoke cancel) also. who can tell me,this is why?
                while (isActive) {
                    delay(1000)
                    val f = mediaController.playbackState?.position ?: 0
                    if (f < 0) {
                        continue
                    }
                    if (!musicViewModel.sliderTouching) {
                        musicViewModel.sliderPosition.floatValue = f.toFloat()
                    }
                }
            }
        } else {
            jobSeek?.cancel()
        }
    }

    val callback = object : MediaControllerCompat.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackStateCompat?) {
            super.onPlaybackStateChanged(state)
            if (state != null) {
                musicViewModel.playStatus.value = state.state == PlaybackStateCompat.STATE_PLAYING
                getSeek()
            }
        }

        override fun onExtrasChanged(extras: Bundle?) {
            super.onExtrasChanged(extras)
            extras?.let {
                if (it.getInt("type") == EVENT_changePlayQueue) {
//                    val playList = it.getParcelable<MusicPlayList>("playList")
//                    musicViewModel.musicQueue.value = musicViewModel.musicListMap[playList?.id]
                } else if (it.getInt("type") == EVENT_MEDIA_ITEM_Change) {
                    // before switch to another music, must clear lyrics
                    val index = it.getInt("index")
                    if (index >= 0 && musicViewModel.musicQueue.size > index && index != musicViewModel.currentPlayQueueIndex.intValue) {
                        musicViewModel.currentCaptionList.clear()
                        musicViewModel.currentMusicCover.value = null
                        musicViewModel.currentPlay.value =
                            musicViewModel.musicQueue[index]
                        musicViewModel.currentPlayQueueIndex.intValue = index
                        musicViewModel.currentDuration.longValue =
                            musicViewModel.currentPlay.value?.duration ?: 0
                        musicViewModel.dealLyrics(
                            this@MainActivity,
                            musicViewModel.musicQueue[index]
                        )
                    }
                } else if (it.getInt("type") == EVENT_MEDIA_METADATA_Change) {
                    val cover = it.getByteArray("cover")
                    if (cover != null)
                        musicViewModel.currentMusicCover.value = BitmapFactory.decodeByteArray(
                            cover, 0,
                            cover.size
                        )
                } else if (it.getInt("type") == EVENT_SLEEP_TIME_Change) {
                    val remainTime = it.getLong("remaining")
                    musicViewModel.remainTime.longValue = remainTime
                    if (remainTime == 0L) {
                        musicViewModel.sleepTime.longValue = 0
                    }
                }
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadataCompat?) {
            super.onMetadataChanged(metadata)
        }

        override fun onQueueChanged(queue: MutableList<MediaSessionCompat.QueueItem>?) {
            super.onQueueChanged(queue)
        }

        override fun onQueueTitleChanged(title: CharSequence?) {
            super.onQueueTitleChanged(title)
        }

        override fun onAudioInfoChanged(info: MediaControllerCompat.PlaybackInfo?) {
            super.onAudioInfoChanged(info)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            super.onRepeatModeChanged(repeatMode)
            when (repeatMode) {
                PlaybackStateCompat.REPEAT_MODE_NONE -> {
                    musicViewModel.repeatModel.intValue = Player.REPEAT_MODE_OFF
                }

                PlaybackStateCompat.REPEAT_MODE_ALL -> {
                    musicViewModel.repeatModel.intValue = Player.REPEAT_MODE_ALL
                }

                PlaybackStateCompat.REPEAT_MODE_ONE -> {
                    musicViewModel.repeatModel.intValue = Player.REPEAT_MODE_ONE
                }

                PlaybackStateCompat.REPEAT_MODE_GROUP -> {
                }

                PlaybackStateCompat.REPEAT_MODE_INVALID -> {
                }
            }
        }
    }
    private val connectionCallbacks = object : MediaBrowserCompat.ConnectionCallback() {

        override fun onConnected() {
            // Get the token for the MediaSession
            mediaBrowser?.sessionToken.also { token ->
                // Create a MediaControllerCompat
                val mediaController = token?.let {
                    MediaControllerCompat(
                        this@MainActivity, // Context
                        it
                    )
                }
                musicViewModel.mediaBrowser = mediaBrowser
                musicViewModel.mediaController = mediaController
                val extras = mediaBrowser?.extras
                if (extras != null) {
                    getInitData(extras)
                }
                MediaControllerCompat.setMediaController(this@MainActivity, mediaController)
                mediaController?.registerCallback(callback)
            }
        }

        override fun onConnectionSuspended() {
        }

        override fun onConnectionFailed() {
            // The Service has refused our connection
        }

    }

    fun getInitData(resultData: Bundle) {
        resultData.getParcelableArrayList<MusicItem>("musicQueue")?.also {
            musicViewModel.musicQueue.clear()
            musicViewModel.musicQueue.addAll(it)
        }
        resultData.getParcelableArrayList<MusicItem>("songsList")?.also {
            musicViewModel.songsList.clear()
            musicViewModel.songsList.addAll(it)
        }
        resultData.getParcelableArrayList<MainTab>("mainTabList")?.also {
            musicViewModel.mainTabList.clear()
            musicViewModel.mainTabList.addAll(it)
        }
        resultData.getSerializable("playListCurrent")?.also {
            musicViewModel.playListCurrent.value = it as AnyListBase
        }
        val isPlaying = resultData.getBoolean("isPlaying")
        musicViewModel.playStatus.value = isPlaying
        val index = resultData.getInt("index")
        if (index >= 0 && musicViewModel.musicQueue.size > index && index != musicViewModel.currentPlayQueueIndex.intValue
        ) {
            musicViewModel.currentMusicCover.value = null
            musicViewModel.currentPlay.value =
                musicViewModel.musicQueue[index]
            musicViewModel.currentPlayQueueIndex.intValue = index
            musicViewModel.currentDuration.longValue =
                musicViewModel.currentPlay.value?.duration ?: 0
            if (musicViewModel.currentPlay.value != null) {
                musicViewModel.dealLyrics(
                    this@MainActivity,
                    musicViewModel.currentPlay.value!!
                )
            }
        }
        val pitch = resultData.getFloat("pitch", 1f)
        val speed = resultData.getFloat("speed", 1f)
        musicViewModel.pitch.floatValue = pitch
        musicViewModel.speed.floatValue = speed
        musicViewModel.sleepTime.longValue =
            resultData.getLong("sleepTime", 0)
        musicViewModel.remainTime.longValue =
            resultData.getLong("remaining")

        musicViewModel.enableEqualizer.value =
            resultData.getBoolean("equalizerEnable")
        val equalizerValue =
            resultData.getIntArray("equalizerValue") ?: intArrayOf(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0
            )
        musicViewModel.equalizerBands.forEachIndexed { index1, _ ->
            musicViewModel.equalizerBands[index1].value =
                equalizerValue[index1]
        }
        musicViewModel.delayTime.floatValue = resultData.getFloat("delayTime")
        musicViewModel.decay.floatValue = resultData.getFloat("decay")
        musicViewModel.enableEcho.value = resultData.getBoolean("echoActive")
        musicViewModel.echoFeedBack.value = resultData.getBoolean("echoFeedBack")
        musicViewModel.repeatModel.intValue = resultData.getInt("repeat", Player.REPEAT_MODE_ALL)
        musicViewModel.sliderPosition.floatValue = resultData.getFloat("position", 0F)
        // SleepTime wait when play next
        musicViewModel.playCompleted.value =
            resultData.getBoolean("play_completed")
        getSeek()
    }


}
