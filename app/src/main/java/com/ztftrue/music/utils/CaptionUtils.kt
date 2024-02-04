package com.ztftrue.music.utils

import android.content.Context
import com.ztftrue.music.R
import java.io.File
import java.util.Locale
import java.util.regex.Matcher
import java.util.regex.Pattern
enum class LyricsType {
    TEXT,
    LRC,
    SRT,
    VTT,
}

object CaptionUtils {
    fun parseVttFile(file: File): List<Caption> {
        val captions = mutableListOf<Caption>()
        file.bufferedReader().useLines { lines ->
            var startTime = 0L
            var endTime = 0L
            val text = StringBuilder()
            for (line in lines) {
                // Check if the line represents a time range
                if (line.matches(Regex("\\d{2}:\\d{2}:\\d{2}\\.\\d{3} --> \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"))) {
                    if (text.isNotEmpty()) {
                        captions.add(Caption(text.toString().trim(), startTime, endTime))
                        text.clear()
                    }

                    // Parse the time range
                    val times = line.split(" --> ")
                    startTime = captionTimestampToMilliseconds(times[0])
                    endTime = captionTimestampToMilliseconds(times[1])
                } else {
                    // Add the text to the current caption
                    text.append(line).append("\n")
                }
            }

            // Add the last caption
            if (text.isNotEmpty()) {
                captions.add(Caption(text.toString().trim(), startTime, endTime))
            }
        }

        return captions
    }

    fun parseSrtFile(file: File): List<Caption> {
        val subtitles = mutableListOf<Caption>()

        file.bufferedReader().useLines { lines ->
            var startTime = 0L
            var endTime = 0L
            var text = StringBuilder()

            for (line in lines) {
                when {
                    line.isBlank() -> {
                        // Blank line indicates the end of a subtitle
                        if (text.isNotEmpty()) {
                            subtitles.add(Caption(text.toString().trim(), startTime, endTime))
                            text = StringBuilder()
                        }
                    }

                    line.matches(Regex("\\d+ --> \\d+")) -> {
                        // Time range line
                        val times = line.split(Pattern.compile("\\s+-->\\s+"))
                        startTime = captionTimestampToMilliseconds(times[0])
                        endTime = captionTimestampToMilliseconds(times[1])
                    }

                    else -> {
                        // Text line
                        text.append(line).append("\n")
                    }
                }
            }

            // Add the last subtitle
            if (text.isNotEmpty()) {
                subtitles.add(Caption(text.toString().trim(), startTime, endTime))
            }
        }

        return subtitles
    }

    private fun captionTimestampToMilliseconds(timestamp: String): Long {
        val parts = timestamp.split(":")
        val hours = parts[0].toLong()
        val minutes = parts[1].toLong()
        val secondsAndMilliseconds = parts[2].split(".")
        val seconds = secondsAndMilliseconds[0].toLong()
        val milliseconds = secondsAndMilliseconds[1].toLong()

        return ((hours * 3600 + minutes * 60 + seconds) * 1000 + milliseconds)
    }
    /**
     * timeStr minutes:seconds.milliseconds
     */
    private fun parseTime(timeStr: String?): Long {
        if (timeStr.isNullOrEmpty()) {
            return 0L
        }
        val timeParts = timeStr.split(":".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray()
        val minutes = timeParts[0].toInt()
        val secondsParts = timeParts[1].split("\\.".toRegex()).dropLastWhile { it.isEmpty() }
            .toTypedArray()
        val seconds = secondsParts[0].toInt()
        val milliseconds = secondsParts[1].toInt()
        return (minutes * 60 * 1000L + seconds * 1000 + milliseconds)
    }

    // other
    // [al:专辑名]
    // [ar:歌手名]
    // [au:歌词作者-作曲家]
    // [by:此LRC文件的创建者]
    // [offset:+/- 时间补偿值，以毫秒为单位，正值表示加快，负值表示延后]
    // [re:创建此LRC文件的播放器或编辑器]
    // [ti:歌词(歌曲)的标题]
    // [ve:程序的版本]
    private var RString: Map<String, Int> = mapOf(
        "al" to R.string.al,
        "ar" to R.string.ar,
        "au" to R.string.au,
        "by" to R.string.by,
        "re" to R.string.re,
        "ti" to R.string.ti,
        "ve" to R.string.ve
    )

    private fun parseLyricOtherMessage(message: String, context: Context): String {
        val pattern: Pattern = Pattern.compile("\\[([a-z]+):(.*)]")
        val matcher: Matcher = pattern.matcher(message)
        if (matcher.matches()) {
            val name = matcher.group(1)
            val text = matcher.group(2)
            if (name != null) {
                val id = RString[name]
                if (id != null) {
                    return context.getString(id) + text
                }
            }

        }
        return message.replace("[", "").replace("]", "").replaceFirstChar {
            if (it.isLowerCase()) it.titlecase(
                Locale.getDefault()
            ) else it.toString()
        }
    }

    /**
     *  [00:07.04][00:57.36]I don't want a lot for Christmas
     *  or
     *  [00:07.04]I don't want a lot for Christmas
     *  or
     *  I don't want a lot for Christmas
     */
    fun parseLyricLine(line: String, context: Context): Caption {
        // time
        val s = line.replace("\r", "")
        val pattern: Pattern = Pattern.compile("\\[([0-9]+:[0-9]+\\.[0-9]+)](.*)")
        val matcher: Matcher = pattern.matcher(s)
        if (matcher.matches()) {
            val timeStr = matcher.group(1)
            val text = matcher.group(2)
            val time = parseTime(timeStr)
            return Caption(text ?: "", time)
        }
        return Caption(parseLyricOtherMessage(s, context), 0)
    }
}