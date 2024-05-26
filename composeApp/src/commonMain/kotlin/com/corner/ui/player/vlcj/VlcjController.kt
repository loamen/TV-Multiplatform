package com.corner.ui.player.vlcj

import com.corner.catvod.enum.bean.Vod
import com.corner.catvodcore.util.Utils
import com.corner.catvodcore.viewmodel.GlobalModel
import com.corner.database.Db
import com.corner.database.History
import com.corner.ui.player.PlayerController
import com.corner.ui.player.PlayerState
import com.corner.ui.scene.SnackBar
import com.corner.util.catch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.apache.commons.lang3.StringUtils
import org.slf4j.LoggerFactory
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import kotlin.time.Duration
import kotlin.time.DurationUnit

private val log = LoggerFactory.getLogger("PlayerController")

class VlcjController() : PlayerController {
    var player: EmbeddedMediaPlayer? = null
        private set
    private val defferredEffects = mutableListOf<(MediaPlayer) -> Unit>()

    private var isAccelerating = false
    private var originSpeed = 1.0F
    private var currentSpeed = 1.0F
    private var playerReady = false

    override var showTip = false
    override var tip = ""
    override var history: History? = null
    private var tipJob: Job? = null
    private var scope = CoroutineScope(Dispatchers.Default)

    internal val factory by lazy { MediaPlayerFactory() }

    override fun doWithMediaPlayer(block: (MediaPlayer) -> Unit) {
        player?.let {
            block(it)
        } ?: run {
            defferredEffects.add(block)
        }
    }

    override fun onMediaPlayerReady(mediaPlayer: EmbeddedMediaPlayer) {
        this.player = mediaPlayer
        _state.update { it.copy(duration = player?.status()?.length() ?: 0L) }
        defferredEffects.forEach { block ->
            block(mediaPlayer)
        }
        defferredEffects.clear()
    }

//    init {
//        addSearchPath(
//            RuntimeUtil.getLibVlcLibraryName(),
//            Paths.get(System.getProperty("user.dir"), "lib", "libvlc.dll").pathString
//        )
//        NativeDiscovery().discover()
//    }

    val stateListener = object : MediaPlayerEventAdapter() {
        override fun mediaPlayerReady(mediaPlayer: MediaPlayer) {
            log.info("播放器初始化完成")
            playerReady = true
            _state.update { it.copy(duration = mediaPlayer.status().length()) }
            scope.launch {
                delay(1000)
                catch {
                    mediaPlayer.audio().setVolume(50)
//                    seekTo(history?.position ?: 0L)
//                    speed(history?.speed?.toFloat() ?: 1f)
                }
            }
        }

        override fun playing(mediaPlayer: MediaPlayer) {
            _state.update { it.copy(isPlaying = true) }
        }

        override fun paused(mediaPlayer: MediaPlayer) {
            _state.update { it.copy(isPlaying = false) }
        }

        override fun stopped(mediaPlayer: MediaPlayer) {
            _state.update { it.copy(isPlaying = false) }
        }

        override fun finished(mediaPlayer: MediaPlayer) {
            _state.update { it.copy(isPlaying = false) }
//            component.nextEP()
        }

        override fun muted(mediaPlayer: MediaPlayer, muted: Boolean) {
            _state.update { it.copy(isMuted = muted) }
        }

        override fun volumeChanged(mediaPlayer: MediaPlayer, volume: Float) {
            _state.update { it.copy(volume = volume) }
        }

        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) {
            _state.update { it.copy(timestamp = newTime) }
        }

        override fun error(mediaPlayer: MediaPlayer?) {
            log.error("播放错误: ${mediaPlayer?.media()?.info()?.mrl()}")
            SnackBar.postMsg("播放错误")
            super.error(mediaPlayer)
        }
    }

    private val _state = MutableStateFlow(PlayerState())

    override val state: StateFlow<PlayerState>
        get() = _state.asStateFlow()

    override fun init() {
    }

    override fun load(url: String): PlayerController {
        log.debug("加载：$url")
        if (StringUtils.isBlank(url)) {
            return this
        }
        catch {
            dispose()
            player = factory.mediaPlayers()?.newEmbeddedMediaPlayer()?.apply {
                events().addMediaPlayerEventListener(stateListener)
                media().prepare(url)
                video().setScale(1.0f)
            }
        }
        return this
    }

    override fun play() = catch {
        log.debug("play")
        showTips("播放")
        player?.controls()?.play()
    }

    override fun play(url: String) = catch {
        showTips("播放")
        log.debug("play: $url")
        player?.media()?.play(url)
    }

    override fun pause() = catch {
        showTips("暂停")
        player?.controls()?.setPause(true)
    }

    private fun showTips(text: String) {
        tip = text
        showTip = true
        tipJob?.cancel()
        tipJob = scope.launch {
            delay(1500)
            showTip = false
        }
    }

    override fun stop() = catch {
        showTips("停止")
        player?.controls()?.stop()
    }

    override fun dispose() = catch {
        log.debug("dispose")
        player?.run {
            controls().stop()
            events().removeMediaPlayerEventListener(stateListener)
            release()
        }
    }

    override fun seekTo(timestamp: Long) = catch {
        player?.controls()?.setTime(timestamp)
    }

    override fun setVolume(value: Float) = catch {
        showTips("音量：$value")
        player?.audio()?.setVolume((value * 100).toInt().coerceIn(0..150))
    }

    private val volumeStep = 5

    override fun volumeUp() {
        player?.audio()?.setVolume((((player?.audio()?.volume() ?: 0) + volumeStep).coerceIn(0..150)))
        showTips("音量：${player?.audio()?.volume()}")
    }

    override fun volumeDown() {
        player?.audio()?.setVolume((((player?.audio()?.volume() ?: 0) - volumeStep).coerceIn(0..150)))
        showTips("音量：${player?.audio()?.volume()}")
    }

    /**
     * 快进 单位 秒
     */
    override fun forward(time: String) {
        showTips("快进：$time")
        player?.controls()?.skipTime(Duration.parse(time).toLong(DurationUnit.MILLISECONDS))
    }

    override fun backward(time: String) {
        showTips("快退：$time")
        player?.controls()?.skipTime(-Duration.parse(time).toLong(DurationUnit.MILLISECONDS))
    }

    override fun toggleSound() = catch {
        player?.audio()?.mute()
    }

    override fun toggleFullscreen() = catch {
        GlobalModel.toggleVideoFullScreen()
    }

    override fun togglePlayStatus() {
        if (player?.status()?.isPlaying == true) {
            pause()
        } else {
            play()
        }
    }

    override fun speed(speed: Float) = catch {
        showTips("倍速：$speed")
        player?.controls()?.setRate(speed)
    }

    override fun stopForward() {
        isAccelerating = false
        speed(originSpeed)
    }

    override fun fastForward() {
        if (!isAccelerating) {
            currentSpeed = player?.status()?.rate() ?: 1.0f
            originSpeed = currentSpeed.toDouble().toFloat()
            isAccelerating = true
        }
        acceleratePlayback()
    }

    private val maxSpeed = 8.0f

    private fun acceleratePlayback() {
        if (isAccelerating) {
            currentSpeed += 0.5f
            currentSpeed = Math.min(currentSpeed, maxSpeed)
            speed(currentSpeed)
            println("Playback rate: $currentSpeed x")
        }
    }

    override fun updateEnding(detail: Vod?) {
        if (_state.value.ending == -1L) {
            _state.update { it.copy(ending = player?.status()?.time() ?: -1) }
        } else {
            _state.update { it.copy(ending = -1) }
        }
        scope.launch {
            Db.History.updateOpeningEnding(
                _state.value.opening,
                _state.value.ending,
                Utils.getHistoryKey(detail?.site?.key!!, detail.vodId)
            )
        }
    }

    override fun updateOpening(detail: Vod?) {
        if (_state.value.opening == -1L) {
            _state.update { it.copy(opening = player?.status()?.time() ?: -1) }
        } else {
            _state.update { it.copy(opening = -1) }
        }
        scope.launch {
            Db.History.updateOpeningEnding(
                _state.value.opening,
                _state.value.ending,
                Utils.getHistoryKey(detail?.site?.key!!, detail.vodId)
            )
        }
    }

    override fun setStartEnding(opening: Long, ending: Long) {
        _state.update { it.copy(opening = opening, ending = ending) }
    }

}
