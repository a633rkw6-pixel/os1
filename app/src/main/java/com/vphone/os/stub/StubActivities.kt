package com.vphone.os.stub

import android.annotation.SuppressLint
import android.app.Activity
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vphone.os.core.VirtualNativeBridge
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

open class BaseStubActivity : Activity()
class StubActivityP0 : BaseStubActivity()
class StubActivityP1 : BaseStubActivity()
class StubActivityP2 : BaseStubActivity()
class StubActivityP3 : BaseStubActivity()

/**
 * Runs inside isolated process :vp0 using a standard unmodified Android AudioRecord.
 * Proves end-to-end that real apps inside VPhoneOS receive the ClearVoice-processed
 * voice from the host phone microphone without hardware mic conflict.
 */
class VirtualMicTestActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    VirtualMicTestScreen(onClose = { finish() })
                }
            }
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun VirtualMicTestScreen(onClose: () -> Unit) {
    var connected by remember { mutableStateOf(VirtualNativeBridge.nativeIsVirtualMicConnected()) }
    var isRecording by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var meter by remember { mutableFloatStateOf(0f) }
    var statusText by remember { mutableStateOf("Ready inside Virtual Process :vp0") }
    var recordedPcm by remember { mutableStateOf<ShortArray?>(null) }

    val recordThreadRef = remember { mutableStateOf<Thread?>(null) }
    val stopFlag = remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Virtual Phone Mic Test (:vp0)",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "This activity runs inside the virtual phone process (:vp0) and opens a standard Android AudioRecord(16kHz). Our native hook transparently feeds it from your primary phone's ClearVoice Live engine.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (connected) "Virtual Mic Bus: CONNECTED" else "Virtual Mic Bus: WAITING (Start ClearVoice on Host)",
                    fontWeight = FontWeight.SemiBold,
                    color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(text = statusText, style = MaterialTheme.typography.bodySmall)
                LinearProgressIndicator(
                    progress = { meter },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                )
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                connected = VirtualNativeBridge.nativeIsVirtualMicConnected()
                if (!isRecording) {
                    stopFlag.value = false
                    isRecording = true
                    statusText = "Recording 16kHz PCM from Virtual Mic..."
                    val t = Thread {
                        val sampleRate = 16000
                        val minBuf = AudioRecord.getMinBufferSize(
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT
                        ).coerceAtLeast(2048)

                        val rec = AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            sampleRate,
                            AudioFormat.CHANNEL_IN_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            minBuf * 2
                        )
                        val chunk = ShortArray(640)
                        val outStream = ByteArrayOutputStream()
                        try {
                            rec.startRecording()
                            while (!stopFlag.value) {
                                val n = rec.read(chunk, 0, chunk.size)
                                if (n > 0) {
                                    var sq = 0.0
                                    val bb = ByteBuffer.allocate(n * 2).order(ByteOrder.LITTLE_ENDIAN)
                                    for (i in 0 until n) {
                                        val s = chunk[i]
                                        bb.putShort(s)
                                        val f = s / 32768.0
                                        sq += f * f
                                    }
                                    outStream.write(bb.array())
                                    meter = (sqrt(sq / n).toFloat() * 5f).coerceIn(0f, 1f)
                                }
                            }
                        } finally {
                            runCatching { rec.stop() }
                            runCatching { rec.release() }
                            val bytes = outStream.toByteArray()
                            val shorts = ShortArray(bytes.size / 2)
                            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                            recordedPcm = shorts
                            meter = 0f
                            isRecording = false
                            statusText = "Captured ${shorts.size / 16000f}s of ClearVoice audio inside :vp0!"
                        }
                    }.apply { start() }
                    recordThreadRef.value = t
                } else {
                    stopFlag.value = true
                }
            }
        ) {
            Text(if (isRecording) "Stop Test Recording" else "Record Test Clip from Virtual Mic")
        }

        FilledTonalButton(
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = recordedPcm != null && !isRecording && !isPlaying,
            onClick = {
                val pcm = recordedPcm ?: return@FilledTonalButton
                isPlaying = true
                statusText = "Playing back voice recorded inside Virtual Phone..."
                Thread {
                    val sampleRate = 16000
                    val minOut = AudioTrack.getMinBufferSize(
                        sampleRate,
                        AudioFormat.CHANNEL_OUT_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                    ).coerceAtLeast(2048)
                    val track = AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                .setSampleRate(sampleRate)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                                .build()
                        )
                        .setBufferSizeInBytes(minOut)
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build()
                    try {
                        track.play()
                        track.write(pcm, 0, pcm.size)
                    } finally {
                        runCatching { track.stop() }
                        runCatching { track.release() }
                        isPlaying = false
                        statusText = "Playback complete."
                    }
                }.start()
            }
        ) {
            Text(if (isPlaying) "Playing..." else "Play Back Recorded Virtual Mic Clip")
        }

        OutlinedButton(
            modifier = Modifier.fillMaxWidth(),
            onClick = onClose
        ) {
            Text("Back to VPhoneOS Launcher")
        }
    }
}
