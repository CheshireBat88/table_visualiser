package com.groupfund.app.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Экран сканирования QR-приглашения (CameraX + ZXing).
 *
 * [onScanned] получает распознанный текст и возвращает true, если он подошёл
 * (тогда экран закрывается вызывающей стороной). Для постороннего QR возвращаем
 * false — сканирование продолжается, а пользователь видит подсказку.
 */
@Composable
fun QrScannerScreen(
    onBack: () -> Unit,
    onScanned: (String) -> Boolean,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    LaunchedEffect(Unit) {
        if (!granted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (granted) {
        CameraPreview(onBack = onBack, onScanned = onScanned)
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            )
            Text(
                "Нет доступа к камере",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                "Разрешите съёмку, чтобы сканировать QR-код приглашения. " +
                    "Приложение использует камеру только для распознавания кода.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
            )
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Разрешить доступ")
            }
            TextButton(
                onClick = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
                },
            ) {
                Text("Открыть настройки")
            }
            TextButton(onClick = onBack) {
                Text("Назад")
            }
        }
    }
}

@Composable
private fun CameraPreview(
    onBack: () -> Unit,
    onScanned: (String) -> Boolean,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val onScannedState = rememberUpdatedState(onScanned)
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val handled = remember { AtomicBoolean(false) }
    var hint by remember { mutableStateOf<String?>(null) }
    var cameraError by remember { mutableStateOf<String?>(null) }

    DisposableEffect(lifecycleOwner) {
        val mainExecutor = ContextCompat.getMainExecutor(context)
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var analysis: ImageAnalysis? = null

        val listener = Runnable {
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analyzer = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .setTargetResolution(Size(1280, 720))
                    .build()
                analysis = analyzer

                val reader = MultiFormatReader().apply {
                    setHints(
                        mapOf(
                            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                            DecodeHintType.TRY_HARDER to true,
                        ),
                    )
                }
                var lastAttempt = 0L
                analyzer.setAnalyzer(analysisExecutor) { image ->
                    try {
                        if (handled.get()) return@setAnalyzer
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastAttempt < 250) return@setAnalyzer
                        lastAttempt = now
                        val text = decodeQr(image, reader) ?: return@setAnalyzer
                        if (!handled.compareAndSet(false, true)) return@setAnalyzer
                        mainExecutor.execute {
                            if (!onScannedState.value(text)) {
                                handled.set(false)
                                hint = "Это не приглашение. Наведите камеру на QR-код из приложения."
                            }
                        }
                    } finally {
                        image.close()
                    }
                }

                provider.unbindAll()
                provider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analyzer,
                )
            } catch (e: Exception) {
                cameraError = "Камера недоступна: ${e.message ?: "ошибка запуска"}"
            }
        }

        future.addListener(listener, mainExecutor)
        onDispose {
            analysis?.clearAnalyzer()
            runCatching { future.get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.fillMaxSize(),
        )

        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(250.dp)
                .border(3.dp, Color.White, RoundedCornerShape(24.dp)),
        )

        Text(
            text = cameraError ?: hint ?: "Наведите камеру на QR-код приглашения",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 48.dp),
        )

        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .background(Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50)),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Закрыть сканер", tint = Color.White)
        }
    }
}

/** Декодирует только QR-код из Y-плоскости кадра. Возвращает null, если кода нет. */
private fun decodeQr(image: ImageProxy, reader: MultiFormatReader): String? {
    val plane = image.planes.firstOrNull() ?: return null
    if (plane.pixelStride != 1) return null
    val buffer = plane.buffer
    val rowStride = plane.rowStride
    val height = image.height
    if (rowStride < image.width || height <= 0) return null

    val data = ByteArray(rowStride * height)
    buffer.get(data, 0, minOf(buffer.remaining(), data.size))

    return try {
        val source = PlanarYUVLuminanceSource(
            data,
            rowStride,
            height,
            0,
            0,
            image.width,
            height,
            false,
        )
        reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
    } catch (e: Exception) {
        null
    } finally {
        reader.reset()
    }
}
