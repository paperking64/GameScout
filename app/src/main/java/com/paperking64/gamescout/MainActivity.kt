package com.paperking64.gamescout

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.window.Dialog
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
    var isScanning by remember { mutableStateOf(false) }
    var pageUrl by remember { mutableStateOf("https://www.pricecharting.com/") }
    var sampleTestResults by remember { mutableStateOf("") }

    var currencyAmount by remember { mutableStateOf("1.00") }
    var fromCurrency by remember { mutableStateOf("USD") }
    var toCurrency by remember { mutableStateOf("CAD") }
    var currencyRates by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var currencyRateDate by remember { mutableStateOf("") }
    var currencyLoading by remember { mutableStateOf(false) }
    var currencyError by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        readCachedCurrencyRates(context)?.let { cached ->
            currencyRates = cached.first
            currencyRateDate = cached.second
        }
        currencyLoading = true
        refreshCurrencyRates(
            context,
            onSuccess = { rates, date ->
                currencyRates = rates
                currencyRateDate = date
                currencyError = ""
                currencyLoading = false
            },
            onError = { message ->
                currencyError = message
                currencyLoading = false
            }
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        if (!granted) status = "Camera permission is required to scan covers."
    }

    fun openPriceCharting(search: String) {
        query = search.trim()
        pageUrl = "https://www.pricecharting.com/search-products?type=prices&q=" +
            URLEncoder.encode(query, "UTF-8")
        sheetOpen = true
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { imageUri: Uri? ->
        if (imageUri != null) {
            if (apiKey.isBlank()) {
                status = "Add your Gemini API key in Settings before identifying an uploaded image."
            } else {
                isScanning = true
                status = "Loading uploaded cover image..."
                val bitmap = loadImageBitmap(context, imageUri)
                if (bitmap == null) {
                    isScanning = false
                    status = "Couldn't open that image. Please try another photo."
                } else {
                    status = "Identifying uploaded cover artwork..."
                    identifyGameCover(
                        context,
                        bitmap,
                        apiKey,
                        onIdentified = { titleAndPlatform ->
                            isScanning = false
                            status = "Identified: $titleAndPlatform"
                            openPriceCharting(titleAndPlatform)
                        },
                        onStatus = {
                            isScanning = false
                            status = it
                        }
                    )
                }
            }
        }
    }

    val sampleCases = listOf(
        "test-images/ssbm-melee.jpg" to "Super Smash Bros. Melee",
        "test-images/super-mario-64.jpg" to "Super Mario 64",
        "test-images/pokemon-red.jpg" to "Pokemon Red"
    )

    fun runSampleTest(index: Int, results: List<String>) {
        if (index >= sampleCases.size) {
            sampleTestResults = results.joinToString("\n")
            isScanning = false
            status = "Sample recognition tests completed."
            return
        }
        val (assetPath, expectedTitle) = sampleCases[index]
        val bitmap = loadAssetBitmap(context, assetPath)
        if (bitmap == null) {
            runSampleTest(index + 1, results + "$expectedTitle: TEST IMAGE MISSING")
            return
        }
        identifyGameCover(
            context, bitmap, apiKey,
            onIdentified = { actual ->
                val passed = normalizeForTest(actual).contains(normalizeForTest(expectedTitle))
                runSampleTest(index + 1, results + "$expectedTitle: ${if (passed) "PASS" else "CHECK"} - $actual")
            },
            onStatus = { error ->
                runSampleTest(index + 1, results + "$expectedTitle: ERROR - $error")
            }
        )
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
                    .verticalScroll(rememberScrollState())
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
                            .heightIn(min = 240.dp, max = 380.dp),
                        geminiApiKey = apiKey,
                        onScanningChanged = { isScanning = it },
                        onGameIdentified = { titleAndPlatform ->
                            isScanning = false
                            status = "Identified: $titleAndPlatform"
                            openPriceCharting(titleAndPlatform)
                        },
                        onBarcodeDetected = { code ->
                            isScanning = false
                            status = "Barcode detected. Opening PriceCharting..."
                            openPriceCharting(code)
                        },
                        onStatus = { status = it }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Upload cover image")
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (apiKey.isBlank()) {
                                status = "Add your Gemini API key in Settings before running sample tests."
                            } else {
                                sampleTestResults = ""
                                isScanning = true
                                status = "Testing three sample game images..."
                                runSampleTest(0, emptyList())
                            }
                        },
                        enabled = !isScanning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Test sample images")
                    }
                    if (sampleTestResults.isNotBlank()) {
                        Text(
                            sampleTestResults,
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
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


                Spacer(Modifier.height(18.dp))
                CurrencyConverterCard(
                    amount = currencyAmount,
                    onAmountChange = { currencyAmount = it },
                    fromCurrency = fromCurrency,
                    onFromCurrencyChange = { fromCurrency = it },
                    toCurrency = toCurrency,
                    onToCurrencyChange = { toCurrency = it },
                    rates = currencyRates,
                    rateDate = currencyRateDate,
                    loading = currencyLoading,
                    error = currencyError,
                    onRefresh = {
                        currencyLoading = true
                        currencyError = ""
                        refreshCurrencyRates(
                            context,
                            onSuccess = { rates, date ->
                                currencyRates = rates
                                currencyRateDate = date
                                currencyError = ""
                                currencyLoading = false
                            },
                            onError = { message ->
                                currencyError = message
                                currencyLoading = false
                            }
                        )
                    }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (apiKey.isBlank()) {
                        "Add a Gemini API key in Settings to identify cover artwork from camera scans or uploaded images."
                    } else {
                        "Scan a cover or upload an image. Barcode scanning runs first for camera scans; Gemini identifies the artwork."
                    },
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (isScanning) {
            Dialog(onDismissRequest = {}) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = Color(0xFF1B1B22),
                    tonalElevation = 8.dp
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 36.dp, vertical = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(18.dp)
                    ) {
                        CircularProgressIndicator(color = Color(0xFFB388FF))
                        Text(
                            "Scanning",
                            style = MaterialTheme.typography.headlineSmall,
                            color = Color.White
                        )
                        Text(
                            "GameScout uses a fast model and will report a timeout if Google does not respond.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.LightGray
                        )
                    }
                }
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
                        preferences.edit()
                            .putString(PREF_GEMINI_KEY, apiKey)
                            .apply()
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.9f)
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
                        .weight(1f),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            isNestedScrollingEnabled = true
                            setOnTouchListener { view, event ->
                                when (event.action) {
                                    MotionEvent.ACTION_MOVE -> view.parent?.requestDisallowInterceptTouchEvent(true)
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.parent?.requestDisallowInterceptTouchEvent(false)
                                }
                                false
                            }
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
}

@Composable
private fun CameraScanner(
    modifier: Modifier,
    geminiApiKey: String,
    onScanningChanged: (Boolean) -> Unit,
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
                                .setJpegQuality(80)
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
                    onScanningChanged(true)
                    val photoFile = File(context.cacheDir, "gamescout-scan.jpg")
                    val output = ImageCapture.OutputFileOptions.Builder(photoFile).build()
                    capture.takePicture(
                        output,
                        ContextCompat.getMainExecutor(context),
                        object : ImageCapture.OnImageSavedCallback {
                            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                                val bitmap = decodeSampledBitmap(photoFile.absolutePath, 1400)
                                if (bitmap == null) {
                                    onScanningChanged(false)
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
                                            identifyGameCover(
                                                context,
                                                bitmap,
                                                geminiApiKey,
                                                onIdentified = {
                                                    onScanningChanged(false)
                                                    onGameIdentified(it)
                                                },
                                                onStatus = {
                                                    onScanningChanged(false)
                                                    onStatus(it)
                                                }
                                            )
                                        } else {
                                            onScanningChanged(false)
                                            onStatus("No barcode found. Add a Gemini API key in Settings for cover-art recognition.")
                                        }
                                    }
                                    .addOnFailureListener {
                                        if (geminiApiKey.isNotBlank()) {
                                            onStatus("Barcode scan unavailable. Identifying cover artwork...")
                                            identifyGameCover(
                                                context,
                                                bitmap,
                                                geminiApiKey,
                                                onIdentified = {
                                                    onScanningChanged(false)
                                                    onGameIdentified(it)
                                                },
                                                onStatus = {
                                                    onScanningChanged(false)
                                                    onStatus(it)
                                                }
                                            )
                                        } else {
                                            onScanningChanged(false)
                                            onStatus("Barcode scan failed. Add a Gemini API key for cover-art recognition.")
                                        }
                                    }
                                    .addOnCompleteListener {
                                        barcodeScanner.close()
                                    }
                            }

                            override fun onError(exception: ImageCaptureException) {
                                onScanningChanged(false)
                                onStatus("Photo capture failed. Try again.")
                            }
                        }
                    )
                }
            },
            modifier = Modifier.padding(14.dp)
        ) {
            Text("📷")
            Spacer(Modifier.width(8.dp))
            Text("Scan game cover")
        }
    }
}


private fun normalizeForTest(value: String): String =
    java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFD)
        .replace("\\p{Mn}+".toRegex(), "")
        .lowercase()

private fun decodeSampledBitmap(path: String, maxDimension: Int): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > maxDimension) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize; inPreferredConfig = Bitmap.Config.RGB_565 }
        BitmapFactory.decodeFile(path, options)
    } catch (_: Exception) { null }
}

private fun loadAssetBitmap(context: Context, assetPath: String): Bitmap? {
    return try {
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > 1400) sampleSize *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize; inPreferredConfig = Bitmap.Config.RGB_565 }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    } catch (_: Exception) { null }
}

private fun loadImageBitmap(context: Context, uri: Uri): Bitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sampleSize = 1
        val largestSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (largestSide / sampleSize > 2000) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    } catch (_: Exception) {
        null
    }
}

private data class CoverCandidate(
    val title: String,
    val platform: String,
    val confidence: Double
)

private data class ReferenceCover(
    val candidate: CoverCandidate,
    val image: Bitmap,
    val productUrl: String
)

private fun identifyGameCover(
    context: Context,
    originalBitmap: Bitmap,
    apiKey: String,
    onIdentified: (String) -> Unit,
    onStatus: (String) -> Unit
) {
    Thread {
        val startedAt = android.os.SystemClock.elapsedRealtime()
        try {
            val scannedBitmap = resizeBitmap(originalBitmap, 768)
            val initialImage = bitmapToBase64(scannedBitmap, 68)
            val prompt = """
                Identify the actual video game whose box, cartridge, or disc artwork is shown.
                Read the MAIN GAME TITLE printed on the packaging. Do not mistake a number,
                character, object, score, or decoration in the illustration for the game's title.
                For example, a character holding a dice block showing 3 does NOT mean the game
                is the third installment. Prefer explicit title text and distinctive packaging.
                Return the best match first, plus up to two plausible alternatives ONLY if the
                artwork could reasonably be confused with them. Include the console/platform.
                Do not invent editions, subtitles, or alternatives.
                Return JSON only:
                {"candidates":[{"title":"official game title","platform":"console","confidence":0.0}]}
                Confidence is 0 to 1. Maximum three candidates.
            """.trimIndent()

            val firstParts = org.json.JSONArray()
                .put(JSONObject().put("text", prompt))
                .put(imagePart(initialImage))
            val firstResponse = callGemini(apiKey, firstParts, 180)
            val firstJson = extractGeminiJson(firstResponse)
            val candidateArray = firstJson.optJSONArray("candidates")
                ?: throw IllegalStateException("Gemini did not return game candidates. Please scan again.")

            val candidates = mutableListOf<CoverCandidate>()
            for (i in 0 until minOf(candidateArray.length(), 3)) {
                val item = candidateArray.optJSONObject(i) ?: continue
                val title = item.optString("title").trim()
                val platform = item.optString("platform").trim()
                val confidence = item.optDouble("confidence", 0.0)
                if (title.isNotBlank()) candidates.add(CoverCandidate(title, platform, confidence))
            }
            if (candidates.isEmpty() || candidates.first().confidence < 0.2) {
                throw IllegalStateException("Couldn't confidently identify that cover. Try a clearer, straight-on photo.")
            }

            // Search PriceCharting for each plausible title and retrieve the catalog cover art.
            // This is a silent verification step; it does not change the final user search until
            // the reference images have been compared with the photographed cover.
            val references = mutableListOf<ReferenceCover>()
            for (candidate in candidates) {
                try {
                    val reference = findPriceChartingReference(candidate)
                    if (reference != null) references.add(reference)
                } catch (_: Exception) {
                    // A failed reference lookup should not prevent an otherwise useful scan.
                }
            }

            var selected = candidates.first()
            var verificationNote = ""
            if (references.isNotEmpty()) {
                try {
                    val compareParts = org.json.JSONArray()
                    val comparePrompt = StringBuilder()
                        .append("Compare the photographed game packaging with the catalog reference images. ")
                        .append("The first image is the user's scan. Each following image is a known PriceCharting ")
                        .append("product cover with its candidate title written immediately before it. ")
                        .append("Choose the reference whose actual packaging artwork, title placement, characters, ")
                        .append("colors, and layout best match the scan. Ignore decorative numbers and incidental objects. ")
                        .append("Do not choose based only on a similar character or franchise. If none match, return index 0. ")
                        .append("Return JSON only: {\"bestIndex\":1,\"confidence\":0.0,\"reason\":\"brief reason\"}. ")
                        .append("bestIndex is 1-based among the reference images; confidence is 0 to 1.")
                        .toString()
                    compareParts.put(JSONObject().put("text", comparePrompt))
                    compareParts.put(imagePart(initialImage))
                    references.take(3).forEachIndexed { index, reference ->
                        compareParts.put(JSONObject().put("text",
                            "REFERENCE " + (index + 1) + ": " + reference.candidate.title +
                                " (" + reference.candidate.platform + "). PriceCharting catalog cover."))
                        compareParts.put(imagePart(bitmapToBase64(resizeBitmap(reference.image, 512), 68)))
                    }
                    val compareResponse = callGemini(apiKey, compareParts, 120)
                    val comparison = extractGeminiJson(compareResponse)
                    val bestIndex = comparison.optInt("bestIndex", 0)
                    val compareConfidence = comparison.optDouble("confidence", 0.0)
                    if (bestIndex in 1..references.size && compareConfidence >= 0.55) {
                        selected = references[bestIndex - 1].candidate
                        if (!selected.title.equals(candidates.first().title, ignoreCase = true)) {
                            verificationNote = " Reference images corrected the initial guess."
                        } else {
                            verificationNote = " Cover image verified."
                        }
                    } else {
                        verificationNote = " Reference comparison was inconclusive; using the initial best match."
                    }
                } catch (_: Exception) {
                    verificationNote = " Reference comparison unavailable; using the initial best match."
                }
            } else {
                verificationNote = " No catalog reference images were available; using the initial best match."
            }

            val totalSeconds = (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000.0
            val searchText = selected.title
            ContextCompat.getMainExecutor(context).execute {
                onStatus("Identified in " +
                    String.format(java.util.Locale.US, "%.1f", totalSeconds) + "s." + verificationNote)
                onIdentified(searchText)
            }
        } catch (_: java.net.SocketTimeoutException) {
            val elapsed = (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000.0
            ContextCompat.getMainExecutor(context).execute {
                onStatus("Gemini or cover verification timed out after " +
                    String.format(java.util.Locale.US, "%.0f", elapsed) +
                    "s. Check your connection and try again.")
            }
        } catch (exception: Exception) {
            val message = exception.message ?: "Cover-art recognition failed. Try again."
            ContextCompat.getMainExecutor(context).execute { onStatus(message) }
        }
    }.start()
}

private fun findPriceChartingReference(candidate: CoverCandidate): ReferenceCover? {
    val searchTerms = if (candidate.platform.isNotBlank()) {
        candidate.title + " " + candidate.platform
    } else candidate.title
    val searchUrl = URL("https://www.pricecharting.com/search-products?type=prices&q=" +
        URLEncoder.encode(searchTerms, "UTF-8"))
    val connection = (searchUrl.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 6500
        readTimeout = 8000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
        setRequestProperty("Accept", "text/html,application/xhtml+xml")
    }
    val html = try {
        if (connection.responseCode !in 200..299) return null
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    } finally {
        connection.disconnect()
    }

    // PriceCharting search results are product rows containing a /game/ link and cover image.
    val gameLinkRegex = Regex("""href=["']([^"']*/game/[^"']+)["']""", RegexOption.IGNORE_CASE)
    for (match in gameLinkRegex.findAll(html).take(8)) {
        val href = match.groupValues[1]
        val rowStart = html.lastIndexOf("<tr", match.range.first, ignoreCase = true)
        val rowEnd = if (rowStart >= 0) html.indexOf("</tr>", match.range.last, ignoreCase = true) else -1
        val region = if (rowStart >= 0 && rowEnd > rowStart) {
            html.substring(rowStart, rowEnd + 5)
        } else {
            html.substring(
                (match.range.first - 900).coerceAtLeast(0),
                (match.range.last + 1100).coerceAtMost(html.length)
            )
        }
        val imageTag = Regex("""<img\b[^>]*(?:src|data-src)=["']([^"']+)["'][^>]*>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(region) ?: continue
        var imageUrl = imageTag.groupValues[1]
        if (imageUrl.startsWith("//")) imageUrl = "https:" + imageUrl
        if (imageUrl.startsWith("/")) imageUrl = "https://www.pricecharting.com" + imageUrl
        if (!imageUrl.startsWith("https://")) continue

        val productUrl = if (href.startsWith("http")) href else "https://www.pricecharting.com" + href
        val imageConnection = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 6500
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
        }
        try {
            if (imageConnection.responseCode !in 200..299) continue
            val bytes = imageConnection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > 2_500_000) return@use ByteArray(0)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            if (bytes.isEmpty()) continue
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            return ReferenceCover(candidate, bitmap, productUrl)
        } finally {
            imageConnection.disconnect()
        }
    }
    return null
}

private fun resizeBitmap(source: Bitmap, maxDimension: Int): Bitmap {
    val scale = minOf(1.0, maxDimension.toDouble() / maxOf(source.width, source.height))
    return if (scale < 1.0) {
        Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    } else source
}

private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
    val output = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
    return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
}

private fun imagePart(base64: String): JSONObject =
    JSONObject().put("inline_data", JSONObject()
        .put("mime_type", "image/jpeg")
        .put("data", base64))

private fun callGemini(apiKey: String, parts: org.json.JSONArray, maxTokens: Int): String {
    val requestBody = JSONObject()
        .put("contents", org.json.JSONArray().put(JSONObject()
            .put("role", "user")
            .put("parts", parts)))
        .put("generationConfig", JSONObject()
            .put("responseMimeType", "application/json")
            .put("thinkingConfig", JSONObject().put("thinkingLevel", "MINIMAL"))
            .put("temperature", 0.1)
            .put("maxOutputTokens", maxTokens))
    val endpoint = URL("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash-lite:generateContent")
    var responseText = ""
    var responseCode = 0
    var attempt = 0
    while (attempt < 2) {
        attempt++
        val connection = (endpoint.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10000
            readTimeout = 20000
            doOutput = true
            doInput = true
            useCaches = false
            setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
        }
        try {
            connection.outputStream.use {
                it.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }
            responseCode = connection.responseCode
            responseText = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val retryable = responseCode == 429 || responseCode == 500 ||
                responseCode == 502 || responseCode == 503 || responseCode == 504
            if (retryable && attempt == 1) {
                Thread.sleep(500)
                continue
            }
            break
        } finally {
            connection.disconnect()
        }
    }
    if (responseCode !in 200..299) {
        val detail = try {
            JSONObject(responseText).optJSONObject("error")?.optString("message")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
        val message = when (responseCode) {
            400, 401, 403 -> "Gemini rejected the request (HTTP " + responseCode + "). Check the API key and Gemini API access. "
            404 -> "Gemini model or API endpoint was not found (HTTP 404). "
            429 -> "Gemini rate limit reached. Wait a moment and try again. "
            500, 502, 503, 504 -> "Google's Gemini service is temporarily unavailable (HTTP " + responseCode + "). "
            else -> "Gemini request failed (HTTP " + responseCode + "). "
        }
        throw IllegalStateException(message + (detail ?: responseText.take(220)))
    }
    val responseJson = JSONObject(responseText)
    val candidates = responseJson.optJSONArray("candidates")
        ?: throw IllegalStateException("Gemini returned no candidates. Try a clearer photo.")
    if (candidates.length() == 0) throw IllegalStateException("Gemini returned no match. Try again.")
    val candidate = candidates.optJSONObject(0) ?: JSONObject()
    val candidateParts = candidate.optJSONObject("content")?.optJSONArray("parts")
    val text = buildString {
        if (candidateParts != null) {
            for (i in 0 until candidateParts.length()) {
                val part = candidateParts.optJSONObject(i) ?: continue
                if (!part.optBoolean("thought", false)) append(part.optString("text"))
            }
        }
    }.trim()
    if (text.isBlank()) {
        throw IllegalStateException("Gemini returned an empty answer (reason: " +
            candidate.optString("finishReason", "unknown") + "). Try again.")
    }
    return text
}

private fun extractGeminiJson(responseText: String): JSONObject {
    return try {
        JSONObject(responseText)
    } catch (_: Exception) {
        val jsonStart = responseText.indexOf('{')
        val jsonEnd = responseText.lastIndexOf('}')
        if (jsonStart >= 0 && jsonEnd > jsonStart) {
            JSONObject(responseText.substring(jsonStart, jsonEnd + 1))
        } else {
            throw IllegalStateException("Gemini's response was not valid game information. Please scan again.")
        }
    }
}


private val GAME_SCOUT_CURRENCIES = listOf(
    "USD" to "US Dollar",
    "CAD" to "Canadian Dollar",
    "JPY" to "Japanese Yen",
    "EUR" to "Euro",
    "GBP" to "British Pound",
    "AUD" to "Australian Dollar"
)

private const val PREF_CURRENCY_RATES = "currency_rates_json"
private const val PREF_CURRENCY_DATE = "currency_rates_date"

@Composable
private fun CurrencyConverterCard(
    amount: String,
    onAmountChange: (String) -> Unit,
    fromCurrency: String,
    onFromCurrencyChange: (String) -> Unit,
    toCurrency: String,
    onToCurrencyChange: (String) -> Unit,
    rates: Map<String, Double>,
    rateDate: String,
    loading: Boolean,
    error: String,
    onRefresh: () -> Unit
) {
    val numericAmount = amount.toDoubleOrNull()
    val converted = if (numericAmount != null &&
        rates.containsKey(fromCurrency) && rates.containsKey(toCurrency)) {
        numericAmount * (rates.getValue(toCurrency) / rates.getValue(fromCurrency))
    } else null
    val formattedResult = converted?.let {
        val currency = java.util.Currency.getInstance(toCurrency)
        java.text.NumberFormat.getCurrencyInstance(java.util.Locale.US).apply {
            this.currency = currency
            maximumFractionDigits = if (toCurrency == "JPY") 0 else 2
            minimumFractionDigits = if (toCurrency == "JPY") 0 else 2
        }.format(it)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1B1B22),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Currency converter", style = MaterialTheme.typography.titleMedium, color = Color.White)
                TextButton(onClick = onRefresh, enabled = !loading) {
                    Text(if (loading) "Updating…" else "↻ Refresh")
                }
            }
            OutlinedTextField(
                value = amount,
                onValueChange = { value ->
                    if (value.isEmpty() || value.matches(Regex("^\\d*\\.?\\d{0,2}$"))) {
                        onAmountChange(value)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Amount") },
                singleLine = true
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CurrencyPicker(
                    selected = fromCurrency,
                    onSelected = onFromCurrencyChange,
                    modifier = Modifier.weight(1f)
                )
                Text("→", color = Color.LightGray)
                CurrencyPicker(
                    selected = toCurrency,
                    onSelected = onToCurrencyChange,
                    modifier = Modifier.weight(1f)
                )
            }
            if (formattedResult != null) {
                Text(
                    formattedResult,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color(0xFFB388FF)
                )
                val pairRate = rates.getValue(toCurrency) / rates.getValue(fromCurrency)
                Text(
                    "1 $fromCurrency = " + String.format(java.util.Locale.US, "%.5f", pairRate) + " $toCurrency",
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    if (loading) "Loading current exchange rates…" else "Exchange rates are not available yet.",
                    color = Color.LightGray
                )
            }
            if (rateDate.isNotBlank()) {
                Text("Rates dated $rateDate · Daily reference rates", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
            }
            if (error.isNotBlank()) {
                Text(error, color = Color(0xFFFFB4AB), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun CurrencyPicker(
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp)
        ) {
            Text(selected, maxLines = 1)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            GAME_SCOUT_CURRENCIES.forEach { (code, name) ->
                DropdownMenuItem(
                    text = { Text("$code · $name") },
                    onClick = {
                        onSelected(code)
                        expanded = false
                    }
                )
            }
        }
    }
}

private fun readCachedCurrencyRates(context: Context): Pair<Map<String, Double>, String>? {
    return try {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonText = preferences.getString(PREF_CURRENCY_RATES, null) ?: return null
        val json = JSONObject(jsonText)
        val rates = mutableMapOf("USD" to 1.0)
        for (code in listOf("CAD", "JPY", "EUR", "GBP", "AUD")) {
            val value = json.optDouble(code, Double.NaN)
            if (value.isFinite() && value > 0.0) rates[code] = value
        }
        if (rates.size != 6) null
        else rates to (preferences.getString(PREF_CURRENCY_DATE, "") ?: "")
    } catch (_: Exception) {
        null
    }
}

private fun refreshCurrencyRates(
    context: Context,
    onSuccess: (Map<String, Double>, String) -> Unit,
    onError: (String) -> Unit
) {
    Thread {
        try {
            val url = URL("https://api.frankfurter.dev/v2/rates?base=USD&quotes=CAD,JPY,EUR,GBP,AUD")
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 10000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "GameScout-Android")
            }
            val responseText: String
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("Exchange-rate service returned HTTP $code.")
                }
                responseText = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                connection.disconnect()
            }

            val rows = org.json.JSONArray(responseText)
            val rates = mutableMapOf("USD" to 1.0)
            var date = ""
            for (i in 0 until rows.length()) {
                val row = rows.optJSONObject(i) ?: continue
                val quote = row.optString("quote")
                val rate = row.optDouble("rate", Double.NaN)
                if (quote in listOf("CAD", "JPY", "EUR", "GBP", "AUD") && rate.isFinite() && rate > 0.0) {
                    rates[quote] = rate
                    if (date.isBlank()) date = row.optString("date")
                }
            }
            if (rates.size != 6 || date.isBlank()) {
                throw IllegalStateException("The exchange-rate service returned incomplete currency data.")
            }

            val cache = JSONObject()
            rates.filterKeys { it != "USD" }.forEach { (code, rate) -> cache.put(code, rate) }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putString(PREF_CURRENCY_RATES, cache.toString())
                .putString(PREF_CURRENCY_DATE, date)
                .apply()
            ContextCompat.getMainExecutor(context).execute { onSuccess(rates, date) }
        } catch (exception: Exception) {
            val message = exception.message ?: "Couldn't update exchange rates. Check your internet connection."
            ContextCompat.getMainExecutor(context).execute { onError(message) }
        }
    }.start()
}
