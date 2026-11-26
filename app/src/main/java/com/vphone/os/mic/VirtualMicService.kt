package com.vphone.os.mic

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Captures the primary phone's physical microphone, applies ClearVoice Live
 * real-time DSP (noise reduction, clarity, warmth, Male->Female voice changer),
 * and broadcasts the processed voice to every app running inside VPhoneOS.
 *
 * Also supports optional external UDP loopback audio input (127.0.0.1:48123)
 * if you want an external microphone app on your primary phone to feed VPhoneOS.
 */
class VirtualMicService : Service() {

    private var audioThread: Thread? = null
    private val busServer = VirtualMicBusServer()
    @Volatile private var running = false

    companion object {
        private const val TAG = "VirtualMicService"
        const val CHANNEL_ID = "vphone_clearvoice_mic_channel"
        const val NOTIFICATION_ID = 201
        const val SAMPLE_RATE = 48000
        const val EXTERNAL_UDP_PORT = 48123
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        val notification = buildNotification()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                0
            }
        )

        if (!running) {
            busServer.start()
            startAudioPipeline()
        }
        return START_STICKY
    }

    @SuppressLint("MissingPermission")
    private fun startAudioPipeline() {
        running = true
        AudioEngineState.setRunning(true)

        audioThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)

            val minIn = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val minOut = AudioTrack.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val frameSamples = 960 // 20ms @ 48kHz
            val bufferBytes = maxOf(minIn, minOut, frameSamples * 2)
            val bufferShorts = ShortArray(frameSamples)

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes * 2
            )

            val sessionId = audioRecord.audioSessionId
            val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(sessionId)?.apply { enabled = true } else null
            val agc = if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(sessionId)?.apply { enabled = true } else null
            val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } else null

            val audioTrack = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(bufferBytes)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .build()

            var udpSocket: DatagramSocket? = null
            val udpBuf = ByteArray(frameSamples * 2)
            val dsp = VoiceDspProcessor(SAMPLE_RATE.toFloat())

            try {
                audioRecord.startRecording()
                audioTrack.play()

                while (running) {
                    val samplesRead: Int
                    if (AudioEngineState.externalUdpInputEnabled) {
                        if (udpSocket == null) {
                            udpSocket = runCatching {
                                DatagramSocket(EXTERNAL_UDP_PORT, InetAddress.getByName("127.0.0.1")).apply {
                                    soTimeout = 50
                                }
                            }.getOrNull()
                        }
                        val pkt = DatagramPacket(udpBuf, udpBuf.size)
                        val received = try {
                            udpSocket?.receive(pkt)
                            pkt.length / 2
                        } catch (_: Exception) {
                            0
                        }
                        if (received > 0) {
                            val bb = ByteBuffer.wrap(udpBuf, 0, received * 2).order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until received) {
                                bufferShorts[i] = bb.short
                            }
                            samplesRead = received
                        } else {
                            // Keep hardware capture drained while waiting for external UDP packets
                            samplesRead = audioRecord.read(bufferShorts, 0, bufferShorts.size)
                        }
                    } else {
                        if (udpSocket != null) {
                            runCatching { udpSocket?.close() }
                            udpSocket = null
                        }
                        samplesRead = audioRecord.read(bufferShorts, 0, bufferShorts.size)
                    }

                    if (samplesRead > 0) {
                        val level = dsp.processBuffer(bufferShorts, samplesRead)
                        AudioEngineState.updateLevel(level)

                        // 1. Send processed voice to every app inside the Virtual Phone
                        if (AudioEngineState.routeToVirtualPhone) {
                            busServer.broadcastPcm16(bufferShorts, samplesRead)
                        }

                        // 2. Optional local headphone monitor
                        if (AudioEngineState.localHeadphoneMonitor) {
                            audioTrack.write(bufferShorts, 0, samplesRead)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio pipeline error", e)
            } finally {
                runCatching { udpSocket?.close() }
                runCatching { audioRecord.stop() }
                runCatching { audioRecord.release() }
                runCatching { audioTrack.stop() }
                runCatching { audioTrack.release() }
                runCatching { ns?.release() }
                runCatching { agc?.release() }
                runCatching { aec?.release() }
                busServer.stop()
                AudioEngineState.updateLevel(0f)
                AudioEngineState.setRunning(false)
            }
        }.apply {
            name = "VPhoneClearVoicePipeline"
            start()
        }
    }

    override fun onDestroy() {
        running = false
        busServer.stop()
        audioThread?.join(600)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "VPhoneOS Virtual Microphone",
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("ClearVoice Virtual Mic Active")
            .setContentText("Routing real phone microphone -> VPhoneOS apps")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
}
