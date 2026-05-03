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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import kotlin.coroutines.resume
import kotlin.math.log10
import kotlin.math.sqrt

private const val LOCATION_POLL_INTERVAL_MS = 20_000L
private const val STATIONARY_AUDIO_INTERVAL_MS = 5 * 60 * 1000L
private const val MOVEMENT_THRESHOLD_METERS = 5f
private const val AUDIO_SAMPLE_DURATION_MS = 2_000L

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
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(hasAudioPermission, hasLocationPermission) {
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
            val result = sendMeasurement(currentDb, currentHz, location.latitude, location.longitude)
            sendStatus = result

            if (result.startsWith("Response")) {
                sendCount++
            }

            delay(LOCATION_POLL_INTERVAL_MS)
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

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text("SoundReal")

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = if (hasAudioPermission) "Mic: OK" else "Mic: NG",
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Audio: $audioStatus",
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Current dB: %.1f".format(currentDb),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Peak dB: %.1f".format(peakDb),
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Hz: %.1f".format(currentHz),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = sendStatus,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Loc: $locationStatus",
                    textAlign = TextAlign.Center
                )

                Text(
                    text = "Sent: $sendCount",
                    textAlign = TextAlign.Center
                )

                Text(
                    text = latitude?.let { "Lat: %.5f".format(it) } ?: "Lat: locating...",
                    textAlign = TextAlign.Center
                )

                Text(
                    text = longitude?.let { "Lng: %.5f".format(it) } ?: "Lng: locating...",
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(4.dp))

                if (!hasAudioPermission) {
                    Button(onClick = onRequestPermission) {
                        Text("Allow Mic")
                    }
                }
                if (!hasLocationPermission) {
                    Button(onClick = onRequestLocationPermission) {
                        Text("Allow GPS")
                    }
                }
                if (hasAudioPermission && hasLocationPermission) {
                    Button(
                        onClick = {
                            CoroutineScope(Dispatchers.Main).launch {
                                val currentLatitude = latitude
                                val currentLongitude = longitude

                                if (currentLatitude == null || currentLongitude == null) {
                                    sendStatus = "Send paused"
                                    return@launch
                                }

                                sendStatus = "Sending..."
                                sendStatus = sendMeasurement(
                                    currentDb,
                                    currentHz,
                                    currentLatitude,
                                    currentLongitude
                                )
                            }
                        }
                    ) {
                        Text("Send")
                    }
                }
            }
        }
    }
}

suspend fun sendMeasurement(
    db: Double,
    hz: Double,
    latitude: Double,
    longitude: Double
): String {
    val client = OkHttpClient()

    val json = """
        {
            "user_id": "user-001",
            "db": $db,
            "hz": $hz,
            "latitude": $latitude,
            "longitude": $longitude
        }
    """.trimIndent()

    val body = json.toRequestBody("application/json".toMediaType())

    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}/measurements")
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
