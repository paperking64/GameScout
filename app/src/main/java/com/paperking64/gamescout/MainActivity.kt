package com.paperking64.gamescout

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
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
import com.google.mlkit.vision.barcode.Barcode
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.net.URLEncoder

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
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "GameScout",
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White
                    )
                    Text("BETA", color = Color(0xFFB388FF))
                }

                Spacer(Modifier.height(8.dp))
                Text(status, color = Color.LightGray)
                Spacer(Modifier.height(12.dp))

                if (cameraGranted) {
                    CameraScanner(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        onRecognized = { detected ->
                            query = detected
                            status = if (detected.isBlank()) {
                                "No clear title found. Try again or search manually."
                            } else {
                                "Text detected. Check the title before searching."
                            }
                        },
                        onBarcodeDetected = { code ->
                            query = code
                            pageUrl = "https://www.pricecharting.com/search-products?type=prices&q=" +
                                URLEncoder.encode(code, "UTF-8")
                            status = "Barcode detected. Opening PriceCharting search..."
                            sheetOpen = true
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
                            if (query.isNotBlank()) {
                                pageUrl = "https://www.pricecharting.com/search-products?type=prices&q=" +
                                    URLEncoder.encode(query, "UTF-8")
                                sheetOpen = true
                            }
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
                    "Prototype: on-device text recognition reads cover text. Confirm the title before searching.",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall
                )
            }
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
    onRecognized: (String) -> Unit,
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
                                        } else {
                                            recognizeCoverText(input, onRecognized, onStatus)
                                        }
                                    }
                                    .addOnFailureListener {
                                        recognizeCoverText(input, onRecognized, onStatus)
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


private fun recognizeCoverText(
    input: InputImage,
    onRecognized: (String) -> Unit,
    onStatus: (String) -> Unit
) {
    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        .process(input)
        .addOnSuccessListener { result ->
            val detected = result.textBlocks
                .flatMap { block -> block.lines }
                .map { line -> line.text }
                .sortedByDescending { line -> line.length }
                .take(5)
                .joinToString(" ")
                .take(120)
            onRecognized(detected)
        }
        .addOnFailureListener {
            onStatus("Barcode and text recognition failed. Try another photo.")
        }
}
