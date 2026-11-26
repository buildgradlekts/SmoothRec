package com.smoothrec.app.capture

import android.content.Intent
import com.smoothrec.app.recording.AudioSourceOption

/** Instantánea ligera del estado del motor de grabación (emitida ~1 Hz). */
data class RecordingTelemetry(
    val width: Int = 0,
    val height: Int = 0,
    val configuredFps: Int = 60,
    val effectiveFps: Int = 0,
    val bitrateBps: Int = 0,
    val audioSource: AudioSourceOption = AudioSourceOption.INTERNAL,
    val audioActive: Boolean = false,
    val audioSilent: Boolean = false,
    val encoderState: String = STATE_READY,
    val paused: Boolean = false,
    val elapsedActiveMs: Long = 0L,
    val lastError: String? = null,
    // Métricas reales añadidas para diagnóstico de rendimiento. No se muestran
    // como FPS estimados: captureFps solo existe cuando hay SurfaceTexture real.
    val captureFps: Int = 0,
    val droppedFrames: Long = 0L,
    val captureBacklogMax: Int = 0,
    val frameAgeMaxMs: Int = 0,
    val ptsGapFrames: Long = 0L,
    val maxPtsGapMs: Int = 0,
    val slowWrites: Int = 0,
    val maxWriteMs: Int = 0,
    val thermalStatus: Int = THERMAL_UNAVAILABLE,
) {
    fun toIntent(): Intent = Intent(ACTION_RECORDING_STATS).apply {
        putExtra(EXTRA_WIDTH, width)
        putExtra(EXTRA_HEIGHT, height)
        putExtra(EXTRA_CONFIGURED_FPS, configuredFps)
        putExtra(EXTRA_EFFECTIVE_FPS, effectiveFps)
        putExtra(EXTRA_BITRATE_BPS, bitrateBps)
        putExtra(EXTRA_AUDIO_SOURCE, audioSource.key)
        putExtra(EXTRA_AUDIO_ACTIVE, audioActive)
        putExtra(EXTRA_AUDIO_SILENT, audioSilent)
        putExtra(EXTRA_ENCODER_STATE, encoderState)
        putExtra(EXTRA_PAUSED, paused)
        putExtra(EXTRA_ELAPSED_MS, elapsedActiveMs)
        putExtra(EXTRA_LAST_ERROR, lastError)
        putExtra(EXTRA_CAPTURE_FPS, captureFps)
        putExtra(EXTRA_DROPPED_FRAMES, droppedFrames)
        putExtra(EXTRA_CAPTURE_BACKLOG_MAX, captureBacklogMax)
        putExtra(EXTRA_FRAME_AGE_MAX_MS, frameAgeMaxMs)
        putExtra(EXTRA_PTS_GAP_FRAMES, ptsGapFrames)
        putExtra(EXTRA_MAX_PTS_GAP_MS, maxPtsGapMs)
        putExtra(EXTRA_SLOW_WRITES, slowWrites)
        putExtra(EXTRA_MAX_WRITE_MS, maxWriteMs)
        putExtra(EXTRA_THERMAL_STATUS, thermalStatus)
    }

    companion object {
        const val ACTION_RECORDING_STATS = "com.smoothrec.app.action.RECORDING_STATS"
        const val EXTRA_WIDTH = "extra_width"
        const val EXTRA_HEIGHT = "extra_height"
        const val EXTRA_CONFIGURED_FPS = "extra_configured_fps"
        const val EXTRA_EFFECTIVE_FPS = "extra_effective_fps"
        const val EXTRA_BITRATE_BPS = "extra_bitrate_bps"
        const val EXTRA_AUDIO_SOURCE = "extra_audio_source"
        const val EXTRA_AUDIO_ACTIVE = "extra_audio_active"
        const val EXTRA_AUDIO_SILENT = "extra_audio_silent"
        const val EXTRA_ENCODER_STATE = "extra_encoder_state"
        const val EXTRA_PAUSED = "extra_paused"
        const val EXTRA_ELAPSED_MS = "extra_elapsed_ms"
        const val EXTRA_LAST_ERROR = "extra_last_error"

        const val EXTRA_CAPTURE_FPS = "extra_capture_fps"
        const val EXTRA_DROPPED_FRAMES = "extra_dropped_frames"
        const val EXTRA_CAPTURE_BACKLOG_MAX = "extra_capture_backlog_max"
        const val EXTRA_FRAME_AGE_MAX_MS = "extra_frame_age_max_ms"
        const val EXTRA_PTS_GAP_FRAMES = "extra_pts_gap_frames"
        const val EXTRA_MAX_PTS_GAP_MS = "extra_max_pts_gap_ms"
        const val EXTRA_SLOW_WRITES = "extra_slow_writes"
        const val EXTRA_MAX_WRITE_MS = "extra_max_write_ms"
        const val EXTRA_THERMAL_STATUS = "extra_thermal_status"

        const val STATE_READY = "ready"
        const val STATE_RUNNING = "running"
        const val STATE_PAUSED = "paused"
        const val STATE_ERROR = "error"

        const val THERMAL_UNAVAILABLE = -1

        fun fromIntent(intent: Intent): RecordingTelemetry = RecordingTelemetry(
            width = intent.getIntExtra(EXTRA_WIDTH, 0),
            height = intent.getIntExtra(EXTRA_HEIGHT, 0),
            configuredFps = intent.getIntExtra(EXTRA_CONFIGURED_FPS, 60),
            effectiveFps = intent.getIntExtra(EXTRA_EFFECTIVE_FPS, 0),
            bitrateBps = intent.getIntExtra(EXTRA_BITRATE_BPS, 0),
            audioSource = AudioSourceOption.fromKey(intent.getStringExtra(EXTRA_AUDIO_SOURCE)),
            audioActive = intent.getBooleanExtra(EXTRA_AUDIO_ACTIVE, false),
            audioSilent = intent.getBooleanExtra(EXTRA_AUDIO_SILENT, false),
            encoderState = intent.getStringExtra(EXTRA_ENCODER_STATE) ?: STATE_READY,
            paused = intent.getBooleanExtra(EXTRA_PAUSED, false),
            elapsedActiveMs = intent.getLongExtra(EXTRA_ELAPSED_MS, 0L),
            lastError = intent.getStringExtra(EXTRA_LAST_ERROR),
            captureFps = intent.getIntExtra(EXTRA_CAPTURE_FPS, 0),
            droppedFrames = intent.getLongExtra(EXTRA_DROPPED_FRAMES, 0L),
            captureBacklogMax = intent.getIntExtra(EXTRA_CAPTURE_BACKLOG_MAX, 0),
            frameAgeMaxMs = intent.getIntExtra(EXTRA_FRAME_AGE_MAX_MS, 0),
            ptsGapFrames = intent.getLongExtra(EXTRA_PTS_GAP_FRAMES, 0L),
            maxPtsGapMs = intent.getIntExtra(EXTRA_MAX_PTS_GAP_MS, 0),
            slowWrites = intent.getIntExtra(EXTRA_SLOW_WRITES, 0),
            maxWriteMs = intent.getIntExtra(EXTRA_MAX_WRITE_MS, 0),
            thermalStatus = intent.getIntExtra(EXTRA_THERMAL_STATUS, THERMAL_UNAVAILABLE),
        )
    }
}
