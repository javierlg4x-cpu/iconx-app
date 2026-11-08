package cl.javier.iconx

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CompoundButton
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@SuppressLint("SetTextI18n", "UseSwitchCompatOrMaterialCode")
class MainActivity : Activity(), IconXConnection.Listener {

    private val ui = Handler(Looper.getMainLooper())
    private var conn: IconXConnection? = null
    private var connected = false
    private var updatingFromDevice = false
    private val logLines = ArrayDeque<String>()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private lateinit var statusText: TextView
    private lateinit var deviceText: TextView
    private lateinit var connectBtn: Button
    private lateinit var batteryText: TextView
    private lateinit var wearText: TextView
    private lateinit var ambientSw: Switch
    private lateinit var autoAnswerSw: Switch
    private lateinit var shuffleSw: Switch
    private lateinit var sourceGroup: RadioGroup
    private lateinit var srcPhone: RadioButton
    private lateinit var srcBuds: RadioButton
    private lateinit var storageText: TextView
    private lateinit var volumeBar: SeekBar
    private lateinit var volumeText: TextView
    private lateinit var hrText: TextView
    private lateinit var hrDetail: TextView
    private lateinit var hrBtn: Button
    private var measuringHr = false
    private val hrPoll = object : Runnable {
        override fun run() {
            if (!measuringHr || !connected) return
            send(Protocol.DEBUG_STATUS)
            ui.postDelayed(this, 2000)
        }
    }
    private lateinit var logText: TextView
    private lateinit var controls: List<View>

    private val prefs by lazy { getSharedPreferences("iconx", Context.MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        setControlsEnabled(false)
        refreshDeviceLabel()
        ensurePermission()
    }

    override fun onDestroy() {
        conn?.close()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun section(parent: LinearLayout, title: String): LinearLayout {
        val t = TextView(this).apply {
            text = title
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTypeface(typeface, Typeface.BOLD)
            alpha = 0.7f
            setPadding(0, dp(20), 0, dp(6))
        }
        parent.addView(t)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        parent.addView(box)
        return box
    }

    private fun body(text: String, sizeSp: Float = 16f) = TextView(this).apply {
        this.text = text
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun switch(label: String, onChange: (Boolean) -> Unit) = Switch(this).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setPadding(0, dp(10), 0, dp(10))
        setOnCheckedChangeListener { _: CompoundButton, checked: Boolean ->
            if (!updatingFromDevice) onChange(checked)
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        root.addView(TextView(this).apply {
            text = "IconX Control"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f)
            setTypeface(typeface, Typeface.BOLD)
        })
        statusText = body("Desconectado", 15f).apply { alpha = 0.8f }
        root.addView(statusText)

        // Conexión
        val c = section(root, "AUDÍFONOS")
        deviceText = body("")
        c.addView(deviceText)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "Elegir"
            setOnClickListener { pickDevice() }
        }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        connectBtn = Button(this).apply {
            text = "Conectar"
            setOnClickListener { if (connected || conn != null) disconnect() else connect() }
        }
        row.addView(connectBtn, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        c.addView(row)

        // Estado
        val s = section(root, "BATERÍA")
        batteryText = body("Izquierdo: –    Derecho: –", 18f)
        wearText = body("Uso: –", 15f).apply { alpha = 0.8f }
        s.addView(batteryText)
        s.addView(wearText)

        // Ajustes
        val a = section(root, "AJUSTES")
        ambientSw = switch("Sonido ambiente") { send(Protocol.AMBIENT_CONFIG, byteArrayOf(if (it) 1 else 0)) }
        autoAnswerSw = switch("Contestar llamadas automáticamente") {
            send(Protocol.AUTOANSWER_CONFIG, byteArrayOf(if (it) 1 else 0))
        }
        a.addView(ambientSw)
        a.addView(autoAnswerSw)
        volumeText = body("Volumen de los audífonos: –", 15f)
        a.addView(volumeText)
        volumeBar = SeekBar(this).apply {
            max = 15
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    volumeText.text = "Volumen de los audífonos: $value / 15"
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {
                    send(Protocol.A2DP_VOL_SET, byteArrayOf(bar.progress.toByte()))
                }
            })
        }
        a.addView(volumeBar)

        // Pulso
        val h = section(root, "PULSO")
        hrText = body("– lpm", 30f).apply { setTypeface(typeface, Typeface.BOLD) }
        hrDetail = body("Ponte los audífonos bien ajustados y quédate quieto mientras mide.", 13f).apply { alpha = 0.7f }
        hrBtn = Button(this).apply {
            text = "Medir pulso"
            setOnClickListener { toggleHr() }
        }
        h.addView(hrText)
        h.addView(hrDetail)
        h.addView(hrBtn)

        // Música
        val m = section(root, "MÚSICA")
        m.addView(body("Reproducir desde:", 15f))
        srcPhone = RadioButton(this).apply { text = "El celular (Bluetooth)"; id = View.generateViewId() }
        srcBuds = RadioButton(this).apply { text = "La memoria de los audífonos"; id = View.generateViewId() }
        sourceGroup = RadioGroup(this).apply {
            addView(srcPhone)
            addView(srcBuds)
            setOnCheckedChangeListener { _, checkedId ->
                if (!updatingFromDevice) {
                    send(Protocol.MOBILE_MUSIC_CONTROL, byteArrayOf(if (checkedId == srcPhone.id) 1 else 0))
                }
            }
        }
        m.addView(sourceGroup)
        shuffleSw = switch("Aleatorio (música de los audífonos)") {
            send(Protocol.PLAYLIST_CONFIG, byteArrayOf(1, if (it) 1 else 0))
        }
        m.addView(shuffleSw)
        storageText = body("Almacenamiento: –", 15f).apply { alpha = 0.8f }
        m.addView(storageText)
        val refresh = Button(this).apply {
            text = "Actualizar estado"
            setOnClickListener {
                send(Protocol.UPDATE_TIME, null, raw = Protocol.updateTime())
                ui.postDelayed({ send(Protocol.STORAGE_REQ) }, 600)
            }
        }
        m.addView(refresh)

        // Registro
        val l = section(root, "REGISTRO (para diagnóstico)")
        l.addView(Button(this).apply {
            text = "Copiar registro"
            setOnClickListener { copyLog() }
        })
        logText = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setTextIsSelectable(true)
            alpha = 0.85f
        }
        l.addView(logText)

        controls = listOf(ambientSw, autoAnswerSw, volumeBar, hrBtn, srcPhone, srcBuds, shuffleSw, refresh)

        return ScrollView(this).apply {
            isFillViewport = true
            addView(root, MATCH_PARENT, WRAP_CONTENT)
        }
    }

    private fun setControlsEnabled(on: Boolean) {
        controls.forEach { it.isEnabled = on; it.alpha = if (on) 1f else 0.5f }
    }

    // --------------------------------------------------------- Permisos

    private fun hasPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermission(): Boolean {
        if (hasPermission()) return true
        requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (hasPermission()) refreshDeviceLabel()
        else toast("Sin el permiso de \"Dispositivos cercanos\" la app no puede hablar con los audífonos")
    }

    // ------------------------------------------------------- Dispositivo

    @SuppressLint("MissingPermission")
    private fun bondedDevices(): List<BluetoothDevice> {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        if (!hasPermission()) return emptyList()
        return adapter.bondedDevices?.toList().orEmpty()
            .sortedByDescending { (it.name ?: "").contains("IconX", ignoreCase = true) }
    }

    @SuppressLint("MissingPermission")
    private fun savedDevice(): BluetoothDevice? {
        val list = bondedDevices()
        val addr = prefs.getString("addr", null)
        return list.firstOrNull { it.address == addr }
            ?: list.firstOrNull { (it.name ?: "").contains("IconX", ignoreCase = true) }
    }

    @SuppressLint("MissingPermission")
    private fun refreshDeviceLabel() {
        val d = if (hasPermission()) savedDevice() else null
        deviceText.text = when {
            !hasPermission() -> "Falta el permiso de Bluetooth"
            d == null -> "No encuentro unos IconX vinculados. Vincúlalos primero en Ajustes → Conexiones → Bluetooth, y luego toca \"Elegir\"."
            else -> "${d.name}  ·  ${d.address}"
        }
    }

    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        if (!ensurePermission()) return
        val list = bondedDevices()
        if (list.isEmpty()) { toast("No hay dispositivos vinculados"); return }
        val names = list.map { "${it.name ?: "(sin nombre)"}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Elige tus IconX")
            .setItems(names) { _, i ->
                prefs.edit().putString("addr", list[i].address).apply()
                refreshDeviceLabel()
            }
            .show()
    }

    private fun connect() {
        if (!ensurePermission()) return
        val d = savedDevice() ?: run { pickDevice(); return }
        statusText.text = "Conectando…"
        connectBtn.text = "Cancelar"
        conn = IconXConnection(d, this).also { it.start() }
    }

    private fun disconnect() {
        if (measuringHr) toggleHr()
        conn?.close()
        conn = null
        connected = false
        statusText.text = "Desconectado"
        connectBtn.text = "Conectar"
        setControlsEnabled(false)
    }

    private fun send(id: Byte, params: ByteArray? = null, raw: ByteArray? = null) {
        val c = conn
        if (c == null || !connected) { toast("Primero conecta los audífonos"); return }
        val frame = raw ?: Protocol.encode(id, params ?: ByteArray(0))
        Thread { c.send(frame) }.start()
    }

    // ------------------------------------------------- Eventos de conexión

    override fun onConnected() {
        ui.post {
        connected = true
        statusText.text = "Conectado"
        connectBtn.text = "Desconectar"
        setControlsEnabled(true)
        // Igual que la app original: enviar la hora y luego pedir el almacenamiento
        send(Protocol.UPDATE_TIME, raw = Protocol.updateTime())
        ui.postDelayed({ if (connected) send(Protocol.STORAGE_REQ) }, 1500)
        }
    }

    override fun onDisconnected(reason: String) {
        ui.post {
        log("Desconectado: $reason")
        measuringHr = false
        hrBtn.text = "Medir pulso"
        conn = null
        connected = false
        statusText.text = "No se pudo conectar o se cortó la conexión. ¿Están fuera del estuche y encendidos?"
        connectBtn.text = "Conectar"
        setControlsEnabled(false)
        }
    }

    override fun onLog(line: String) {
        ui.post { log(line) }
    }

    override fun onMessage(msg: Protocol.Message) {
        ui.post { handle(msg) }
    }

    private fun handle(m: Protocol.Message) {
        val p = m.params
        updatingFromDevice = true
        try {
            when (m.id) {
                Protocol.STATUS_RES -> {
                    if (p.size >= 2) showBattery(p[1])
                    if (p.size >= 5) showWear(p[4])
                    if (p.size >= 6) showVolume(p[5])
                    if (p.size >= 8) shuffleSw.isChecked = p[7].toInt() == 1
                    if (p.size >= 12) autoAnswerSw.isChecked = p[11].toInt() == 1
                    if (p.size >= 13) ambientSw.isChecked = p[12].toInt() == 1
                    if (p.size >= 19) {
                        if (p[18].toInt() == 1) srcPhone.isChecked = true else srcBuds.isChecked = true
                    }
                    log("Estado recibido (${p.size} bytes)")
                }
                Protocol.HF_STATUS -> {
                    if (p.size >= 2) showBattery(p[1])
                    if (p.size >= 5) showWear(p[4])
                }
                Protocol.A2DP_VOL_STATUS -> if (p.isNotEmpty()) showVolume(p[0])
                Protocol.DEBUG_STATUS -> if (p.size >= 29) {
                    val bpm = p[28].toInt() and 0xFF
                    hrText.text = if (bpm == 0) "midiendo…" else "$bpm lpm"
                    hrDetail.text = "Temperatura (sensor) izq: ${Protocol.leShort(p, 16)}  der: ${Protocol.leShort(p, 18)}\n" +
                        "Movimiento izq: ${Protocol.leShort(p, 4)}, ${Protocol.leShort(p, 6)}, ${Protocol.leShort(p, 8)}  " +
                        "der: ${Protocol.leShort(p, 10)}, ${Protocol.leShort(p, 12)}, ${Protocol.leShort(p, 14)}"
                }
                Protocol.EXERCISE_LIVE -> if (p.size >= 13) {
                    val bpm = p[12].toInt() and 0xFF
                    if (bpm > 0) hrText.text = "$bpm lpm"
                    hrDetail.text = "Pulso en vivo durante el ejercicio"
                }
                Protocol.EXERCISE_STATUS -> log("Estado de ejercicio: ${p.firstOrNull()?.toInt()}")
                Protocol.AMBIENT_STATUS -> if (p.isNotEmpty()) ambientSw.isChecked = p[0].toInt() == 1
                Protocol.STORAGE_RSP -> if (p.size >= 18) {
                    val total = Protocol.leLong(p, 0, 8)
                    val avail = Protocol.leLong(p, 8, 8)
                    val tracks = Protocol.leLong(p, 16, 2)
                    storageText.text = "Almacenamiento: ${mb(avail)} libres de ${mb(total)} · $tracks canciones"
                }
                Protocol.GENERAL_RSP -> if (p.size >= 2) {
                    val ok = p[1].toInt() == 0
                    log("Respuesta a %02X: %s".format(p[0].toInt() and 0xFF, if (ok) "OK" else "rechazado"))
                }
                else -> log("Mensaje %02X sin interpretar".format(m.id.toInt() and 0xFF))
            }
        } finally {
            updatingFromDevice = false
        }
    }

    private fun showVolume(b: Byte) {
        val v = (b.toInt() and 0xFF).coerceIn(0, 15)
        volumeBar.progress = v
        volumeText.text = "Volumen de los audífonos: $v / 15"
    }

    private fun toggleHr() {
        measuringHr = !measuringHr
        // Igual que el modo de diagnóstico de la app original:
        // encender el sensor y pedir el estado de los sensores cada 2 segundos
        send(Protocol.ENABLE_HR, byteArrayOf(if (measuringHr) 1 else 0))
        hrBtn.text = if (measuringHr) "Detener" else "Medir pulso"
        if (measuringHr) {
            hrText.text = "midiendo…"
            ui.postDelayed(hrPoll, 1500)
        } else {
            ui.removeCallbacks(hrPoll)
        }
    }

    private fun showBattery(b: Byte) {
        val l = (b.toInt() and 0xF0) shr 4
        val r = b.toInt() and 0x0F
        batteryText.text = "Izquierdo: ${level(l)}    Derecho: ${level(r)}"
    }

    // El auricular informa la batería en pasos de 0 a 10 (≈ décimas)
    private fun level(v: Int) = if (v in 0..10) "${v * 10}%" else "nivel $v"

    private fun showWear(b: Byte) {
        wearText.text = "Uso: " + when (b.toInt() and 0xFF) {
            17 -> "ambos puestos"
            1, 16 -> "solo uno puesto"
            0 -> "ninguno puesto"
            else -> "desconocido (${b.toInt() and 0xFF})"
        }
    }

    private fun mb(bytes: Long): String =
        if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
        else "%.0f MB".format(bytes / (1024.0 * 1024))

    // ------------------------------------------------------------ Registro

    private fun log(line: String) {
        logLines.addLast("${timeFmt.format(Date())}  $line")
        while (logLines.size > 300) logLines.removeFirst()
        logText.text = logLines.takeLast(80).joinToString("\n")
    }

    private fun copyLog() {
        val cm = getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("Registro IconX", logLines.joinToString("\n")))
        toast("Registro copiado")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
