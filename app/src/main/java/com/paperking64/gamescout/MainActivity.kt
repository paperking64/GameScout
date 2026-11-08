package com.paperking64.gamescout

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
private const val PREF_PRIMARY_REGION = "primary_region"

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
    val regionOptions = listOf("North America (NTSC-U)", "Europe/Australia (PAL)", "Japan (NTSC-J)")
    var primaryRegion by remember { mutableStateOf(preferences.getString(PREF_PRIMARY_REGION, regionOptions[0]) ?: regionOptions[0]) }
    var regionDraft by remember { mutableStateOf(primaryRegion) }
    var regionMenuExpanded by remember { mutableStateOf(false) }
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        cameraGranted = granted
        if (!granted) status = "Camera permission is required to scan covers."
    }

    fun openPriceCharting(search: String, useRegion: Boolean = true) {
        query = search
        val regionTag = when (primaryRegion) {
            "Europe/Australia (PAL)" -> "PAL"
            "Japan (NTSC-J)" -> "NTSC-J"
            else -> "NTSC-U"
        }
        // A barcode already identifies a specific release, so only title searches get a region hint.
        val searchWithRegion = if (useRegion && search.any { it.isLetter() }) "$search $regionTag" else search
        pageUrl = "https://www.pricecharting.com/search-products?type=prices&q=" +
            URLEncoder.encode(searchWithRegion, "UTF-8")
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
                            regionDraft = primaryRegion
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
                            openPriceCharting(code, useRegion = false)
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
                            "Gemini can take up to a minute to respond. GameScout will retry automatically if Google is busy.",
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
                        Text("Primary game region", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.height(6.dp))
                        Box {
                            OutlinedButton(
                                onClick = { regionMenuExpanded = true },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(regionDraft)
                            }
                            DropdownMenu(
                                expanded = regionMenuExpanded,
                                onDismissRequest = { regionMenuExpanded = false }
                            ) {
                                regionOptions.forEach { option ->
                                    DropdownMenuItem(
                                        text = { Text(option) },
                                        onClick = {
                                            regionDraft = option
                                            regionMenuExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                        Text(
                            "Title searches will prioritize this region by adding its region code to the PriceCharting search. Barcode searches use the barcode as-is.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                        Spacer(Modifier.height(16.dp))
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
                        primaryRegion = regionDraft
                        preferences.edit()
                            .putString(PREF_GEMINI_KEY, apiKey)
                            .putString(PREF_PRIMARY_REGION, primaryRegion)
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
            val maxDimension = 960
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
            bitmap.compress(Bitmap.CompressFormat.JPEG, 72, imageOutput)
            val imageBase64 = Base64.encodeToString(imageOutput.toByteArray(), Base64.NO_WRAP)

            val prompt = """
                Identify the video game shown on this image. It may be a front cover, box art,
                promotional image, or a photo of a game case.
                Read the large stylized game title carefully, even if the letters have decorative
                effects. Console branding such as "Nintendo GameCube" identifies the PLATFORM,
                not the game title. Publisher logos, review quotes, stickers, rating symbols, and
                small incidental text are not the game title.
                Combine readable title text with the artwork to identify the exact game.
                Do not return an empty title merely because the image is slightly blurry or the
                title uses unusual lettering. If part of the title is readable, make the best
                supported identification. Do not invent an edition or subtitle that is not shown.
                Return the official game title and platform.
                Respond with JSON only, exactly this shape:
                {"title":"official game title","platform":"platform","confidence":0.0}
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
                    .put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
                    .put("maxOutputTokens", 128))

            val endpoint = URL(
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent"
            )
            var responseText = ""
            var responseCode = 0
            for (attempt in 0..2) {
                var attemptConnection: HttpURLConnection? = null
                try {
                    attemptConnection = (endpoint.openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 20000
                        readTimeout = 60000
                        doOutput = true
                        useCaches = false
                        setRequestProperty("Content-Type", "application/json")
                        setRequestProperty("x-goog-api-key", apiKey)
                    }
                    attemptConnection.outputStream.use {
                        it.write(requestBody.toString().toByteArray(Charsets.UTF_8))
                    }
                    responseCode = attemptConnection.responseCode
                    responseText = (if (responseCode in 200..299) {
                        attemptConnection.inputStream
                    } else {
                        attemptConnection.errorStream
                    })?.bufferedReader()?.use { it.readText() }.orEmpty()

                    if (responseCode in listOf(408, 429, 500, 502, 503, 504) && attempt < 2) {
                        Thread.sleep(1500L * (1L shl attempt))
                        continue
                    }
                    if (responseCode !in 200..299) {
                        val detail = try {
                            JSONObject(responseText).optString("message")
                        } catch (_: Exception) { "" }
                        throw IllegalStateException(
                            when (responseCode) {
                                400, 401, 403 -> "Gemini rejected the API key or request (HTTP $responseCode). Check that the key is copied correctly and that its project has Gemini API access."
                                429 -> "Gemini rate limit reached after retries. Wait a minute and try again."
                                503, 504 -> "Google's Gemini service is temporarily overloaded (HTTP $responseCode). Try again shortly."
                                else -> "Gemini request failed (HTTP $responseCode). $detail"
                            }
                        )
                    }
                    break
                } catch (timeout: java.net.SocketTimeoutException) {
                    if (attempt == 2) {
                        throw IllegalStateException(
                            "Gemini did not respond after 3 attempts. Google may be busy; please try again in a minute."
                        )
                    }
                    Thread.sleep(1500L * (1L shl attempt))
                } finally {
                    attemptConnection?.disconnect()
                }
            }

            if (responseCode !in 200..299) {
                throw IllegalStateException("Gemini request failed (HTTP $responseCode). Please try again.")
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

            if (title.isBlank() || confidence < 0.30) {
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

