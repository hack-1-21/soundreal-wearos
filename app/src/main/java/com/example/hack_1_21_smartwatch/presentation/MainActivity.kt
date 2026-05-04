package com.example.hack_1_21_smartwatch.presentation

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.location.Location
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.wear.compose.material.*
import com.example.hack_1_21_smartwatch.BuildConfig
import com.example.hack_1_21_smartwatch.presentation.theme.Hack121smartwatchTheme
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.math.log10
import kotlin.math.sqrt

private const val LOCATION_POLL_INTERVAL_MS = 20_000L
private const val STATIONARY_AUDIO_INTERVAL_MS = 5 * 60 * 1000L
private const val MOVEMENT_THRESHOLD_METERS = 5f
private const val AUDIO_SAMPLE_DURATION_MS = 2_000L
private const val DEVICE_PREFS_NAME = "device_auth"
private const val DEVICE_ID_KEY = "device_id"
private const val DEVICE_TOKEN_KEY = "device_token"

class MainActivity : ComponentActivity() {

    private var hasAudioPermission by mutableStateOf(false)
    private var hasLocationPermission by mutableStateOf(false)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            hasAudioPermission = isGranted
        }

    private val requestLocationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            hasLocationPermission = isGranted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        setTheme(android.R.style.Theme_DeviceDefault)

        hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        hasLocationPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        setContent {
            WearApp(
                hasAudioPermission = hasAudioPermission,
                hasLocationPermission = hasLocationPermission,
                onRequestPermission = {
                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onRequestLocationPermission = {
                    requestLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
            )
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
fun WearApp(
    hasAudioPermission: Boolean,
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit,
    onRequestLocationPermission: () -> Unit
) {
    var currentDb by remember { mutableStateOf(0.0) }
    var peakDb by remember { mutableStateOf(0.0) }
    var currentHz by remember { mutableStateOf(0.0) }
    var sendStatus by remember { mutableStateOf("Not sent") }
    var locationStatus by remember { mutableStateOf("GPS not started") }
    var audioStatus by remember { mutableStateOf("Mic not started") }
    var sendCount by remember { mutableStateOf(0) }
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    var deviceId by remember { mutableStateOf<String?>(null) }
    var pairingCode by remember { mutableStateOf<String?>(null) }
    var deviceToken by remember { mutableStateOf<String?>(null) }
    var linkStatus by remember { mutableStateOf("Checking link") }
    var linkRetryNonce by remember { mutableStateOf(0) }
    var isRequestingCode by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    LaunchedEffect(linkRetryNonce) {
        val prefs = context.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
        val savedDeviceToken = prefs.getString(DEVICE_TOKEN_KEY, null)
        val savedDeviceId = prefs.getString(DEVICE_ID_KEY, null)

        if (savedDeviceToken != null) {
            linkStatus = "Checking link"
            if (validateDeviceToken(savedDeviceToken)) {
                deviceToken = savedDeviceToken
                deviceId = savedDeviceId
                linkStatus = "Linked"
                return@LaunchedEffect
            }

            prefs.edit()
                .remove(DEVICE_TOKEN_KEY)
                .remove(DEVICE_ID_KEY)
                .apply()
            deviceToken = null
            deviceId = null
            pairingCode = null
            linkStatus = "Link removed"
        }

        linkStatus = "Not linked"
    }

    LaunchedEffect(deviceId, deviceToken) {
        val currentDeviceId = deviceId ?: return@LaunchedEffect
        if (deviceToken != null) return@LaunchedEffect

        val prefs = context.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
        while (deviceToken == null) {
            when (val result = pollDeviceLink(currentDeviceId)) {
                is PollLinkResult.Pending -> linkStatus = "Waiting for link"
                is PollLinkResult.Expired -> {
                    linkStatus = "Code expired"
                    pairingCode = null
                    prefs.edit().remove(DEVICE_ID_KEY).apply()
                    deviceId = null
                    return@LaunchedEffect
                }
                is PollLinkResult.Linked -> {
                    deviceToken = result.deviceToken
                    pairingCode = null
                    linkStatus = "Linked"
                    prefs.edit()
                        .putString(DEVICE_TOKEN_KEY, result.deviceToken)
                        .putString(DEVICE_ID_KEY, currentDeviceId)
                        .apply()
                    return@LaunchedEffect
                }
                is PollLinkResult.Failure -> linkStatus = result.message
            }
            delay(3_000L)
        }
    }

    LaunchedEffect(hasAudioPermission, hasLocationPermission, deviceToken) {
        val currentDeviceToken = deviceToken
        if (currentDeviceToken == null) {
            sendStatus = "Link required"
            return@LaunchedEffect
        }
        if (!hasAudioPermission) {
            audioStatus = "Mic permission needed"
            return@LaunchedEffect
        }
        if (!hasLocationPermission) {
            locationStatus = "GPS permission needed"
            return@LaunchedEffect
        }

        val fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var lastAudioLocation: Location? = null
        var lastAudioTimeMillis = 0L

        while (true) {
            val location = getCurrentLocation(fusedLocationClient, locationManager)

            if (location == null) {
                locationStatus = "No GPS fix"
                sendStatus = "Send paused"
                audioStatus = "Mic skipped"
                delay(LOCATION_POLL_INTERVAL_MS)
                continue
            }

            latitude = location.latitude
            longitude = location.longitude
            locationStatus = "GPS acquired"

            val now = System.currentTimeMillis()
            val previousLocation = lastAudioLocation
            val movedMeters = previousLocation?.distanceTo(location)
            val shouldMeasureAudio = previousLocation == null ||
                movedMeters == null ||
                movedMeters >= MOVEMENT_THRESHOLD_METERS ||
                now - lastAudioTimeMillis >= STATIONARY_AUDIO_INTERVAL_MS

            if (!shouldMeasureAudio) {
                val remainingSeconds = (
                    STATIONARY_AUDIO_INTERVAL_MS - (now - lastAudioTimeMillis)
                    ).coerceAtLeast(0L) / 1000L
                val distanceText = movedMeters?.let { "%.0fm".format(it) } ?: "--"
                audioStatus = "Mic skipped ${remainingSeconds}s"
                sendStatus = "Stationary $distanceText"
                delay(LOCATION_POLL_INTERVAL_MS)
                continue
            }

            audioStatus = "Mic sampling"
            val measurement = recordAudioSample(AUDIO_SAMPLE_DURATION_MS) { status ->
                audioStatus = status
            }

            if (measurement == null) {
                sendStatus = "Audio failed"
                delay(LOCATION_POLL_INTERVAL_MS)
                continue
            }

            currentDb = measurement.db
            currentHz = measurement.hz
            if (measurement.db > peakDb) {
                peakDb = measurement.db
            }
            lastAudioLocation = location
            lastAudioTimeMillis = now

            sendStatus = "Auto sending..."
            val result = sendMeasurement(
                currentDb,
                currentHz,
                location.latitude,
                location.longitude,
                currentDeviceToken
            )
            sendStatus = result

            if (result == "Response: 401") {
                context.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .remove(DEVICE_TOKEN_KEY)
                    .remove(DEVICE_ID_KEY)
                    .apply()
                deviceToken = null
                deviceId = null
                pairingCode = null
                linkStatus = "Link removed"
                linkRetryNonce++
                return@LaunchedEffect
            }

            if (result.startsWith("Response")) {
                sendCount++
            }

            delay(LOCATION_POLL_INTERVAL_MS)
        }
    }

    fun requestPairingCode() {
        if (isRequestingCode) return

        scope.launch {
            val prefs = context.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
            isRequestingCode = true
            linkStatus = "Getting code"
            pairingCode = null
            deviceId = null
            deviceToken = null
            prefs.edit()
                .remove(DEVICE_ID_KEY)
                .remove(DEVICE_TOKEN_KEY)
                .apply()

            when (val result = startDeviceLink()) {
                is StartLinkResult.Success -> {
                    deviceId = result.deviceId
                    pairingCode = result.pairingCode
                    prefs.edit().putString(DEVICE_ID_KEY, result.deviceId).apply()
                    linkStatus = "Enter code"
                }
                is StartLinkResult.Failure -> {
                    linkStatus = result.message
                }
            }
            isRequestingCode = false
        }
    }

    Hack121smartwatchTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colors.background),
            contentAlignment = Alignment.Center
        ) {
            TimeText()

            if (deviceToken == null) {
                SetupContent(
                    linkStatus = linkStatus,
                    pairingCode = pairingCode,
                    isRequestingCode = isRequestingCode,
                    onRequestPairingCode = { requestPairingCode() }
                )
            } else {
                MeasurementContent(
                    currentDb = currentDb,
                    peakDb = peakDb,
                    currentHz = currentHz,
                    sendStatus = sendStatus,
                    locationStatus = locationStatus,
                    audioStatus = audioStatus,
                    sendCount = sendCount,
                    hasAudioPermission = hasAudioPermission,
                    hasLocationPermission = hasLocationPermission,
                    onRequestPermission = onRequestPermission,
                    onRequestLocationPermission = onRequestLocationPermission,
                    onSend = {
                        scope.launch {
                            val currentLatitude = latitude
                            val currentLongitude = longitude
                            val currentDeviceToken = deviceToken

                            if (currentLatitude == null || currentLongitude == null) {
                                sendStatus = "Waiting for GPS"
                                return@launch
                            }
                            if (currentDeviceToken == null) {
                                sendStatus = "Link required"
                                return@launch
                            }

                            sendStatus = "Sending"
                            sendStatus = sendMeasurement(
                                currentDb,
                                currentHz,
                                currentLatitude,
                                currentLongitude,
                                currentDeviceToken
                            )
                            if (sendStatus == "Response: 401") {
                                context.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)
                                    .edit()
                                    .remove(DEVICE_TOKEN_KEY)
                                    .remove(DEVICE_ID_KEY)
                                    .apply()
                                deviceToken = null
                                deviceId = null
                                pairingCode = null
                                linkStatus = "Link removed"
                                linkRetryNonce++
                            }
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun SetupContent(
    linkStatus: String,
    pairingCode: String?,
    isRequestingCode: Boolean,
    onRequestPairingCode: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .padding(top = 22.dp, bottom = 8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "SoundReal",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (pairingCode == null) {
            Text(
                text = "Connect your watch",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = "Get a code, then enter it in the mobile app.",
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(10.dp))
            Chip(
                onClick = onRequestPairingCode,
                enabled = !isRequestingCode,
                label = {
                    Text(
                        text = if (isRequestingCode) "Getting..." else "Get Code",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
            )
        } else {
            Text(
                text = pairingCode,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = "Enter this code in the mobile app.",
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(7.dp))
            Text(
                text = if (linkStatus == "Waiting for link") "Waiting..." else linkStatus,
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )
        }

        if (pairingCode == null && linkStatus != "Not linked") {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = linkStatus,
                fontSize = 10.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
fun MeasurementContent(
    currentDb: Double,
    peakDb: Double,
    currentHz: Double,
    sendStatus: String,
    locationStatus: String,
    audioStatus: String,
    sendCount: Int,
    hasAudioPermission: Boolean,
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit,
    onRequestLocationPermission: () -> Unit,
    onSend: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(top = 22.dp, bottom = 8.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = "SoundReal",
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = "%.1f dB".format(currentDb),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Text(
            text = "%.1f Hz".format(currentHz),
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(5.dp))

        Text(
            text = "Peak %.1f dB".format(peakDb),
            fontSize = 10.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = compactStatus(locationStatus, audioStatus, sendStatus),
            fontSize = 10.sp,
            textAlign = TextAlign.Center
        )
        Text(
            text = "Sent $sendCount",
            fontSize = 10.sp,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        if (!hasAudioPermission) {
            Button(onClick = onRequestPermission) {
                Text("Mic")
            }
        }
        if (!hasLocationPermission) {
            Button(onClick = onRequestLocationPermission) {
                Text("GPS")
            }
        }
        if (hasAudioPermission && hasLocationPermission) {
            Button(onClick = onSend) {
                Text("Send")
            }
        }
    }
}

fun compactStatus(
    locationStatus: String,
    audioStatus: String,
    sendStatus: String
): String {
    return when {
        locationStatus.contains("No GPS", ignoreCase = true) -> "GPS waiting"
        audioStatus.contains("sampling", ignoreCase = true) -> "Listening"
        sendStatus.contains("Sending", ignoreCase = true) -> "Sending"
        sendStatus.startsWith("Response: 2") -> "Synced"
        sendStatus.startsWith("Error") -> "Network error"
        else -> locationStatus
    }
}

suspend fun sendMeasurement(
    db: Double,
    hz: Double,
    latitude: Double,
    longitude: Double,
    deviceToken: String
): String {
    val client = OkHttpClient()

    val json = """
        {
            "db": $db,
            "hz": $hz,
            "latitude": $latitude,
            "longitude": $longitude
        }
    """.trimIndent()

    val body = json.toRequestBody("application/json".toMediaType())

    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}/measurements")
        .header("Authorization", "Bearer $deviceToken")
        .post(body)
        .build()

    return withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response: okhttp3.Response ->
                "Response: ${response.code}"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }
}

suspend fun validateDeviceToken(deviceToken: String): Boolean {
    val client = OkHttpClient()
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}/device/me")
        .header("Authorization", "Bearer $deviceToken")
        .get()
        .build()

    return withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: Exception) {
            false
        }
    }
}

sealed class StartLinkResult {
    data class Success(val deviceId: String, val pairingCode: String) : StartLinkResult()
    data class Failure(val message: String) : StartLinkResult()
}

sealed class PollLinkResult {
    data object Pending : PollLinkResult()
    data object Expired : PollLinkResult()
    data class Linked(val deviceToken: String) : PollLinkResult()
    data class Failure(val message: String) : PollLinkResult()
}

suspend fun startDeviceLink(): StartLinkResult {
    val client = OkHttpClient()
    val body = "{}".toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}/device/start-link")
        .post(body)
        .build()

    return withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext StartLinkResult.Failure("Link start ${response.code}")
                }

                val json = JSONObject(response.body?.string().orEmpty())
                StartLinkResult.Success(
                    deviceId = json.getString("device_id"),
                    pairingCode = json.getString("pairing_code")
                )
            }
        } catch (e: Exception) {
            StartLinkResult.Failure("Link error: ${e.message}")
        }
    }
}

suspend fun pollDeviceLink(deviceId: String): PollLinkResult {
    val client = OkHttpClient()
    val json = """
        {
            "device_id": "$deviceId"
        }
    """.trimIndent()
    val body = json.toRequestBody("application/json".toMediaType())
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}/device/poll-link")
        .post(body)
        .build()

    return withContext(Dispatchers.IO) {
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext PollLinkResult.Failure("Poll ${response.code}")
                }

                val payload = JSONObject(response.body?.string().orEmpty())
                when (payload.getString("status")) {
                    "pending" -> PollLinkResult.Pending
                    "expired" -> PollLinkResult.Expired
                    "linked" -> PollLinkResult.Linked(payload.getString("device_token"))
                    else -> PollLinkResult.Failure("Unknown link status")
                }
            }
        } catch (e: Exception) {
            PollLinkResult.Failure("Poll error: ${e.message}")
        }
    }
}

@SuppressLint("MissingPermission")
suspend fun getCurrentLocation(
    fusedLocationClient: FusedLocationProviderClient,
    locationManager: LocationManager
): Location? {
    val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
    val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

    if (!gpsEnabled && !networkEnabled) {
        return null
    }

    val currentLocation = withTimeoutOrNull(15_000L) {
        suspendCancellableCoroutine { continuation ->
            val cancellationTokenSource = CancellationTokenSource()

            fusedLocationClient.getCurrentLocation(
                Priority.PRIORITY_BALANCED_POWER_ACCURACY,
                cancellationTokenSource.token
            ).addOnSuccessListener { location: Location? ->
                if (continuation.isActive) {
                    continuation.resume(location)
                }
            }.addOnFailureListener {
                if (continuation.isActive) {
                    continuation.resume(null)
                }
            }

            continuation.invokeOnCancellation {
                cancellationTokenSource.cancel()
            }
        }
    }

    if (currentLocation != null) {
        return currentLocation
    }

    val lastGpsLocation = if (gpsEnabled) {
        locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
    } else {
        null
    }
    val lastNetworkLocation = if (networkEnabled) {
        locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
    } else {
        null
    }

    return listOfNotNull(lastGpsLocation, lastNetworkLocation)
        .maxByOrNull { it.time }
}

data class AudioMeasurement(
    val db: Double,
    val hz: Double
)

@SuppressLint("MissingPermission")
suspend fun recordAudioSample(
    durationMillis: Long,
    onStatus: (String) -> Unit
): AudioMeasurement? {
    val sampleRate = 8000
    val bufferSize = AudioRecord.getMinBufferSize(
        sampleRate,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    if (bufferSize <= 0) {
        onStatus("Mic buffer error: $bufferSize")
        return null
    }

    val audioRecord = try {
        AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )
    } catch (e: Exception) {
        onStatus("Mic init error: ${e.message ?: e.javaClass.simpleName}")
        return null
    }

    if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
        onStatus("Mic init failed")
        audioRecord.release()
        return null
    }

    val buffer = ShortArray(bufferSize)
    var peakDb = 0.0
    var lastHz = 0.0
    val endTimeMillis = System.currentTimeMillis() + durationMillis

    return try {
        audioRecord.startRecording()

        if (audioRecord.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            onStatus("Mic start failed")
            return null
        }

        onStatus("Mic sampling")

        while (System.currentTimeMillis() < endTimeMillis) {
            val readSize = audioRecord.read(buffer, 0, buffer.size)

            if (readSize > 0) {
                var sum = 0.0

                for (i in 0 until readSize) {
                    sum += buffer[i] * buffer[i]
                }

                val rms = sqrt(sum / readSize)
                val db = if (rms > 0) {
                    20 * log10(rms)
                } else {
                    0.0
                }

                if (db > peakDb) {
                    peakDb = db
                }
                lastHz = estimateFrequencyByZeroCrossing(buffer, readSize, sampleRate)
            } else {
                onStatus("Mic read error: $readSize")
            }
        }

        onStatus(if (peakDb > 0.0) "Mic sampled" else "Mic silent")
        AudioMeasurement(peakDb, lastHz)
    } catch (e: SecurityException) {
        onStatus("Mic security error")
        null
    } catch (e: Exception) {
        onStatus("Mic error: ${e.message ?: e.javaClass.simpleName}")
        null
    } finally {
        audioRecord.release()
    }
}

fun estimateFrequencyByZeroCrossing(
    buffer: ShortArray,
    readSize: Int,
    sampleRate: Int
): Double {
    var crossings = 0

    for (i in 1 until readSize) {
        if ((buffer[i - 1] < 0 && buffer[i] >= 0) ||
            (buffer[i - 1] >= 0 && buffer[i] < 0)
        ) {
            crossings++
        }
    }

    val seconds = readSize.toDouble() / sampleRate
    if (seconds <= 0.0) return 0.0

    return crossings / 2.0 / seconds
}
