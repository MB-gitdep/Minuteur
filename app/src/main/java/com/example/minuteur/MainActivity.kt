package com.example.minuteur   // ⚠️ Remplace par le nom de package généré par ton projet

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ---------- Palette ----------
val Bg = Color(0xFF14161A)
val Panel = Color(0xFF1C2024)
val Panel2 = Color(0xFF22262B)
val BorderCol = Color(0xFF2B3037)
val Steel = Color(0xFF8B929C)
val SteelDim = Color(0xFF565B62)
val Amber = Color(0xFFFF9F45)
val AmberDim = Color(0xFF4D3520)
val Green = Color(0xFF5BE6A4)
val GreenDim = Color(0xFF1C3A2D)
val Red = Color(0xFFFF5C5C)

enum class Mode { CHRONO, TIMER }
enum class Screen { TIMER, CHANGELOG }

const val APP_VERSION = "1.4.0"

data class ChangelogEntry(val version: String, val title: String, val changes: List<String>)

val CHANGELOG = listOf(
    ChangelogEntry(
        "1.4.0", "Page changelog",
        listOf("Ajout de cette page listant l'historique des versions de l'application.")
    ),
    ChangelogEntry(
        "1.3.0", "Mémoire & écran actif",
        listOf(
            "Nouvelle fonction mémoire : enregistrer, consulter et supprimer des temps",
            "L'écran ne se met plus en veille pendant qu'un chrono ou un minuteur est en cours",
            "Nouvelle icône d'application"
        )
    ),
    ChangelogEntry(
        "1.2.0", "Icônes vectorielles",
        listOf("Correction des icônes play/pause/réinitialiser dont la couleur variait selon les téléphones")
    ),
    ChangelogEntry(
        "1.1.0", "Optimisations internes",
        listOf("Nettoyage du code : types d'état Compose optimisés, éléments inutilisés retirés")
    ),
    ChangelogEntry(
        "1.0.0", "Version initiale",
        listOf(
            "Chronomètre et minuteur paramétrable",
            "Pause, reprise et réinitialisation",
            "Cadran avec repères façon chronomètre mécanique"
        )
    )
)

data class SavedTime(val mode: Mode, val ms: Long, val savedAt: Long)

private const val PREFS_NAME = "minuteur_prefs"
private const val KEY_ENTRIES = "entries"

private fun loadSavedTimes(prefs: SharedPreferences): List<SavedTime> {
    val raw = prefs.getString(KEY_ENTRIES, "") ?: ""
    if (raw.isBlank()) return emptyList()
    return raw.split("|").mapNotNull { entry ->
        val parts = entry.split(":")
        if (parts.size != 3) return@mapNotNull null
        try {
            SavedTime(Mode.valueOf(parts[0]), parts[1].toLong(), parts[2].toLong())
        } catch (_: Exception) {
            null
        }
    }
}

private fun persistSavedTimes(prefs: SharedPreferences, list: List<SavedTime>) {
    val raw = list.joinToString("|") { "${it.mode.name}:${it.ms}:${it.savedAt}" }
    prefs.edit().putString(KEY_ENTRIES, raw).apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Bg)) {
                Surface(color = Bg, modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
fun AppRoot() {
    var screen by remember { mutableStateOf(Screen.TIMER) }
    when (screen) {
        Screen.TIMER -> TimerScreen(onOpenChangelog = { screen = Screen.CHANGELOG })
        Screen.CHANGELOG -> ChangelogScreen(onBack = { screen = Screen.TIMER })
    }
}

private fun pad(n: Int, len: Int = 2) = n.toString().padStart(len, '0')

private fun formatChrono(ms: Long): Pair<String, String> {
    val totalCs = ms / 10
    val cs = (totalCs % 100).toInt()
    val totalSec = totalCs / 100
    val sec = (totalSec % 60).toInt()
    val min = (totalSec / 60).toInt()
    return Pair(pad(min) + ":" + pad(sec), "." + pad(cs))
}

private fun formatTimer(ms: Long): String {
    val totalSec = max(0L, (ms + 999) / 1000)
    val sec = (totalSec % 60).toInt()
    val min = min((totalSec / 60).toInt(), 99)
    return pad(min) + ":" + pad(sec)
}

@Composable
fun TimerScreen(onOpenChangelog: () -> Unit) {
    val context = LocalContext.current

    var mode by remember { mutableStateOf(Mode.CHRONO) }
    var running by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var finished by remember { mutableStateOf(false) }

    var startTs by remember { mutableLongStateOf(0L) }
    var accumulated by remember { mutableLongStateOf(0L) }        // chrono
    var timerRemainingMs by remember { mutableLongStateOf(5 * 60 * 1000L) } // remaining at last pause/reset

    var configMin by remember { mutableIntStateOf(5) }
    var configSec by remember { mutableIntStateOf(0) }
    var timerTotalMs by remember { mutableLongStateOf(5 * 60 * 1000L) }

    var clockTick by remember { mutableLongStateOf(System.currentTimeMillis()) }

    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    val savedTimes = remember { mutableStateListOf<SavedTime>().apply { addAll(loadSavedTimes(prefs)) } }
    var showHistory by remember { mutableStateOf(false) }

    val vibrator = remember {
        context.getSystemService(Vibrator::class.java)
    }
    val toneGen = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }

    fun vibrate(pattern: LongArray) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
        }
    }

    fun applyConfig() {
        if (configMin == 0 && configSec == 0) configSec = 10
        timerTotalMs = (configMin * 60 + configSec) * 1000L
        timerRemainingMs = timerTotalMs
    }

    fun reset() {
        running = false; paused = false; finished = false
        accumulated = 0L
        applyConfig()
    }

    fun setMode(m: Mode) {
        if (running) return
        mode = m
        reset()
    }

    fun startOrPause() {
        if (finished) return
        if (!running) {
            running = true; paused = false
            startTs = System.currentTimeMillis()
            clockTick = startTs
            vibrate(longArrayOf(0, 15))
        } else {
            running = false; paused = true
            val now = System.currentTimeMillis()
            if (mode == Mode.CHRONO) {
                accumulated += now - startTs
            } else {
                timerRemainingMs = max(0L, timerRemainingMs - (now - startTs))
            }
            vibrate(longArrayOf(0, 15))
        }
    }

    // Empêche l'écran de se mettre en veille tant que le minuteur/chrono tourne
    DisposableEffect(running) {
        val activity = context as? Activity
        if (running) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Tick loop while running
    LaunchedEffect(running, mode) {
        while (running) {
            clockTick = System.currentTimeMillis()
            if (mode == Mode.TIMER) {
                val remaining = timerRemainingMs - (clockTick - startTs)
                if (remaining <= 0) {
                    running = false; finished = true
                    timerRemainingMs = 0
                    vibrate(longArrayOf(0, 200, 100, 200, 100, 300))
                    try { toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 400) } catch (_: Exception) {}
                    break
                }
            }
            delay(30)
        }
    }

    val displayMs = when (mode) {
        Mode.CHRONO -> if (running) accumulated + (clockTick - startTs) else accumulated
        Mode.TIMER -> if (running) max(0L, timerRemainingMs - (clockTick - startTs)) else timerRemainingMs
    }
    val fraction = when (mode) {
        Mode.CHRONO -> ((displayMs % 60000L).toFloat() / 60000f)
        Mode.TIMER -> if (timerTotalMs > 0) (displayMs.toFloat() / timerTotalMs) else 0f
    }

    val ringColor = when {
        finished -> Red
        running -> Green
        else -> Amber
    }
    val statusText = when {
        finished -> "TERMINÉ"
        running -> "EN COURS"
        paused -> "EN PAUSE"
        else -> "PRÊT"
    }
    val statusColor = when {
        finished -> Red
        running -> Green
        paused -> Amber
        else -> SteelDim
    }

    fun saveCurrentTime() {
        savedTimes.add(0, SavedTime(mode, displayMs, System.currentTimeMillis()))
        persistSavedTimes(prefs, savedTimes)
        vibrate(longArrayOf(0, 20))
    }

    fun deleteSavedTime(item: SavedTime) {
        savedTimes.remove(item)
        persistSavedTimes(prefs, savedTimes)
    }

    fun clearSavedTimes() {
        savedTimes.clear()
        persistSavedTimes(prefs, savedTimes)
    }

    fun loadSavedTime(item: SavedTime) {
        if (running) return // on ne perturbe pas un chrono/minuteur en cours
        if (item.mode != mode) {
            mode = item.mode
        }
        paused = false; finished = false
        if (item.mode == Mode.CHRONO) {
            accumulated = item.ms
        } else {
            val totalSec = (item.ms / 1000L).coerceAtLeast(0L)
            configMin = (totalSec / 60L).toInt().coerceIn(0, 99)
            configSec = (totalSec % 60L).toInt()
            timerTotalMs = item.ms
            timerRemainingMs = item.ms
        }
        vibrate(longArrayOf(0, 15, 40, 15))
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "MINUTEUR & CHRONOMÈTRE",
                color = SteelDim,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 3.sp,
                modifier = Modifier.padding(bottom = 18.dp)
            )

            // Mode switch
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .background(Panel)
                    .border(1.dp, BorderCol, RoundedCornerShape(999.dp))
                    .padding(4.dp),
            ) {
                ModePill("CHRONO", mode == Mode.CHRONO, enabled = !running) { setMode(Mode.CHRONO) }
                ModePill("MINUTEUR", mode == Mode.TIMER, enabled = !running) { setMode(Mode.TIMER) }
            }

            Spacer(Modifier.height(28.dp))

            // Dial
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(280.dp)) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val centerOffset = Offset(size.width / 2, size.height / 2)
                    val outerR = size.minDimension / 2 - 20f
                    val ringR = size.minDimension / 2 - 40f

                    // Tick marks
                    for (i in 0 until 60) {
                        val angle = (i / 60f) * 2f * Math.PI.toFloat() - Math.PI.toFloat() / 2f
                        val major = i % 5 == 0
                        val rInner = if (major) outerR - 12f else outerR - 6f
                        val p1 = Offset(
                            centerOffset.x + outerR * cos(angle),
                            centerOffset.y + outerR * sin(angle)
                        )
                        val p2 = Offset(
                            centerOffset.x + rInner * cos(angle),
                            centerOffset.y + rInner * sin(angle)
                        )
                        drawLine(
                            color = if (major) Steel else SteelDim,
                            start = p1, end = p2,
                            strokeWidth = if (major) 2.5f else 1.5f,
                            alpha = if (major) 0.9f else 0.5f
                        )
                    }

                    // Background ring
                    drawCircle(
                        color = BorderCol,
                        radius = ringR,
                        center = centerOffset,
                        style = Stroke(width = 10f)
                    )

                    // Progress arc
                    drawArc(
                        color = ringColor,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction.coerceIn(0f, 1f),
                        useCenter = false,
                        topLeft = Offset(centerOffset.x - ringR, centerOffset.y - ringR),
                        size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2),
                        style = Stroke(width = 10f, cap = StrokeCap.Round)
                    )
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (mode == Mode.CHRONO) {
                        val (main, sub) = formatChrono(displayMs)
                        Row(verticalAlignment = Alignment.Bottom) {
                            Text(main, color = Color(0xFFF2F0EA), fontSize = 42.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
                            Text(sub, color = Steel, fontSize = 20.sp, fontFamily = FontFamily.Monospace)
                        }
                    } else {
                        Text(
                            formatTimer(displayMs),
                            color = Color(0xFFF2F0EA),
                            fontSize = 42.sp,
                            fontWeight = FontWeight.SemiBold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(statusColor)
                        )
                        Spacer(Modifier.width(7.dp))
                        Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            // Config (timer only, idle only)
            val showConfig = mode == Mode.TIMER && !running && !paused && !finished
            if (showConfig) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Stepper("MINUTES", pad(configMin), onMinus = {
                        configMin = max(0, configMin - 1); applyConfig()
                    }, onPlus = {
                        configMin = min(99, configMin + 1); applyConfig()
                    })
                    Stepper("SECONDES", pad(configSec), onMinus = {
                        configSec = if (configSec - 5 < 0) 55 else configSec - 5; applyConfig()
                    }, onPlus = {
                        configSec = if (configSec + 5 > 59) 0 else configSec + 5; applyConfig()
                    })
                }
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1 to "1 min", 3 to "3 min", 5 to "5 min", 10 to "10 min").forEach { (m, label) ->
                        Chip(label) {
                            configMin = m; configSec = 0; applyConfig()
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            } else {
                Spacer(Modifier.height(46.dp))
            }

            // Controls
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                // Reset
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Panel)
                        .border(1.dp, BorderCol, CircleShape)
                        .clickable { reset() },
                    contentAlignment = Alignment.Center
                ) {
                    ResetIcon(color = Steel, iconSize = 20.dp)
                }

                // Main
                Box(
                    modifier = Modifier
                        .size(82.dp)
                        .clip(CircleShape)
                        .background(if (running) Green else Amber)
                        .border(6.dp, if (running) GreenDim else AmberDim, CircleShape)
                        .clickable(enabled = !finished) { startOrPause() },
                    contentAlignment = Alignment.Center
                ) {
                    val iconColor = if (running) Color(0xFF032015) else Color(0xFF201400)
                    if (running) {
                        PauseIcon(color = iconColor, iconSize = 22.dp)
                    } else {
                        PlayIcon(color = iconColor, iconSize = 22.dp)
                    }
                }

                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .clip(CircleShape)
                        .background(Panel)
                        .border(1.dp, BorderCol, CircleShape)
                        .clickable { saveCurrentTime() },
                    contentAlignment = Alignment.Center
                ) {
                    BookmarkIcon(color = Steel, iconSize = 18.dp)
                }
            }

            Spacer(Modifier.height(26.dp))

            // Mémoire — historique des temps enregistrés
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(Panel)
                    .border(1.dp, BorderCol, RoundedCornerShape(16.dp))
                    .clickable { showHistory = !showHistory }
                    .padding(16.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "MÉMOIRE (${savedTimes.size})",
                            color = Steel,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.5.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (savedTimes.isNotEmpty()) {
                                Text(
                                    "TOUT EFFACER",
                                    color = Red,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    letterSpacing = 1.sp,
                                    modifier = Modifier
                                        .clickable { clearSavedTimes() }
                                        .padding(end = 14.dp)
                                )
                            }
                            Text(
                                if (showHistory) "▲" else "▼",
                                color = SteelDim,
                                fontSize = 12.sp
                            )
                        }
                    }

                    if (showHistory) {
                        Spacer(Modifier.height(12.dp))
                        if (savedTimes.isEmpty()) {
                            Text(
                                "Aucun temps enregistré pour l'instant.",
                                color = SteelDim,
                                fontSize = 12.sp
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                savedTimes.forEach { item ->
                                    SavedTimeRow(
                                        item = item,
                                        onLoad = { loadSavedTime(item) },
                                        onDelete = { deleteSavedTime(item) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Bouton version / changelog (aligné en haut à droite de l'écran)
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 18.dp, end = 18.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Panel)
                .border(1.dp, BorderCol, RoundedCornerShape(999.dp))
                .clickable { onOpenChangelog() }
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "v$APP_VERSION",
                color = Steel,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun ChangelogScreen(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 28.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Panel)
                    .border(1.dp, BorderCol, CircleShape)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                BackIcon(color = Steel, iconSize = 16.dp)
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    "HISTORIQUE DES VERSIONS",
                    color = SteelDim,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 2.sp
                )
                Text(
                    "Changelog",
                    color = Color(0xFFF2F0EA),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(Modifier.height(28.dp))

        CHANGELOG.forEachIndexed { index, entry ->
            val isLatest = index == 0
            Row(modifier = Modifier.fillMaxWidth()) {
                // Puce de la ligne de temps
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isLatest) Amber else SteelDim)
                )

                Spacer(Modifier.width(16.dp))

                Column(modifier = Modifier.padding(bottom = 26.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(if (isLatest) AmberDim else Panel2)
                                .border(1.dp, if (isLatest) Amber else BorderCol, RoundedCornerShape(999.dp))
                                .padding(horizontal = 10.dp, vertical = 3.dp)
                        ) {
                            Text(
                                "v${entry.version}",
                                color = if (isLatest) Amber else Steel,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        if (isLatest) {
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "ACTUELLE",
                                color = Green,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 1.sp
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        entry.title,
                        color = Color(0xFFF2F0EA),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    entry.changes.forEach { change ->
                        Row(modifier = Modifier.padding(top = 3.dp)) {
                            Text("· ", color = SteelDim, fontSize = 13.sp)
                            Text(change, color = Steel, fontSize = 13.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun BackIcon(color: Color, iconSize: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.68f, h * 0.08f)
            lineTo(w * 0.30f, h * 0.5f)
            lineTo(w * 0.68f, h * 0.92f)
        }
        drawPath(
            path,
            color = color,
            style = Stroke(width = w * 0.14f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        )
    }
}

@Composable
private fun SavedTimeRow(item: SavedTime, onLoad: () -> Unit, onDelete: () -> Unit) {
    val label = if (item.mode == Mode.CHRONO) "CHRONO" else "MINUTEUR"
    val timeText = if (item.mode == Mode.CHRONO) {
        val (main, sub) = formatChrono(item.ms)
        main + sub
    } else {
        formatTimer(item.ms)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Panel2)
            .clickable { onLoad() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(timeText, color = Color(0xFFF2F0EA), fontSize = 16.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
            Text(label, color = SteelDim, fontSize = 10.sp, letterSpacing = 1.sp)
        }
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Text("✕", color = Red, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ModePill(label: String, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (active) Panel2 else Color.Transparent)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 22.dp, vertical = 10.dp)
    ) {
        Text(
            label,
            color = if (active) Amber else SteelDim,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = SteelDim, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StepButton("–", onMinus)
            Text(value, color = Color(0xFFF2F0EA), fontSize = 22.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(42.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            StepButton("+", onPlus)
        }
    }
}

@Composable
private fun StepButton(symbol: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Panel)
            .border(1.dp, BorderCol, RoundedCornerShape(10.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(symbol, color = Steel, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Chip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Panel)
            .border(1.dp, BorderCol, RoundedCornerShape(999.dp))
            .clickable { onClick() }
            .padding(horizontal = 13.dp, vertical = 7.dp)
    ) {
        Text(label, color = Steel, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BookmarkIcon(color: Color, iconSize: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.24f, h * 0.06f)
            lineTo(w * 0.76f, h * 0.06f)
            lineTo(w * 0.76f, h * 0.94f)
            lineTo(w * 0.50f, h * 0.72f)
            lineTo(w * 0.24f, h * 0.94f)
            close()
        }
        drawPath(
            path,
            color = color,
            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
        )
    }
}

@Composable
private fun PlayIcon(color: Color, iconSize: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(iconSize)) {
        val w = size.width
        val h = size.height
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.22f, h * 0.10f)
            lineTo(w * 0.22f, h * 0.90f)
            lineTo(w * 0.88f, h * 0.50f)
            close()
        }
        drawPath(path, color = color)
    }
}

@Composable
private fun PauseIcon(color: Color, iconSize: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(iconSize)) {
        val barWidth = this.size.width * 0.26f
        val gap = this.size.width * 0.20f
        val startX = (this.size.width - (barWidth * 2 + gap)) / 2
        drawRoundRect(
            color = color,
            topLeft = Offset(startX, 0f),
            size = androidx.compose.ui.geometry.Size(barWidth, this.size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(startX + barWidth + gap, 0f),
            size = androidx.compose.ui.geometry.Size(barWidth, this.size.height),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(2f, 2f)
        )
    }
}

@Composable
private fun ResetIcon(color: Color, iconSize: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(iconSize)) {
        val strokeW = this.size.minDimension * 0.16f
        val inset = strokeW / 2
        drawArc(
            color = color,
            startAngle = -50f,
            sweepAngle = 280f,
            useCenter = false,
            style = Stroke(width = strokeW, cap = StrokeCap.Round),
            topLeft = Offset(inset, inset),
            size = androidx.compose.ui.geometry.Size(this.size.width - strokeW, this.size.height - strokeW)
        )
        val angleRad = Math.toRadians(-50.0)
        val r = (this.size.minDimension - strokeW) / 2
        val cx = this.size.width / 2
        val cy = this.size.height / 2
        val tipX = cx + r * cos(angleRad).toFloat()
        val tipY = cy + r * sin(angleRad).toFloat()
        val arrow = androidx.compose.ui.graphics.Path().apply {
            moveTo(tipX - 5f, tipY - 6f)
            lineTo(tipX + 7f, tipY - 1f)
            lineTo(tipX - 1f, tipY + 7f)
            close()
        }
        drawPath(arrow, color = color)
    }
}