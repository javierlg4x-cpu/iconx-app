package cl.javier.iconx

import java.util.TimeZone

/**
 * Protocolo SPP del Gear IconX 2016 (SM-R150), descifrado del plugin oficial
 * com.samsung.accessory.triathlonmgr 2.3.18061462.
 *
 * Trama: [0xEF] [tipo<<4 | índice] [id] [largo] [datos...] [0xEE]
 *   tipo 0 = petición, 1 = respuesta. El índice siempre es 0.
 */
object Protocol {
    const val SOM: Byte = 0xEF.toByte()
    const val EOM: Byte = 0xEE.toByte()

    // IDs de mensaje (byte con signo, igual que en la app original)
    const val HF_STATUS: Byte = 0xA1.toByte()            // auricular -> tel: batería/uso
    const val PLAYLIST_CONFIG: Byte = 0xA2.toByte()      // aleatorio
    const val AUTOANSWER_CONFIG: Byte = 0xA4.toByte()    // contestar automático
    const val A2DP_VOL_STATUS: Byte = 0xA6.toByte()
    const val A2DP_VOL_SET: Byte = 0xA7.toByte()         // volumen interno 0..15
    const val EXERCISE_LIVE: Byte = 0xB6.toByte()        // datos en vivo de un ejercicio (incluye pulso)
    const val EXERCISE_STATUS: Byte = 0xBD.toByte()
    const val DEBUG_STATUS: Byte = 0x63                  // sensores: acelerómetros, temperatura, pulso
    const val ENABLE_HR: Byte = 0x65                     // encender/apagar sensor de pulso
    const val AMBIENT_CONFIG: Byte = 0xAB.toByte()       // sonido ambiente (soundthrough)
    const val RESET_REQ: Byte = 0xAC.toByte()
    const val AMBIENT_STATUS: Byte = 0xAF.toByte()
    const val STATUS_RES: Byte = 0xB3.toByte()           // estado completo
    const val UPDATE_TIME: Byte = 0xBA.toByte()
    const val STORAGE_REQ: Byte = 0xC0.toByte()
    const val STORAGE_RSP: Byte = 0xC1.toByte()
    const val MOBILE_MUSIC_CONTROL: Byte = 0xC2.toByte() // música desde el celular o desde los audífonos
    const val GENERAL_RSP: Byte = 0xFD.toByte()
    const val SERIAL_REQ: Byte = 0x66
    const val SERIAL_RES: Byte = 0x67

    // Mensajes con largo especial (texto unicode / lista de canciones)
    private const val LAST_PLAYED: Byte = 0xAA.toByte()
    private const val MUSIC_LIST_RSP: Byte = 0xCD.toByte()

    class Message(val type: Int, val id: Byte, val params: ByteArray)

    fun encode(id: Byte, params: ByteArray = ByteArray(0), type: Int = 0): ByteArray {
        val out = ByteArray(params.size + 5)
        out[0] = SOM
        out[1] = ((type and 0x0F) shl 4).toByte()
        out[2] = id
        out[3] = params.size.toByte()
        System.arraycopy(params, 0, out, 4, params.size)
        out[out.size - 1] = EOM
        return out
    }

    /** Acuse de recibo que la app original envía a cada mensaje del auricular. */
    fun ack(msg: Message, ok: Boolean = true): ByteArray =
        encode(GENERAL_RSP, byteArrayOf(msg.id, if (ok) 0 else 1), type = 1)

    fun updateTime(): ByteArray {
        val now = System.currentTimeMillis()
        val tz = TimeZone.getDefault().getOffset(now).toLong()
        val p = ByteArray(12)
        for (i in 0 until 8) p[i] = (now ushr (8 * i)).toByte()
        for (i in 0 until 4) p[8 + i] = (tz ushr (8 * i)).toByte()
        return encode(UPDATE_TIME, p)
    }

    /**
     * Extrae mensajes completos de [buf] (que puede traer basura o tramas partidas).
     * Devuelve los mensajes y cuántos bytes se consumieron.
     */
    fun parse(buf: ByteArray, len: Int): Pair<List<Message>, Int> {
        val msgs = ArrayList<Message>()
        var i = 0
        while (i < len) {
            if (buf[i] != SOM) { i++; continue }
            if (len - i < 5) break
            val id = buf[i + 2]
            val paramLen: Int = when (id) {
                LAST_PLAYED -> (buf[i + 3].toInt() and 0xFF) * 2 - 1
                MUSIC_LIST_RSP -> {
                    var count = buf[i + 3].toInt() and 0xFF
                    var p = 8
                    var complete = true
                    while (count-- > 0) {
                        if (i + p >= len) { complete = false; break }
                        val n = buf[i + p].toInt() and 0xFF
                        p += 1 + n * 2
                    }
                    if (!complete) -1 else p - 4
                }
                else -> buf[i + 3].toInt() and 0xFF
            }
            if (paramLen < 0) break
            val end = i + 4 + paramLen
            if (end >= len) break // falta el resto de la trama
            if (buf[end] != EOM) { i++; continue } // trama inválida, buscar la siguiente
            val type = (buf[i + 1].toInt() and 0xF0) shr 4
            msgs.add(Message(type, id, buf.copyOfRange(i + 4, end)))
            i = end + 1
        }
        return Pair(msgs, i)
    }

    fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    fun leShort(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) or (b[off + 1].toInt() shl 8)).toShort().toInt()

    fun leLong(b: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (k in 0 until n) v = v or ((b[off + k].toLong() and 0xFF) shl (8 * k))
        return v
    }
}
