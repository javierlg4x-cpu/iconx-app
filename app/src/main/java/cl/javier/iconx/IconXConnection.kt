package cl.javier.iconx

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Conexión RFCOMM con el IconX en un hilo propio. */
class IconXConnection(
    private val device: BluetoothDevice,
    private val listener: Listener,
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected(reason: String)
        fun onMessage(msg: Protocol.Message)
        fun onLog(line: String)
    }

    companion object {
        // UUID propio de Samsung (no es el SPP estándar 00001101)
        val SPP_UUID: UUID = UUID.fromString("00011101-0000-1000-8000-00805f9b34fb")
    }

    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: OutputStream? = null
    @Volatile private var closed = false

    @SuppressLint("MissingPermission")
    fun start() {
        Thread({
            try {
                listener.onLog("Conectando a ${device.name} (${device.address})…")
                val s = openSocket()
                socket = s
                out = s.outputStream
                listener.onConnected()
                readLoop(s.inputStream)
            } catch (e: Exception) {
                if (!closed) listener.onDisconnected(e.message ?: e.javaClass.simpleName)
            } finally {
                closeQuietly()
            }
        }, "IconX-SPP").start()
    }

    @SuppressLint("MissingPermission")
    private fun openSocket(): BluetoothSocket {
        val attempts = listOf<Pair<String, () -> BluetoothSocket>>(
            "seguro" to { device.createRfcommSocketToServiceRecord(SPP_UUID) },
            "inseguro" to { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID) },
        )
        var last: Exception? = null
        for ((name, make) in attempts) {
            var s: BluetoothSocket? = null
            try {
                s = make()
                s.connect()
                listener.onLog("Canal $name abierto")
                return s
            } catch (e: Exception) {
                listener.onLog("Canal $name falló: ${e.message}")
                last = e
                try { s?.close() } catch (_: IOException) {}
            }
        }
        throw last ?: IOException("No se pudo abrir el canal")
    }

    private fun readLoop(input: InputStream) {
        val buf = ByteArray(8192)
        var len = 0
        val chunk = ByteArray(1024)
        while (!closed) {
            val n = input.read(chunk)
            if (n < 0) throw IOException("El auricular cerró la conexión")
            if (len + n > buf.size) len = 0 // desborde: descartar
            System.arraycopy(chunk, 0, buf, len, n)
            len += n
            listener.onLog("← " + Protocol.hex(chunk.copyOf(n)))
            val (msgs, used) = Protocol.parse(buf, len)
            if (used > 0) {
                System.arraycopy(buf, used, buf, 0, len - used)
                len -= used
            }
            for (m in msgs) {
                // La app original confirma estos dos mensajes (OK si traen el largo esperado)
                when (m.id) {
                    Protocol.HF_STATUS -> send(Protocol.ack(m, m.params.size == 11), quiet = true)
                    Protocol.STATUS_RES -> send(Protocol.ack(m, m.params.size == 25), quiet = true)
                }
                listener.onMessage(m)
            }
        }
    }

    @Synchronized
    fun send(frame: ByteArray, quiet: Boolean = false): Boolean {
        val o = out ?: return false
        return try {
            o.write(frame)
            o.flush()
            listener.onLog((if (quiet) "→ (ack) " else "→ ") + Protocol.hex(frame))
            true
        } catch (e: IOException) {
            listener.onLog("Error al enviar: ${e.message}")
            false
        }
    }

    fun close() {
        closed = true
        closeQuietly()
    }

    private fun closeQuietly() {
        try { socket?.close() } catch (_: IOException) {}
        socket = null
        out = null
    }
}
