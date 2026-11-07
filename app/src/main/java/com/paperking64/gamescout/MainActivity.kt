package com.paperking64.gamescout

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private const val PREFS_NAME = "gamescout_settings"
private const val PREF_GEMINI_KEY = "gemini_api_key"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { GameScoutScreen() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameScoutScreen() {
    val context = LocalContext.current
    val preferences = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }
    var apiKey by remember { mutableStateOf(preferences.getString(PREF_GEMINI_KEY, "") ?: "") }
    var apiKeyDraft by remember { mutableStateOf(apiKey) }
    var settingsOpen by remember { mutableStateOf(false) }
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var status by remember { mutableStateOf("Point your camera at a game cover") }
    var query by remember { mutableStateOf("") }
    var sheetOpen by remember { mutableStateOf(false) }
    var pageUrl by remember { mutableStateOf("https://www.pricecharting.com/") }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        if (!granted) status = "Camera permission is required to scan covers."
    }

    fun openPriceCharting(search: String) {
        query = search
        pageUrl = "https://www.pricecharting.com/search-products?type=prices&q=" +
            URLEncoder.encode(search, "UTF-8")
        sheetOpen = true
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFB388FF),
            background = Color(0xFF101014),
            surface = Color(0xFF1B1B22)
        )
    ) {
        Scaffold(containerColor = Color(0xFF101014)) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "GameScout",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("BETA", color = Color(0xFFB388FF))
                        IconButton(onClick = {
                            apiKeyDraft = apiKey
                            settingsOpen = true
                        }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(status, color = Color.LightGray)
                Spacer(Modifier.height(12.dp))

                if (cameraGranted) {
                    CameraScanner(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        geminiApiKey = apiKey,
                        onGameIdentified = { titleAndPlatform ->
                            status = "Identified: $titleAndPlatform"
                            openPriceCharting(titleAndPlatform)
                        },
                        onBarcodeDetected = { code ->
                            status = "Barcode detected. Opening PriceCharting..."
                            openPriceCharting(code)
                        },
                        onStatus = { status = it }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Color(0xFF24242D), RoundedCornerShape(20.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Button(onClick = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }) {
                            Text("Enable camera")
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Game title or barcode") },
                    singleLine = true
                )
                Spacer(Modifier.height(10.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            if (query.isNotBlank()) openPriceCharting(query)
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Search PriceCharting")
                    }
                    OutlinedButton(onClick = {
                        query = ""
                        status = "Ready for the next game"
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Clear")
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    if (apiKey.isBlank()) {
                        "Add a Gemini API key in Settings to identify cover artwork. Barcode scanning works without it."
                    } else {
                        "Barcode scanning runs first. If no barcode is found, Gemini identifies the cover artwork."
                    },
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (settingsOpen) {
            AlertDialog(
                onDismissRequest = { settingsOpen = false },
                title = { Text("GameScout settings") },
                text = {
                    Column {
                        Text(
                            "Gemini API key",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = apiKeyDraft,
                            onValueChange = { apiKeyDraft = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Paste your API key") },
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "The key is saved privately on this device. Do not share this test APK with your key entered. For a public release, the API call should move behind a protected backend.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        apiKey = apiKeyDraft.trim()
                        preferences.edit().putString(PREF_GEMINI_KEY, apiKey).apply()
                        settingsOpen = false
                        status = if (apiKey.isBlank()) {
                            "Gemini key removed. Barcode scanning is still available."
                        } else {
                            "Gemini key saved. Ready to identify game covers."
                        }
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { settingsOpen = false }) { Text("Cancel") }
                }
            )
        }

        if (sheetOpen) {
            ModalBottomSheet(
                onDismissRequest = { sheetOpen = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF17171E)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "PriceCharting",
                            style = MaterialTheme.typography.titleLarge,
                            color = Color.White
                        )
                        Text(query, color = Color.LightGray, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { sheetOpen = false }) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }

                AndroidView(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(650.dp),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            loadUrl(pageUrl)
                        }
                    },
                    update = { webView ->
                        if (webView.url != pageUrl) webView.loadUrl(pageUrl)
                    }
                )
            }
        }
    }
}

@Composable
private fun CameraScanner(
    modifier: Modifier,
    geminiApiKey: String,
    onGameIdentified: (String) -> Unit,
    onBarcodeDetected: (String) -> Unit,
    onStatus: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    Box(modifier, contentAlignment = Alignment.BottomCenter) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).also { previewView ->
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val capture = ImageCapture.Builder()
                                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                .build()

                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                capture
                            )
                            imageCapture = capture
                        } catch (exception: Exception) {
                            onStatus("Camera could not start. Please reopen the app.")
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            }
        )

        Button(
            onClick = {
                val capture = imageCapture
                if (capture == null) {
                    onStatus("Camera is starting. Try again in a moment.")
                } else {
                    val photoFile = File(context.cacheDir, "gamescout-scan.jpg")
                    val output = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                    capture.takePicture(
                        output,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                val bitmap = android.graphics.BitmapFactory.decodeFile(photoFile.absolutePath)
                                if (bitmap == null) {
                                    onStatus("Could not read the captured photo.")
                                    return
                                }

                                val input = InputImage.fromBitmap(bitmap, 0)
                                val barcodeOptions = BarcodeScannerOptions.Builder()
                                    .setBarcodeFormats(
                                        Barcode.FORMAT_UPC_A,
                                        Barcode.FORMAT_UPC_E,
                                        Barcode.FORMAT_EAN_13,
                                        Barcode.FORMAT_EAN_8
                                    )
                                    .build()
                                val barcodeScanner = BarcodeScanning.getClient(barcodeOptions)
                                barcodeScanner.process(input)
                                    .addOnSuccessListener { barcodes ->
                                        val code = barcodes.firstNotNullOfOrNull { it.rawValue }
                                        if (!code.isNullOrBlank()) {
                                            onBarcodeDetected(code)
                                        } else if (geminiApiKey.isNotBlank()) {
                                            onStatus("No barcode found. Identifying cover artwork...")
                                            identifyGameCover(context, bitmap, geminiApiKey, onGameIdentified, onStatus)
                                        } else {
                                            onStatus("No barcode found. Add a Gemini API key in Settings for cover-art recognition.")
                                        }
                                    }
                                    .addOnFailureListener {
                                        if (geminiApiKey.isNotBlank()) {
                                            onStatus("Barcode scan unavailable. Identifying cover artwork...")
                                            identifyGameCover(context, bitmap, geminiApiKey, onGameIdentified, onStatus)
                                        } else {
                                            onStatus("Barcode scan failed. Add a Gemini API key for cover-art recognition.")
                                        }
                                    }
                                    .addOnCompleteListener {
                                        barcodeScanner.close()
                                    }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                onStatus("Photo capture failed. Try again.")
                            }
                        }
                    )
                }
            },
            modifier = Modifier.padding(14.dp)
        ) {
            Icon(Icons.Default.CameraAlt, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Scan game cover")
        }
    }
}

private fun identifyGameCover(
    context: Context,
    originalBitmap: Bitmap,
    apiKey: String,
    onIdentified: (String) -> Unit,
    onStatus: (String) -> Unit
) {
    Thread {
        var connection: HttpURLConnection? = null
        try {
            val maxDimension = 1280
            val scale = minOf(
                1.0,
                maxDimension.toDouble() / maxOf(originalBitmap.width, originalBitmap.height)
            )
            val bitmap = if (scale < 1.0) {
                Bitmap.createScaledBitmap(
                    originalBitmap,
                    (originalBitmap.width * scale).toInt(),
                    (originalBitmap.height * scale).toInt(),
                    true
                )
            } else originalBitmap

            val imageOutput = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, imageOutput)
            val imageBase64 = Base64.encodeToString(imageOutput.toByteArray(), Base64.NO_WRAP)

            val prompt = """
                Identify the video game shown by the FRONT COVER ART in this photo.
                Use the artwork and the main game title, not publisher logos, console branding,
                review quotes, stickers, rating symbols, spine text, or other incidental text.
                Return the official game title and platform when you can identify them.
                If the image is not a recognizable video game front cover, or you cannot confidently
                identify the game, return an empty title. Do not guess.
                Respond with JSON only, exactly this shape:
                {"title":"game title or empty string","platform":"platform or empty string","confidence":0.0}
                Confidence must be a number from 0 to 1.
            """.trimIndent()

            val parts = org.json.JSONArray()
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", "image/jpeg")
                    .put("data", imageBase64)))
                .put(JSONObject().put("text", prompt))
            val requestBody = JSONObject()
                .put("contents", org.json.JSONArray().put(JSONObject().put("parts", parts)))
                .put("generationConfig", JSONObject()
                    .put("responseMimeType", "application/json")
                    .put("thinkingConfig", JSONObject().put("thinkingLevel", "low")) )

            val endpoint = URL(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"
            )
            connection = (endpoint.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 20000
                readTimeout = 45000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
            }
            connection.outputStream.use { it.write(requestBody.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val responseText = (if (responseCode in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            })?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (responseCode !in 200..299) {
                throw IllegalStateException(
                    when (responseCode) {
                        400, 403 -> "Gemini rejected the API key or request. Check the key in Settings."
                        429 -> "Gemini request limit reached. Wait a moment and try again."
                        else -> "Gemini could not identify this cover (HTTP $responseCode)."
                    }
                )
            }

            val responseJson = JSONObject(responseText)
            val candidateText = responseJson
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .getString("text")
            val result = JSONObject(candidateText)
            val title = result.optString("title").trim()
            val platform = result.optString("platform").trim()
            val confidence = result.optDouble("confidence", 0.0)

            if (title.isBlank() || confidence < 0.55) {
                throw IllegalStateException("Couldn't confidently identify that cover. Try a clearer, straight-on photo.")
            }

            val searchText = if (platform.isNotBlank()) "$title $platform" else title
            ContextCompat.getMainExecutor(context).execute {
                onIdentified(searchText)
            }
        } catch (exception: Exception) {
            val message = exception.message ?: "Cover-art recognition failed. Try again."
            ContextCompat.getMainExecutor(context).execute {
                onStatus(message)
            }
        } finally {
            connection?.disconnect()
        }
    }.start()
}

