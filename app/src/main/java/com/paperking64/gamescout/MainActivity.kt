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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.rememberNestedScrollInteropConnection
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
    var hardwareTestResults by remember { mutableStateOf("") }
    var scanMode by remember { mutableStateOf("Games") }

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
                status = if (scanMode == "Consoles") "Loading console photo..." else "Loading uploaded cover image..."
                val bitmap = loadImageBitmap(context, imageUri)
                if (bitmap == null) {
                    isScanning = false
                    status = "Couldn't open that image. Please try another photo."
                } else if (scanMode == "Consoles") {
                    status = "Identifying console hardware and variant..."
                    identifyConsoleHardware(
                        context,
                        bitmap,
                        apiKey,
                        onIdentified = { hardware ->
                            isScanning = false
                            status = "Identified console: $hardware"
                        },
                        onStatus = {
                            isScanning = false
                            status = it
                        }
                    )
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


    val hardwareCases = listOf(
        HardwareTestCase("test-images/consoles/nintendo-3ds-01.jpg", "Nintendo 3DS", "Open front", listOf("nintendo 3ds", "3ds")),
        HardwareTestCase("test-images/consoles/nintendo-3ds-02.jpg", "Nintendo 3DS", "Closed front", listOf("nintendo 3ds", "3ds")),
        HardwareTestCase("test-images/consoles/nintendo-3ds-03.jpg", "Nintendo 3DS", "Rear", listOf("nintendo 3ds", "3ds")),
        HardwareTestCase("test-images/consoles/nintendo-3ds-04.jpg", "Nintendo 3DS", "Angled", listOf("nintendo 3ds", "3ds")),
        HardwareTestCase("test-images/consoles/gba-sp-01.jpg", "Game Boy Advance SP", "Closed", listOf("game boy advance sp", "gba sp")),
        HardwareTestCase("test-images/consoles/gba-sp-02.jpg", "Game Boy Advance SP", "Open", listOf("game boy advance sp", "gba sp")),
        HardwareTestCase("test-images/consoles/gba-sp-03.jpg", "Game Boy Advance SP", "Blue variant", listOf("game boy advance sp", "gba sp")),
        HardwareTestCase("test-images/consoles/gba-sp-04.jpg", "Game Boy Advance SP", "Alternate angle", listOf("game boy advance sp", "gba sp")),
        HardwareTestCase("test-images/consoles/wii-01.jpg", "Nintendo Wii", "Front", listOf("nintendo wii", "wii")),
        HardwareTestCase("test-images/consoles/wii-02.jpg", "Nintendo Wii", "Rear", listOf("nintendo wii", "wii")),
        HardwareTestCase("test-images/consoles/wii-03.jpg", "Nintendo Wii", "Side / ports", listOf("nintendo wii", "wii")),
        HardwareTestCase("test-images/consoles/wii-04.jpg", "Nintendo Wii", "Alternate view", listOf("nintendo wii", "wii")),
        HardwareTestCase("test-images/consoles/nes-01.jpg", "Nintendo Entertainment System", "Front", listOf("nintendo entertainment system", "nes")),
        HardwareTestCase("test-images/consoles/nes-02.jpg", "Nintendo Entertainment System", "Rear", listOf("nintendo entertainment system", "nes")),
        HardwareTestCase("test-images/consoles/nes-03.jpg", "Nintendo Entertainment System", "Rear-left angle", listOf("nintendo entertainment system", "nes")),
        HardwareTestCase("test-images/consoles/nes-04.jpg", "Nintendo Entertainment System", "Rear-right angle", listOf("nintendo entertainment system", "nes")),
        HardwareTestCase("test-images/consoles/ps2-01.jpg", "PlayStation 2", "Fat front", listOf("playstation 2", "ps2")),
        HardwareTestCase("test-images/consoles/ps2-02.jpg", "PlayStation 2", "Fat rear", listOf("playstation 2", "ps2")),
        HardwareTestCase("test-images/consoles/ps2-03.jpg", "PlayStation 2", "Fat side", listOf("playstation 2", "ps2")),
        HardwareTestCase("test-images/consoles/ps2-04.jpg", "PlayStation 2", "Alternate model front", listOf("playstation 2", "ps2"))
    )

    fun runHardwareTest(index: Int, results: List<String>) {
        if (index >= hardwareCases.size) {
            val summary = hardwareCases.map { it.expectedSystem }.distinct().map { system ->
                val matching = results.filter { it.startsWith("$system|") }
                val passed = matching.count { it.contains("|PASS|") }
                "$system: $passed/${matching.size} passed"
            }
            hardwareTestResults = (summary + "" + results.map { row ->
                val parts = row.split("|", limit = 4)
                if (parts.size == 4) "${parts[0]} · ${parts[1]}: ${parts[2]} · ${parts[3]}" else row
            }).joinToString("\n")
            isScanning = false
            status = "Hardware recognition tests completed."
            return
        }
        val test = hardwareCases[index]
        status = "Testing ${test.expectedSystem} · ${test.angle} (${index + 1}/${hardwareCases.size})..."
        val bitmap = loadAssetBitmap(context, test.assetPath)
        if (bitmap == null) {
            runHardwareTest(index + 1, results + "${test.expectedSystem}|${test.angle}|FAIL|Test image missing")
            return
        }
        identifyConsoleHardware(
            context, bitmap, apiKey,
            onIdentified = { actual ->
                val normalized = normalizeForTest(actual)
                val passed = test.expectedAliases.any { alias -> normalized.contains(normalizeForTest(alias)) }
                runHardwareTest(
                    index + 1,
                    results + "${test.expectedSystem}|${test.angle}|${if (passed) "PASS" else "FAIL"}|$actual"
                )
            },
            onStatus = { error ->
                runHardwareTest(index + 1, results + "${test.expectedSystem}|${test.angle}|ERROR|$error")
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

                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Games", "Consoles").forEach { mode ->
                        val selected = scanMode == mode
                        if (selected) {
                            Button(
                                onClick = { scanMode = mode },
                                modifier = Modifier.weight(1f),
                                enabled = !isScanning
                            ) { Text(mode) }
                        } else {
                            OutlinedButton(
                                onClick = { scanMode = mode },
                                modifier = Modifier.weight(1f),
                                enabled = !isScanning
                            ) { Text(mode) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    status,
                    color = Color.LightGray,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))

                if (cameraGranted && scanMode == "Games") {
                    CameraScanner(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(270.dp),
                        scanMode = scanMode,
                        geminiApiKey = apiKey,
                        onScanningChanged = { isScanning = it },
                        onConsoleIdentified = { hardware ->
                            isScanning = false
                            status = "Identified console: $hardware"
                        },
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
                        Text("Upload game cover image")
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

                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            if (apiKey.isBlank()) {
                                status = "Add your Gemini API key in Settings before running hardware tests."
                            } else {
                                hardwareTestResults = ""
                                isScanning = true
                                status = "Preparing 20 console recognition tests..."
                                runHardwareTest(0, emptyList())
                            }
                        },
                        enabled = !isScanning,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Test 20 console photos")
                    }
                    if (hardwareTestResults.isNotBlank()) {
                        Text(
                            hardwareTestResults,
                            color = Color.LightGray,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else if (scanMode == "Consoles") {
                    if (cameraGranted) {
                        CameraScanner(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(270.dp),
                            scanMode = scanMode,
                            geminiApiKey = apiKey,
                            onScanningChanged = { isScanning = it },
                            onConsoleIdentified = { hardware ->
                                isScanning = false
                                status = "Identified console: $hardware"
                            },
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
                    } else {
                        OutlinedButton(
                            onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Enable camera to scan consoles") }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Point the camera at a console or handheld and tap Scan console. GameScout will identify the system, color variant, special edition, and hardware revision when visible.",
                        color = Color.LightGray,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(
                        onClick = { imagePickerLauncher.launch("image/*") },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !isScanning
                    ) {
                        Text("Upload console image")
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
                    onSwapCurrencies = {
                        val previousFrom = fromCurrency
                        fromCurrency = toCurrency
                        toCurrency = previousFrom
                    },
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
                        .weight(1f)
                        .nestedScroll(rememberNestedScrollInteropConnection()),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = WebViewClient()
                            // Keep vertical gestures inside the PriceCharting WebView.
                            // The Compose/View nested-scroll bridge above coordinates with the sheet.
                            setOnTouchListener { view, event ->
                                when (event.actionMasked) {
                                    MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE ->
                                        view.parent?.requestDisallowInterceptTouchEvent(true)
                                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                                        view.parent?.requestDisallowInterceptTouchEvent(false)
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
    scanMode: String,
    geminiApiKey: String,
    onScanningChanged: (Boolean) -> Unit,
    onConsoleIdentified: (String) -> Unit,
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
                    // TextureView avoids the camera SurfaceView drawing over Compose
                    // controls such as the Games / Consoles selector.
                    previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
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

                                if (scanMode == "Consoles") {
                                    if (geminiApiKey.isBlank()) {
                                        onScanningChanged(false)
                                        onStatus("Add your Gemini API key in Settings to identify consoles.")
                                    } else {
                                        onStatus("Identifying console hardware and variant...")
                                        identifyConsoleHardware(
                                            context,
                                            bitmap,
                                            geminiApiKey,
                                            onIdentified = {
                                                onScanningChanged(false)
                                                onConsoleIdentified(it)
                                            },
                                            onStatus = {
                                                onScanningChanged(false)
                                                onStatus(it)
                                            }
                                        )
                                    }
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
            Text(if (scanMode == "Consoles") "Scan console" else "Scan game cover")
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


private data class HardwareTestCase(
    val assetPath: String,
    val expectedSystem: String,
    val angle: String,
    val expectedAliases: List<String>
)

private fun identifyConsoleHardware(
    context: Context,
    originalBitmap: Bitmap,
    apiKey: String,
    onIdentified: (String) -> Unit,
    onStatus: (String) -> Unit
) {
    Thread {
        try {
            val image = bitmapToBase64(resizeBitmap(originalBitmap, 1024), 82)
            val prompt = """
                Identify the physical video game console or handheld shown in this photograph.
                This is a hardware-recognition task, NOT a game-cover or text-reading task.
                Identify it from physical shape, proportions, controls, screens, hinges, ports,
                vents, disc slot, labels, and other visible hardware features. Do not require a
                readable logo. The photo may show any side, an oblique angle, or a handheld open
                or closed.

                IMPORTANT: Identify the exact visible VARIANT, not just the product family.
                Treat shell colorways as meaningful variants when the manufacturer gave them
                distinct names. For example, a Nintendo GameCube can be Indigo, Jet Black,
                or Spice Orange. If the shell is orange, report variant "Spice Orange", not
                merely "orange"; if purple/indigo, report "Indigo". Apply the same care to
                other systems with named colorways, special editions, and hardware revisions.
                Keep the base product in "model" (for example, "GameCube") and put its named
                colorway in "variant". Put the observed plain color in "color". Use "edition"
                for a special themed edition, and "revision" for a hardware revision/model
                number such as AGS-101, SCPH-70000, or New Nintendo 3DS XL when supported.
                Do not confuse a color with a hardware revision or special edition.

                Be conservative: infer a named colorway only when the visible shell color and
                design support it. Lighting, reflections, filters, and white balance can alter
                appearance. If exact colorway cannot be distinguished, set variant to
                "Unknown" and lower variant_confidence. Never invent a limited edition or
                revision. Identify the broad family even if the variant is uncertain.

                Return JSON only:
                {"manufacturer":"...","family":"...","model":"...","variant":"...","color":"...","edition":"...","revision":"...","form_factor":"...","visible_features":"...","confidence":0.0,"variant_confidence":0.0}
            """.trimIndent()
            val parts = org.json.JSONArray()
                .put(JSONObject().put("text", prompt))
                .put(imagePart(image))
            val response = callGemini(apiKey, parts, 240)
            val result = extractGeminiJson(response)
            val manufacturer = result.optString("manufacturer").trim()
            val family = result.optString("family").trim()
            val model = result.optString("model").trim()
            val variant = result.optString("variant").trim().takeUnless {
                it.equals("unknown", ignoreCase = true) || it.equals("n/a", ignoreCase = true)
            }.orEmpty()
            val color = result.optString("color").trim()
            val edition = result.optString("edition").trim().takeUnless {
                it.equals("unknown", ignoreCase = true) || it.equals("n/a", ignoreCase = true)
            }.orEmpty()
            val revision = result.optString("revision").trim().takeUnless {
                it.equals("unknown", ignoreCase = true) || it.equals("n/a", ignoreCase = true)
            }.orEmpty()
            val features = result.optString("visible_features").trim()
            val confidence = result.optDouble("confidence", 0.0)
            val variantConfidence = result.optDouble("variant_confidence", 0.0)
            if (family.isBlank() || confidence < 0.15) {
                throw IllegalStateException("Couldn't confidently identify this hardware. Try another angle.")
            }
            val details = mutableListOf<String>()
            if (variant.isNotBlank() && variantConfidence >= 0.35) details += variant
            else if (color.isNotBlank()) details += color
            if (edition.isNotBlank()) details += edition
            if (revision.isNotBlank()) details += revision
            val identity = (listOf(manufacturer, family, model) + details)
                .filter { it.isNotBlank() }.distinct().joinToString(" ")
            val actual = identity +
                " (confidence ${(confidence * 100).toInt()}%, variant confidence ${(variantConfidence * 100).toInt()}%)" +
                if (features.isNotBlank()) " · $features" else ""
            ContextCompat.getMainExecutor(context).execute { onIdentified(actual) }
        } catch (_: java.net.SocketTimeoutException) {
            ContextCompat.getMainExecutor(context).execute {
                onStatus("Gemini timed out. Check the connection and retry.")
            }
        } catch (exception: Exception) {
            val message = exception.message ?: "Hardware recognition failed."
            ContextCompat.getMainExecutor(context).execute { onStatus(message) }
        }
    }.start()
}

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
            // Send one clear, reasonably detailed image. Do not wait for multiple
            // PriceCharting searches or a second image-comparison request.
            val scannedBitmap = resizeBitmap(originalBitmap, 1024)
            val initialImage = bitmapToBase64(scannedBitmap, 82)
            val prompt = """
                Identify the exact video game shown on this physical game packaging.
                Your most important task is to READ THE PRINTED GAME TITLE, not guess
                from the illustration, characters, objects, colors, or franchise.

                TITLE-NUMBER RULE:
                A number in the artwork is NOT a sequel number. Only include a sequel
                number in the game title if that number is visibly printed as part of
                the title/logo itself. For example, if Mario is holding a dice block
                showing 3 but the printed logo says only "Mario Party", the game is
                "Mario Party", NOT "Mario Party 3". Do not infer a sequel from a die,
                score, jersey, item, character count, or other decorative detail.

                Carefully inspect the title logo and any printed subtitle. If the logo
                has no sequel number, return the original/base title even if the
                illustration contains numbers. Do not invent a sequel or edition.
                Identify the platform from the packaging where possible.

                Return JSON only, using exactly these fields:
                {"title":"exact game title","platform":"console","visible_title_text":"words and numbers visibly printed in the title/logo","title_number_is_printed":false,"confidence":0.0}
                Set title_number_is_printed to true only when a sequel number is
                actually visible in the printed title/logo. Confidence is 0 to 1.
            """.trimIndent()

            val parts = org.json.JSONArray()
                .put(JSONObject().put("text", prompt))
                .put(imagePart(initialImage))
            val response = callGemini(apiKey, parts, 220)
            val result = extractGeminiJson(response)

            var title = result.optString("title").trim()
            val platform = result.optString("platform").trim()
            val visibleTitle = result.optString("visible_title_text").trim()
            val confidence = result.optDouble("confidence", 0.0)

            if (title.isBlank() || confidence < 0.15) {
                throw IllegalStateException("Couldn't confidently read the game title. Try a clearer, straight-on photo.")
            }

            // Safety net for the exact class of mistake reported: if the model
            // adds a trailing sequel number but says it is not printed in the
            // title, remove that unsupported suffix before PriceCharting search.
            var correctedUnsupportedNumber = false
            if (!result.optBoolean("title_number_is_printed", false) && visibleTitle.isNotBlank()) {
                val suffix = Regex("""\s+(?i:(\d{1,3}|II|III|IV|V|VI|VII|VIII|IX|X))$""")
                    .find(title)
                if (suffix != null) {
                    val numberToken = suffix.groupValues[1]
                    if (!visibleTitle.contains(numberToken, ignoreCase = true)) {
                        title = title.removeRange(suffix.range).trim()
                        correctedUnsupportedNumber = true
                    }
                }
            }

            val totalSeconds = (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000.0
            val searchText = title
            val detail = if (correctedUnsupportedNumber) {
                " Removed a sequel number that was not printed in the title."
            } else if (platform.isNotBlank()) {
                " Platform: $platform."
            } else {
                ""
            }
            ContextCompat.getMainExecutor(context).execute {
                onStatus("Identified in " +
                    String.format(java.util.Locale.US, "%.1f", totalSeconds) + "s." + detail)
                onIdentified(searchText)
            }
        } catch (_: java.net.SocketTimeoutException) {
            val elapsed = (android.os.SystemClock.elapsedRealtime() - startedAt) / 1000.0
            ContextCompat.getMainExecutor(context).execute {
                onStatus("Gemini timed out after " +
                    String.format(java.util.Locale.US, "%.0f", elapsed) +
                    "s. Check your connection and try again.")
            }
        } catch (exception: Exception) {
            val message = exception.message ?: "Cover-art recognition failed. Try again."
            ContextCompat.getMainExecutor(context).execute { onStatus(message) }
        }
    }.start()
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
    onSwapCurrencies: () -> Unit,
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
                IconButton(onClick = onSwapCurrencies) {
                    Text("⇄", color = Color.White, style = MaterialTheme.typography.titleLarge)
                }
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