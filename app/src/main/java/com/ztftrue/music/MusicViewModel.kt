package com.ztftrue.music

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.session.MediaControllerCompat
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.text.style.TextAlign
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.ViewModel
import androidx.media3.common.Player
import androidx.navigation.NavHostController
import com.ztftrue.music.sqlData.MusicDatabase
import com.ztftrue.music.sqlData.model.DictionaryApp
import com.ztftrue.music.sqlData.model.MainTab
import com.ztftrue.music.sqlData.model.MusicItem
import com.ztftrue.music.ui.play.Lyrics
import com.ztftrue.music.utils.CaptionUtils
import com.ztftrue.music.utils.LyricsType
import com.ztftrue.music.utils.PlayListType
import com.ztftrue.music.utils.ScrollDirectionType
import com.ztftrue.music.utils.Utils
import com.ztftrue.music.utils.model.AnyListBase
import com.ztftrue.music.utils.model.Caption
import com.ztftrue.music.utils.model.EqualizerBand
import com.ztftrue.music.utils.model.ListStringCaption
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader


var SongsPlayList = AnyListBase(-1, PlayListType.Songs)
var QueuePlayList = AnyListBase(-1, PlayListType.Queue)


class MusicViewModel : ViewModel() {

    private val retriever = MediaMetadataRetriever()
    var db: MusicDatabase? = null
    val refreshList = mutableStateOf(false)
    var navController: NavHostController? = null
    var themeSelected = mutableIntStateOf(0)
    val albumItemsCount = mutableIntStateOf(2)
    val artistItemsCount = mutableIntStateOf(2)
    val genreItemsCount = mutableIntStateOf(2)
    var mediaController: MediaControllerCompat? = null
    var mediaBrowser: MediaBrowserCompat? = null

    val albumScrollDirection = mutableStateOf(ScrollDirectionType.GRID_VERTICAL)
    val artistScrollDirection = mutableStateOf(ScrollDirectionType.GRID_VERTICAL)
    val genreScrollDirection = mutableStateOf(ScrollDirectionType.GRID_VERTICAL)

    // 当前播放的列表，应该换数据结构存储，每个列表设置变量 播放状态，album和 genres 也是，艺术家跳转到 album， 然后在下一步处理
    // 每次播放仅设置当前列表的状态

    var musicQueue = mutableStateListOf<MusicItem>()

    var currentMusicCover = mutableStateOf<Bitmap?>(null)
    var currentPlay = mutableStateOf<MusicItem?>(null)

    var currentPlayQueueIndex = mutableIntStateOf(0)
    var songsList = mutableStateListOf<MusicItem>()
    var playListCurrent = mutableStateOf<AnyListBase?>(null)

    var delayTime = mutableFloatStateOf(0.5f)
    var decay = mutableFloatStateOf(1f)
    var echoFeedBack = mutableStateOf(false)

    var pitch = mutableFloatStateOf(1f)
    var speed = mutableFloatStateOf(1f)
    var currentDuration = mutableLongStateOf(0)

    var mainTabList = mutableStateListOf<MainTab>()

    var sliderPosition = mutableFloatStateOf(0F)
    var sliderTouching = false
    var autoScroll = mutableStateOf(true)
    var autoHighLight = mutableStateOf(true)

    var dictionaryAppList = mutableStateListOf<DictionaryApp>()

    var enableEqualizer = mutableStateOf(false)
    var enableEcho = mutableStateOf(false)

    var playStatus = mutableStateOf(false)
    var equalizerBands = mutableStateListOf<EqualizerBand>()

    // lyrics
    var itemDuration: Long = 1
    var lyricsType: LyricsType = LyricsType.TEXT
    var currentCaptionList = mutableStateListOf<ListStringCaption>()

    var fontSize = mutableIntStateOf(18)
    var textAlign = mutableStateOf(TextAlign.Center)

    // sleep time
    var sleepTime = mutableLongStateOf(0L)
    var remainTime = mutableLongStateOf(0L)
    var playCompleted = mutableStateOf(false)

    var repeatModel = mutableIntStateOf(Player.REPEAT_MODE_ALL)
    fun reset() {
        if (currentPlay.value != null) {
            currentCaptionList.clear()
        }
        mediaBrowser = null
        mediaController = null
        currentPlay.value = null
        currentMusicCover.value = null
        playListCurrent.value = null
        musicQueue.clear()
        songsList.clear()
        mainTabList.clear()
        sliderPosition.floatValue = 0f
        sleepTime.longValue = 0
        remainTime.longValue = 0
        playCompleted.value = false
        repeatModel.intValue = Player.REPEAT_MODE_ALL
    }

    init {
        val band = ArrayList<EqualizerBand>()
        for (index in 0 until Utils.kThirdOct.size) {
            val hz = if (Utils.kThirdOct[index] < 1000) {
                "${Utils.kThirdOct[index]}"
            } else {
                "${Utils.kThirdOct[index] / 1000}k"
            }
            band.add(EqualizerBand(index, hz, 0))
        }
        equalizerBands.addAll(band)
    }

    fun getDb(context: Context): MusicDatabase {
        if (db == null) {
            db = MusicDatabase.getDatabase(context)
        }
        return db!!
    }

    fun dealLyrics(context: Context, currentPlay: MusicItem) {
        currentCaptionList.clear()
        if (currentCaptionList.isEmpty()) {
            val regexPattern = Regex("[<>\"/~'{}?,+=)(^&*%!@#\$]")
            val artistsFolder = currentPlay.artist.replace(
                regexPattern,
                "_"
            )
            val folderPath = "$Lyrics/$artistsFolder"
            val folder = context.getExternalFilesDir(
                folderPath
            )
            folder?.mkdirs()
            val id = currentPlay.name.replace(regexPattern, "_")
            val path = "${context.getExternalFilesDir(folderPath)?.absolutePath}/$id"
            val text = File("$path.txt")
            if (text.exists()) {
                lyricsType = LyricsType.TEXT
                currentCaptionList.addAll(
                    readCaptions(
                        text.bufferedReader(),
                        LyricsType.TEXT,
                        context
                    )
                )
            } else if (File("$path.lrc").exists()) {
                lyricsType = LyricsType.LRC
                currentCaptionList.addAll(
                    readCaptions(
                        File("$path.lrc").bufferedReader(),
                        LyricsType.LRC,
                        context
                    )
                )
            } else if (File("$path.srt").exists()) {
                lyricsType = LyricsType.SRT
                currentCaptionList.addAll(
                    readCaptions(
                        File("$path.srt").bufferedReader(),
                        LyricsType.SRT,
                        context
                    )
                )
            } else if (File("$path.vtt").exists()) {
                lyricsType = LyricsType.VTT
                currentCaptionList.addAll(
                    readCaptions(
                        File("$path.vtt").bufferedReader(),
                        LyricsType.VTT,
                        context
                    )
                )
            } else {
                CoroutineScope(Dispatchers.IO).launch {
                    getDb(context).StorageFolderDao().findAll()?.forEach { storageFolder ->
                        val treeUri = Uri.parse(storageFolder.uri)
                        if (treeUri != null) {
                            context.contentResolver.takePersistableUriPermission(
                                treeUri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                            )
                            val pickedDir = DocumentFile.fromTreeUri(context, treeUri)
                            val d = pickedDir?.listFiles()
                            d?.forEach {
                                if (it.isFile && it.canRead()
                                ) {
                                    val musicName: String = try {
                                        currentPlay.path.substring(
                                            currentPlay.path.lastIndexOf("/")+1,
                                            currentPlay.path.lastIndexOf(".")
                                        )
                                    } catch (e: Exception) {
                                        ""
                                    }
                                    val fileNameWithSuffix = it.name?.lowercase() ?: ""
                                    val fileName = try {
                                        fileNameWithSuffix.substring(
                                            0,
                                            fileNameWithSuffix.lastIndexOf(".")
                                        )
                                    } catch (e: Exception) {
                                        ""
                                    }
                                    if (fileName.trim().lowercase() == musicName.trim().lowercase()) {
                                        if (fileNameWithSuffix.endsWith(".lrc")) {
                                            fileRead(it.uri, context, LyricsType.LRC)
                                        } else if (fileNameWithSuffix.endsWith(".srt")) {
                                            fileRead(it.uri, context, LyricsType.SRT)
                                        } else if (fileNameWithSuffix.endsWith(".vtt")) {
                                            fileRead(it.uri, context, LyricsType.VTT)
                                        } else if (fileNameWithSuffix.endsWith(".txt")) {
                                            fileRead(it.uri, context, LyricsType.TEXT)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                return
            }
        }
        val duration = currentPlay.duration
        // every lyrics line duration
        itemDuration = duration / if (currentCaptionList.size == 0) 1 else currentCaptionList.size
    }

    private fun fileRead(uri: Uri, context: Context, captionType: LyricsType) {
        lyricsType = captionType
        val inputStream =
            context.contentResolver.openInputStream(uri)
        val reader =
            BufferedReader(InputStreamReader(inputStream))
        currentCaptionList.addAll(readCaptions(reader, lyricsType, context))
    }

    private fun readCaptions(
        bufferedReader: BufferedReader,
        captionType: LyricsType,
        context: Context
    ): ArrayList<ListStringCaption> {
        val captions = arrayListOf<Caption>()
        if (captionType == LyricsType.SRT) {
            captions.addAll(CaptionUtils.parseSrtFile(bufferedReader))
        } else if (captionType == LyricsType.VTT) {
            captions.addAll(CaptionUtils.parseVttFile(bufferedReader))
        } else if (captionType == LyricsType.LRC) {
            captions.addAll(CaptionUtils.parseLrcFile(bufferedReader, context))
        } else if (captionType == LyricsType.TEXT) {
            captions.addAll(CaptionUtils.parseTextFile(bufferedReader, context))
        }
        val arrayList = arrayListOf<ListStringCaption>()
        captions.forEach {
            val an = ListStringCaption(
                text = ArrayList(it.text.split(Regex("[\\n\\r\\s]+"))),
                timeStart = it.timeStart,
                timeEnd = it.timeEnd
            )
            arrayList.add(an)
        }

        return arrayList
    }

    fun getCover(path: String): Bitmap? {
        try {
            retriever.setDataSource(path)
            val coverT = retriever.embeddedPicture
            if (coverT != null) {
                return BitmapFactory.decodeByteArray(coverT, 0, coverT.size)
            }
        } catch (_: Exception) {
        }
        return null
    }

    fun getCurrentMusicCover(): Bitmap? {
        if (currentMusicCover.value != null) {
            return currentMusicCover.value
        }
        val v = currentPlay.value
        if (v != null) {
            currentMusicCover.value = getCover(v.path)
            return currentMusicCover.value
        }
        return null
    }

    fun getAlbumCover(id: Long, context: Context): Bitmap? {
        try {
            val albumUri = ContentUris.withAppendedId(
                Uri.parse("content://media/external/audio/albumart"),
                id
            )
            return BitmapFactory.decodeStream(
                context.contentResolver.openInputStream(
                    albumUri
                )
            )
        } catch (_: Exception) {

        }
        return null
    }
}
