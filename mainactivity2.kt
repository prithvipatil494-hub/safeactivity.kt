package com.example.helloworld

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.android.gms.location.*
import com.mapbox.geojson.Point
import com.mapbox.maps.CameraOptions
import com.mapbox.maps.Style
import com.mapbox.maps.extension.compose.MapboxMap
import com.mapbox.maps.extension.compose.animation.viewport.rememberMapViewportState
import com.mapbox.maps.extension.compose.annotation.generated.CircleAnnotation
import com.mapbox.maps.extension.compose.annotation.generated.PolylineAnnotation
import com.mapbox.maps.extension.compose.style.MapStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

// ─── Config ───────────────────────────────────────────────────────────────────
const val WEB_CLIENT_ID = 
const val MAPBOX_TOKEN  = 
const val BACKEND       = 
const val MAX_FRIENDS   = 

// ─── SharedPreferences keys ───────────────────────────────────────────────────
const val PREFS_NAME         = "LiveLocPrefs"
const val PREF_TRACK_ID      = "trackId"
const val PREF_UID           = "uid"
const val PREF_DISPLAY_NAME  = "displayName"
const val PREF_SAVED_FRIENDS = "savedFriends"

// ─── Foreground service constants ─────────────────────────────────────────────
const val CHANNEL_ID          = "liveloc_bg_channel"
const val NOTIF_ID            = 1001
const val ACTION_STOP_SERVICE = "com.example.helloworld.STOP_SERVICE"
const val CHAT_CHANNEL_ID     = "liveloc_chat_messages"

// ─── Timing ───────────────────────────────────────────────────────────────────
const val PUSH_INTERVAL = 2_000L
const val POLL_INTERVAL = 1_000L

// ─── Green/Black Palette ──────────────────────────────────────────────────────
val NeonGreen      = Color(0xFF00FF87)   // Signature neon green
val MidGreen       = Color(0xFF00C96B)   // Primary green
val DarkGreen      = Color(0xFF00843F)   // Deep green
val ForestGreen    = Color(0xFF00522A)   // Very deep green
val PureBlack      = Color(0xFF000000)
val DeepBlack      = Color(0xFF080E08)   // Black with faint green tint
val SurfaceBlack   = Color(0xFF0D150D)   // Card backgrounds
val ElevatedBlack  = Color(0xFF142014)   // Elevated cards
val BorderGreen    = Color(0xFF1A3020)   // Subtle borders
val GlowGreen      = Color(0x2200FF87)   // Glow effect
val TextWhite      = Color(0xFFF0FFF4)   // Off-white with green tint
val TextDimmed     = Color(0xFF6B8F6B)   // Dimmed text
val TextMutedG     = Color(0xFF3D5C3D)   // More muted
val GreenOnline    = Color(0xFF00FF87)
val RedAlert       = Color(0xFFFF3B5C)
val AmberWarn      = Color(0xFFFFB800)

val FriendColors   = listOf(Color(0xFF00FF87), Color(0xFF00C9FF), Color(0xFFFFB800), Color(0xFFFF3B5C))
val FriendColorHex = listOf("#00FF87", "#00C9FF", "#FFB800", "#FF3B5C")
val FriendLabels   = listOf("Emerald", "Cyan", "Amber", "Rose")

// ─── Thresholds ───────────────────────────────────────────────────────────────
private const val ACC_GOOD           = 30f
private const val ACC_ACCEPTABLE     = 100f
private const val MIN_DIST           = 2.0
private const val FORCE_MS           = 4_000L
private const val MAX_SPEED_JUMP_MS  = 55.0
private const val ACCURACY_WINDOW    = 8

// ─── Bottom Nav Tabs ──────────────────────────────────────────────────────────
enum class BottomTab { MAP, CHAT, FRIENDS, PROFILE }

// ─── HTTP helpers ─────────────────────────────────────────────────────────────
fun httpGet(path: String): JSONObject? = try {
    val conn = URL("$BACKEND$path").openConnection() as HttpURLConnection
    conn.connectTimeout = 5000; conn.readTimeout = 5000; conn.requestMethod = "GET"
    if (conn.responseCode == 200) JSONObject(conn.inputStream.bufferedReader().readText()) else null
} catch (_: Exception) { null }

private fun httpGetArray(path: String): JSONArray? = try {
    val conn = URL("$BACKEND$path").openConnection() as HttpURLConnection
    conn.connectTimeout = 5000; conn.readTimeout = 5000; conn.requestMethod = "GET"
    if (conn.responseCode == 200) JSONArray(conn.inputStream.bufferedReader().readText()) else null
} catch (_: Exception) { null }

fun httpPost(path: String, body: JSONObject): JSONObject? = try {
    val conn = URL("$BACKEND$path").openConnection() as HttpURLConnection
    conn.connectTimeout = 8000; conn.readTimeout = 8000
    conn.requestMethod = "POST"; conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
    if (conn.responseCode in 200..299)
        JSONObject(conn.inputStream.bufferedReader().readText()) else null
} catch (_: Exception) { null }

fun httpPatch(path: String, body: JSONObject): JSONObject? = try {
    val conn = URL("$BACKEND$path").openConnection() as HttpURLConnection
    conn.connectTimeout = 8000; conn.readTimeout = 8000
    conn.requestMethod = "PATCH"; conn.doOutput = true
    conn.setRequestProperty("Content-Type", "application/json")
    OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
    if (conn.responseCode in 200..299)
        JSONObject(conn.inputStream.bufferedReader().readText()) else null
} catch (_: Exception) { null }

fun httpDelete(path: String): Boolean = try {
    val conn = URL("$BACKEND$path").openConnection() as HttpURLConnection
    conn.connectTimeout = 5000; conn.readTimeout = 5000; conn.requestMethod = "DELETE"
    conn.responseCode in 200..299
} catch (_: Exception) { false }

fun ensureChatNotificationChannel(ctx: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channel = NotificationChannel(CHAT_CHANNEL_ID, "Chat Messages", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "Notifications for new chat messages"
    }
    manager.createNotificationChannel(channel)
}

fun showChatNotification(ctx: Context, friendName: String, message: String, notificationId: Int) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) return
    val notification = NotificationCompat.Builder(ctx, CHAT_CHANNEL_ID)
        .setSmallIcon(android.R.drawable.ic_dialog_email)
        .setContentTitle(friendName.ifBlank { "New message" })
        .setContentText(message.ifBlank { "Sent a new message" })
        .setStyle(NotificationCompat.BigTextStyle().bigText(message.ifBlank { "Sent a new message" }))
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setAutoCancel(true)
        .build()
    NotificationManagerCompat.from(ctx).notify(notificationId, notification)
}

fun accuracyLabel(acc: Float): Pair<String, Color> = when {
    acc < 20f  -> "Excellent" to NeonGreen
    acc < 50f  -> "Good"      to Color(0xFF66FF66)
    acc < 100f -> "Fair"      to AmberWarn
    else       -> "Poor"      to RedAlert
}

fun distM(a: Point, b: Point): Double {
    val r    = 6_371_000.0
    val dLat = Math.toRadians(b.latitude()  - a.latitude())
    val dLon = Math.toRadians(b.longitude() - a.longitude())
    val x    = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(a.latitude())) * cos(Math.toRadians(b.latitude())) *
            sin(dLon / 2).pow(2)
    return r * 2 * asin(sqrt(x))
}

// ─── Kalman Filter ────────────────────────────────────────────────────────────
class KalmanLatLng {
    private val Q_METRES_PER_SECOND = 3.0
    private var latEstimate = 0.0; private var latVariance = Double.MAX_VALUE
    private var lngEstimate = 0.0; private var lngVariance = Double.MAX_VALUE
    private var lastTimestamp = 0L
    val isInitialised: Boolean get() = lastTimestamp != 0L
    fun process(lat: Double, lng: Double, accuracy: Float, timestampMs: Long): Pair<Double, Double> {
        val accMetres = accuracy.toDouble().coerceAtLeast(1.0)
        if (!isInitialised) {
            latEstimate = lat; latVariance = accMetres * accMetres
            lngEstimate = lng; lngVariance = accMetres * accMetres
            lastTimestamp = timestampMs; return lat to lng
        }
        val dtSeconds = ((timestampMs - lastTimestamp) / 1000.0).coerceIn(0.0, 10.0)
        lastTimestamp = timestampMs
        val qContrib = Q_METRES_PER_SECOND * Q_METRES_PER_SECOND * dtSeconds
        latVariance += qContrib; lngVariance += qContrib
        val R = accMetres * accMetres
        val kLat = latVariance / (latVariance + R); latEstimate = latEstimate + kLat * (lat - latEstimate); latVariance = (1.0 - kLat) * latVariance
        val kLng = lngVariance / (lngVariance + R); lngEstimate = lngEstimate + kLng * (lng - lngEstimate); lngVariance = (1.0 - kLng) * lngVariance
        return latEstimate to lngEstimate
    }
    fun reset() { latVariance = Double.MAX_VALUE; lngVariance = Double.MAX_VALUE; lastTimestamp = 0L }
}

class AccuracyTracker(private val windowSize: Int = ACCURACY_WINDOW) {
    private val buffer = ArrayDeque<Float>(windowSize)
    fun push(acc: Float) { if (buffer.size >= windowSize) buffer.removeFirst(); buffer.addLast(acc) }
    val smoothed: Float get() {
        if (buffer.isEmpty()) return 999f
        var wSum = 0.0; var totalW = 0.0
        buffer.forEachIndexed { i, a -> val w = (i + 1).toDouble() / (a * a).toDouble(); wSum += w * a; totalW += w }
        return if (totalW == 0.0) buffer.last() else (wSum / totalW).toFloat()
    }
    val best: Float get() = buffer.minOrNull() ?: 999f
    fun reset() = buffer.clear()
}

class TeleportGuard {
    private var lastPoint: Point? = null; private var lastMs = 0L
    fun isValid(pt: Point, nowMs: Long): Boolean {
        val prev = lastPoint
        val ok = if (prev == null || lastMs == 0L) true
        else { val dtSec = (nowMs - lastMs) / 1000.0; val d = distM(prev, pt); val s = if (dtSec > 0) d / dtSec else 0.0; s < MAX_SPEED_JUMP_MS }
        if (ok) { lastPoint = pt; lastMs = nowMs }; return ok
    }
    fun reset() { lastPoint = null; lastMs = 0L }
}

object GpsFilter {
    private val kalman          = KalmanLatLng()
    private val accuracyTracker = AccuracyTracker()
    private val teleportGuard   = TeleportGuard()
    fun process(lat: Double, lon: Double, accuracy: Float, timestampMs: Long = System.currentTimeMillis()): Pair<Double, Double>? {
        val pt = Point.fromLngLat(lon, lat)
        if (!teleportGuard.isValid(pt, timestampMs)) return null
        accuracyTracker.push(accuracy)
        return kalman.process(lat, lon, accuracy, timestampMs)
    }
    val smoothedAccuracy: Float get() = accuracyTracker.smoothed
    val bestAccuracy:     Float get() = accuracyTracker.best
    fun reset() { kalman.reset(); accuracyTracker.reset(); teleportGuard.reset() }
}

// ─── Data models ──────────────────────────────────────────────────────────────
data class LocationData(
    val point: Point, val accuracy: Float,
    val speedKmh: Float = 0f, val address: String = "Fetching address…"
)

data class FriendLocation(
    val point: Point, val accuracy: Float,
    val speedKmh: Float, val isRecent: Boolean
)

data class SavedFriend(
    val trackId: String, val displayName: String, val email: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("trackId", trackId); put("displayName", displayName); put("email", email)
    }
    companion object {
        fun fromJson(obj: JSONObject) = SavedFriend(
            trackId     = obj.getString("trackId"),
            displayName = obj.optString("displayName", ""),
            email       = obj.optString("email", "")
        )
    }
}

// ─── Activity ─────────────────────────────────────────────────────────────────
class MainActivity : ComponentActivity() {
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val auth = FirebaseAuth.getInstance()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.mapbox.common.MapboxOptions.accessToken = MAPBOX_TOKEN
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        setContent { AppRoot(auth, fusedLocationClient) { locationCallback = it } }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::locationCallback.isInitialized)
            fusedLocationClient.removeLocationUpdates(locationCallback)
    }
}

// ─── Root ─────────────────────────────────────────────────────────────────────
@Composable
fun AppRoot(auth: FirebaseAuth, fused: FusedLocationProviderClient, onCb: (LocationCallback) -> Unit) {
    var user by remember { mutableStateOf<FirebaseUser?>(auth.currentUser) }
    MaterialTheme(colorScheme = darkColorScheme(
        primary        = NeonGreen,
        background     = DeepBlack,
        surface        = SurfaceBlack,
        onSurface      = TextWhite,
        onPrimary      = PureBlack,
        secondary      = MidGreen,
        onSecondary    = PureBlack,
        tertiary       = DarkGreen,
        outline        = BorderGreen
    )) {
        if (user == null) AuthScreen(auth) { user = it }
        else MainApp(fused, onCb, user!!) { auth.signOut(); user = null }
    }
}

// ─── Auth Screen ──────────────────────────────────────────────────────────────
@Composable
fun AuthScreen(auth: FirebaseAuth, onSignedIn: (FirebaseUser) -> Unit) {
    val ctx     = LocalContext.current
    var loading by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        try {
            val acc = GoogleSignIn.getSignedInAccountFromIntent(result.data).getResult(ApiException::class.java)
            loading = true
            auth.signInWithCredential(GoogleAuthProvider.getCredential(acc.idToken, null))
                .addOnSuccessListener { r -> loading = false; r.user?.let { onSignedIn(it) } }
                .addOnFailureListener { e -> loading = false; Toast.makeText(ctx, "Auth failed: ${e.message}", Toast.LENGTH_SHORT).show() }
        } catch (e: ApiException) { loading = false; Toast.makeText(ctx, "Sign-In failed: ${e.message}", Toast.LENGTH_SHORT).show() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepBlack),
        contentAlignment = Alignment.Center
    ) {
        // Subtle grid background effect
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    colors = listOf(ForestGreen.copy(alpha = 0.25f), Color.Transparent),
                    radius = 600f
                )
            )
        )

        Column(
            Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo
            Box(
                Modifier
                    .size(100.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(ElevatedBlack)
                    .border(1.dp, NeonGreen.copy(alpha = 0.4f), RoundedCornerShape(28.dp)),
                Alignment.Center
            ) {
                Text("📍", fontSize = 48.sp)
            }

            Spacer(Modifier.height(32.dp))

            Text(
                "LiveLoc",
                fontSize    = 42.sp,
                fontWeight  = FontWeight.Black,
                color       = TextWhite,
                letterSpacing = (-1).sp,
                fontFamily  = FontFamily.Monospace
            )
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(NeonGreen))
                Text(
                    "Live location sharing, simplified",
                    fontSize = 14.sp,
                    color    = TextDimmed
                )
            }

            Spacer(Modifier.height(56.dp))

            if (loading) {
                CircularProgressIndicator(color = NeonGreen, strokeWidth = 2.dp)
            } else {
                Button(
                    onClick = {
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                            .requestIdToken(WEB_CLIENT_ID).requestEmail().build()
                        launcher.launch(GoogleSignIn.getClient(ctx, gso).signInIntent)
                    },
                    shape   = RoundedCornerShape(16.dp),
                    colors  = ButtonDefaults.buttonColors(containerColor = ElevatedBlack),
                    modifier = Modifier.fillMaxWidth().height(60.dp).border(1.dp, BorderGreen, RoundedCornerShape(16.dp)),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Text(
                        "G",
                        fontWeight  = FontWeight.Black,
                        fontSize    = 20.sp,
                        color       = Color(0xFF4285F4),
                        modifier    = Modifier.padding(end = 14.dp)
                    )
                    Text(
                        "Continue with Google",
                        fontWeight  = FontWeight.SemiBold,
                        fontSize    = 15.sp,
                        color       = TextWhite
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    "Encrypted • Secure • Private",
                    fontSize = 11.sp,
                    color    = TextMutedG,
                    letterSpacing = 1.sp
                )
            }
        }
    }
}

// ─── Main App ─────────────────────────────────────────────────────────────────
@Composable
fun MainApp(
    fused: FusedLocationProviderClient,
    onCb: (LocationCallback) -> Unit,
    user: FirebaseUser,
    onSignOut: () -> Unit
) {
    val ctx   = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    var myLocation   by remember { mutableStateOf<LocationData?>(null) }
    var rawPoint     by remember { mutableStateOf<Point?>(null) }
    var rawAccuracy  by remember { mutableStateOf(0f) }
    var rawSpeedMs   by remember { mutableStateOf(0f) }
    var gpsAvailable by remember { mutableStateOf(true) }

    var myTrackId    by remember { mutableStateOf(prefs.getString(PREF_TRACK_ID, null) ?: "Loading…") }
    var trackIdReady by remember { mutableStateOf(prefs.contains(PREF_TRACK_ID)) }
    var isConnected  by remember { mutableStateOf(false) }
    var bgTrackingOn by remember { mutableStateOf(false) }

    var pathPoints    by remember { mutableStateOf<List<Point>>(emptyList()) }
    var isRecording   by remember { mutableStateOf(false) }
    var sessionId     by remember { mutableStateOf<String?>(null) }
    var lastRecPoint  by remember { mutableStateOf<Point?>(null) }
    var lastRecTimeMs by remember { mutableStateOf(0L) }

    val trackedIds      = remember { mutableStateListOf<String>() }
    val friendLocations = remember { mutableStateMapOf<String, FriendLocation?>() }
    var savedFriends by remember { mutableStateOf<List<SavedFriend>>(emptyList()) }

    // ─── Navigation state ───────────────────────────────────────────────────
    var activeTab        by remember { mutableStateOf(BottomTab.MAP) }
    var showFriendDialog by remember { mutableStateOf(false) }
    var viewingProfile   by remember { mutableStateOf<SavedFriend?>(null) }
    var chatWithTrackId  by remember { mutableStateOf<String?>(null) }
    var chatWithName     by remember { mutableStateOf<String?>(null) }

    var hasPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
    }
    var hasBgPerm by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            else true
        )
    }
    var hasNotifPerm by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            else true
        )
    }

    val permLauncher       = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPerm = it }
    val bgPermLauncher     = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasBgPerm = granted
        if (granted) { ContextCompat.startForegroundService(ctx, Intent(ctx, LocationForegroundService::class.java)); bgTrackingOn = true }
        else Toast.makeText(ctx, "Select 'Allow all the time' for background tracking", Toast.LENGTH_LONG).show()
    }
    val notifPermLauncher  = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasNotifPerm = it }

    LaunchedEffect(Unit) {
        ensureChatNotificationChannel(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPerm)
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        val json = prefs.getString(PREF_SAVED_FRIENDS, null)
        if (json != null) try {
            val arr = JSONArray(json); val list = mutableListOf<SavedFriend>()
            for (i in 0 until arr.length()) list.add(SavedFriend.fromJson(arr.getJSONObject(i)))
            savedFriends = list
        } catch (_: Exception) {}
    }

    fun persistSavedFriends(list: List<SavedFriend>) {
        val arr = JSONArray(); list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(PREF_SAVED_FRIENDS, arr.toString()).apply()
        scope.launch(Dispatchers.IO) {
            val backendArr = JSONArray()
            list.forEach { sf -> backendArr.put(JSONObject().apply { put("trackId", sf.trackId); put("displayName", sf.displayName); put("email", sf.email) }) }
            httpPost("/api/user/${user.uid}/saved-friends", JSONObject().apply { put("savedFriends", backendArr) })
        }
    }

    fun addSavedFriend(sf: SavedFriend) {
        if (savedFriends.any { it.trackId == sf.trackId }) return
        val updated = savedFriends + sf; savedFriends = updated; persistSavedFriends(updated)
    }

    fun removeSavedFriend(trackId: String) {
        val updated = savedFriends.filter { it.trackId != trackId }
        savedFriends = updated; persistSavedFriends(updated)
        if (trackId in trackedIds) { trackedIds.remove(trackId); friendLocations.remove(trackId) }
    }

    LaunchedEffect(user.uid) {
        withContext(Dispatchers.IO) {
            isConnected = httpGet("/api/health")?.optString("status") == "OK"
            val userDoc = httpGet("/api/user/${user.uid}")
            if (userDoc != null && userDoc.has("trackId")) {
                val remoteTrackId = userDoc.getString("trackId")
                myTrackId = remoteTrackId; trackIdReady = true
                prefs.edit().putString(PREF_TRACK_ID, remoteTrackId).putString(PREF_UID, user.uid).putString(PREF_DISPLAY_NAME, user.displayName ?: "").apply()
                val remoteFriends = userDoc.optJSONArray("friends")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } } ?: emptyList()
                val remoteSaved = userDoc.optJSONArray("savedFriends")
                if (remoteSaved != null && remoteSaved.length() > 0) {
                    val list = mutableListOf<SavedFriend>()
                    for (i in 0 until remoteSaved.length()) list.add(SavedFriend.fromJson(remoteSaved.getJSONObject(i)))
                    withContext(Dispatchers.Main) {
                        savedFriends = list
                        prefs.edit().putString(PREF_SAVED_FRIENDS, JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()).apply()
                    }
                }
                withContext(Dispatchers.Main) {
                    remoteFriends.forEach { fid -> if (fid !in trackedIds) { trackedIds.add(fid); friendLocations[fid] = null } }
                    trackedIds.filter { it !in remoteFriends }.forEach { fid -> trackedIds.remove(fid); friendLocations.remove(fid) }
                }
                httpPost("/api/user/upsert", JSONObject().apply { put("uid", user.uid); put("trackId", remoteTrackId); put("displayName", user.displayName ?: ""); put("email", user.email ?: "") })
            } else {
                val localCached = prefs.getString(PREF_TRACK_ID, null)
                val trackId = localCached ?: (httpPost("/api/track/generate", JSONObject())?.optString("trackId") ?: "TRK-${UUID.randomUUID().toString().take(8).uppercase()}")
                httpPost("/api/user/upsert", JSONObject().apply { put("uid", user.uid); put("trackId", trackId); put("displayName", user.displayName ?: ""); put("email", user.email ?: "") })
                withContext(Dispatchers.Main) { myTrackId = trackId; trackIdReady = true }
                prefs.edit().putString(PREF_TRACK_ID, trackId).putString(PREF_UID, user.uid).putString(PREF_DISPLAY_NAME, user.displayName ?: "").apply()
            }
        }
    }

    LaunchedEffect(trackIdReady, bgTrackingOn) {
        if (!trackIdReady) return@LaunchedEffect
        while (true) {
            if (!bgTrackingOn) {
                val pt = rawPoint
                if (pt != null) {
                    withContext(Dispatchers.IO) {
                        val ok = httpPost("/api/location/update", JSONObject().apply {
                            put("trackId", myTrackId); put("lat", pt.latitude()); put("lng", pt.longitude())
                            put("accuracy", rawAccuracy.toDouble()); put("speed", rawSpeedMs.toDouble())
                        }) != null
                        withContext(Dispatchers.Main) { isConnected = ok }
                    }
                }
            }
            delay(PUSH_INTERVAL)
        }
    }

    val lastNotifiedChatTs = remember { mutableStateMapOf<String, Long>() }
    var chatNotifBootstrapped by remember { mutableStateOf(false) }
    LaunchedEffect(trackIdReady, myTrackId, hasNotifPerm) {
        if (!trackIdReady) return@LaunchedEffect
        while (true) {
            val conversations = withContext(Dispatchers.IO) {
                httpGetArray("/api/chat/conversations/$myTrackId")?.let { parseConversations(it) } ?: emptyList()
            }
            if (!chatNotifBootstrapped) {
                conversations.forEach { c -> lastNotifiedChatTs[c.conversationId] = c.lastTimestamp }
                chatNotifBootstrapped = true
            } else {
                conversations.forEach { c ->
                    val previousTs = lastNotifiedChatTs[c.conversationId] ?: 0L
                    if (c.lastTimestamp > previousTs && (c.unread[myTrackId] ?: 0) > 0) {
                        val friendId = c.participants.firstOrNull { it != myTrackId } ?: "Friend"
                        showChatNotification(ctx, c.names[friendId].orEmpty().ifBlank { friendId }, c.lastMessage, c.conversationId.hashCode())
                    }
                    lastNotifiedChatTs[c.conversationId] = c.lastTimestamp
                }
            }
            delay(2_500L)
        }
    }

    @SuppressLint("MissingPermission")
    fun bootstrapLastLocation() {
        fused.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && rawPoint == null) {
                val acc = loc.accuracy.coerceAtLeast(1f)
                val spd = if (loc.hasSpeed() && loc.speed >= 0) loc.speed else 0f
                val smoothed = GpsFilter.process(loc.latitude, loc.longitude, acc, System.currentTimeMillis()) ?: return@addOnSuccessListener
                val smoothPt = Point.fromLngLat(smoothed.second, smoothed.first)
                rawPoint = smoothPt; rawAccuracy = GpsFilter.smoothedAccuracy; rawSpeedMs = spd
                myLocation = LocationData(smoothPt, GpsFilter.smoothedAccuracy, spd * 3.6f)
                scope.launch { val addr = withContext(Dispatchers.IO) { geocode(ctx, smoothPt.latitude(), smoothPt.longitude()) }; myLocation = myLocation?.copy(address = addr) }
            }
        }
    }

    LaunchedEffect(hasPerm) {
        if (hasPerm) {
            bootstrapLastLocation()
            startGps(fused = fused, onCb = onCb,
                onAvailability = { available -> if (!available) GpsFilter.reset(); gpsAvailable = available },
                onLoc = { pt, smoothAcc, spdMs ->
                    rawPoint = pt; rawAccuracy = smoothAcc; rawSpeedMs = spdMs
                    myLocation = myLocation?.copy(point = pt, accuracy = smoothAcc, speedKmh = spdMs * 3.6f) ?: LocationData(pt, smoothAcc, spdMs * 3.6f)
                    scope.launch { val addr = withContext(Dispatchers.IO) { geocode(ctx, pt.latitude(), pt.longitude()) }; myLocation = myLocation?.copy(address = addr) }
                    if (isRecording && sessionId != null && smoothAcc < ACC_ACCEPTABLE) {
                        val now = System.currentTimeMillis(); val good = smoothAcc < ACC_GOOD
                        val moved = lastRecPoint == null || distM(lastRecPoint!!, pt) > MIN_DIST
                        val forced = (now - lastRecTimeMs) > FORCE_MS
                        if (good || moved || forced) {
                            lastRecTimeMs = now; lastRecPoint = pt; pathPoints = pathPoints + pt
                            val sid = sessionId!!
                            scope.launch(Dispatchers.IO) { httpPost("/api/session/$sid/point", JSONObject().apply { put("lat", pt.latitude()); put("lng", pt.longitude()) }) }
                        }
                    }
                }
            )
        } else permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    val trackedSnapshot = trackedIds.toList()
    LaunchedEffect(trackedSnapshot) {
        if (trackedSnapshot.isEmpty()) return@LaunchedEffect
        while (true) {
            coroutineScope {
                trackedSnapshot.forEach { id ->
                    launch(Dispatchers.IO) {
                        val j = httpGet("/api/location/$id") ?: return@launch
                        val lat = j.optDouble("lat", Double.NaN); val lng = j.optDouble("lng", Double.NaN)
                        if (!lat.isNaN() && !lng.isNaN()) {
                            friendLocations[id] = FriendLocation(Point.fromLngLat(lng, lat), j.optDouble("accuracy", 0.0).toFloat(), (j.optDouble("speed", 0.0) * 3.6).toFloat(), j.optBoolean("isRecent", false))
                        }
                    }
                }
            }
            delay(POLL_INTERVAL)
        }
    }

    LaunchedEffect(trackedSnapshot, user.uid) {
        val idsNeedingProfile = trackedSnapshot.filter { id ->
            val existing = savedFriends.find { it.trackId == id }
            existing == null || existing.displayName.isBlank() || existing.email.isBlank()
        }
        if (idsNeedingProfile.isEmpty()) return@LaunchedEffect
        val fetched = withContext(Dispatchers.IO) {
            idsNeedingProfile.mapNotNull { id ->
                val j = httpGet("/api/user/by-trackid/$id") ?: return@mapNotNull null
                if (!j.has("trackId")) return@mapNotNull null
                SavedFriend(trackId = j.getString("trackId"), displayName = j.optString("displayName", "").ifBlank { j.getString("trackId") }, email = j.optString("email", ""))
            }
        }
        if (fetched.isEmpty()) return@LaunchedEffect
        val merged = savedFriends.toMutableList()
        fetched.forEach { incoming ->
            val idx = merged.indexOfFirst { it.trackId == incoming.trackId }
            if (idx < 0) merged.add(incoming)
            else { val c = merged[idx]; merged[idx] = c.copy(displayName = if (incoming.displayName.isNotBlank()) incoming.displayName else c.displayName, email = if (incoming.email.isNotBlank()) incoming.email else c.email) }
        }
        if (merged != savedFriends) { savedFriends = merged; persistSavedFriends(merged) }
    }

    fun persistFriends(newList: List<String>) {
        scope.launch(Dispatchers.IO) {
            httpPost("/api/user/${user.uid}/friends", JSONObject().apply { put("friends", JSONArray().apply { newList.forEach { put(it) } }) })
        }
    }

    fun addFriend(id: String, profile: SavedFriend? = null) {
        if (trackedIds.size >= MAX_FRIENDS || id in trackedIds) return
        trackedIds.add(id); friendLocations[id] = null; persistFriends(trackedIds.toList())
        if (profile != null) addSavedFriend(profile)
    }

    fun fetchFriendProfile(trackId: String, fallback: SavedFriend? = null): SavedFriend? {
        val normalized = trackId.trim().uppercase()
        val j = httpGet("/api/user/by-trackid/$normalized")
        if (j != null && j.has("trackId")) {
            val resolvedTrackId = j.getString("trackId")
            return SavedFriend(trackId = resolvedTrackId, displayName = j.optString("displayName", "").ifBlank { resolvedTrackId }, email = j.optString("email", ""))
        }
        return fallback?.copy(trackId = normalized, displayName = fallback.displayName.ifBlank { normalized })
    }

    fun removeFriend(id: String) { trackedIds.remove(id); friendLocations.remove(id); persistFriends(trackedIds.toList()) }
    fun toggleTrackSavedFriend(trackId: String) { if (trackId in trackedIds) removeFriend(trackId) else addFriend(trackId) }

    val onStartRecording: () -> Unit = {
        val sid = UUID.randomUUID().toString()
        sessionId = sid; pathPoints = emptyList(); lastRecPoint = null; lastRecTimeMs = 0L
        GpsFilter.reset(); isRecording = true
        val startTime = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        scope.launch(Dispatchers.IO) { httpPost("/api/session/start", JSONObject().apply { put("sessionId", sid); put("uid", user.uid); put("trackId", myTrackId); put("startTime", startTime) }) }
    }

    val onStopRecording: () -> Unit = {
        isRecording = false
        val sid = sessionId
        val endTime = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault()).format(Date())
        if (sid != null) scope.launch(Dispatchers.IO) { httpPatch("/api/session/$sid/end", JSONObject().apply { put("endTime", endTime) }) }
        sessionId = null; lastRecPoint = null
    }

    fun startBgTracking() {
        if (!hasPerm) { Toast.makeText(ctx, "Grant location permission first", Toast.LENGTH_SHORT).show(); return }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !hasBgPerm) bgPermLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else { ContextCompat.startForegroundService(ctx, Intent(ctx, LocationForegroundService::class.java)); bgTrackingOn = true }
    }

    fun stopBgTracking() { ctx.stopService(Intent(ctx, LocationForegroundService::class.java)); bgTrackingOn = false }

    if (!hasPerm) { PermScreen { permLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) }; return }

    // ─── Full-screen overlays (Chat / Profile) ───────────────────────────────
    if (activeTab == BottomTab.CHAT) {
        ChatEntryPoint(
            myTrackId          = myTrackId,
            myName             = user.displayName ?: myTrackId,
            trackedIds         = trackedIds.toList(),
            initialChatTrackId = chatWithTrackId,
            initialChatName    = chatWithName,
            onBack = {
                activeTab       = BottomTab.MAP
                chatWithTrackId = null
                chatWithName    = null
            }
        )
        return
    }

    if (viewingProfile != null) {
        val vp = viewingProfile!!
        UserProfileScreen(
            profile        = vp,
            isTracking     = vp.trackId in trackedIds,
            liveLocation   = friendLocations[vp.trackId],
            isAlreadySaved = savedFriends.any { it.trackId == vp.trackId },
            canTrack       = vp.trackId in trackedIds || trackedIds.size < MAX_FRIENDS,
            onBack         = { viewingProfile = null },
            onToggleTrack  = { toggleTrackSavedFriend(it) },
            onAddFriend    = { sf -> addSavedFriend(sf) },
            onRemoveFriend = { removeSavedFriend(it) },
            onOpenChat     = { sf ->
                chatWithTrackId = sf.trackId
                chatWithName    = sf.displayName.ifBlank { sf.trackId }
                viewingProfile  = null
                activeTab       = BottomTab.CHAT
            }
        )
        return
    }

    // ─── Main scaffold with bottom nav ───────────────────────────────────────
    Box(Modifier.fillMaxSize().background(DeepBlack)) {

        // Content area (full screen minus bottom nav)
        Box(
            Modifier
                .fillMaxSize()
                .padding(bottom = 72.dp)
        ) {
            when (activeTab) {
                BottomTab.MAP -> MapTabContent(
                    myLocation      = myLocation,
                    myTrackId       = myTrackId,
                    trackIdReady    = trackIdReady,
                    isConnected     = isConnected,
                    gpsAvailable    = gpsAvailable,
                    pathPoints      = pathPoints,
                    isRecording     = isRecording,
                    trackedIds      = trackedIds,
                    friendLocations = friendLocations,
                    savedFriends    = savedFriends,
                    bgTrackingOn    = bgTrackingOn,
                    isRecording2    = isRecording,
                    onStartRecording = onStartRecording,
                    onStopRecording  = onStopRecording,
                    onStartBgTracking = { startBgTracking() },
                    onStopBgTracking  = { stopBgTracking() },
                    onShowFriendDialog = { showFriendDialog = true },
                    onOpenChat        = { activeTab = BottomTab.CHAT }
                )
                BottomTab.FRIENDS -> FriendsTabContent(
                    savedFriends        = savedFriends,
                    trackedIds          = trackedIds.toList(),
                    friendLocations     = friendLocations,
                    onViewProfile       = { sf -> viewingProfile = sf },
                    onToggleTrack       = { toggleTrackSavedFriend(it) },
                    onRemoveSavedFriend = { removeSavedFriend(it) },
                    onOpenChat          = { sf ->
                        chatWithTrackId = sf.trackId
                        chatWithName    = sf.displayName.ifBlank { sf.trackId }
                        activeTab       = BottomTab.CHAT
                    },
                    onShowFriendDialog  = { showFriendDialog = true }
                )
                BottomTab.PROFILE -> ProfileTabContent(
                    user         = user,
                    myTrackId    = myTrackId,
                    trackIdReady = trackIdReady,
                    isConnected  = isConnected,
                    myLocation   = myLocation,
                    gpsAvailable = gpsAvailable,
                    bgTrackingOn = bgTrackingOn,
                    onStartBgTracking = { startBgTracking() },
                    onStopBgTracking  = { stopBgTracking() },
                    onSignOut    = onSignOut
                )
                else -> {}
            }
        }

        // ─── Bottom Navigation Bar ───────────────────────────────────────────
        BottomNavBar(
            activeTab = activeTab,
            onTabSelected = { tab ->
                if (tab == BottomTab.CHAT) {
                    chatWithTrackId = null; chatWithName = null
                }
                activeTab = tab
            },
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }

    // ─── Friend Dialog overlay ───────────────────────────────────────────────
    if (showFriendDialog) {
        FriendDialog(
            currentIds   = trackedIds.toList(),
            savedFriends = savedFriends,
            myUid        = user.uid,
            onDismiss    = { showFriendDialog = false },
            onTrackNow   = { sf ->
                scope.launch(Dispatchers.IO) {
                    val resolved = fetchFriendProfile(sf.trackId, sf)
                    withContext(Dispatchers.Main) {
                        if (resolved == null) { Toast.makeText(ctx, "Could not load this friend profile", Toast.LENGTH_SHORT).show(); return@withContext }
                        if (trackedIds.size >= MAX_FRIENDS) { Toast.makeText(ctx, "Maximum $MAX_FRIENDS friends reached", Toast.LENGTH_SHORT).show(); return@withContext }
                        addFriend(resolved.trackId.trim().uppercase(), resolved)
                        showFriendDialog = false
                    }
                }
            },
            onAddAndTrack = { sf ->
                scope.launch(Dispatchers.IO) {
                    val resolved = fetchFriendProfile(sf.trackId, sf) ?: sf
                    withContext(Dispatchers.Main) { addSavedFriend(resolved); addFriend(resolved.trackId, resolved); showFriendDialog = false }
                }
            },
            onViewProfile = { sf -> showFriendDialog = false; viewingProfile = sf }
        )
    }
}

// ─── Bottom Navigation Bar ────────────────────────────────────────────────────
@Composable
fun BottomNavBar(
    activeTab: BottomTab,
    onTabSelected: (BottomTab) -> Unit,
    modifier: Modifier = Modifier
) {
    val tabs = listOf(
        Triple(BottomTab.MAP,     "📍", "Map"),
        Triple(BottomTab.CHAT,    "💬", "Chat"),
        Triple(BottomTab.FRIENDS, "👥", "Friends"),
        Triple(BottomTab.PROFILE, "⚙", "Profile")
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(listOf(Color.Transparent, SurfaceBlack))
            )
    ) {
        Surface(
            modifier      = Modifier.fillMaxWidth(),
            color         = SurfaceBlack,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        brush = Brush.horizontalGradient(listOf(Color.Transparent, NeonGreen.copy(alpha = 0.25f), NeonGreen.copy(alpha = 0.5f), NeonGreen.copy(alpha = 0.25f), Color.Transparent)),
                        shape = RoundedCornerShape(0.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(72.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    tabs.forEach { (tab, emoji, label) ->
                        val selected = activeTab == tab
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { onTabSelected(tab) },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                if (selected) {
                                    // Active indicator line at top
                                    Box(
                                        Modifier
                                            .width(32.dp)
                                            .height(2.dp)
                                            .clip(RoundedCornerShape(1.dp))
                                            .background(NeonGreen)
                                    )
                                    Spacer(Modifier.height(6.dp))
                                }
                                Text(
                                    emoji,
                                    fontSize = if (selected) 22.sp else 20.sp
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    label,
                                    fontSize   = 10.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color      = if (selected) NeonGreen else TextMutedG,
                                    letterSpacing = 0.3.sp
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── Map Tab Content ──────────────────────────────────────────────────────────
@Composable
fun MapTabContent(
    myLocation: LocationData?,
    myTrackId: String,
    trackIdReady: Boolean,
    isConnected: Boolean,
    gpsAvailable: Boolean,
    pathPoints: List<Point>,
    isRecording: Boolean,
    trackedIds: List<String>,
    friendLocations: Map<String, FriendLocation?>,
    savedFriends: List<SavedFriend>,
    bgTrackingOn: Boolean,
    isRecording2: Boolean,
    onStartRecording: () -> Unit,
    onStopRecording: () -> Unit,
    onStartBgTracking: () -> Unit,
    onStopBgTracking: () -> Unit,
    onShowFriendDialog: () -> Unit,
    onOpenChat: () -> Unit
) {
    val viewport = rememberMapViewportState {
        setCameraOptions { zoom(4.0); center(Point.fromLngLat(78.9629, 20.5937)) }
    }
    val myCentred              = remember { mutableStateOf(false) }
    var previousTrackedIds     by remember { mutableStateOf(trackedIds) }
    var pendingFriendFocusId   by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(myLocation) {
        val d = myLocation ?: return@LaunchedEffect
        if (!myCentred.value) { viewport.flyTo(CameraOptions.Builder().center(d.point).zoom(16.0).build()); myCentred.value = true }
    }

    LaunchedEffect(trackedIds) {
        val added = trackedIds.filter { it !in previousTrackedIds }
        if (added.isNotEmpty()) pendingFriendFocusId = added.last()
        previousTrackedIds = trackedIds
    }

    LaunchedEffect(pendingFriendFocusId, friendLocations) {
        val id  = pendingFriendFocusId ?: return@LaunchedEffect
        val loc = friendLocations[id]  ?: return@LaunchedEffect
        viewport.flyTo(CameraOptions.Builder().center(loc.point).zoom(16.5).build())
        pendingFriendFocusId = null
    }

    Box(Modifier.fillMaxSize()) {
        // Full-screen map
        MapboxMap(
            modifier          = Modifier.fillMaxSize(),
            mapViewportState  = viewport,
            style             = { MapStyle(style = Style.MAPBOX_STREETS) }
        ) {
            if (pathPoints.size >= 2)
                PolylineAnnotation(points = pathPoints, lineColorString = "#00FF87", lineWidth = 4.0, lineOpacity = 0.9)
            pathPoints.forEachIndexed { i, pt ->
                if (i % 5 == 0) CircleAnnotation(point = pt, circleRadius = 4.0, circleColorString = "#00FF87", circleStrokeWidth = 1.5, circleStrokeColorString = "#000000")
            }
            trackedIds.forEachIndexed { slot, id ->
                val loc = friendLocations[id] ?: return@forEachIndexed
                val hex = FriendColorHex[slot % FriendColorHex.size]
                CircleAnnotation(point = loc.point, circleRadius = 22.0, circleColorString = hex, circleOpacity = 0.18, circleStrokeWidth = 0.0)
                CircleAnnotation(point = loc.point, circleRadius = 11.0, circleColorString = hex, circleStrokeWidth = 3.0, circleStrokeColorString = "#FFFFFF", circleOpacity = if (loc.isRecent) 1.0 else 0.5)
            }
            myLocation?.let { d ->
                val col = if (isRecording) "#FF3B5C" else "#00FF87"
                CircleAnnotation(point = d.point, circleRadius = 22.0, circleColorString = col, circleOpacity = 0.2, circleStrokeWidth = 0.0)
                CircleAnnotation(point = d.point, circleRadius = 11.0, circleColorString = col, circleStrokeWidth = 3.0, circleStrokeColorString = "#FFFFFF")
            }
        }

        // Status chip top-left
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(top = 52.dp, start = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatusChip(isConnected, isRecording, bgTrackingOn)
        }

        // FABs right side
        Column(
            modifier  = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Re-center
            MapFab(emoji = "🎯") {
                myLocation?.let { d -> viewport.setCameraOptions { center(d.point); zoom(16.0) } }
            }
            // Record
            MapFab(
                emoji  = if (isRecording) "⏹" else "⏺",
                color  = if (isRecording) RedAlert else ElevatedBlack,
                border = if (isRecording) RedAlert else NeonGreen.copy(alpha = 0.5f)
            ) { if (isRecording) onStopRecording() else onStartRecording() }
            // Add friend
            MapFab(emoji = "👥") { onShowFriendDialog() }
        }

        // Compact info strip at bottom
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, DeepBlack.copy(alpha = 0.95f))
                    )
                )
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            if (myLocation != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment     = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(NeonGreen)
                    )
                    Text(
                        myLocation.address,
                        fontSize   = 12.sp,
                        color      = TextWhite,
                        modifier   = Modifier.weight(1f),
                        maxLines   = 1,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        "±${myLocation.accuracy.toInt()}m",
                        fontSize  = 11.sp,
                        color     = NeonGreen,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = NeonGreen, strokeWidth = 1.5.dp)
                    Text("Acquiring GPS fix…", fontSize = 12.sp, color = TextDimmed)
                }
            }
        }
    }
}

@Composable
private fun StatusChip(isConnected: Boolean, isRecording: Boolean, bgTracking: Boolean) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = SurfaceBlack.copy(alpha = 0.9f)
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(if (isConnected) NeonGreen else TextMutedG))
            Text(
                if (isConnected) "Live" else "Offline",
                fontSize   = 11.sp,
                color      = if (isConnected) NeonGreen else TextDimmed,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            if (isRecording) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(RedAlert))
                Text("REC", fontSize = 11.sp, color = RedAlert, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
            }
            if (bgTracking) {
                Text("BG", fontSize = 10.sp, color = NeonGreen.copy(alpha = 0.7f), fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun MapFab(
    emoji: String,
    color: Color = ElevatedBlack,
    border: Color = NeonGreen.copy(alpha = 0.4f),
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, border, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(emoji, fontSize = 18.sp)
    }
}

// ─── Friends Tab Content ──────────────────────────────────────────────────────
@Composable
fun FriendsTabContent(
    savedFriends:        List<SavedFriend>,
    trackedIds:          List<String>,
    friendLocations:     Map<String, FriendLocation?>,
    onViewProfile:       (SavedFriend) -> Unit,
    onToggleTrack:       (String) -> Unit,
    onRemoveSavedFriend: (String) -> Unit,
    onOpenChat:          (SavedFriend) -> Unit,
    onShowFriendDialog:  () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }
    val liveCount   = trackedIds.count { id -> friendLocations[id]?.isRecent == true }

    val filteredFriends = savedFriends.filter { sf ->
        val matchesSearch = searchQuery.isBlank() ||
                sf.displayName.contains(searchQuery, ignoreCase = true) ||
                sf.trackId.contains(searchQuery, ignoreCase = true) ||
                sf.email.contains(searchQuery, ignoreCase = true)
        val matchesTab = when (selectedTab) {
            1    -> sf.trackId in trackedIds
            else -> true
        }
        matchesSearch && matchesTab
    }

    val untitledTracked = trackedIds.filter { id -> savedFriends.none { it.trackId == id } }

    Box(Modifier.fillMaxSize().background(DeepBlack)) {
        Column(Modifier.fillMaxSize()) {

            // Header
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(SurfaceBlack, DeepBlack))
                    )
                    .padding(top = 52.dp, bottom = 20.dp, start = 20.dp, end = 20.dp)
            ) {
                Column {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Friends",
                                fontSize   = 28.sp,
                                fontWeight = FontWeight.Black,
                                color      = TextWhite,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                GreenChip("${savedFriends.size} contacts")
                                if (liveCount > 0) GreenChip("$liveCount live 🟢")
                            }
                        }
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(ElevatedBlack)
                                .border(1.dp, NeonGreen.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                .clickable { onShowFriendDialog() },
                            Alignment.Center
                        ) {
                            Text("＋", fontSize = 20.sp, color = NeonGreen, fontWeight = FontWeight.Black)
                        }
                    }
                    Spacer(Modifier.height(16.dp))

                    // Search bar
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(ElevatedBlack)
                            .border(1.dp, BorderGreen, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔍", fontSize = 14.sp)
                        Spacer(Modifier.width(10.dp))
                        BasicTextField2Stub(
                            value         = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder   = "Search by name, ID or email…"
                        )
                    }

                    Spacer(Modifier.height(14.dp))

                    // Tab pills
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(ElevatedBlack)
                            .border(1.dp, BorderGreen, RoundedCornerShape(12.dp)),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        listOf("All" to "👥", "Live" to "🟢").forEachIndexed { idx, (label, emoji) ->
                            val sel = selectedTab == idx
                            Box(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (sel) NeonGreen else Color.Transparent)
                                    .clickable { selectedTab = idx }
                                    .padding(vertical = 12.dp),
                                Alignment.Center
                            ) {
                                Text(
                                    "$emoji  $label",
                                    fontSize   = 13.sp,
                                    fontWeight = if (sel) FontWeight.Black else FontWeight.Normal,
                                    color      = if (sel) PureBlack else TextDimmed
                                )
                            }
                        }
                    }
                }
            }

            // Friends list
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                if (filteredFriends.isEmpty() && (selectedTab != 0 || untitledTracked.isEmpty())) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("👥", fontSize = 48.sp)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                when {
                                    searchQuery.isNotBlank() -> "No friends match"
                                    selectedTab == 1         -> "No one is live right now"
                                    else                     -> "No saved friends yet"
                                },
                                fontSize  = 14.sp,
                                color     = TextDimmed,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                } else {
                    filteredFriends.forEach { sf ->
                        FriendProfileCard(
                            sf            = sf,
                            isTracking    = sf.trackId in trackedIds,
                            liveLocation  = friendLocations[sf.trackId],
                            onViewProfile = { onViewProfile(sf) },
                            onToggleTrack = { onToggleTrack(sf.trackId) },
                            onOpenChat    = { onOpenChat(sf) }
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    if (selectedTab == 0 && untitledTracked.isNotEmpty()) {
                        DarkSectionLabel("TRACKING WITHOUT PROFILE")
                        Spacer(Modifier.height(8.dp))
                        untitledTracked.forEachIndexed { idx, id ->
                            FriendProfileCard(
                                sf            = SavedFriend(trackId = id, displayName = "", email = ""),
                                isTracking    = true,
                                liveLocation  = friendLocations[id],
                                onViewProfile = {},
                                onToggleTrack = { onToggleTrack(id) },
                                onOpenChat    = {},
                                slotOverride  = trackedIds.indexOf(id).takeIf { it >= 0 } ?: idx
                            )
                            Spacer(Modifier.height(12.dp))
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// Simple inline text field without full OutlinedTextField to avoid import issues
@Composable
private fun BasicTextField2Stub(value: String, onValueChange: (String) -> Unit, placeholder: String) {
    // Use standard OutlinedTextField body approach with Box
    Box(Modifier.weight(1f)) {
        if (value.isEmpty()) Text(placeholder, color = TextMutedG, fontSize = 13.sp)
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = androidx.compose.ui.text.TextStyle(color = TextWhite, fontSize = 13.sp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }
}

// ─── Profile Tab Content ──────────────────────────────────────────────────────
@Composable
fun ProfileTabContent(
    user: FirebaseUser,
    myTrackId: String,
    trackIdReady: Boolean,
    isConnected: Boolean,
    myLocation: LocationData?,
    gpsAvailable: Boolean,
    bgTrackingOn: Boolean,
    onStartBgTracking: () -> Unit,
    onStopBgTracking: () -> Unit,
    onSignOut: () -> Unit
) {
    val ctx = LocalContext.current
    val (accLabel, accColor) = myLocation?.let { accuracyLabel(it.accuracy) } ?: ("—" to TextMutedG)

    Box(Modifier.fillMaxSize().background(DeepBlack)) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            // Profile header
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(listOf(SurfaceBlack, DeepBlack))
                    )
                    .padding(top = 52.dp, bottom = 32.dp)
            ) {
                Column(
                    Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Avatar
                    Box(
                        Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(ElevatedBlack)
                            .border(2.dp, NeonGreen.copy(alpha = 0.5f), CircleShape),
                        Alignment.Center
                    ) {
                        Text(
                            (user.displayName?.firstOrNull() ?: "U").toString().uppercase(),
                            fontSize   = 36.sp,
                            fontWeight = FontWeight.Black,
                            color      = NeonGreen,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(
                        user.displayName ?: "User",
                        fontSize   = 22.sp,
                        fontWeight = FontWeight.Black,
                        color      = TextWhite
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        user.email ?: "",
                        fontSize = 13.sp,
                        color    = TextDimmed
                    )
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = if (isConnected) NeonGreen.copy(alpha = 0.12f) else TextMutedG.copy(alpha = 0.12f)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(Modifier.size(6.dp).clip(CircleShape).background(if (isConnected) NeonGreen else TextMutedG))
                            Text(
                                if (isConnected) "Online" else "Offline",
                                fontSize   = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color      = if (isConnected) NeonGreen else TextDimmed
                            )
                        }
                    }
                }
            }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {

                // Track ID card
                DarkCard {
                    Column(Modifier.padding(16.dp)) {
                        DarkSectionLabel("TRACK ID")
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (trackIdReady) myTrackId else "Loading…",
                                modifier      = Modifier.weight(1f),
                                fontFamily    = FontFamily.Monospace,
                                fontSize      = 18.sp,
                                color         = NeonGreen,
                                fontWeight    = FontWeight.Black,
                                letterSpacing = 1.sp
                            )
                            var copied by remember { mutableStateOf(false) }
                            if (trackIdReady) {
                                Box(
                                    Modifier
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (copied) NeonGreen else ElevatedBlack)
                                        .border(1.dp, if (copied) NeonGreen else BorderGreen, RoundedCornerShape(10.dp))
                                        .clickable {
                                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Track ID", myTrackId))
                                            copied = true
                                        }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                ) {
                                    Text(
                                        if (copied) "✓" else "Copy",
                                        fontSize  = 12.sp,
                                        color     = if (copied) PureBlack else NeonGreen,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("Share this ID once — it never changes", fontSize = 11.sp, color = TextMutedG)
                    }
                }

                // Location card
                DarkCard {
                    Column(Modifier.padding(16.dp)) {
                        DarkSectionLabel("MY LOCATION")
                        Spacer(Modifier.height(10.dp))
                        val addressText = when {
                            myLocation == null -> "Acquiring GPS fix…"
                            !gpsAvailable      -> "${myLocation.address} (last known)"
                            else               -> myLocation.address
                        }
                        Text(addressText, fontSize = 13.sp, color = TextWhite, lineHeight = 19.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DarkMiniStat("LAT",  myLocation?.point?.latitude()?.let  { "%.5f".format(it) } ?: "—", Modifier.weight(1f))
                            DarkMiniStat("LNG",  myLocation?.point?.longitude()?.let { "%.5f".format(it) } ?: "—", Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DarkMiniStat("SPEED",    myLocation?.speedKmh?.let { if (it < 0.5f) "0.0 km/h" else "%.1f km/h".format(it) } ?: "—", Modifier.weight(1f))
                            DarkMiniStat("ACCURACY", myLocation?.accuracy?.let { "±${it.toInt()} m  $accLabel" } ?: "—", Modifier.weight(1f), valueColor = accColor)
                        }
                    }
                }

                // Background tracking card
                DarkCard(borderColor = if (bgTrackingOn) NeonGreen.copy(alpha = 0.35f) else BorderGreen) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                if (bgTrackingOn) "Background sharing ON" else "Background sharing OFF",
                                fontWeight = FontWeight.Bold,
                                fontSize   = 13.sp,
                                color      = if (bgTrackingOn) NeonGreen else TextWhite
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                if (bgTrackingOn) "Friends can see you when closed" else "Enable to share when app is closed",
                                fontSize = 11.sp,
                                color    = TextDimmed
                            )
                        }
                        Switch(
                            checked  = bgTrackingOn,
                            onCheckedChange = { if (it) onStartBgTracking() else onStopBgTracking() },
                            colors   = SwitchDefaults.colors(
                                checkedThumbColor  = PureBlack,
                                checkedTrackColor  = NeonGreen,
                                uncheckedThumbColor = TextDimmed,
                                uncheckedTrackColor = ElevatedBlack
                            )
                        )
                    }
                }

                // Sign out
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(ElevatedBlack)
                        .border(1.dp, RedAlert.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                        .clickable { onSignOut() }
                        .padding(vertical = 16.dp),
                    Alignment.Center
                ) {
                    Text("Sign Out", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = RedAlert)
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ─── Dark Design Helpers ──────────────────────────────────────────────────────
@Composable
fun DarkCard(
    borderColor: Color = BorderGreen,
    content: @Composable () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ElevatedBlack)
            .border(1.dp, borderColor, RoundedCornerShape(16.dp))
    ) {
        content()
    }
}

@Composable
fun DarkSectionLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(NeonGreen))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp, color = TextDimmed)
    }
}

@Composable
fun DarkMiniStat(
    label: String, value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = TextWhite
) {
    Box(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceBlack)
            .border(1.dp, BorderGreen, RoundedCornerShape(10.dp))
            .padding(10.dp)
    ) {
        Column {
            Text(label, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, color = TextMutedG)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = valueColor, fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
fun GreenChip(text: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = NeonGreen.copy(alpha = 0.1f)) {
        Text(
            text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            fontSize = 11.sp,
            color    = NeonGreen,
            fontWeight = FontWeight.Bold
        )
    }
}

// ─── Friend Profile Card (dark theme) ────────────────────────────────────────
@Composable
fun FriendProfileCard(
    sf:           SavedFriend,
    isTracking:   Boolean,
    liveLocation: FriendLocation?,
    onViewProfile: () -> Unit,
    onToggleTrack: () -> Unit,
    onOpenChat:    () -> Unit,
    slotOverride: Int = 0
) {
    val hasProfile = sf.displayName.isNotBlank()
    val isLive     = liveLocation?.isRecent == true
    val borderCol  = when { isLive -> NeonGreen.copy(alpha = 0.5f); isTracking -> AmberWarn.copy(alpha = 0.4f); else -> BorderGreen }

    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ElevatedBlack)
            .border(1.dp, borderCol, RoundedCornerShape(16.dp))
            .then(if (hasProfile) Modifier.clickable { onViewProfile() } else Modifier)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(if (isTracking) NeonGreen.copy(alpha = 0.15f) else SurfaceBlack)
                        .border(2.dp, if (isLive) NeonGreen.copy(alpha = 0.7f) else BorderGreen, CircleShape),
                    Alignment.Center
                ) {
                    Text(
                        sf.displayName.firstOrNull()?.toString()?.uppercase() ?: sf.trackId.firstOrNull()?.toString() ?: "?",
                        fontSize   = 20.sp,
                        fontWeight = FontWeight.Black,
                        color      = if (isTracking) NeonGreen else TextDimmed,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    if (hasProfile) {
                        Text(sf.displayName, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = TextWhite)
                        Text(sf.email.ifBlank { "" }, fontSize = 10.sp, color = TextDimmed)
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(
                        sf.trackId,
                        fontSize   = if (hasProfile) 10.sp else 13.sp,
                        color      = NeonGreen.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(when { isLive -> NeonGreen; isTracking -> AmberWarn; else -> TextMutedG }))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            when { isLive -> "Live now"; isTracking -> "Tracking"; else -> "Not tracked" },
                            fontSize   = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color      = when { isLive -> NeonGreen; isTracking -> AmberWarn; else -> TextDimmed }
                        )
                        if (isLive && liveLocation != null && liveLocation.speedKmh > 0.5f) {
                            Text("  •  %.0f km/h".format(liveLocation.speedKmh), fontSize = 10.sp, color = TextDimmed)
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isTracking) RedAlert.copy(alpha = 0.15f) else NeonGreen.copy(alpha = 0.12f))
                            .border(1.dp, if (isTracking) RedAlert.copy(alpha = 0.5f) else NeonGreen.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .clickable { onToggleTrack() }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            if (isTracking) "Untrack" else "Track",
                            fontSize   = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color      = if (isTracking) RedAlert else NeonGreen
                        )
                    }
                }
            }

            if (hasProfile) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(BorderGreen))
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(SurfaceBlack)
                        .border(1.dp, BorderGreen, RoundedCornerShape(8.dp))
                        .clickable { onOpenChat() }
                        .padding(vertical = 9.dp),
                    Alignment.Center
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("💬", fontSize = 13.sp)
                        Text(
                            "Message ${sf.displayName.split(" ").first()}",
                            fontSize   = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color      = TextWhite
                        )
                    }
                }
            }
        }
    }
}

// ─── User Profile Screen (dark theme) ────────────────────────────────────────
@Composable
fun UserProfileScreen(
    profile:        SavedFriend,
    isTracking:     Boolean,
    liveLocation:   FriendLocation?,
    isAlreadySaved: Boolean,
    canTrack:       Boolean,
    onBack:         () -> Unit,
    onToggleTrack:  (String) -> Unit,
    onAddFriend:    (SavedFriend) -> Unit,
    onRemoveFriend: (String) -> Unit,
    onOpenChat:     (SavedFriend) -> Unit
) {
    val isLive = liveLocation?.isRecent == true

    Box(Modifier.fillMaxSize().background(DeepBlack)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            // Header
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(SurfaceBlack, DeepBlack)))
                    .padding(bottom = 32.dp)
            ) {
                Box(
                    Modifier
                        .padding(top = 52.dp, start = 16.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(ElevatedBlack)
                        .border(1.dp, BorderGreen, CircleShape)
                        .clickable { onBack() },
                    Alignment.Center
                ) {
                    Icon(Icons.Default.ArrowBack, null, tint = NeonGreen, modifier = Modifier.size(18.dp))
                }

                Column(Modifier.align(Alignment.BottomCenter).padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .background(ElevatedBlack)
                            .border(2.dp, if (isLive) NeonGreen.copy(alpha = 0.6f) else BorderGreen, CircleShape),
                        Alignment.Center
                    ) {
                        Text(
                            profile.displayName.firstOrNull()?.toString()?.uppercase() ?: "?",
                            fontSize   = 40.sp,
                            fontWeight = FontWeight.Black,
                            color      = NeonGreen,
                            fontFamily = FontFamily.Monospace
                        )
                        if (isLive) {
                            Box(Modifier.size(20.dp).align(Alignment.BottomEnd).offset((-4).dp, (-4).dp).clip(CircleShape).background(NeonGreen).border(2.dp, DeepBlack, CircleShape))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(profile.displayName.ifBlank { "Unknown" }, fontSize = 22.sp, fontWeight = FontWeight.Black, color = TextWhite)
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .background(ElevatedBlack)
                            .border(1.dp, BorderGreen, RoundedCornerShape(20.dp))
                            .padding(horizontal = 14.dp, vertical = 5.dp)
                    ) {
                        Text(profile.trackId, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = NeonGreen.copy(alpha = 0.9f), fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                    }
                }
            }

            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

                // Action buttons
                Row(Modifier.fillMaxWidth().offset(y = (-12).dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isTracking) RedAlert.copy(alpha = 0.15f) else NeonGreen.copy(alpha = 0.12f))
                            .border(1.dp, if (isTracking) RedAlert.copy(alpha = 0.5f) else NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .then(if (canTrack) Modifier.clickable { onToggleTrack(profile.trackId) } else Modifier)
                            .padding(vertical = 14.dp),
                        Alignment.Center
                    ) {
                        Text(
                            if (isTracking) "⏹  Untrack" else "📍  Track",
                            fontWeight = FontWeight.Bold,
                            fontSize   = 14.sp,
                            color      = if (!canTrack && !isTracking) TextDimmed else if (isTracking) RedAlert else NeonGreen
                        )
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (isAlreadySaved) ElevatedBlack else NeonGreen.copy(alpha = 0.12f))
                            .border(1.dp, if (isAlreadySaved) BorderGreen else NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
                            .clickable { if (isAlreadySaved) onRemoveFriend(profile.trackId) else onAddFriend(profile) }
                            .padding(vertical = 14.dp),
                        Alignment.Center
                    ) {
                        Text(
                            if (isAlreadySaved) "✓  Saved" else "＋  Add",
                            fontWeight = FontWeight.Bold,
                            fontSize   = 14.sp,
                            color      = if (isAlreadySaved) TextDimmed else NeonGreen
                        )
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .offset(y = (-4).dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(ElevatedBlack)
                        .border(1.dp, BorderGreen, RoundedCornerShape(14.dp))
                        .clickable { onOpenChat(profile) }
                        .padding(vertical = 14.dp),
                    Alignment.Center
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("💬", fontSize = 18.sp)
                        Text(
                            "Message ${profile.displayName.split(" ").first().ifBlank { "Friend" }}",
                            fontWeight = FontWeight.Bold,
                            fontSize   = 14.sp,
                            color      = TextWhite
                        )
                    }
                }

                // Info card
                DarkCard {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(when { isLive -> NeonGreen.copy(alpha = 0.12f); isTracking -> AmberWarn.copy(alpha = 0.12f); else -> SurfaceBlack }), Alignment.Center) {
                                Text(when { isLive -> "🟢"; isTracking -> "🟡"; else -> "⚫" }, fontSize = 14.sp)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(when { isLive -> "Live Now"; isTracking -> "Being Tracked"; else -> "Not Tracked" }, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = when { isLive -> NeonGreen; isTracking -> AmberWarn; else -> TextDimmed })
                                Text(when { isLive -> "Location updating in real-time"; isTracking -> "Last known location available"; else -> "Add to tracking to see location" }, fontSize = 11.sp, color = TextMutedG)
                            }
                        }

                        if (profile.email.isNotBlank()) {
                            Box(Modifier.fillMaxWidth().height(1.dp).background(BorderGreen))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(36.dp).clip(CircleShape).background(SurfaceBlack), Alignment.Center) { Text("✉", fontSize = 14.sp) }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text("EMAIL", fontSize = 9.sp, color = TextMutedG, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                    Text(profile.email, fontSize = 13.sp, color = TextWhite)
                                }
                            }
                        }

                        Box(Modifier.fillMaxWidth().height(1.dp).background(BorderGreen))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(36.dp).clip(CircleShape).background(SurfaceBlack), Alignment.Center) { Text("🔑", fontSize = 14.sp) }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("TRACK ID", fontSize = 9.sp, color = TextMutedG, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                                Text(profile.trackId, fontSize = 13.sp, color = NeonGreen, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                            }
                        }
                    }
                }

                // Live location card
                if (isTracking && liveLocation != null) {
                    DarkCard(borderColor = if (isLive) NeonGreen.copy(alpha = 0.4f) else BorderGreen) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                DarkSectionLabel("LIVE LOCATION")
                                Spacer(Modifier.weight(1f))
                                if (isLive) {
                                    GreenChip("● Live")
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DarkMiniStat("LAT",  "%.5f".format(liveLocation.point.latitude()),  Modifier.weight(1f))
                                DarkMiniStat("LNG",  "%.5f".format(liveLocation.point.longitude()), Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                DarkMiniStat("SPEED",    if (liveLocation.speedKmh < 0.5f) "0.0 km/h" else "%.1f km/h".format(liveLocation.speedKmh), Modifier.weight(1f))
                                DarkMiniStat("ACCURACY", "±${liveLocation.accuracy.toInt()} m",     Modifier.weight(1f))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

// ─── Friend Dialog (dark theme) ───────────────────────────────────────────────
@Composable
fun FriendDialog(
    currentIds:    List<String>,
    savedFriends:  List<SavedFriend>,
    myUid:         String,
    onDismiss:     () -> Unit,
    onTrackNow:    (SavedFriend) -> Unit,
    onAddAndTrack: (SavedFriend) -> Unit,
    onViewProfile: (SavedFriend) -> Unit
) {
    var idInput         by remember { mutableStateOf("") }
    var lookupResult    by remember { mutableStateOf<SavedFriend?>(null) }
    var lookupState     by remember { mutableStateOf("idle") }
    var lookupRequestId by remember { mutableIntStateOf(0) }

    val scope           = rememberCoroutineScope()
    val kb              = LocalSoftwareKeyboardController.current

    val idNorm         = idInput.trim().uppercase()
    val alreadyTracked = idNorm in currentIds
    val alreadySaved   = savedFriends.any { it.trackId == idNorm }
    val canTrackById   = idNorm.isNotBlank() && !alreadyTracked && currentIds.size < MAX_FRIENDS && lookupState == "found" && lookupResult != null

    fun doIdLookup() {
        if (idNorm.isBlank()) return
        val requestedId = idNorm; lookupRequestId += 1; val requestId = lookupRequestId
        lookupState = "loading"; lookupResult = null
        scope.launch(Dispatchers.IO) {
            val result = httpGet("/api/user/by-trackid/$requestedId")
            withContext(Dispatchers.Main) {
                if (requestId != lookupRequestId || idInput.trim().uppercase() != requestedId) return@withContext
                if (result != null && result.has("trackId")) {
                    val resolvedTrackId = result.getString("trackId")
                    lookupResult = SavedFriend(trackId = resolvedTrackId, displayName = result.optString("displayName", "").ifBlank { resolvedTrackId }, email = result.optString("email", ""))
                    lookupState = "found"
                } else lookupState = "notfound"
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(SurfaceBlack)
                .border(1.dp, BorderGreen, RoundedCornerShape(20.dp))
        ) {
            Column(Modifier.padding(22.dp)) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(ElevatedBlack).border(1.dp, NeonGreen.copy(alpha = 0.3f), RoundedCornerShape(12.dp)), Alignment.Center) {
                        Text("👥", fontSize = 16.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Add Friend", fontWeight = FontWeight.Black, fontSize = 16.sp, color = TextWhite)
                        Text("Enter their Track ID to connect", fontSize = 11.sp, color = TextDimmed)
                    }
                }

                Spacer(Modifier.height(18.dp))

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ElevatedBlack)
                            .border(1.dp, if (alreadyTracked) RedAlert.copy(alpha = 0.5f) else BorderGreen, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = idInput,
                            onValueChange = {
                                idInput = it.uppercase()
                                lookupRequestId += 1; lookupState = "idle"; lookupResult = null
                            },
                            textStyle = androidx.compose.ui.text.TextStyle(
                                color = NeonGreen,
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            ),
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { kb?.hide(); doIdLookup() }),
                            decorationBox = { inner ->
                                if (idInput.isEmpty()) Text("TRK-XXXXXXXX", color = TextMutedG, fontSize = 14.sp, fontFamily = FontFamily.Monospace)
                                inner()
                            }
                        )
                    }
                    Box(
                        Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (idNorm.isNotBlank() && lookupState != "loading") NeonGreen else ElevatedBlack)
                            .border(1.dp, BorderGreen, RoundedCornerShape(12.dp))
                            .clickable(enabled = idNorm.isNotBlank() && lookupState != "loading") { kb?.hide(); doIdLookup() },
                        Alignment.Center
                    ) {
                        if (lookupState == "loading") CircularProgressIndicator(Modifier.size(18.dp), color = NeonGreen, strokeWidth = 2.dp)
                        else Text("🔍", fontSize = 16.sp)
                    }
                }

                if (alreadyTracked) {
                    Spacer(Modifier.height(6.dp))
                    Text("Already tracking this ID", fontSize = 11.sp, color = RedAlert, fontWeight = FontWeight.SemiBold)
                }

                if (lookupState == "found" && lookupResult != null) {
                    Spacer(Modifier.height(14.dp))
                    DarkSearchResultCard(
                        sf             = lookupResult!!,
                        alreadySaved   = alreadySaved,
                        alreadyTracked = alreadyTracked,
                        onViewProfile  = { onViewProfile(lookupResult!!) },
                        onAddAndTrack  = { onAddAndTrack(lookupResult!!) }
                    )
                }

                if (lookupState == "notfound") {
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(RedAlert.copy(alpha = 0.08f))
                            .border(1.dp, RedAlert.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚠", fontSize = 14.sp); Spacer(Modifier.width(8.dp))
                        Text("No account found for this Track ID", fontSize = 12.sp, color = RedAlert.copy(alpha = 0.9f), fontWeight = FontWeight.SemiBold)
                    }
                }

                Spacer(Modifier.height(18.dp))

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ElevatedBlack)
                            .border(1.dp, BorderGreen, RoundedCornerShape(12.dp))
                            .clickable { onDismiss() }
                            .padding(vertical = 14.dp),
                        Alignment.Center
                    ) {
                        Text("Cancel", fontSize = 14.sp, color = TextDimmed)
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (canTrackById) NeonGreen else ElevatedBlack)
                            .border(1.dp, if (canTrackById) NeonGreen else BorderGreen, RoundedCornerShape(12.dp))
                            .clickable(enabled = canTrackById) { lookupResult?.let { onTrackNow(it) } }
                            .padding(vertical = 14.dp),
                        Alignment.Center
                    ) {
                        Text(
                            if (lookupState == "found") "Add Friend" else "Search First",
                            fontSize   = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color      = if (canTrackById) PureBlack else TextMutedG
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DarkSearchResultCard(
    sf:             SavedFriend,
    alreadySaved:   Boolean,
    alreadyTracked: Boolean,
    onViewProfile:  () -> Unit,
    onAddAndTrack:  () -> Unit
) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(ElevatedBlack)
            .border(1.dp, NeonGreen.copy(alpha = 0.3f), RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(48.dp).clip(CircleShape)
                        .background(NeonGreen.copy(alpha = 0.12f))
                        .border(1.dp, NeonGreen.copy(alpha = 0.4f), CircleShape),
                    Alignment.Center
                ) {
                    Text(sf.displayName.firstOrNull()?.toString()?.uppercase() ?: "?", fontWeight = FontWeight.Black, color = NeonGreen, fontSize = 18.sp, fontFamily = FontFamily.Monospace)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(sf.displayName.ifBlank { "Unknown" }, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextWhite)
                    Text(sf.email.ifBlank { "No email" }, fontSize = 10.sp, color = TextDimmed)
                    Text(sf.trackId, fontSize = 10.sp, color = NeonGreen.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(BorderGreen))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(SurfaceBlack)
                        .border(1.dp, NeonGreen.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                        .clickable { onViewProfile() }
                        .padding(vertical = 10.dp),
                    Alignment.Center
                ) {
                    Text("View Profile", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = NeonGreen)
                }
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (alreadySaved || alreadyTracked) SurfaceBlack else NeonGreen.copy(alpha = 0.15f))
                        .border(1.dp, if (alreadySaved || alreadyTracked) BorderGreen else NeonGreen.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                        .clickable { onAddAndTrack() }
                        .padding(vertical = 10.dp),
                    Alignment.Center
                ) {
                    Text(
                        when { alreadyTracked -> "✓ Tracking"; alreadySaved -> "✓ Added"; else -> "＋ Add Friend" },
                        fontSize   = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color      = if (alreadySaved || alreadyTracked) TextDimmed else NeonGreen
                    )
                }
            }
        }
    }
}

// ─── Permission Screen ────────────────────────────────────────────────────────
@Composable
fun PermScreen(onRequest: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(DeepBlack),
        Alignment.Center
    ) {
        Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(ForestGreen.copy(alpha = 0.2f), Color.Transparent), radius = 700f)))
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(88.dp).clip(RoundedCornerShape(24.dp))
                    .background(ElevatedBlack)
                    .border(1.dp, NeonGreen.copy(alpha = 0.4f), RoundedCornerShape(24.dp)),
                Alignment.Center
            ) { Text("📍", fontSize = 44.sp) }
            Spacer(Modifier.height(28.dp))
            Text("Location Access Needed", fontSize = 22.sp, fontWeight = FontWeight.Black, color = TextWhite, textAlign = TextAlign.Center, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(10.dp))
            Text("Grant location access to appear on the map and share with friends.", fontSize = 14.sp, color = TextDimmed, textAlign = TextAlign.Center, lineHeight = 21.sp)
            Spacer(Modifier.height(40.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(NeonGreen)
                    .clickable { onRequest() }
                    .padding(vertical = 16.dp),
                Alignment.Center
            ) {
                Text("Grant Permission", fontWeight = FontWeight.Black, fontSize = 15.sp, color = PureBlack)
            }
        }
    }
}

// ─── GPS helpers ──────────────────────────────────────────────────────────────
@SuppressLint("MissingPermission")
fun startGps(fused: FusedLocationProviderClient, onCb: (LocationCallback) -> Unit, onAvailability: (Boolean) -> Unit, onLoc: (Point, Float, Float) -> Unit) {
    val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L)
        .setMinUpdateIntervalMillis(300L).setWaitForAccurateLocation(false).setMaxUpdateDelayMillis(1_000L).build()
    val cb = object : LocationCallback() {
        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!availability.isLocationAvailable) GpsFilter.reset()
            onAvailability(availability.isLocationAvailable)
        }
        override fun onLocationResult(result: LocationResult) {
            val loc = result.locations.filter { it.accuracy <= ACC_ACCEPTABLE }.minByOrNull { it.accuracy }
                ?: result.locations.minByOrNull { it.accuracy } ?: return
            val nowMs = System.currentTimeMillis()
            val spdMs = if (loc.hasSpeed() && loc.speed >= 0) loc.speed else 0f
            val smoothed = GpsFilter.process(loc.latitude, loc.longitude, loc.accuracy, nowMs) ?: return
            onLoc(Point.fromLngLat(smoothed.second, smoothed.first), GpsFilter.smoothedAccuracy, spdMs)
        }
    }
    onCb(cb); fused.requestLocationUpdates(req, cb, Looper.getMainLooper())
}

// ─── Geocoder ─────────────────────────────────────────────────────────────────
fun geocode(ctx: Context, lat: Double, lon: Double): String = try {
    val list = Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)
    if (!list.isNullOrEmpty()) {
        val a = list[0]
        listOfNotNull(a.subLocality ?: a.thoroughfare, a.locality ?: a.subAdminArea, a.adminArea, a.countryName).joinToString(", ")
    } else "Address not found"
} catch (_: Exception) { "Address unavailable" }
