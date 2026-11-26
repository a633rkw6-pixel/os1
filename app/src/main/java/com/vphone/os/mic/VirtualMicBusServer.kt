package com.vphone.os.mic

import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Broadcasts 48kHz mono PCM-16 processed voice frames from the primary phone
 * host process to all virtual phone processes (:vp0 - :vp3) over a Linux
 * abstract Unix domain socket (@vphone_vmic_bus).
 */
class VirtualMicBusServer {

    companion object {
        private const val TAG = "VirtualMicBusServer"
        const val SOCKET_NAME = "vphone_vmic_bus"
    }

    private data class ClientSlot(
        val socket: LocalSocket,
        val out: OutputStream
    )

    private val clients = CopyOnWriteArrayList<ClientSlot>()
    @Volatile private var running = false
    private var serverSocket: LocalServerSocket? = null
    private var acceptThread: Thread? = null
    private var scratchBytes = ByteArray(4096)

    fun start() {
        if (running) return
        running = true
        acceptThread = Thread {
            try {
                serverSocket = LocalServerSocket(SOCKET_NAME)
                Log.i(TAG, "VirtualMicBusServer listening on @$SOCKET_NAME")
                while (running) {
                    val sock = serverSocket?.accept() ?: break
                    try {
                        sock.sendBufferSize = 32768
                        sock.soTimeout = 100
                        clients.add(ClientSlot(sock, sock.outputStream))
                        AudioEngineState.updateConnectedVirtualApps(clients.size)
                        Log.i(TAG, "Virtual app process connected. Active listeners=${clients.size}")
                    } catch (e: IOException) {
                        runCatching { sock.close() }
                    }
                }
            } catch (e: IOException) {
                if (running) Log.e(TAG, "Accept loop error: ${e.message}")
            }
        }.apply {
            name = "VPhoneMicBusAccept"
            isDaemon = true
            start()
        }
    }

    fun broadcastPcm16(samples: ShortArray, sampleCount: Int) {
        if (clients.isEmpty() || sampleCount <= 0) return

        val byteCount = sampleCount * 2
        if (scratchBytes.size < byteCount) {
            scratchBytes = ByteArray(byteCount)
        }
        val bb = ByteBuffer.wrap(scratchBytes, 0, byteCount).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until sampleCount) {
            bb.putShort(samples[i])
        }

        var removedAny = false
        for (client in clients) {
            try {
                client.out.write(scratchBytes, 0, byteCount)
            } catch (e: IOException) {
                runCatching { client.socket.close() }
                clients.remove(client)
                removedAny = true
            }
        }
        if (removedAny) {
            AudioEngineState.updateConnectedVirtualApps(clients.size)
        }
    }

    fun stop() {
        running = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        for (client in clients) {
            runCatching { client.socket.close() }
        }
        clients.clear()
        AudioEngineState.updateConnectedVirtualApps(0)
    }
}
