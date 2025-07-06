package com.example.photocombiner

import android.content.Context
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.core.graphics.blue
import androidx.core.graphics.green
import androidx.core.graphics.red
import com.example.photocombiner.ui.theme.PhotoCombinerTheme
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import kotlin.text.toFloat
import android.graphics.Color as AndroidGraphicsColor // Alias this one
import androidx.compose.runtime.rememberCoroutineScope // Add this import
import androidx.compose.ui.geometry.isEmpty
import kotlinx.coroutines.launch // Add this import
import kotlinx.coroutines.Dispatchers // Add this import
import kotlinx.coroutines.withContext // Add this import (for potential UI updates from background)
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalContext // Ensure this is present
import kotlin.ranges.coerceIn
import kotlin.text.all
import kotlin.text.first
import kotlinx.coroutines.delay
import androidx.exifinterface.media.ExifInterface // Add this import
import java.io.IOException // Add this import if not already present
import kotlin.io.path.getAttribute
import kotlin.io.path.setAttribute
import android.os.ParcelFileDescriptor // Ensure this is imported
import androidx.compose.runtime.LaunchedEffect
import java.io.FileInputStream // For copying
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.IntentFilter
import androidx.compose.runtime.DisposableEffect
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import android.widget.Toast // For showing error messages
import androidx.activity.result.launch


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PhotoCombinerTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    PhotoCombinerApp(
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }
}

@Composable
fun PhotoCombinerApp(modifier: Modifier = Modifier) {
    var averageImageChecked by remember { mutableStateOf(false) }
    var medianImageChecked by remember { mutableStateOf(false) }
    var isServiceProcessing by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    val context = LocalContext.current

    // BroadcastReceiver to listen for service completion
    val processingStateReceiver = remember {
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.d("PhotoCombinerApp", "Received broadcast: ${intent?.action}")
                when (intent?.action) {
                    ImageProcessingService.BROADCAST_PROCESSING_COMPLETE -> {
                        isServiceProcessing = false
                        // Optionally reset checkboxes or show a success message
                        Toast.makeText(context, "Processing complete!", Toast.LENGTH_SHORT).show()
                        // Reset checkboxes if desired for a full UI reset
                        // averageImageChecked = false
                        // medianImageChecked = false
                        selectedUris = emptyList() // Clear selected URIs
                    }
                    ImageProcessingService.BROADCAST_PROCESSING_ERROR -> {
                        isServiceProcessing = false
                        val errorMessage = intent.getStringExtra(ImageProcessingService.EXTRA_ERROR_MESSAGE)
                        Toast.makeText(context, "Processing error: $errorMessage", Toast.LENGTH_LONG).show()
                        selectedUris = emptyList() // Clear selected URIs
                    }
                }
            }
        }
    }

    // Register and unregister the receiver using DisposableEffect
    DisposableEffect(Unit) {
        val intentFilter = IntentFilter().apply {
            addAction(ImageProcessingService.BROADCAST_PROCESSING_COMPLETE)
            addAction(ImageProcessingService.BROADCAST_PROCESSING_ERROR)
        }
        LocalBroadcastManager.getInstance(context).registerReceiver(processingStateReceiver, intentFilter)
        Log.d("PhotoCombinerApp", "BroadcastReceiver registered.")

        onDispose {
            LocalBroadcastManager.getInstance(context).unregisterReceiver(processingStateReceiver)
            Log.d("PhotoCombinerApp", "BroadcastReceiver unregistered.")
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(), // Explicitly state the contract
        onResult = { isGranted: Boolean -> // Explicitly type the lambda parameter
            if (isGranted) {
                Log.d("PhotoCombinerApp", "POST_NOTIFICATIONS permission granted.")
                // If URIs were selected (meaning picker ran, then permission was asked),
                // and now granted, start service.
                if (selectedUris.isNotEmpty() && (averageImageChecked || medianImageChecked)) {
                    startImageProcessingService(context, selectedUris, averageImageChecked, medianImageChecked)
                    isServiceProcessing = true // Service is definitely starting or attempting to
                } else {
                    // This case might occur if permission was requested for some other reason
                    // or if the state changed before service could start.
                    Log.w("PhotoCombinerApp", "Permission granted, but selectedUris is empty or no processing type. Not starting service.")
                    // isServiceProcessing should ideally not have been set to true if we reached here without starting.
                }
            } else {
                Log.w("PhotoCombinerApp", "POST_NOTIFICATIONS permission denied.")
                isServiceProcessing = false // Crucial: update state if permission denied and service won't start
                Toast.makeText(context, "Notification permission denied. Processing cannot start.", Toast.LENGTH_LONG).show()
            }
        }
    )


    val pickMultipleMedia =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickMultipleVisualMedia(500)
        ) { urisFromPicker: List<Uri>? ->
            // ... (your pickMultipleMedia logic as discussed before) ...
            if (!urisFromPicker.isNullOrEmpty()) {
                selectedUris = urisFromPicker

                if (!averageImageChecked && !medianImageChecked) {
                    Log.i("PhotoCombinerApp", "No processing type selected.")
                    return@rememberLauncherForActivityResult
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    when (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)) {
                        android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                            startImageProcessingService(context, selectedUris, averageImageChecked, medianImageChecked)
                            isServiceProcessing = true
                        }
                        else -> {
                            // Only set isServiceProcessing to true IF we know we are trying to start
                            // The actual start will happen in notificationPermissionLauncher callback
                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                } else {
                    startImageProcessingService(context, selectedUris, averageImageChecked, medianImageChecked)
                    isServiceProcessing = true
                }
            } else {
                selectedUris = emptyList()
            }
        }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = averageImageChecked,
                onCheckedChange = { if (!isServiceProcessing) averageImageChecked = it },
                enabled = !isServiceProcessing
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Average Image")
        }
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = medianImageChecked,
                onCheckedChange = { if (!isServiceProcessing) medianImageChecked = it },
                enabled = !isServiceProcessing
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Median Image")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                pickMultipleMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            enabled = !isServiceProcessing && (averageImageChecked || medianImageChecked)
        ) {
            Text(if (isServiceProcessing) "Processing in Background..." else "Load Images & Start Processing")
        }

        if (isServiceProcessing) {
            Spacer(modifier = Modifier.height(8.dp))
            Text("Check notification for progress.", color = Color.Gray)
        }
    }
}


// Helper function to start the service
private fun startImageProcessingService(
    context: Context,
    imageUris: List<Uri>,
    processAverage: Boolean,
    processMedian: Boolean
) {
    val intent = Intent(context, ImageProcessingService::class.java).apply {
        action = ImageProcessingService.ACTION_START_PROCESSING
        putStringArrayListExtra(ImageProcessingService.EXTRA_IMAGE_URIS, ArrayList(imageUris.map { it.toString() }))
        putExtra(ImageProcessingService.EXTRA_PROCESS_AVERAGE, processAverage)
        putExtra(ImageProcessingService.EXTRA_PROCESS_MEDIAN, processMedian)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
    Log.d("PhotoCombinerApp", "Attempted to start ImageProcessingService.")
}

suspend fun processImages(
    context: Context,
    uris: List<Uri>,
    processAverage: Boolean, // True if this call is for averaging
    processMedian: Boolean,  // True if this call is for median
    onProgressUpdate: (Float) -> Unit
) {
    Log.d("ImageProcessing", "Processing. Average: $processAverage, Median: $processMedian")
    onProgressUpdate(0f) // Start progress for this operation at 0%

    if (uris.isEmpty()) {
        Log.w("ImageProcessing", "No URIs provided.")
        onProgressUpdate(1f) // Indicate completion (or error state)
        return
    }
    val firstImageUriForExif: Uri? = uris.firstOrNull()

    // Load bitmaps from URIs
    val bitmaps: List<Bitmap> = uris.mapNotNull { uri ->
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.e("ImageProcessing", "Error loading bitmap from URI: $uri", e)
            null // Return null if a bitmap fails to load, mapNotNull will filter it out
        }
    }
    if (bitmaps.isEmpty()) { /* ... call onProgressUpdate(1f) and return ... */ }
    val firstBitmap = bitmaps.first()
    val width = firstBitmap.width
    val height = firstBitmap.height
    val totalPixels = width * height.toFloat()
    // ... (dimension check, call onProgressUpdate(1f) and return on error) ...


    if (processAverage) {
        Log.d("ImageProcessing", "Starting average image calculation...")
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var pixelsProcessed = 0f

        for (x in 0 until width) {
            for (y in 0 until height) {
                // ... (averaging pixel logic as before) ...
                var sumRed = 0f
                var sumGreen = 0f
                var sumBlue = 0f
                var sumAlpha = 0f

                for (bitmap in bitmaps) {
                    val pixel = bitmap.getPixel(x, y)
                    sumAlpha += AndroidGraphicsColor.alpha(pixel).toFloat()
                    sumRed += AndroidGraphicsColor.red(pixel).toFloat()
                    sumGreen += AndroidGraphicsColor.green(pixel).toFloat()
                    sumBlue += AndroidGraphicsColor.blue(pixel).toFloat()
                }

                val numImages = bitmaps.size
                val avgAlpha = (sumAlpha / numImages).toInt().coerceIn(0, 255)
                val avgRed = (sumRed / numImages).toInt().coerceIn(0, 255)
                val avgGreen = (sumGreen / numImages).toInt().coerceIn(0, 255)
                val avgBlue = (sumBlue / numImages).toInt().coerceIn(0, 255)

                resultBitmap.setPixel(x, y, AndroidGraphicsColor.argb(avgAlpha, avgRed, avgGreen, avgBlue))

                pixelsProcessed++
                if (y == height - 1 || pixelsProcessed.toInt() % 1000 == 0) {
                    onProgressUpdate(pixelsProcessed / totalPixels)
                }
            }
        }
        Log.d("ImageProcessing", "Average image calculation complete.")
        val displayName = "averaged_image_${System.currentTimeMillis()}.jpg"
        saveBitmap(context, resultBitmap, displayName, "image/jpeg", "_average", firstImageUriForExif)
        // resultBitmap.recycle()
    }

    if (processMedian) {
        Log.d("ImageProcessing", "Starting median image calculation...")
        // Note: Progress for median starts from 0 here because onProgressUpdate(0f)
        // was called at the beginning of this function invocation.
        val medianResultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var pixelsProcessedMedian = 0f
        val redValues = mutableListOf<Int>()
        // ... (rest of median lists and logic as before) ...
        val greenValues = mutableListOf<Int>()
        val blueValues = mutableListOf<Int>()
        val alphaValues = mutableListOf<Int>()

        for (x in 0 until width) {
            for (y in 0 until height) {
                redValues.clear()
                greenValues.clear()
                blueValues.clear()
                alphaValues.clear()

                for (bitmap in bitmaps) {
                    val pixel = bitmap.getPixel(x, y)
                    alphaValues.add(AndroidGraphicsColor.alpha(pixel))
                    redValues.add(AndroidGraphicsColor.red(pixel))
                    greenValues.add(AndroidGraphicsColor.green(pixel))
                    blueValues.add(AndroidGraphicsColor.blue(pixel))
                }

                redValues.sort()
                greenValues.sort()
                blueValues.sort()
                alphaValues.sort()

                val numImages = bitmaps.size
                val medianIndex = if (numImages % 2 == 0) (numImages / 2) - 1 else numImages / 2

                val medAlpha = alphaValues[medianIndex].coerceIn(0, 255)
                val medRed = redValues[medianIndex].coerceIn(0, 255)
                val medGreen = greenValues[medianIndex].coerceIn(0, 255)
                val medBlue = blueValues[medianIndex].coerceIn(0, 255)

                medianResultBitmap.setPixel(x, y, AndroidGraphicsColor.argb(medAlpha, medRed, medGreen, medBlue))

                pixelsProcessedMedian++
                if (y == height - 1 || pixelsProcessedMedian.toInt() % 1000 == 0) {
                    onProgressUpdate(pixelsProcessedMedian / totalPixels)
                }
            }
        }
        Log.d("ImageProcessing", "Median image calculation complete.")
        val displayNameMedian = "median_image_${System.currentTimeMillis()}.jpg"
        saveBitmap(context, medianResultBitmap, displayNameMedian, "image/jpeg", "_median", firstImageUriForExif)
        // medianResultBitmap.recycle()
    }

    // --- Cleanup Original Bitmaps ---
    if (!bitmaps.all { it.isRecycled }) {
        // ... recycle bitmaps ...
    }
    onProgressUpdate(1f) // Signal completion of THIS operation (average OR median)
    Log.d("ImageProcessing", "Current processing step finished.")
}

private fun saveBitmap(
    context: Context,
    bitmap: Bitmap,
    displayName: String,
    mimeType: String,
    suffix: String,
    originalUriForExif: Uri? = null // New parameter to receive the URI of the first image
) {
    val fileNameWithSuffix = displayName.substringBeforeLast(".") + suffix + "." + displayName.substringAfterLast(".")
    var imageUri: Uri? = null // To hold the URI of the saved file, useful for EXIF writing

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileNameWithSuffix)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + File.separator + "PhotoCombiner") // Optional: sub-folder
            put(MediaStore.MediaColumns.IS_PENDING, 1) // Set as pending
        }
        val resolver = context.contentResolver
        try {
            imageUri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            imageUri?.let { uri ->
                resolver.openOutputStream(uri).use { outputStream ->
                    if (outputStream == null) throw IOException("Failed to get output stream for MediaStore.")
                    val format = if (mimeType.equals("image/png", ignoreCase = true)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                    bitmap.compress(format, 95, outputStream)
                }

                // --- Copy EXIF data AFTER bitmap is compressed and stream is closed ---
                if (originalUriForExif != null && mimeType.equals("image/jpeg", ignoreCase = true)) {
                    Log.d("ImageProcessing", "DOING EXIF COPY")
                    copyExifData(context, originalUriForExif, uri)
                } else {
                    Log.d("ImageProcessing", "SKIPPING EXIF COPY")
                }
                // --- End EXIF Copy ---

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                Log.d("ImageProcessing", "Image saved successfully to $uri")
            } ?: Log.e("ImageProcessing", "MediaStore.insert() returned null URI.")

        } catch (e: Exception) {
            Log.e("ImageProcessing", "Error saving image with MediaStore: ${e.message}", e)
            imageUri?.let { resolver.delete(it, null, null) } // Clean up pending entry if error
        }
    } else {
        // For older versions (below Android 10)
        val imagesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES + File.separator + "PhotoCombiner")
        if (!imagesDir.exists()) {
            imagesDir.mkdirs()
        }
        val imageFile = File(imagesDir, fileNameWithSuffix)
        try {
            FileOutputStream(imageFile).use { outputStream ->
                val format = if (mimeType.equals("image/png", ignoreCase = true)) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                bitmap.compress(format, 95, outputStream)
            }
            Log.d("ImageProcessing", "Image saved successfully to ${imageFile.absolutePath}")

            // --- Copy EXIF data AFTER bitmap is compressed and stream is closed ---
            // For file paths, ExifInterface can work directly with the path.
            if (originalUriForExif != null && mimeType.equals("image/jpeg", ignoreCase = true)) {
                // We need an InputStream for the source, but can use the file path for the destination.
                copyExifData(context, originalUriForExif, Uri.fromFile(imageFile), isFilePathDestination = true, destFilePath = imageFile.absolutePath)
            }
            // --- End EXIF Copy ---

        } catch (e: Exception) {
            Log.e("ImageProcessing", "Error saving image to file: ${e.message}", e)
        }
    }
}

// Helper function to copy EXIF data
private fun copyExifData(
    context: Context,
    sourceUri: Uri,
    destinationUri: Uri,
    isFilePathDestination: Boolean = false,
    destFilePath: String? = null
) {
    Log.d("ExifCopy", "Attempting EXIF copy. Source: $sourceUri, Dest: $destinationUri")

    try {
        val sourceExif: ExifInterface
        context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
            sourceExif = ExifInterface(inputStream)
        } ?: run {
            Log.e("ExifCopy", "Failed to open InputStream for source URI: $sourceUri. Cannot copy EXIF.")
            return
        }

        // Define the list of EXIF attributes you want to copy
        // Keep this list comprehensive for the data you care about.
        val attributesToCopy = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_ISO_SPEED_RATINGS,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_IMAGE_WIDTH,
            ExifInterface.TAG_IMAGE_LENGTH,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_GPS_TRACK,
            ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_DEST_LATITUDE,
            ExifInterface.TAG_GPS_DEST_LATITUDE_REF,
            ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF
            // Add or remove tags as needed
        )

        val setAttributeAction: (destinationExif: ExifInterface) -> Unit = { destinationExif ->
            attributesToCopy.forEach { tag ->
                try {
                    sourceExif.getAttribute(tag)?.let { value ->
                        destinationExif.setAttribute(tag, value)
                    }
                } catch (e: Exception) {
                    // Log specific errors during attribute setting if necessary,
                    // but often ExifInterface handles individual tag errors silently.
                    // Log.w("ExifCopy", "Could not set attribute $tag: ${e.message}")
                }
            }
        }

        if (isFilePathDestination && destFilePath != null) {
            val destinationExifInterface = ExifInterface(destFilePath)
            setAttributeAction(destinationExifInterface)
            destinationExifInterface.saveAttributes()
            Log.d("ExifCopy", "EXIF data successfully copied to file path: $destFilePath")
        } else {
            // For Android Q+ (API 29+) using MediaStore URI with temporary file strategy
            val tempFile = File.createTempFile("exif_temp_", ".jpg", context.cacheDir)
            try {
                context.contentResolver.openInputStream(destinationUri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Could not open input stream for destination URI $destinationUri for temp copy.")

                val tempExifInterface = ExifInterface(tempFile.absolutePath)
                setAttributeAction(tempExifInterface)
                tempExifInterface.saveAttributes()

                context.contentResolver.openOutputStream(destinationUri, "wt")?.use { output -> // "wt" for write, truncate
                    FileInputStream(tempFile).use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IOException("Could not open output stream for destination URI $destinationUri to write back modified content.")
                Log.d("ExifCopy", "EXIF data successfully processed for MediaStore URI: $destinationUri")
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete()
                }
            }
        }
    } catch (e: Exception) {
        Log.e("ExifCopy", "Major error during EXIF copy operation: ${e.message}", e)
    }
}

@Preview(showBackground = true)
@Composable
fun PhotoCombinerAppPreview() {
    PhotoCombinerTheme {
        PhotoCombinerApp()
    }
}