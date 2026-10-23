package com.snuffy.app

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Callable
import java.util.concurrent.FutureTask
import kotlin.math.max

class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private lateinit var player: ExoPlayer
    private val main = Handler(Looper.getMainLooper())

    private var queue = JSONArray()
    private var index = -1
    private var shuffle = false
    private var repeat = false
    private var volume = 70
    private var state = "stopped"
    private var error = ""
    private var gen = 0

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!Python.isStarted()) Python.start(AndroidPlatform(this))

        player = ExoPlayer.Builder(this).build()
        player.volume = volume / 100f
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) advance()
            }

            override fun onPlayerError(e: PlaybackException) {
                state = "error"
                error = "Playback failed"
            }
        })

        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = WebViewClient()
            addJavascriptInterface(Bridge(), "AndroidBridge")
            loadUrl("file:///android_asset/index.html")
        }
        setContentView(web)
    }

    override fun onDestroy() {
        player.release()
        super.onDestroy()
    }

    // ---------- helpers ----------
    private fun str(o: JSONObject, k: String): String =
        if (o.isNull(k)) "" else o.optString(k)

    /** Runs on the main thread and waits for the result. ExoPlayer must be used on the main thread. */
    private fun onMain(block: () -> String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val task = FutureTask(Callable { block() })
        main.post(task)
        return task.get()
    }

    // ---------- playback (main thread only) ----------
    private fun applyQueue(arr: JSONArray, start: Int) {
        queue = arr
        playIndex(start)
    }

    private fun playIndex(i: Int) {
        val n = queue.length()
        if (n == 0) return
        index = ((i % n) + n) % n
        val item = queue.getJSONObject(index)
        val id = str(item, "id")
        val target = if (id.isNotEmpty())
            "https://www.youtube.com/watch?v=$id"
        else
            "ytsearch1:" + str(item, "title")
        val g = ++gen
        player.stop()
        state = "loading"
        error = ""

        Thread {
            try {
                val raw = Python.getInstance().getModule("ytresolve")
                    .callAttr("stream_url", target).toString()
                val info = JSONObject(raw)
                val url = info.getString("url")
                val headers = info.optJSONObject("headers") ?: JSONObject()
                main.post {
                    if (g != gen) return@post
                    startPlayback(url, headers)
                }
            } catch (e: Exception) {
                main.post {
                    if (g == gen) {
                        state = "error"
                        error = "Could not load this track"
                    }
                }
            }
        }.start()
    }

    private fun startPlayback(url: String, headers: JSONObject) {
        val props = HashMap<String, String>()
        val keys = headers.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            props[k] = headers.optString(k)
        }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setDefaultRequestProperties(props)
        val source = DefaultMediaSourceFactory(httpFactory)
            .createMediaSource(MediaItem.fromUri(url))
        player.setMediaSource(source)
        player.prepare()
        player.play()
        state = "playing"
    }

    private fun advance() {
        val n = queue.length()
        if (n == 0) return
        if (repeat) {
            playIndex(index)
            return
        }
        val next = if (shuffle && n > 1)
            (0 until n).filter { it != index }.random()
        else
            index + 1
        if (next >= n) {
            state = "stopped"
            return
        }
        playIndex(next)
    }

    private fun control(action: String, value: String) {
        when (action) {
            "toggle" -> {
                if (state == "playing" && player.isPlaying) {
                    player.pause()
                } else if (state == "playing") {
                    player.play()
                } else if (queue.length() > 0) {
                    playIndex(max(index, 0))
                }
            }
            "next" -> {
                val n = queue.length()
                if (n == 0) return
                playIndex(if (shuffle && n > 1) (0 until n).random() else index + 1)
            }
            "prev" -> {
                if (player.currentPosition > 3000) player.seekTo(0)
                else playIndex(index - 1)
            }
            "seek" -> player.seekTo(((value.toDoubleOrNull() ?: 0.0) * 1000).toLong())
            "volume" -> {
                volume = value.toIntOrNull() ?: volume
                player.volume = volume / 100f
            }
            "shuffle" -> shuffle = !shuffle
            "repeat" -> repeat = !repeat
            "stop" -> {
                gen++
                player.stop()
                state = "stopped"
            }
        }
    }

    private fun statusJson(): JSONObject {
        val o = JSONObject()
        val t = queue.optJSONObject(index)
        o.put("state", state)
        o.put("error", error)
        o.put("paused", state == "playing" && !player.isPlaying)
        o.put("shuffle", shuffle)
        o.put("repeat", repeat)
        o.put("volume", volume)
        o.put("index", index)
        o.put("position", player.currentPosition / 1000.0)
        o.put("duration", if (player.duration > 0) player.duration / 1000.0 else 0.0)

        if (t == null) {
            o.put("track", JSONObject.NULL)
        } else {
            o.put(
                "track",
                JSONObject()
                    .put("title", str(t, "title"))
                    .put("artist", str(t, "artist"))
                    .put("thumb", str(t, "thumb"))
            )
        }

        val q = JSONArray()
        for (i in 0 until queue.length()) {
            val item = queue.getJSONObject(i)
            q.put(
                JSONObject()
                    .put("title", str(item, "title"))
                    .put("artist", str(item, "artist"))
            )
        }
        o.put("queue", q)
        return o
    }

    // ---------- JavaScript bridge (called from index.html) ----------
    inner class Bridge {

        @JavascriptInterface
        fun status(): String = onMain { this@MainActivity.statusJson().toString() }

        @JavascriptInterface
        fun control(action: String, value: String) {
            main.post { this@MainActivity.control(action, value) }
        }

        @JavascriptInterface
        fun setQueue(json: String, index: Int) {
            main.post { this@MainActivity.applyQueue(JSONArray(json), index) }
        }

        @JavascriptInterface
        fun load(name: String): String = onMain {
            if (name != "songs.txt" && name != "song.txt") {
                return@onMain JSONObject().put("error", "unknown list").toString()
            }
            val lines = try {
                this@MainActivity.assets.open(name).bufferedReader().readLines()
            } catch (e: Exception) {
                return@onMain JSONObject().put("error", "$name not found in the app").toString()
            }
            val items = lines.map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
            if (items.isEmpty()) {
                return@onMain JSONObject().put("error", "list is empty").toString()
            }
            val arr = JSONArray()
            items.shuffled().forEach {
                arr.put(JSONObject().put("title", it).put("artist", name))
            }
            this@MainActivity.applyQueue(arr, 0)
            JSONObject().put("count", items.size).toString()
        }

        @JavascriptInterface
        fun search(q: String) {
            Thread {
                val json = try {
                    Python.getInstance().getModule("ytresolve")
                        .callAttr("search", q).toString()
                } catch (e: Exception) {
                    "[]"
                }
                main.post {
                    web.evaluateJavascript("window.__searchRes && window.__searchRes($json)", null)
                }
            }.start()
        }
    }
}
