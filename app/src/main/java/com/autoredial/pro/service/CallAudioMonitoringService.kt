package com.autoredial.pro.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.telecom.TelecomManager
import androidx.core.app.NotificationCompat
import com.autoredial.pro.R
import com.autoredial.pro.audio.AudioToneClassifier
import com.autoredial.pro.telecom.CallManager
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class CallAudioMonitoringService : Service() {

    companion object {
        const val ACTION_START_REDIAL = "com.autoredial.pro.START_REDIAL"
        const val ACTION_STOP_REDIAL = "com.autoredial.pro.STOP_REDIAL"
        const val ACTION_CALL_STATE_CHANGED = "com.autoredial.pro.CALL_STATE_CHANGED"
        const val ACTION_REDIAL_TERMINATED = "com.autoredial.pro.REDIAL_TERMINATED"

        const val EXTRA_PHONE_NUMBER = "extra_phone_number"
        const val EXTRA_REDIAL_DELAY_MS = "extra_redial_delay_ms"
        const val EXTRA_AUTO_SPEAKER = "extra_auto_speaker"
        const val EXTRA_MUTE_REDIALS = "extra_mute_redials"
        const val EXTRA_STATE_NAME = "extra_state_name"
        const val EXTRA_STATE_DETAIL = "extra_state_detail"
        const val EXTRA_ATTEMPT_COUNT = "extra_attempt_count"
        const val EXTRA_CUT_LATENCY_MS = "extra_cut_latency_ms"
        const val EXTRA_VAD_ENERGY = "extra_vad_energy"
        const val EXTRA_TERMINATION_REASON = "extra_termination_reason"

        private const val NOTIFICATION_CHANNEL_ID = "channel_autoredial_service"
        private const val NOTIFICATION_ID = 1001

        private const val SAMPLE_RATE = 16000
        private const val CHUNK_DURATION_MS = 20
        private const val BUFFER_SIZE_SAMPLES = (SAMPLE_RATE * CHUNK_DURATION_MS) / 1000
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val isRunning = AtomicBoolean(false)
    private var isAudioSampling = false

    private var targetPhoneNumber = ""
    private var redialDelayMs = 50L
    private var isAutoSpeakerEnabled = true
    private var isMuteRedialsEnabled = true
    private var attemptCount = 0

    private var audioManager: AudioManager? = null
    private var audioRecord: AudioRecord? = null
    private val toneClassifier = AudioToneClassifier()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_REDIAL -> {
                targetPhoneNumber = intent.getStringExtra(EXTRA_PHONE_NUMBER) ?: ""
                redialDelayMs = intent.getLongExtra(EXTRA_REDIAL_DELAY_MS, 50L)
                isAutoSpeakerEnabled = intent.getBooleanExtra(EXTRA_AUTO_SPEAKER, true)
                isMuteRedialsEnabled = intent.getBooleanExtra(EXTRA_MUTE_REDIALS, true)

                startForeground(NOTIFICATION_ID, buildForegroundNotification("AutoRedial Pro Active"))
                startRedialLoop()
            }
            ACTION_STOP_REDIAL -> {
                stopRedialLoop("User requested stop")
            }
        }
        return START_NOT_STICKY
    }

    private fun startRedialLoop() {
        if (isRunning.getAndSet(true)) return
        attemptCount = 0

        serviceScope.launch {
            while (isRunning.get()) {
                attemptCount++
                val dialStartTime = System.currentTimeMillis()

                if (isMuteRedialsEnabled) {
                    silenceDialingAudio(true)
                }

                broadcastState(
                    state = "DIALING...",
                    detail = "Placing attempt #$attemptCount to $targetPhoneNumber",
                    attempts = attemptCount
                )

                initiateOutgoingCall(targetPhoneNumber)
                delay(120)

                val result = monitorCallAudioUntilCutOrConnected(dialStartTime)

                if (result == CallResult.CONNECTED) {
                    restoreAudioStreams()
                    if (isAutoSpeakerEnabled) {
                        setSpeakerphone(true)
                    }

                    broadcastState("CONNECTED!", "Recipient answered! Smart stop initiated.", attemptCount)
                    stopRedialLoop("Call connected with remote party")
                    break
                }

                if (!isRunning.get()) break

                broadcastState(
                    state = "DIALING...",
                    detail = "Instant cut executed. Redialing in ${redialDelayMs}ms...",
                    attempts = attemptCount
                )
                delay(redialDelayMs)
            }
        }
    }

    private suspend fun monitorCallAudioUntilCutOrConnected(dialStartTime: Long): CallResult = withContext(Dispatchers.IO) {
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val bufferSize = maxOf(minBufferSize, BUFFER_SIZE_SAMPLES * 2)

        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }

            audioRecord?.startRecording()
            isAudioSampling = true

            val audioBuffer = ShortArray(BUFFER_SIZE_SAMPLES)
            var consecutiveVoiceFrames = 0
            val voiceThresholdRms = 280.0

            val maxCallWaitTimeoutMs = 45000L
            val callStartEpoch = System.currentTimeMillis()

            while (isRunning.get() && isAudioSampling) {
                if (System.currentTimeMillis() - callStartEpoch > maxCallWaitTimeoutMs) {
                    disconnectCallInstantly()
                    return@withContext CallResult.DISCONNECTED_TIMEOUT
                }

                if (CallManager.isCallActive()) {
                    return@withContext CallResult.CONNECTED
                }

                val readCount = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (readCount <= 0) {
                    delay(5)
                    continue
                }

                var sum = 0.0
                for (i in 0 until readCount) {
                    sum += audioBuffer[i] * audioBuffer[i]
                }
                val rms = sqrt(sum / readCount)
                val normalizedEnergy = (rms / 3000.0).toFloat().coerceIn(0f, 1f)

                val toneType = toneClassifier.classifyAudioChunk(audioBuffer, readCount, SAMPLE_RATE)

                when (toneType) {
                    AudioToneClassifier.ToneType.RINGBACK_CADENCE -> {
                        restoreAudioStreams()
                        if (isAutoSpeakerEnabled) {
                            setSpeakerphone(true)
                        }

                        broadcastState(
                            state = "RINGING...",
                            detail = if (isAutoSpeakerEnabled) "Ringing detected (Speaker ON)" else "Ringing cadence locked. Keeping call alive.",
                            attempts = attemptCount,
                            vadEnergy = normalizedEnergy
                        )
                        consecutiveVoiceFrames = 0
                    }

                    AudioToneClassifier.ToneType.BUSY_OR_REORDER -> {
                        val cutSpeedMs = System.currentTimeMillis() - dialStartTime
                        disconnectCallInstantly()
                        broadcastState(
                            state = "VOICE / BUSY DETECTED!",
                            detail = "Busy / Congestion tone detected. Disconnected in ${cutSpeedMs}ms!",
                            attempts = attemptCount,
                            cutLatencyMs = cutSpeedMs,
                            vadEnergy = normalizedEnergy
                        )
                        return@withContext CallResult.DISCONNECTED_BUSY
                    }

                    AudioToneClassifier.ToneType.HUMAN_VOICE_OR_IVR -> {
                        if (rms > voiceThresholdRms) {
                            consecutiveVoiceFrames++
                            if (consecutiveVoiceFrames >= 2) {
                                val cutSpeedMs = System.currentTimeMillis() - dialStartTime
                                disconnectCallInstantly()
                                broadcastState(
                                    state = "VOICE / BUSY DETECTED!",
                                    detail = "IVR voice prompt cut in ${cutSpeedMs}ms! Redialing...",
                                    attempts = attemptCount,
                                    cutLatencyMs = cutSpeedMs,
                                    vadEnergy = normalizedEnergy
                                )
                                return@withContext CallResult.DISCONNECTED_IVR
                            }
                        }
                    }

                    AudioToneClassifier.ToneType.SILENCE -> {
                        consecutiveVoiceFrames = 0
                        broadcastState(
                            state = "DETECTING AUDIO...",
                            detail = "Monitoring audio stream (<20ms VAD window)...",
                            attempts = attemptCount,
                            vadEnergy = normalizedEnergy
                        )
                    }
                }
            }

            CallResult.DISCONNECTED_MANUAL
        } finally {
            cleanupAudioRecord()
        }
    }

    private fun setSpeakerphone(enable: Boolean) {
        try {
            audioManager?.let { am ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val speakerDevice = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (enable && speakerDevice != null) {
                        am.setCommunicationDevice(speakerDevice)
                    } else {
                        am.clearCommunicationDevice()
                    }
                } else {
                    @Suppress("DEPRECATION")
                    am.isSpeakerphoneOn = enable
                }
            }
        } catch (_: Exception) {}
    }

    private fun silenceDialingAudio(mute: Boolean) {
        try {
            audioManager?.let { am ->
                val direction = if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE
                am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, direction, 0)
            }
        } catch (_: Exception) {}
    }

    private fun restoreAudioStreams() {
        silenceDialingAudio(false)
    }

    private fun initiateOutgoingCall(phoneNumber: String) {
        try {
            val callIntent = Intent(Intent.ACTION_CALL).apply {
                data = Uri.parse("tel:${Uri.encode(phoneNumber)}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(callIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun disconnectCallInstantly() {
        val inCallHandled = CallManager.disconnectActiveCall()
        if (!inCallHandled) {
            try {
                val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    telecomManager.endCall()
                }
            } catch (_: SecurityException) {
                CallManager.triggerAccessibilityEndCall()
            }
        }
    }

    private fun stopRedialLoop(reason: String) {
        isRunning.set(false)
        isAudioSampling = false
        restoreAudioStreams()
        cleanupAudioRecord()

        val intent = Intent(ACTION_REDIAL_TERMINATED).apply {
            putExtra(EXTRA_TERMINATION_REASON, reason)
        }
        sendBroadcast(intent)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun cleanupAudioRecord() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
        } catch (_: Exception) {}
        audioRecord = null
        isAudioSampling = false
    }

    private fun broadcastState(
        state: String,
        detail: String,
        attempts: Int,
        cutLatencyMs: Long = -1L,
        vadEnergy: Float = 0f
    ) {
        val intent = Intent(ACTION_CALL_STATE_CHANGED).apply {
            putExtra(EXTRA_STATE_NAME, state)
            putExtra(EXTRA_STATE_DETAIL, detail)
            putExtra(EXTRA_ATTEMPT_COUNT, attempts)
            putExtra(EXTRA_CUT_LATENCY_MS, cutLatencyMs)
            putExtra(EXTRA_VAD_ENERGY, vadEnergy)
        }
        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "AutoRedial Pro Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(content: String): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("AutoRedial Pro")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_call)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopRedialLoop("Service destroyed")
        serviceScope.cancel()
    }

    enum class CallResult {
        CONNECTED,
        DISCONNECTED_IVR,
        DISCONNECTED_BUSY,
        DISCONNECTED_TIMEOUT,
        DISCONNECTED_MANUAL
    }
}
