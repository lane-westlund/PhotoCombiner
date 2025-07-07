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
    var modalImageChecked by remember { mutableStateOf(false) }
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
                    startImageProcessingService(context, selectedUris, averageImageChecked, medianImageChecked, modalImageChecked)
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

                if (!averageImageChecked && !medianImageChecked && !modalImageChecked) {
                    Log.i("PhotoCombinerApp", "No processing type selected.")
                Toast.makeText(context, "Please select at least one processing type.", Toast.LENGTH_SHORT).show()
                return@rememberLauncherForActivityResult
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    when (context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)) {
                        android.content.pm.PackageManager.PERMISSION_GRANTED -> {
                            startImageProcessingService(context, selectedUris, averageImageChecked, medianImageChecked, modalImageChecked)
                            isServiceProcessing = true
                        }
                        else -> {
                            // Only set isServiceProcessing to true IF we know we are trying to start
                            // The actual start will happen in notificationPermissionLauncher callback
                            notificationPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                } else {
                    startImageProcessingService(context, selectedUris, averageImageChecked, medianImageChecked, modalImageChecked)
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
        Spacer(modifier = Modifier.height(8.dp)) // <-- Spacer before new checkbox

        Row(verticalAlignment = Alignment.CenterVertically) { // <-- NEW Checkbox for Modal
            Checkbox(
                checked = modalImageChecked,
                onCheckedChange = { if (!isServiceProcessing) modalImageChecked = it },
                enabled = !isServiceProcessing
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Modal Image")
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                pickMultipleMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            enabled = !isServiceProcessing && (averageImageChecked || medianImageChecked || modalImageChecked)
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
    processMedian: Boolean,
    processModal: Boolean
) {
    val intent = Intent(context, ImageProcessingService::class.java).apply {
        action = ImageProcessingService.ACTION_START_PROCESSING
        putStringArrayListExtra(ImageProcessingService.EXTRA_IMAGE_URIS, ArrayList(imageUris.map { it.toString() }))
        putExtra(ImageProcessingService.EXTRA_PROCESS_AVERAGE, processAverage)
        putExtra(ImageProcessingService.EXTRA_PROCESS_MEDIAN, processMedian)
        putExtra(ImageProcessingService.EXTRA_PROCESS_MODAL, processModal)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
    Log.d("PhotoCombinerApp", "Attempted to start ImageProcessingService.")
}

fun processImages(
    context: Context,
    uris: List<Uri>,
    processAverage: Boolean,
    processMedian: Boolean,
    processModal: Boolean, // <-- NEW
    onProgressUpdate: (overallProgress: Float, operationName: String, operationProgress: Float) -> Unit,
    onOperationStart: (operationName: String) -> Unit // Callback when a new operation (avg, med, mod) starts
) {
    Log.d("ImageProcessing", "Loading bitmaps...")
    // ... (your existing bitmap loading logic from uris) ...
    val bitmaps = uris.mapNotNull { uri ->
        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            Log.e("ImageProcessing", "Error loading bitmap from URI: $uri", e)
            null
        }
    }

    if (bitmaps.isEmpty()) {
        Log.e("ImageProcessing", "No bitmaps loaded or all failed.")
        onProgressUpdate(1f, "Error", 1f) // Signal completion with error
        return
    }

    // --- Dimension Check (ensure all bitmaps have the same dimensions) ---
    val firstBitmap = bitmaps.first()
    val width = firstBitmap.width
    val height = firstBitmap.height
    if (bitmaps.any { it.width != width || it.height != height }) {
        Log.e("ImageProcessing", "All images must have the same dimensions.")
        onProgressUpdate(1f, "Error", 1f) // Signal completion with error
        return
    }
    val firstImageUriForExif = uris.firstOrNull() // For saving EXIF

    val totalOperations = listOf(processAverage, processMedian, processModal).count { it }
    var completedOperations = 0

    val calculateOverallProgress = { operationProgress: Float ->
        (completedOperations.toFloat() / totalOperations) + (operationProgress / totalOperations)
    }

    // --- Process Average ---
    if (processAverage) {
        onOperationStart("Average Image")
        Log.d("ImageProcessing", "Starting average image calculation...")
        val resultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // ... (your existing averaging pixel logic) ...
        // Inside your loops for average:
        // onProgressUpdate(calculateOverallProgress(pixelsProcessed / totalPixels), "Averaging", pixelsProcessed / totalPixels)
        var pixelsProcessed = 0f
        val totalPixels = width * height.toFloat()
        for (x in 0 until width) {
            for (y in 0 until height) {
                var sumRed = 0f; var sumGreen = 0f; var sumBlue = 0f; var sumAlpha = 0f
                for (bitmap in bitmaps) {
                    val pixel = bitmap.getPixel(x, y)
                    sumAlpha += android.graphics.Color.alpha(pixel).toFloat()
                    sumRed += android.graphics.Color.red(pixel).toFloat()
                    sumGreen += android.graphics.Color.green(pixel).toFloat()
                    sumBlue += android.graphics.Color.blue(pixel).toFloat()
                }
                val numImages = bitmaps.size
                resultBitmap.setPixel(x, y, android.graphics.Color.argb(
                    (sumAlpha / numImages).toInt().coerceIn(0, 255),
                    (sumRed / numImages).toInt().coerceIn(0, 255),
                    (sumGreen / numImages).toInt().coerceIn(0, 255),
                    (sumBlue / numImages).toInt().coerceIn(0, 255)
                ))
                pixelsProcessed++
                if (x == width -1 && y == height -1 || pixelsProcessed.toInt() % 1000 == 0) {
                    onProgressUpdate(calculateOverallProgress(pixelsProcessed / totalPixels), "Average Image", pixelsProcessed / totalPixels)
                }
            }
        }
        saveBitmap(context, resultBitmap, "averaged_image_${System.currentTimeMillis()}.jpg", "image/jpeg", "_average", firstImageUriForExif)
        // resultBitmap.recycle() // Be careful with recycling if you plan to reuse source bitmaps
        completedOperations++
        Log.d("ImageProcessing", "Average image calculation complete.")
    }

    // --- Process Median ---
    if (processMedian) {
        onOperationStart("Median Image")
        Log.d("ImageProcessing", "Starting median image calculation...")
        val medianResultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // ... (your existing median pixel logic) ...
        // Inside your loops for median:
        // onProgressUpdate(calculateOverallProgress(pixelsProcessedMedian / totalPixels), "Medianing", pixelsProcessedMedian / totalPixels)
        var pixelsProcessedMedian = 0f
        val totalPixels = width * height.toFloat()
        val redValues = mutableListOf<Int>(); val greenValues = mutableListOf<Int>()
        val blueValues = mutableListOf<Int>(); val alphaValues = mutableListOf<Int>()

        for (x in 0 until width) {
            for (y in 0 until height) {
                redValues.clear(); greenValues.clear(); blueValues.clear(); alphaValues.clear()
                for (bitmap in bitmaps) {
                    val pixel = bitmap.getPixel(x, y)
                    alphaValues.add(android.graphics.Color.alpha(pixel))
                    redValues.add(android.graphics.Color.red(pixel))
                    // ... add green, blue
                    greenValues.add(android.graphics.Color.green(pixel))
                    blueValues.add(android.graphics.Color.blue(pixel))
                }
                alphaValues.sort(); redValues.sort(); greenValues.sort(); blueValues.sort()
                val medianIndex = if (bitmaps.size % 2 == 0) (bitmaps.size / 2) - 1 else bitmaps.size / 2
                medianResultBitmap.setPixel(x, y, android.graphics.Color.argb(
                    alphaValues[medianIndex].coerceIn(0,255),
                    redValues[medianIndex].coerceIn(0,255),
                    greenValues[medianIndex].coerceIn(0,255),
                    blueValues[medianIndex].coerceIn(0,255)
                ))
                pixelsProcessedMedian++
                if (x == width -1 && y == height -1 || pixelsProcessedMedian.toInt() % 1000 == 0) {
                    onProgressUpdate(calculateOverallProgress(pixelsProcessedMedian / totalPixels), "Median Image", pixelsProcessedMedian / totalPixels)
                }
            }
        }
        saveBitmap(context, medianResultBitmap, "median_image_${System.currentTimeMillis()}.jpg", "image/jpeg", "_median", firstImageUriForExif)
        // medianResultBitmap.recycle()
        completedOperations++
        Log.d("ImageProcessing", "Median image calculation complete.")
    }

    // --- Process Modal ---
    if (processModal) {
        onOperationStart("Modal Image")
        Log.d("ImageProcessing", "Starting modal image calculation...")
        val modalResultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        var pixelsProcessedModal = 0f
        val totalPixels = width * height.toFloat()

        val redValues = mutableListOf<Int>()
        val greenValues = mutableListOf<Int>()
        val blueValues = mutableListOf<Int>()
        val alphaValues = mutableListOf<Int>()

        for (x in 0 until width) {
            for (y in 0 until height) {
                redValues.clear(); greenValues.clear(); blueValues.clear(); alphaValues.clear()

                for (bitmap in bitmaps) {
                    val pixel = bitmap.getPixel(x, y)
                    alphaValues.add(android.graphics.Color.alpha(pixel))
                    redValues.add(android.graphics.Color.red(pixel))
                    greenValues.add(android.graphics.Color.green(pixel))
                    blueValues.add(android.graphics.Color.blue(pixel))
                }

                val modalAlpha = findMode(alphaValues)
                val modalRed = findMode(redValues)
                val modalGreen = findMode(greenValues)
                val modalBlue = findMode(blueValues)

                modalResultBitmap.setPixel(x, y, android.graphics.Color.argb(
                    modalAlpha.coerceIn(0, 255),
                    modalRed.coerceIn(0, 255),
                    modalGreen.coerceIn(0, 255),
                    modalBlue.coerceIn(0, 255)
                ))

                pixelsProcessedModal++
                if (x == width - 1 && y == height - 1 || pixelsProcessedModal.toInt() % 1000 == 0) {
                    onProgressUpdate(calculateOverallProgress(pixelsProcessedModal / totalPixels), "Modal Image", pixelsProcessedModal / totalPixels)
                }
            }
        }
        saveBitmap(context, modalResultBitmap, "modal_image_${System.currentTimeMillis()}.jpg", "image/jpeg", "_modal", firstImageUriForExif)
        // modalResultBitmap.recycle()
        completedOperations++
        Log.d("ImageProcessing", "Modal image calculation complete.")
    }

    // --- Cleanup Original Bitmaps (if desired and not already handled) ---
    // if (!bitmaps.all { it.isRecycled }) {
    //     bitmaps.forEach { if (!it.isRecycled) it.recycle() }
    //     Log.d("ImageProcessing", "Recycled source bitmaps.")
    // }

    // Ensure final progress update signals 100% completion if all selected operations are done
    if (completedOperations == totalOperations && totalOperations > 0) {
        onProgressUpdate(1f, "All Operations Complete", 1f)
    } else if (totalOperations == 0) {
        onProgressUpdate(1f, "No operations selected", 1f) // Or handle as error
    }
    Log.d("ImageProcessing", "All selected processing steps finished.")
}

// Helper function to find the mode of a list of integers
// Tie-breaking: returns the smallest modal value if multiple modes exist.
fun findMode(numbers: List<Int>): Int {
    if (numbers.isEmpty()) {
        // Decide what to do for an empty list: throw error, return default (e.g., 0 or 128)
        // For pixel data, returning a mid-range value or black might be okay.
        return 0 // Defaulting to 0 for missing data, adjust as needed
    }

    val frequencyMap = numbers.groupingBy { it }.eachCount()

    // Find the maximum frequency.
    val maxFrequency = frequencyMap.values.maxOrNull()

    // Find all numbers that have the maximum frequency.
    val modes = frequencyMap.filter { it.value == maxFrequency }.keys

    // Tie-breaking rule: if multiple modes, return the smallest one.
    // You could also return the largest, the first one encountered, or average them (though averaging modes is unusual).
    return modes.minOrNull() ?: numbers.first() // Fallback to the first element if modes set is somehow empty (shouldn't happen if numbers isn't empty)
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