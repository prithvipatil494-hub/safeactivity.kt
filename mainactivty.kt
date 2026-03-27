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
const val MAX_FRIENDS   = 4

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

// ─── Palette ──────────────────────────────────────────────────────────────────
val SkyPrimary    = Color(0xFF0EA5E9)
val SkyLight      = Color(0xFF38BDF8)
val SkyPale       = Color(0xFFBAE6FD)
val SkyDeep       = Color(0xFF0369A1)
val SheetBg       = Color(0xFFF0F9FF)
val SheetCard     = Color(0xFFFFFFFF)
val SheetBorder   = Color(0xFFE0F2FE)
val TextPrimary   = Color(0xFF0C4A6E)
val TextSecondary = Color(0xFF64748B)
val TextMuted     = Color(0xFF94A3B8)
val GreenOnline   = Color(0xFF10B981)
val RedRecord     = Color(0xFFEF4444)

val FriendColors   = listOf(Color(0xFF10B981), Color(0xFF8B5CF6), Color(0xFFF59E0B), Color(0xFFF43F5E))
val FriendColorHex = listOf("#10B981", "#8B5CF6", "#F59E0B", "#F43F5E")
val FriendLabels   = listOf("Emerald", "Violet", "Amber", "Rose")

// ─── Thresholds ───────────────────────────────────────────────────────────────
private const val ACC_GOOD           = 30f
private const val ACC_ACCEPTABLE     = 100f
private const val MIN_DIST           = 2.0
private const val FORCE_MS           = 4_000L
private const val MAX_SPEED_JUMP_MS  = 55.0
private const val ACCURACY_WINDOW    = 8

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
    val channel = NotificationChannel(
        CHAT_CHANNEL_ID,
        "Chat Messages",
        NotificationManager.IMPORTANCE_DEFAULT
    ).apply {
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

// ─── Accuracy label ───────────────────────────────────────────────────────────
fun accuracyLabel(acc: Float): Pair<String, Color> = when {
    acc < 20f  -> "Excellent" to GreenOnline
    acc < 50f  -> "Good"      to Color(0xFF84CC16)
    acc < 100f -> "Fair"      to Color(0xFFF59E0B)
    else       -> "Poor"      to RedRecord
}

// ─── Haversine ────────────────────────────────────────────────────────────────
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
    private var latEstimate = 0.0
    private var latVariance = Double.MAX_VALUE
    private var lngEstimate = 0.0
    private var lngVariance = Double.MAX_VALUE
    private var lastTimestamp = 0L
    val isInitialised: Boolean get() = lastTimestamp != 0L
    fun process(lat: Double, lng: Double, accuracy: Float, timestampMs: Long): Pair<Double, Double> {
        val accMetres = accuracy.toDouble().coerceAtLeast(1.0)
        if (!isInitialised) {
            latEstimate = lat; latVariance = accMetres * accMetres
            lngEstimate = lng; lngVariance = accMetres * accMetres
            lastTimestamp = timestampMs
            return lat to lng
        }
        val dtSeconds = ((timestampMs - lastTimestamp) / 1000.0).coerceIn(0.0, 10.0)
        lastTimestamp = timestampMs
        val qContrib = Q_METRES_PER_SECOND * Q_METRES_PER_SECOND * dtSeconds
        latVariance += qContrib; lngVariance += qContrib
        val R = accMetres * accMetres
        val kLat = latVariance / (latVariance + R)
        latEstimate = latEstimate + kLat * (lat - latEstimate)
        latVariance = (1.0 - kLat) * latVariance
        val kLng = lngVariance / (lngVariance + R)
        lngEstimate = lngEstimate + kLng * (lng - lngEstimate)
        lngVariance = (1.0 - kLng) * lngVariance
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
    private var lastPoint: Point? = null
    private var lastMs = 0L
    fun isValid(pt: Point, nowMs: Long): Boolean {
        val prev = lastPoint
        val ok = if (prev == null || lastMs == 0L) true
        else { val dtSec = (nowMs - lastMs) / 1000.0; val d = distM(prev, pt); val s = if (dtSec > 0) d / dtSec else 0.0; s < MAX_SPEED_JUMP_MS }
        if (ok) { lastPoint = pt; lastMs = nowMs }
        return ok
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
    MaterialTheme(colorScheme = lightColorScheme(
        primary    = SkyPrimary,
        background = SheetBg,
        surface    = SheetCard,
        onSurface  = TextPrimary,
        onPrimary  = Color.White
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
    Box(modifier = Modifier.fillMaxSize().background(
        Brush.verticalGradient(listOf(Color(0xFF0C4A6E), Color(0xFF0EA5E9), Color(0xFF38BDF8)))
    ), contentAlignment = Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(88.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f)), Alignment.Center) {
                Text("📍", fontSize = 44.sp)
            }
            Spacer(Modifier.height(28.dp))
            Text("LiveLoc", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, letterSpacing = (-0.5).sp)
            Spacer(Modifier.height(8.dp))
            Text("Live location sharing, simplified", fontSize = 15.sp, color = SkyPale, textAlign = TextAlign.Center)
            Spacer(Modifier.height(52.dp))
            if (loading) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
            } else {
                Button(
                    onClick = {
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestIdToken(WEB_CLIENT_ID).requestEmail().build()
                        launcher.launch(GoogleSignIn.getClient(ctx, gso).signInIntent)
                    },
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White),
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Text("G", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color(0xFF4285F4), modifier = Modifier.padding(end = 14.dp))
                    Text("Continue with Google", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = TextPrimary)
                }
                Spacer(Modifier.height(16.dp))
                Text("Your location data is stored securely in MongoDB", fontSize = 11.sp, color = SkyPale.copy(alpha = 0.7f), textAlign = TextAlign.Center)
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

    var showFriendDialog by remember { mutableStateOf(false) }

    var showChat            by remember { mutableStateOf(false) }
    var chatWithTrackId     by remember { mutableStateOf<String?>(null) }
    var chatWithName        by remember { mutableStateOf<String?>(null) }

    var showMyFriends  by remember { mutableStateOf(false) }
    var viewingProfile by remember { mutableStateOf<SavedFriend?>(null) }

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
    val permLauncher   = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPerm = it }
    val bgPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasBgPerm = granted
        if (granted) { ContextCompat.startForegroundService(ctx, Intent(ctx, LocationForegroundService::class.java)); bgTrackingOn = true }
        else Toast.makeText(ctx, "Select 'Allow all the time' for background tracking", Toast.LENGTH_LONG).show()
    }
    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasNotifPerm = granted
    }

    LaunchedEffect(Unit) {
        ensureChatNotificationChannel(ctx)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasNotifPerm) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val json = prefs.getString(PREF_SAVED_FRIENDS, null)
        if (json != null) {
            try {
                val arr = JSONArray(json); val list = mutableListOf<SavedFriend>()
                for (i in 0 until arr.length()) list.add(SavedFriend.fromJson(arr.getJSONObject(i)))
                savedFriends = list
            } catch (_: Exception) {}
        }
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
                httpGetArray("/api/chat/conversations/$myTrackId")
                    ?.let { parseConversations(it) }
                    ?: emptyList()
            }

            if (!chatNotifBootstrapped) {
                conversations.forEach { c -> lastNotifiedChatTs[c.conversationId] = c.lastTimestamp }
                chatNotifBootstrapped = true
            } else {
                conversations.forEach { c ->
                    val previousTs = lastNotifiedChatTs[c.conversationId] ?: 0L
                    val hasNewerMessage = c.lastTimestamp > previousTs
                    val unreadForMe = c.unread[myTrackId] ?: 0
                    if (hasNewerMessage && unreadForMe > 0) {
                        val friendId = c.participants.firstOrNull { it != myTrackId } ?: "Friend"
                        val friendName = c.names[friendId].orEmpty().ifBlank { friendId }
                        showChatNotification(
                            ctx = ctx,
                            friendName = friendName,
                            message = c.lastMessage,
                            notificationId = c.conversationId.hashCode()
                        )
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
    LaunchedEffect(trackedSnapshot, showFriendDialog) {
        if (trackedSnapshot.isEmpty() || showFriendDialog) return@LaunchedEffect
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
                val track = j.getString("trackId")
                SavedFriend(
                    trackId = track,
                    displayName = j.optString("displayName", "").ifBlank { track },
                    email = j.optString("email", "")
                )
            }
        }
        if (fetched.isEmpty()) return@LaunchedEffect

        val merged = savedFriends.toMutableList()
        fetched.forEach { incoming ->
            val idx = merged.indexOfFirst { it.trackId == incoming.trackId }
            if (idx < 0) {
                merged.add(incoming)
            } else {
                val current = merged[idx]
                merged[idx] = current.copy(
                    displayName = if (incoming.displayName.isNotBlank()) incoming.displayName else current.displayName,
                    email = if (incoming.email.isNotBlank()) incoming.email else current.email
                )
            }
        }
        if (merged != savedFriends) {
            savedFriends = merged
            persistSavedFriends(merged)
        }
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
            return SavedFriend(
                trackId = resolvedTrackId,
                displayName = j.optString("displayName", "").ifBlank { resolvedTrackId },
                email = j.optString("email", "")
            )
        }
        return fallback?.copy(
            trackId = normalized,
            displayName = fallback.displayName.ifBlank { normalized }
        )
    }

    fun removeFriend(id: String) {
        trackedIds.remove(id); friendLocations.remove(id); persistFriends(trackedIds.toList())
    }

    fun toggleTrackSavedFriend(trackId: String) {
        if (trackId in trackedIds) removeFriend(trackId) else addFriend(trackId)
    }

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

    if (showChat) {
        ChatEntryPoint(
            myTrackId   = myTrackId,
            myName      = user.displayName ?: myTrackId,
            trackedIds  = trackedIds.toList(),
            initialChatTrackId = chatWithTrackId,
            initialChatName    = chatWithName,
            onBack = {
                showChat = false
                chatWithTrackId = null
                chatWithName    = null
            }
        )
        return
    }

    if (showMyFriends && viewingProfile == null) {
        MyFriendsScreen(
            savedFriends    = savedFriends,
            trackedIds      = trackedIds.toList(),
            friendLocations = friendLocations,
            onBack          = { showMyFriends = false },
            onViewProfile   = { sf -> viewingProfile = sf },
            onToggleTrack   = { toggleTrackSavedFriend(it) },
            onRemoveSavedFriend = { removeSavedFriend(it) },
            onOpenChat      = { sf ->
                chatWithTrackId = sf.trackId
                chatWithName    = sf.displayName.ifBlank { sf.trackId }
                showMyFriends   = false
                showChat        = true
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
                showMyFriends   = false
                showChat        = true
            }
        )
        return
    }

    MapScreen(
        myLocation          = myLocation,
        myTrackId           = myTrackId,
        trackIdReady        = trackIdReady,
        isConnected         = isConnected,
        gpsAvailable        = gpsAvailable,
        pathPoints          = pathPoints,
        isRecording         = isRecording,
        trackedIds          = trackedIds,
        friendLocations     = friendLocations,
        savedFriends        = savedFriends,
        bgTrackingOn        = bgTrackingOn,
        user                = user,
        onStartRecording    = onStartRecording,
        onStopRecording     = onStopRecording,
        onShowFriendDialog  = { showFriendDialog = true },
        onRemoveFriend      = { removeFriend(it) },
        onOpenChat          = { showChat = true },
        onStartBgTracking   = { startBgTracking() },
        onStopBgTracking    = { stopBgTracking() },
        onRemoveSavedFriend = { removeSavedFriend(it) },
        onToggleTrack       = { toggleTrackSavedFriend(it) },
        onOpenMyFriends     = { showMyFriends = true },
        onViewProfile       = { sf -> viewingProfile = sf },
        onOpenChatWith      = { sf ->
            chatWithTrackId = sf.trackId
            chatWithName    = sf.displayName.ifBlank { sf.trackId }
            showChat        = true
        },
        onSignOut           = onSignOut
    )

    if (showFriendDialog) {
        FriendDialog(
            currentIds    = trackedIds.toList(),
            savedFriends  = savedFriends,
            myUid         = user.uid,
            onDismiss     = { showFriendDialog = false },
            onTrackNow    = { sf ->
                scope.launch(Dispatchers.IO) {
                    val resolved = fetchFriendProfile(sf.trackId, sf)
                    withContext(Dispatchers.Main) {
                        if (resolved == null) {
                            Toast.makeText(ctx, "Could not load this friend profile", Toast.LENGTH_SHORT).show()
                            return@withContext
                        }
                        if (trackedIds.size >= MAX_FRIENDS) {
                            Toast.makeText(ctx, "Maximum $MAX_FRIENDS friends reached", Toast.LENGTH_SHORT).show()
                            return@withContext
                        }
                        addFriend(resolved.trackId.trim().uppercase(), resolved)
                        showFriendDialog = false
                    }
                }
            },
            onAddAndTrack = { sf ->
                scope.launch(Dispatchers.IO) {
                    val resolved = fetchFriendProfile(sf.trackId, sf) ?: sf
                    withContext(Dispatchers.Main) {
                        addSavedFriend(resolved)
                        addFriend(resolved.trackId, resolved)
                        showFriendDialog = false
                    }
                }
            },
            onViewProfile = { sf -> showFriendDialog = false; viewingProfile = sf }
        )
    }
}

// ─── Map Screen ───────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    myLocation: LocationData?, myTrackId: String, trackIdReady: Boolean,
    isConnected: Boolean, gpsAvailable: Boolean,
    pathPoints: List<Point>, isRecording: Boolean,
    trackedIds: List<String>,
    friendLocations: Map<String, FriendLocation?>,
    savedFriends: List<SavedFriend>,
    bgTrackingOn: Boolean,
    user: FirebaseUser,
    onStartRecording: () -> Unit, onStopRecording: () -> Unit,
    onShowFriendDialog: () -> Unit, onRemoveFriend: (String) -> Unit,
    onOpenChat: () -> Unit,
    onStartBgTracking: () -> Unit, onStopBgTracking: () -> Unit,
    onRemoveSavedFriend: (String) -> Unit,
    onToggleTrack: (String) -> Unit,
    onOpenMyFriends: () -> Unit,
    onViewProfile: (SavedFriend) -> Unit,
    onOpenChatWith: (SavedFriend) -> Unit,
    onSignOut: () -> Unit
) {
    val viewport  = rememberMapViewportState { setCameraOptions { zoom(4.0); center(Point.fromLngLat(78.9629, 20.5937)) } }
    val myCentred = remember { mutableStateOf(false) }
    var previousTrackedIds by remember { mutableStateOf(trackedIds) }
    var pendingFriendFocusId by remember { mutableStateOf<String?>(null) }

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
        val id = pendingFriendFocusId ?: return@LaunchedEffect
        val loc = friendLocations[id] ?: return@LaunchedEffect
        viewport.flyTo(
            CameraOptions.Builder()
                .center(loc.point)
                .zoom(16.5)
                .build()
        )
        pendingFriendFocusId = null
    }

    BottomSheetScaffold(
        sheetPeekHeight     = 280.dp,
        sheetContainerColor = SheetBg,
        sheetDragHandle = {
            Box(Modifier.padding(vertical = 10.dp).width(44.dp).height(4.dp).clip(CircleShape).background(SkyPrimary.copy(alpha = 0.3f)))
        },
        sheetContent = {
            BottomCardV2(
                myLocation          = myLocation,
                myTrackId           = myTrackId,
                trackIdReady        = trackIdReady,
                isConnected         = isConnected,
                gpsAvailable        = gpsAvailable,
                pathPoints          = pathPoints,
                isRecording         = isRecording,
                trackedIds          = trackedIds,
                friendLocations     = friendLocations,
                savedFriends        = savedFriends,
                bgTrackingOn        = bgTrackingOn,
                user                = user,
                onStartRecording    = onStartRecording,
                onStopRecording     = onStopRecording,
                onShowFriendDialog  = onShowFriendDialog,
                onRemoveFriend      = onRemoveFriend,
                onOpenChat          = onOpenChat,
                onStartBgTracking   = onStartBgTracking,
                onStopBgTracking    = onStopBgTracking,
                onRemoveSavedFriend = onRemoveSavedFriend,
                onToggleTrack       = onToggleTrack,
                onOpenMyFriends     = onOpenMyFriends,
                onViewProfile       = onViewProfile,
                onOpenChatWith      = onOpenChatWith,
                onSignOut           = onSignOut
            )
        }
    ) {
        Box(Modifier.fillMaxSize()) {
            MapboxMap(modifier = Modifier.fillMaxSize(), mapViewportState = viewport, style = { MapStyle(style = Style.MAPBOX_STREETS) }) {
                if (pathPoints.size >= 2)
                    PolylineAnnotation(points = pathPoints, lineColorString = "#0EA5E9", lineWidth = 4.0, lineOpacity = 0.9)
                pathPoints.forEachIndexed { i, pt ->
                    if (i % 5 == 0) CircleAnnotation(point = pt, circleRadius = 4.0, circleColorString = "#0EA5E9", circleStrokeWidth = 1.5, circleStrokeColorString = "#FFFFFF")
                }
                trackedIds.forEachIndexed { slot, id ->
                    val loc = friendLocations[id] ?: return@forEachIndexed
                    val hex = FriendColorHex[slot % FriendColorHex.size]
                    CircleAnnotation(point = loc.point, circleRadius = 22.0, circleColorString = hex, circleOpacity = 0.18, circleStrokeWidth = 0.0)
                    CircleAnnotation(point = loc.point, circleRadius = 11.0, circleColorString = hex, circleStrokeWidth = 3.0, circleStrokeColorString = "#FFFFFF", circleOpacity = if (loc.isRecent) 1.0 else 0.5)
                }
                myLocation?.let { d ->
                    val col = if (isRecording) "#EF4444" else "#0EA5E9"
                    CircleAnnotation(point = d.point, circleRadius = 22.0, circleColorString = col, circleOpacity = 0.2, circleStrokeWidth = 0.0)
                    CircleAnnotation(point = d.point, circleRadius = 11.0, circleColorString = col, circleStrokeWidth = 3.0, circleStrokeColorString = "#FFFFFF")
                }
            }
            FloatingActionButton(onClick = onOpenChat, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 356.dp), containerColor = SkyDeep, shape = CircleShape, elevation = FloatingActionButtonDefaults.elevation(6.dp)) {
                Text("Chat", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            FloatingActionButton(onClick = { myLocation?.let { d -> viewport.setCameraOptions { center(d.point); zoom(16.0) } } }, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 296.dp), containerColor = SkyPrimary, shape = CircleShape, elevation = FloatingActionButtonDefaults.elevation(6.dp)) { Icon(Icons.Default.LocationOn, null, tint = Color.White) }
        }
    }
}

@Composable
fun BottomCardV2(
    myLocation: LocationData?, myTrackId: String, trackIdReady: Boolean,
    isConnected: Boolean, gpsAvailable: Boolean,
    pathPoints: List<Point>, isRecording: Boolean,
    trackedIds: List<String>,
    friendLocations: Map<String, FriendLocation?>,
    savedFriends: List<SavedFriend>,
    bgTrackingOn: Boolean,
    user: FirebaseUser,
    onStartRecording: () -> Unit, onStopRecording: () -> Unit,
    onShowFriendDialog: () -> Unit, onRemoveFriend: (String) -> Unit,
    onOpenChat: () -> Unit,
    onStartBgTracking: () -> Unit, onStopBgTracking: () -> Unit,
    onRemoveSavedFriend: (String) -> Unit,
    onToggleTrack: (String) -> Unit,
    onOpenMyFriends: () -> Unit,
    onViewProfile: (SavedFriend) -> Unit,
    onOpenChatWith: (SavedFriend) -> Unit,
    onSignOut: () -> Unit
) {
    val ctx = LocalContext.current
    val liveCount = trackedIds.count { id -> friendLocations[id]?.isRecent == true }
    var activeSection by remember { mutableIntStateOf(0) }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Brush.linearGradient(listOf(SkyPrimary, SkyDeep))), Alignment.Center) {
                Text((user.displayName?.firstOrNull() ?: "U").toString().uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(user.displayName ?: "User", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = TextPrimary)
                Text(if (isConnected) "Online" else "Offline", fontSize = 11.sp, color = if (isConnected) GreenOnline else TextMuted)
            }
            TextButton(onClick = onSignOut) { Text("Sign out", color = TextMuted, fontSize = 11.sp) }
        }

        Spacer(Modifier.height(12.dp))
        Surface(shape = RoundedCornerShape(14.dp), color = Color.White, shadowElevation = 2.dp) {
            Row(Modifier.fillMaxWidth()) {
                listOf("Dashboard", "Friends", "Tracking").forEachIndexed { idx, label ->
                    val selected = activeSection == idx
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(14.dp))
                            .background(if (selected) SkyPrimary else Color.Transparent)
                            .clickable { activeSection = idx }
                            .padding(vertical = 12.dp),
                        Alignment.Center
                    ) {
                        Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, color = if (selected) Color.White else TextMuted)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        when (activeSection) {
            0 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { showFriendDialog_stub(onShowFriendDialog, trackedIds.size) },
                        enabled = trackedIds.size < MAX_FRIENDS,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SkyPrimary, disabledContainerColor = SheetBorder)
                    ) { Text("Add Friend", fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = onOpenMyFriends,
                        modifier = Modifier.weight(1f).height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, SheetBorder)
                    ) { Text("My Friends") }
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onOpenChat,
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SkyDeep)
                ) { Text("Open Messages", fontWeight = FontWeight.Bold) }

                Spacer(Modifier.height(12.dp))
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, SheetBorder)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("Track ID", fontSize = 11.sp, color = TextMuted, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (trackIdReady) myTrackId else "Loading", modifier = Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 16.sp, color = SkyPrimary, fontWeight = FontWeight.ExtraBold)
                            var copied by remember { mutableStateOf(false) }
                            Button(
                                onClick = {
                                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Track ID", myTrackId))
                                    copied = true
                                },
                                enabled = trackIdReady,
                                shape = RoundedCornerShape(10.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = if (copied) GreenOnline else SkyPrimary)
                            ) { Text(if (copied) "Copied" else "Copy", fontSize = 12.sp) }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                val addressText = when { myLocation == null -> "Acquiring GPS fix"; !gpsAvailable -> "${myLocation!!.address} (last known)"; else -> myLocation!!.address }
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, SheetBorder)) {
                    Column(Modifier.padding(14.dp)) {
                        Text(addressText, fontSize = 13.sp, color = TextPrimary, lineHeight = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MiniStat("LAT", myLocation?.point?.latitude()?.let { "%.5f".format(it) } ?: "—", Modifier.weight(1f))
                            MiniStat("LNG", myLocation?.point?.longitude()?.let { "%.5f".format(it) } ?: "—", Modifier.weight(1f))
                        }
                    }
                }
            }

            1 -> Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                // ── FIX: removed onClick from Surface, use Modifier.clickable instead ──
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenMyFriends() },
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White,
                    border = androidx.compose.foundation.BorderStroke(1.dp, SheetBorder)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Contacts ${savedFriends.size}  •  Live $liveCount", modifier = Modifier.weight(1f), fontSize = 13.sp, color = TextPrimary)
                        Text("Open", color = SkyPrimary, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (savedFriends.isNotEmpty()) {
                    SavedFriendsSection(
                        savedFriends = savedFriends,
                        trackedIds = trackedIds,
                        onToggleTrack = onToggleTrack,
                        onRemoveSavedFriend = onRemoveSavedFriend,
                        onViewProfile = onViewProfile,
                        onOpenChatWith = onOpenChatWith
                    )
                } else {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, SheetBorder)) {
                        Text("No saved contacts available.", modifier = Modifier.padding(14.dp), color = TextMuted, fontSize = 12.sp)
                    }
                }
            }

            else -> Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, if (bgTrackingOn) GreenOnline.copy(alpha = 0.35f) else SheetBorder)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (bgTrackingOn) "Background sharing enabled" else "Background sharing disabled", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (bgTrackingOn) GreenOnline else TextPrimary)
                            Text("Share location while app is not visible", fontSize = 11.sp, color = TextMuted)
                        }
                        Switch(checked = bgTrackingOn, onCheckedChange = { if (it) onStartBgTracking() else onStopBgTracking() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = GreenOnline))
                    }
                }
                Spacer(Modifier.height(10.dp))
                trackedIds.forEachIndexed { slot, id ->
                    val savedName = savedFriends.find { it.trackId == id }?.displayName
                    FriendCard(id = id, slot = slot, color = FriendColors[slot % FriendColors.size], label = FriendLabels[slot % FriendLabels.size], loc = friendLocations[id], savedName = savedName, onRemove = { onRemoveFriend(id) })
                    Spacer(Modifier.height(8.dp))
                }
                if (isRecording) {
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = RedRecord.copy(alpha = 0.08f)) {
                        Text("Recording in progress. Points captured: ${pathPoints.size}", modifier = Modifier.padding(12.dp), color = RedRecord, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Button(onClick = if (isRecording) onStopRecording else onStartRecording, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) RedRecord else SkyDeep), modifier = Modifier.fillMaxWidth().height(46.dp)) {
                    Text(if (isRecording) "Stop Recording" else "Start Recording", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// ─── Bottom Card ──────────────────────────────────────────────────────────────
@Composable
fun BottomCard(
    myLocation: LocationData?, myTrackId: String, trackIdReady: Boolean,
    isConnected: Boolean, gpsAvailable: Boolean,
    pathPoints: List<Point>, isRecording: Boolean,
    trackedIds: List<String>,
    friendLocations: Map<String, FriendLocation?>,
    savedFriends: List<SavedFriend>,
    bgTrackingOn: Boolean,
    user: FirebaseUser,
    onStartRecording: () -> Unit, onStopRecording: () -> Unit,
    onShowFriendDialog: () -> Unit, onRemoveFriend: (String) -> Unit,
    onOpenChat: () -> Unit,
    onStartBgTracking: () -> Unit, onStopBgTracking: () -> Unit,
    onRemoveSavedFriend: (String) -> Unit,
    onToggleTrack: (String) -> Unit,
    onOpenMyFriends: () -> Unit,
    onViewProfile: (SavedFriend) -> Unit,
    onOpenChatWith: (SavedFriend) -> Unit,
    onSignOut: () -> Unit
) {
    val ctx = LocalContext.current
    val (accLabel, accColor) = myLocation?.let { accuracyLabel(it.accuracy) } ?: ("—" to TextMuted)
    val liveCount = trackedIds.count { id -> friendLocations[id]?.isRecent == true }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 48.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(Brush.linearGradient(listOf(SkyPrimary, SkyDeep))), Alignment.Center) {
                Text((user.displayName?.firstOrNull() ?: "U").toString().uppercase(), color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(user.displayName ?: "User", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = TextPrimary)
                Text(user.email ?: "", fontSize = 11.sp, color = TextMuted)
            }
            Surface(shape = RoundedCornerShape(20.dp), color = if (isConnected) GreenOnline.copy(alpha = 0.10f) else TextMuted.copy(alpha = 0.08f)) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (isConnected) GreenOnline else TextMuted))
                    Spacer(Modifier.width(6.dp))
                    Text(if (isConnected) "Live" else "Offline", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isConnected) GreenOnline else TextMuted)
                }
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onSignOut, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                Text("Sign out", fontSize = 11.sp, color = TextMuted)
            }
        }

        Spacer(Modifier.height(18.dp))

        Button(
            onClick  = { showFriendDialog_stub(onShowFriendDialog, trackedIds.size) },
            enabled  = trackedIds.size < MAX_FRIENDS,
            shape    = RoundedCornerShape(14.dp),
            colors   = ButtonDefaults.buttonColors(containerColor = SkyPrimary, disabledContainerColor = SheetBorder),
            modifier = Modifier.fillMaxWidth().height(52.dp),
            elevation = ButtonDefaults.buttonElevation(0.dp)
        ) {
            Text(
                if (trackedIds.size < MAX_FRIENDS) "＋ Add a Friend" else "Maximum 4 friends reached",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp,
                color = if (trackedIds.size < MAX_FRIENDS) Color.White else TextMuted
            )
        }

        Spacer(Modifier.height(14.dp))

        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 3.dp, border = androidx.compose.foundation.BorderStroke(1.dp, SheetBorder)) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(SkyPrimary))
                    Spacer(Modifier.width(8.dp))
                    Text("YOUR TRACK ID", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp, color = TextMuted)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (trackIdReady) myTrackId else "Loading…", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = SkyPrimary, fontFamily = FontFamily.Monospace, letterSpacing = 1.5.sp, modifier = Modifier.weight(1f))
                    var copied by remember { mutableStateOf(false) }
                    if (trackIdReady) {
                        Button(onClick = {
                            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Track ID", myTrackId)); copied = true
                        }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = if (copied) GreenOnline else SkyPrimary), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp), modifier = Modifier.height(38.dp)) {
                            Text(if (copied) "✓ Copied" else "Copy", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text("This ID never changes — share it once with friends", fontSize = 12.sp, color = TextMuted)
            }
        }

        Spacer(Modifier.height(16.dp))

        // ── FIX: removed onClick from Surface, use Modifier.clickable instead ──
        Surface(
            modifier = Modifier.fillMaxWidth().clickable { onOpenMyFriends() },
            shape    = RoundedCornerShape(20.dp),
            color    = Color.White,
            shadowElevation = 3.dp,
            border   = androidx.compose.foundation.BorderStroke(1.dp, SkyPrimary.copy(alpha = 0.25f))
        ) {
            Row(
                Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.width(if (savedFriends.isNotEmpty()) (minOf(savedFriends.size, 3) * 26 + 14).dp else 40.dp).height(40.dp)) {
                    if (savedFriends.isEmpty()) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(SkyPale), Alignment.Center) {
                            Text("👥", fontSize = 18.sp)
                        }
                    } else {
                        savedFriends.take(3).forEachIndexed { i, sf ->
                            Box(
                                Modifier.size(40.dp).offset(x = (i * 26).dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(FriendColors[i % FriendColors.size], FriendColors[i % FriendColors.size].copy(alpha = 0.7f))))
                                    .border(2.dp, Color.White, CircleShape),
                                Alignment.Center
                            ) {
                                Text(sf.displayName.firstOrNull()?.toString()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("My Friends", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = TextPrimary)
                    Text(
                        buildString {
                            append("${savedFriends.size} contact${if (savedFriends.size != 1) "s" else ""}")
                            if (liveCount > 0) append("  •  $liveCount live now 🟢")
                        },
                        fontSize = 12.sp, color = TextMuted
                    )
                }
                Icon(Icons.Default.ArrowBack, null, tint = TextMuted, modifier = Modifier.size(18.dp).rotate(180f))
            }
        }

        Spacer(Modifier.height(20.dp))

        if (savedFriends.isNotEmpty()) {
            SavedFriendsSection(
                savedFriends        = savedFriends,
                trackedIds          = trackedIds,
                onToggleTrack       = onToggleTrack,
                onRemoveSavedFriend = onRemoveSavedFriend,
                onViewProfile       = onViewProfile,
                onOpenChatWith      = onOpenChatWith
            )
            Spacer(Modifier.height(20.dp))
        }

        SectionLabel("BACKGROUND TRACKING")
        Spacer(Modifier.height(10.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 3.dp,
            border = androidx.compose.foundation.BorderStroke(1.dp, if (bgTrackingOn) GreenOnline.copy(alpha = 0.4f) else SheetBorder)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (bgTrackingOn) "Sharing in background" else "Background sharing off", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = if (bgTrackingOn) GreenOnline else TextPrimary)
                    Spacer(Modifier.height(3.dp))
                    Text(if (bgTrackingOn) "Friends can see you when app is closed" else "Enable to keep sharing when app is closed", fontSize = 12.sp, color = TextMuted, lineHeight = 17.sp)
                }
                Spacer(Modifier.width(12.dp))
                Switch(checked = bgTrackingOn, onCheckedChange = { if (it) onStartBgTracking() else onStopBgTracking() }, colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = GreenOnline))
            }
        }

        Spacer(Modifier.height(20.dp))

        SectionLabel("MY LOCATION")
        Spacer(Modifier.height(10.dp))
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 3.dp, border = androidx.compose.foundation.BorderStroke(1.dp, SheetBorder)) {
            Column(Modifier.padding(16.dp)) {
                val addressText = when { myLocation == null -> "Acquiring GPS fix…"; !gpsAvailable -> "${myLocation!!.address} (last known)"; else -> myLocation!!.address }
                Text(addressText, fontSize = 14.sp, color = TextPrimary, lineHeight = 21.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniStat("LAT", myLocation?.point?.latitude()?.let { "%.5f°".format(it) } ?: "—", Modifier.weight(1f))
                    MiniStat("LNG", myLocation?.point?.longitude()?.let { "%.5f°".format(it) } ?: "—", Modifier.weight(1f))
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MiniStat("SPEED", myLocation?.speedKmh?.let { if (it < 0.5f) "0.0 km/h" else "%.1f km/h".format(it) } ?: "—", Modifier.weight(1f))
                    Surface(Modifier.weight(1f), shape = RoundedCornerShape(12.dp), color = SheetBg) {
                        Column(Modifier.padding(12.dp)) {
                            Text("ACCURACY", fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = TextMuted)
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(myLocation?.accuracy?.let { "±${it.toInt()}m" } ?: "—", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = if (gpsAvailable) accColor else TextMuted, fontFamily = FontFamily.Monospace)
                                Spacer(Modifier.width(6.dp))
                                Text(if (gpsAvailable) accLabel else "Stale", fontSize = 11.sp, color = if (gpsAvailable) accColor else TextMuted, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(20.dp))

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("FRIENDS TRACKING")
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(MAX_FRIENDS) { slot ->
                    Box(Modifier.size(11.dp).clip(CircleShape).background(if (slot < trackedIds.size) FriendColors[slot].copy(alpha = 0.9f) else SheetBorder))
                }
                Spacer(Modifier.width(6.dp))
                Text("${trackedIds.size}/$MAX_FRIENDS", fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, color = TextMuted)
            }
        }
        Spacer(Modifier.height(10.dp))

        trackedIds.forEachIndexed { slot, id ->
            val savedName = savedFriends.find { it.trackId == id }?.displayName
            FriendCard(id = id, slot = slot, color = FriendColors[slot % FriendColors.size], label = FriendLabels[slot % FriendLabels.size], loc = friendLocations[id], savedName = savedName, onRemove = { onRemoveFriend(id) })
            Spacer(Modifier.height(10.dp))
        }

        Button(onClick = onOpenChat, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = SkyDeep), modifier = Modifier.fillMaxWidth().height(52.dp), elevation = ButtonDefaults.buttonElevation(0.dp)) {
            Text("💬", fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
            Text("Open Messages", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = Color.White)
        }

        Spacer(Modifier.height(20.dp))

        SectionLabel("PATH RECORDING")
        Spacer(Modifier.height(10.dp))
        if (isRecording) {
            Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = RedRecord.copy(alpha = 0.07f)) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(RedRecord))
                    Spacer(Modifier.width(10.dp))
                    Text("Recording  •  ${pathPoints.size} points captured", fontSize = 13.sp, color = RedRecord, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Button(onClick = if (isRecording) onStopRecording else onStartRecording, shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.buttonColors(containerColor = if (isRecording) RedRecord else SkyDeep), modifier = Modifier.fillMaxWidth().height(54.dp), elevation = ButtonDefaults.buttonElevation(0.dp)) {
            Text(if (isRecording) "⏹  Stop Recording" else "⏺  Start Recording Path", fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = Color.White)
        }
        if (!isRecording && pathPoints.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text("${pathPoints.size} points saved to database", fontSize = 12.sp, color = TextMuted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

private fun showFriendDialog_stub(onShow: () -> Unit, currentSize: Int) { if (currentSize < MAX_FRIENDS) onShow() }

// ═══════════════════════════════════════════════════════════════════════════════
// ─── My Friends Screen ────────────────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════════════════
@Composable
fun MyFriendsScreen(
    savedFriends:        List<SavedFriend>,
    trackedIds:          List<String>,
    friendLocations:     Map<String, FriendLocation?>,
    onBack:              () -> Unit,
    onViewProfile:       (SavedFriend) -> Unit,
    onToggleTrack:       (String) -> Unit,
    onRemoveSavedFriend: (String) -> Unit,
    onOpenChat:          (SavedFriend) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(0) }

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
    val liveCount = trackedIds.count { id -> friendLocations[id]?.isRecent == true }

    Box(Modifier.fillMaxSize().background(SheetBg)) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xFF0C4A6E), SkyPrimary, Color(0xFF29B6F6))))
                    .padding(top = 52.dp, bottom = 28.dp, start = 20.dp, end = 20.dp)
            ) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable { onBack() }
                        .align(Alignment.TopStart),
                    Alignment.Center
                ) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("My Friends", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Spacer(Modifier.height(5.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        StatChip("${savedFriends.size}", "contacts")
                        StatChip("${trackedIds.size}", "tracking")
                        if (liveCount > 0) StatChip("$liveCount", "live 🟢")
                    }
                }
            }

            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 16.dp)
            ) {
                OutlinedTextField(
                    value         = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder   = { Text("Search name, email, or ID…", color = TextMuted) },
                    leadingIcon   = { Text("🔍", fontSize = 16.sp, modifier = Modifier.padding(start = 8.dp)) },
                    trailingIcon  = if (searchQuery.isNotBlank()) {
                        { TextButton(onClick = { searchQuery = "" }) { Text("✕", color = TextMuted) } }
                    } else null,
                    shape  = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor      = SkyPrimary,
                        unfocusedBorderColor    = SheetBorder,
                        focusedContainerColor   = Color.White,
                        unfocusedContainerColor = Color.White,
                        focusedTextColor        = TextPrimary,
                        unfocusedTextColor      = TextPrimary
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(Modifier.height(14.dp))

                Surface(shape = RoundedCornerShape(14.dp), color = Color.White, shadowElevation = 2.dp) {
                    Row(Modifier.fillMaxWidth()) {
                        listOf("All Friends" to "👥", "Live Now" to "🟢").forEachIndexed { idx, (label, emoji) ->
                            val selected = selectedTab == idx
                            Box(
                                Modifier.weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(if (selected) SkyPrimary else Color.Transparent)
                                    .clickable { selectedTab = idx }
                                    .padding(vertical = 13.dp),
                                Alignment.Center
                            ) {
                                Text("$emoji  $label", fontSize = 13.sp, fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium, color = if (selected) Color.White else TextMuted)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))

                if (filteredFriends.isEmpty() && (selectedTab != 0 || untitledTracked.isEmpty())) {
                    Box(Modifier.fillMaxWidth().padding(vertical = 48.dp), Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("👥", fontSize = 48.sp)
                            Spacer(Modifier.height(14.dp))
                            Text(
                                when {
                                    searchQuery.isNotBlank() -> "No friends match your search"
                                    selectedTab == 1         -> "No one is live right now"
                                    else                     -> "No saved friends yet"
                                },
                                fontSize = 15.sp, color = TextMuted, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center
                            )
                            if (selectedTab == 0 && searchQuery.isBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text("Track a friend and tap 'Add Friend' to save them here", fontSize = 12.sp, color = TextMuted.copy(alpha = 0.7f), textAlign = TextAlign.Center)
                            }
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
                        if (filteredFriends.isNotEmpty()) Spacer(Modifier.height(6.dp))
                        SectionLabel("TRACKING WITHOUT PROFILE")
                        Spacer(Modifier.height(10.dp))
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
            }
        }
    }
}

@Composable
private fun StatChip(value: String, label: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = 0.18f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(value, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
        }
    }
}

// ─── Friend Profile Card ──────────────────────────────────────────────────────
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
    val hasProfile   = sf.displayName.isNotBlank()
    val isLive       = liveLocation?.isRecent == true

    // ── FIX: removed onClick from Surface, use Modifier.clickable instead ──
    Surface(
        modifier        = Modifier.fillMaxWidth().then(
            if (hasProfile) Modifier.clickable { onViewProfile() } else Modifier
        ),
        shape           = RoundedCornerShape(20.dp),
        color           = Color.White,
        shadowElevation = 3.dp,
        border          = androidx.compose.foundation.BorderStroke(
            1.5.dp,
            when { isLive -> SkyPrimary.copy(alpha = 0.35f); isTracking -> Color(0xFFF59E0B).copy(alpha = 0.35f); else -> SheetBorder }
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(58.dp).clip(CircleShape)
                        .background(
                            if (isTracking)
                                Brush.linearGradient(listOf(SkyPrimary, SkyDeep))
                            else
                                Brush.linearGradient(listOf(Color(0xFFCBD5E1), Color(0xFF94A3B8)))
                        )
                        .border(2.5.dp, if (isLive) GreenOnline.copy(alpha = 0.6f) else Color.Transparent, CircleShape),
                    Alignment.Center
                ) {
                    Text(
                        sf.displayName.firstOrNull()?.toString()?.uppercase()
                            ?: sf.trackId.firstOrNull()?.toString() ?: "?",
                        fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White
                    )
                }

                Spacer(Modifier.width(14.dp))

                Column(Modifier.weight(1f)) {
                    if (hasProfile) {
                        Text(sf.displayName, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary)
                        Text(sf.email.ifBlank { "No email" }, fontSize = 11.sp, color = TextMuted)
                        Spacer(Modifier.height(2.dp))
                    }
                    Text(sf.trackId, fontSize = if (hasProfile) 10.sp else 14.sp, color = SkyPrimary.copy(alpha = if (hasProfile) 0.8f else 1f), fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                    Spacer(Modifier.height(5.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(when { isLive -> GreenOnline; isTracking -> Color(0xFFF59E0B); else -> TextMuted }))
                        Spacer(Modifier.width(5.dp))
                        Text(
                            when { isLive -> "Live now"; isTracking -> "Tracking"; else -> "Not tracked" },
                            fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            color = when { isLive -> GreenOnline; isTracking -> Color(0xFFF59E0B); else -> TextMuted }
                        )
                        if (isLive && liveLocation != null) {
                            Text("  •  ", fontSize = 10.sp, color = TextMuted)
                            Text(if (liveLocation.speedKmh > 0.5f) "%.0f km/h".format(liveLocation.speedKmh) else "Stationary", fontSize = 11.sp, color = TextMuted)
                        }
                    }
                }

                Spacer(Modifier.width(8.dp))

                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = onToggleTrack,
                        shape   = RoundedCornerShape(10.dp),
                        colors  = ButtonDefaults.buttonColors(containerColor = if (isTracking) RedRecord else SkyPrimary),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(if (isTracking) "Untrack" else "Track", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    }
                    if (hasProfile) {
                        TextButton(onClick = onViewProfile, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), modifier = Modifier.height(22.dp)) {
                            Text("View Profile →", fontSize = 10.sp, color = SkyPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (hasProfile) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(SheetBorder))
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onOpenChat,
                    shape   = RoundedCornerShape(10.dp),
                    colors  = ButtonDefaults.buttonColors(containerColor = SkyDeep),
                    modifier = Modifier.fillMaxWidth().height(38.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Text("💬", fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
                    Text("Message ${sf.displayName.split(" ").first()}", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                }
            }
        }
    }
}

// ─── User Profile Screen ──────────────────────────────────────────────────────
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

    Box(Modifier.fillMaxSize().background(SheetBg)) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {

            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xFF0C4A6E), SkyPrimary, SkyLight)))
                    .padding(bottom = 32.dp)
            ) {
                Box(
                    Modifier.padding(top = 52.dp, start = 16.dp).size(40.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                        .clickable { onBack() },
                    Alignment.Center
                ) {
                    Icon(Icons.Default.ArrowBack, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }

                Column(Modifier.align(Alignment.BottomCenter).padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(96.dp).clip(CircleShape)
                            .background(Color.White.copy(alpha = 0.20f))
                            .border(3.dp, Color.White.copy(alpha = 0.55f), CircleShape),
                        Alignment.Center
                    ) {
                        Text(profile.displayName.firstOrNull()?.toString()?.uppercase() ?: "?", fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        if (isLive) {
                            Box(Modifier.size(20.dp).align(Alignment.BottomEnd).offset((-4).dp, (-4).dp).clip(CircleShape).background(GreenOnline).border(2.dp, Color.White, CircleShape))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Text(profile.displayName.ifBlank { "Unknown" }, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                    Spacer(Modifier.height(6.dp))
                    Surface(shape = RoundedCornerShape(20.dp), color = Color.White.copy(alpha = 0.16f)) {
                        Text(profile.trackId, modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.92f), fontFamily = FontFamily.Monospace, letterSpacing = 1.2.sp)
                    }
                }
            }

            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).offset(y = (-22).dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick  = { onToggleTrack(profile.trackId) },
                    enabled  = canTrack,
                    shape    = RoundedCornerShape(14.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = if (isTracking) RedRecord else SkyPrimary, disabledContainerColor = SheetBorder),
                    modifier = Modifier.weight(1f).height(50.dp),
                    elevation = ButtonDefaults.buttonElevation(6.dp)
                ) {
                    Text(if (isTracking) "⏹  Untrack" else "📍  Track", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = if (!canTrack && !isTracking) TextMuted else Color.White)
                }
                Button(
                    onClick  = { if (isAlreadySaved) onRemoveFriend(profile.trackId) else onAddFriend(profile) },
                    shape    = RoundedCornerShape(14.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = if (isAlreadySaved) SheetBorder else GreenOnline),
                    modifier = Modifier.weight(1f).height(50.dp),
                    elevation = ButtonDefaults.buttonElevation(6.dp)
                ) {
                    Text(if (isAlreadySaved) "✓  In Contacts" else "＋  Add Friend", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = if (isAlreadySaved) TextMuted else Color.White)
                }
            }

            Button(
                onClick  = { onOpenChat(profile) },
                shape    = RoundedCornerShape(14.dp),
                colors   = ButtonDefaults.buttonColors(containerColor = SkyDeep),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).offset(y = (-10).dp).height(50.dp),
                elevation = ButtonDefaults.buttonElevation(6.dp)
            ) {
                Text("💬", fontSize = 18.sp, modifier = Modifier.padding(end = 10.dp))
                Text("Message ${profile.displayName.split(" ").first().ifBlank { "Friend" }}", fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = Color.White)
            }

            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp).offset(y = (-10).dp),
                shape = RoundedCornerShape(24.dp), color = Color.White, shadowElevation = 4.dp
            ) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(when { isLive -> GreenOnline.copy(alpha = 0.12f); isTracking -> Color(0xFFF59E0B).copy(alpha = 0.12f); else -> TextMuted.copy(alpha = 0.08f) }), Alignment.Center) {
                            Text(when { isLive -> "🟢"; isTracking -> "🟡"; else -> "⚫" }, fontSize = 16.sp)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(when { isLive -> "Live Now"; isTracking -> "Being Tracked"; else -> "Not Tracked" }, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = when { isLive -> GreenOnline; isTracking -> Color(0xFFF59E0B); else -> TextMuted })
                            Text(when { isLive -> "Location updating in real-time"; isTracking -> "Last known location available"; else -> "Add to tracking to see location" }, fontSize = 11.sp, color = TextMuted)
                        }
                    }

                    if (profile.email.isNotBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Box(Modifier.fillMaxWidth().height(1.dp).background(SheetBorder))
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(38.dp).clip(CircleShape).background(SkyPale), Alignment.Center) { Text("✉", fontSize = 16.sp) }
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text("EMAIL", fontSize = 10.sp, color = TextMuted, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                                Text(profile.email, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Box(Modifier.fillMaxWidth().height(1.dp).background(SheetBorder))
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(CircleShape).background(SkyPale), Alignment.Center) { Text("🔑", fontSize = 16.sp) }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("TRACK ID", fontSize = 10.sp, color = TextMuted, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp)
                            Text(profile.trackId, fontSize = 13.sp, color = SkyPrimary, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, letterSpacing = 1.sp)
                        }
                    }
                }
            }

            if (isTracking && liveLocation != null) {
                Surface(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(24.dp), color = Color.White, shadowElevation = 4.dp,
                    border = androidx.compose.foundation.BorderStroke(1.5.dp, if (isLive) GreenOnline.copy(alpha = 0.3f) else SheetBorder)
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SectionLabel("LIVE LOCATION")
                            Spacer(Modifier.weight(1f))
                            if (isLive) {
                                Surface(shape = RoundedCornerShape(20.dp), color = GreenOnline.copy(alpha = 0.10f)) {
                                    Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.size(6.dp).clip(CircleShape).background(GreenOnline))
                                        Spacer(Modifier.width(5.dp))
                                        Text("Live", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = GreenOnline)
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(14.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MiniStat("LAT", "%.5f°".format(liveLocation.point.latitude()), Modifier.weight(1f))
                            MiniStat("LNG", "%.5f°".format(liveLocation.point.longitude()), Modifier.weight(1f))
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            MiniStat("SPEED", if (liveLocation.speedKmh < 0.5f) "0.0 km/h" else "%.1f km/h".format(liveLocation.speedKmh), Modifier.weight(1f))
                            MiniStat("ACCURACY", "±${liveLocation.accuracy.toInt()} m", Modifier.weight(1f))
                        }
                    }
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─── Saved Friends Section ────────────────────────────────────────────────────
@Composable
fun SavedFriendsSection(
    savedFriends:        List<SavedFriend>,
    trackedIds:          List<String>,
    onToggleTrack:       (String) -> Unit,
    onRemoveSavedFriend: (String) -> Unit,
    onViewProfile:       (SavedFriend) -> Unit,
    onOpenChatWith:      (SavedFriend) -> Unit
) {
    SectionLabel("SAVED FRIENDS")
    Spacer(Modifier.height(10.dp))

    savedFriends.forEach { sf ->
        val isTracking = sf.trackId in trackedIds
        val slotsLeft  = MAX_FRIENDS - trackedIds.size
        val canTrack   = !isTracking && slotsLeft <= 0

        // ── FIX: removed onClick from Surface, use Modifier.clickable instead ──
        Surface(
            modifier        = Modifier.fillMaxWidth().padding(bottom = 10.dp).clickable { onViewProfile(sf) },
            shape           = RoundedCornerShape(20.dp),
            color           = Color.White,
            shadowElevation = 3.dp,
            border          = androidx.compose.foundation.BorderStroke(1.5.dp, if (isTracking) SkyPrimary.copy(alpha = 0.40f) else SheetBorder)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(46.dp).clip(CircleShape)
                            .background(Brush.linearGradient(if (isTracking) listOf(SkyPrimary, SkyDeep) else listOf(SheetBorder, Color(0xFFCBD5E1)))),
                        Alignment.Center
                    ) {
                        Text(sf.displayName.firstOrNull()?.toString()?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(sf.displayName.ifBlank { "Unknown" }, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp, color = TextPrimary)
                        Text(sf.email.ifBlank { "No email" }, fontSize = 11.sp, color = TextMuted)
                        Spacer(Modifier.height(3.dp))
                        Text(sf.trackId, fontSize = 11.sp, color = SkyPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp)
                        if (isTracking) {
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(GreenOnline))
                                Spacer(Modifier.width(5.dp))
                                Text("Live tracking", fontSize = 10.sp, color = GreenOnline, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick        = { onToggleTrack(sf.trackId) },
                            enabled        = isTracking || !canTrack,
                            shape          = RoundedCornerShape(10.dp),
                            colors         = ButtonDefaults.buttonColors(containerColor = if (isTracking) RedRecord else SkyPrimary, disabledContainerColor = SheetBorder),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier       = Modifier.height(36.dp)
                        ) {
                            Text(when { isTracking -> "Untrack"; canTrack -> "Full"; else -> "Track" }, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = if (canTrack && !isTracking) TextMuted else Color.White)
                        }
                        TextButton(onClick = { onViewProfile(sf) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp), modifier = Modifier.height(22.dp)) {
                            Text("Profile →", fontSize = 10.sp, color = SkyPrimary, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(SheetBorder))
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { onOpenChatWith(sf) },
                    shape   = RoundedCornerShape(10.dp),
                    colors  = ButtonDefaults.buttonColors(containerColor = SkyDeep),
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Text("💬", fontSize = 13.sp, modifier = Modifier.padding(end = 6.dp))
                    Text("Message", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                }
            }
        }
    }
}

// ─── Friend Card ──────────────────────────────────────────────────────────────
@Composable
fun FriendCard(id: String, slot: Int, color: Color, label: String, loc: FriendLocation?, savedName: String? = null, onRemove: () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 3.dp,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, if (loc?.isRecent == true) color.copy(alpha = 0.40f) else SheetBorder)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(color.copy(alpha = 0.12f)), Alignment.Center) {
                    Text(savedName?.firstOrNull()?.toString()?.uppercase() ?: "${slot + 1}", fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = color)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    if (!savedName.isNullOrBlank()) { Text(savedName, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary); Spacer(Modifier.height(1.dp)) }
                    Text(id, fontSize = if (savedName.isNullOrBlank()) 14.sp else 11.sp, fontWeight = FontWeight.ExtraBold, color = if (savedName.isNullOrBlank()) TextPrimary else TextMuted, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(when { loc == null -> TextMuted; loc.isRecent -> GreenOnline; else -> Color(0xFFF59E0B) }))
                        Spacer(Modifier.width(6.dp))
                        Text(when { loc == null -> "Locating…"; loc.isRecent -> "Live  •  $label"; else -> "Last known  •  $label" }, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = when { loc == null -> TextMuted; loc.isRecent -> GreenOnline; else -> Color(0xFFF59E0B) })
                    }
                }
                TextButton(onClick = onRemove, contentPadding = PaddingValues(4.dp), modifier = Modifier.size(36.dp)) { Text("✕", fontSize = 15.sp, color = TextMuted, fontWeight = FontWeight.Bold) }
            }
            if (loc == null) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(15.dp), color = color, strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Fetching location…", fontSize = 12.sp, color = TextMuted) }
                return@Column
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("LAT", "%.5f°".format(loc.point.latitude()), Modifier.weight(1f))
                MiniStat("LNG", "%.5f°".format(loc.point.longitude()), Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MiniStat("SPEED", if (loc.speedKmh < 0.5f) "0.0 km/h" else "%.1f km/h".format(loc.speedKmh), Modifier.weight(1f))
                MiniStat("ACCURACY", "±${loc.accuracy.toInt()} m", Modifier.weight(1f))
            }
        }
    }
}

// ─── Reusable UI ──────────────────────────────────────────────────────────────
@Composable
fun SectionLabel(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.5.dp).height(16.dp).clip(RoundedCornerShape(2.dp)).background(SkyPrimary))
        Spacer(Modifier.width(9.dp))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp, color = TextSecondary)
    }
}

@Composable
fun MiniStat(label: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = SheetBg) {
        Column(Modifier.padding(12.dp)) {
            Text(label, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.sp, color = TextMuted)
            Spacer(Modifier.height(4.dp))
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = TextPrimary, fontFamily = FontFamily.Monospace)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ─── Friend Dialog ────────────────────────────────────────────────────────────
// ═══════════════════════════════════════════════════════════════════════════════
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
    var idInput      by remember { mutableStateOf("") }
    var lookupResult by remember { mutableStateOf<SavedFriend?>(null) }
    var lookupState  by remember { mutableStateOf("idle") }
    var lookupRequestId by remember { mutableIntStateOf(0) }

    val scope  = rememberCoroutineScope()
    val kb     = LocalSoftwareKeyboardController.current

    val idNorm         = idInput.trim().uppercase()
    val alreadyTracked = idNorm in currentIds
    val alreadySaved   = savedFriends.any { it.trackId == idNorm }
    val slotsLeft      = MAX_FRIENDS - currentIds.size
    val canTrackById   = idNorm.isNotBlank() &&
            !alreadyTracked &&
            slotsLeft > 0 &&
            lookupState == "found" &&
            lookupResult != null

    fun doIdLookup() {
        if (idNorm.isBlank()) return
        val requestedId = idNorm
        lookupRequestId += 1
        val requestId = lookupRequestId
        lookupState = "loading"; lookupResult = null
        scope.launch(Dispatchers.IO) {
            val result = httpGet("/api/user/by-trackid/$requestedId")
            withContext(Dispatchers.Main) {
                if (requestId != lookupRequestId) return@withContext
                if (idInput.trim().uppercase() != requestedId) return@withContext
                if (result != null && result.has("trackId")) {
                    val resolvedTrackId = result.getString("trackId")
                    val resolvedName = result.optString("displayName", "").ifBlank { resolvedTrackId }
                    lookupResult = SavedFriend(
                        trackId = resolvedTrackId,
                        displayName = resolvedName,
                        email = result.optString("email", "")
                    )
                    lookupState = "found"
                } else lookupState = "notfound"
            }
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = SheetCard, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(24.dp)) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(SkyPrimary.copy(alpha = 0.12f)), Alignment.Center) { Text("👥", fontSize = 18.sp) }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text("Add Friend by Track ID", fontWeight = FontWeight.Bold, fontSize = 18.sp, color = TextPrimary)
                        Text("Live tracking is paused while adding", fontSize = 12.sp, color = TextMuted)
                    }
                }

                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value         = idInput,
                        onValueChange = {
                            idInput = it.uppercase()
                            lookupRequestId += 1
                            lookupState = "idle"
                            lookupResult = null
                        },
                        placeholder   = { Text("TRK-XXXXXXXX", color = TextMuted) },
                        isError       = alreadyTracked,
                        supportingText = if (alreadyTracked) { { Text("Already tracking this ID", color = RedRecord, fontSize = 11.sp) } } else null,
                        shape   = RoundedCornerShape(12.dp),
                        colors  = OutlinedTextFieldDefaults.colors(focusedBorderColor = SkyPrimary, unfocusedBorderColor = SheetBorder, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary, cursorColor = SkyPrimary, focusedContainerColor = SheetBg, unfocusedContainerColor = SheetBg),
                        singleLine      = true,
                        modifier        = Modifier.weight(1f),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { kb?.hide(); doIdLookup() })
                    )
                    Button(onClick = { kb?.hide(); doIdLookup() }, enabled = idNorm.isNotBlank() && lookupState != "loading", shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = SkyPrimary), modifier = Modifier.height(52.dp), contentPadding = PaddingValues(horizontal = 14.dp)) {
                        if (lookupState == "loading") CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                        else Text("🔍", fontSize = 16.sp)
                    }
                }

                if (lookupState == "found" && lookupResult != null) {
                    Spacer(Modifier.height(12.dp))
                    SearchResultCard(
                        sf = lookupResult!!,
                        alreadySaved = alreadySaved,
                        alreadyTracked = alreadyTracked,
                        onViewProfile = { onViewProfile(lookupResult!!) },
                        onAddAndTrack = { onAddAndTrack(lookupResult!!) }
                    )
                    if (alreadyTracked) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "You're already tracking this ID.",
                            fontSize = 11.sp,
                            color = RedRecord,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (lookupState == "notfound") {
                    Spacer(Modifier.height(10.dp))
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color(0xFFFFFBEB)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("⚠", fontSize = 14.sp); Spacer(Modifier.width(8.dp))
                            Text("No account found for this Track ID", fontSize = 12.sp, color = Color(0xFF92400E), fontWeight = FontWeight.SemiBold)
                        }
                    }
                }

                Spacer(Modifier.height(18.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(48.dp), shape = RoundedCornerShape(12.dp), border = androidx.compose.foundation.BorderStroke(1.dp, SheetBorder), colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)) { Text("Cancel") }
                    Button(
                        onClick = { lookupResult?.let { onTrackNow(it) } },
                        modifier = Modifier.weight(1f).height(48.dp),
                        shape = RoundedCornerShape(12.dp),
                        enabled = canTrackById,
                        colors = ButtonDefaults.buttonColors(containerColor = SkyPrimary),
                        elevation = ButtonDefaults.buttonElevation(0.dp)
                    ) {
                        Text(if (lookupState == "found") "Add Friend" else "Search First", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ─── Search Result Card ───────────────────────────────────────────────────────
@Composable
fun SearchResultCard(
    sf:             SavedFriend,
    alreadySaved:   Boolean,
    alreadyTracked: Boolean,
    onViewProfile:  () -> Unit,
    onAddAndTrack:  () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth()
            .border(1.5.dp, if (alreadyTracked) SkyPrimary.copy(alpha = 0.40f) else SheetBorder, RoundedCornerShape(20.dp)),
        shape          = RoundedCornerShape(20.dp),
        color          = Color.White,
        tonalElevation = 3.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Brush.linearGradient(listOf(SkyPrimary, SkyDeep))), Alignment.Center) {
                    Text(sf.displayName.firstOrNull()?.toString()?.uppercase() ?: "?", fontWeight = FontWeight.ExtraBold, color = Color.White, fontSize = 20.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(sf.displayName.ifBlank { "Unknown" }, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, color = TextPrimary)
                    Text(sf.email.ifBlank { "No email" }, fontSize = 11.sp, color = TextMuted)
                    Text(sf.trackId, fontSize = 10.sp, color = SkyPrimary, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                }
                Box(Modifier.size(32.dp).clip(CircleShape).background(SkyPale), Alignment.Center) {
                    Text("→", fontSize = 14.sp, color = SkyPrimary, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(SheetBorder))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick  = onViewProfile,
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape    = RoundedCornerShape(10.dp),
                    border   = androidx.compose.foundation.BorderStroke(1.5.dp, SkyPrimary),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = SkyPrimary)
                ) { Text("View Profile", fontSize = 12.sp, fontWeight = FontWeight.ExtraBold) }

                Button(
                    onClick  = onAddAndTrack,
                    modifier = Modifier.weight(1f).height(38.dp),
                    shape    = RoundedCornerShape(10.dp),
                    colors   = ButtonDefaults.buttonColors(containerColor = if (alreadySaved || alreadyTracked) SheetBorder else GreenOnline),
                    elevation = ButtonDefaults.buttonElevation(0.dp)
                ) {
                    Text(
                        when {
                            alreadyTracked -> "✓ Tracking"
                            alreadySaved -> "✓ Added"
                            else -> "＋ Add Friend"
                        },
                        fontSize = 12.sp, fontWeight = FontWeight.ExtraBold,
                        color = if (alreadySaved || alreadyTracked) TextMuted else Color.White
                    )
                }
            }
        }
    }
}

// ─── Permission Screen ────────────────────────────────────────────────────────
@Composable
fun PermScreen(onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0C4A6E), SkyPrimary, SkyLight))), Alignment.Center) {
        Column(Modifier.padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(80.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)), Alignment.Center) { Text("📍", fontSize = 40.sp) }
            Spacer(Modifier.height(24.dp))
            Text("Location Access Needed", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Text("Grant location access to see your position on the map and share with friends.", fontSize = 14.sp, color = SkyPale, textAlign = TextAlign.Center, lineHeight = 22.sp)
            Spacer(Modifier.height(36.dp))
            Button(onClick = onRequest, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth().height(54.dp)) {
                Text("Grant Permission", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = SkyDeep)
            }
        }
    }
}

// ─── GPS ──────────────────────────────────────────────────────────────────────
@SuppressLint("MissingPermission")
fun startGps(fused: FusedLocationProviderClient, onCb: (LocationCallback) -> Unit, onAvailability: (Boolean) -> Unit, onLoc: (Point, Float, Float) -> Unit) {
    val req = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500L).setMinUpdateIntervalMillis(300L).setWaitForAccurateLocation(false).setMaxUpdateDelayMillis(1_000L).build()
    val cb = object : LocationCallback() {
        override fun onLocationAvailability(availability: LocationAvailability) {
            if (!availability.isLocationAvailable) GpsFilter.reset()
            onAvailability(availability.isLocationAvailable)
        }
        override fun onLocationResult(result: LocationResult) {
            val loc = result.locations.filter { it.accuracy <= ACC_ACCEPTABLE }.minByOrNull { it.accuracy } ?: result.locations.minByOrNull { it.accuracy } ?: return
            val nowMs = System.currentTimeMillis()
            val spdMs = if (loc.hasSpeed() && loc.speed >= 0) loc.speed else 0f
            val smoothed = GpsFilter.process(loc.latitude, loc.longitude, loc.accuracy, nowMs) ?: return
            onLoc(Point.fromLngLat(smoothed.second, smoothed.first), GpsFilter.smoothedAccuracy, spdMs)
        }
    }
    onCb(cb)
    fused.requestLocationUpdates(req, cb, Looper.getMainLooper())
}

// ─── Geocoder ─────────────────────────────────────────────────────────────────
fun geocode(ctx: Context, lat: Double, lon: Double): String = try {
    val list = Geocoder(ctx, Locale.getDefault()).getFromLocation(lat, lon, 1)
    if (!list.isNullOrEmpty()) {
        val a = list[0]
        listOfNotNull(a.subLocality ?: a.thoroughfare, a.locality ?: a.subAdminArea, a.adminArea, a.countryName).joinToString(", ")
    } else "Address not found"
} catch (_: Exception) { "Address unavailable" }
