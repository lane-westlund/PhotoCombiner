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
import java.io.FileInputStream // For copying


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
    var isProcessing by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }
    var processingStage by remember { mutableStateOf<String?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pickMultipleMedia =
        rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(500)) { uris ->
            if (uris.isNotEmpty()) {
                if (!averageImageChecked && !medianImageChecked) {
                    Log.i("PhotoCombinerApp", "No processing type selected.")
                    return@rememberLauncherForActivityResult
                }

                scope.launch {
                    isProcessing = true
                    progress = 0f // Reset overall progress

                    try {
                        if (averageImageChecked) {
                            processingStage = "average_processing"
                            Log.d("PhotoCombinerApp", "Starting Average processing stage.")
                            withContext(Dispatchers.IO) {
                                processImages(
                                    context = context,
                                    uris = uris,
                                    processAverage = true,
                                    processMedian = false
                                ) { p -> scope.launch(Dispatchers.Main) { progress = p } }
                            }
                            Log.d("PhotoCombinerApp", "Average processing stage complete.")
                        }

                        if (medianImageChecked) {
                            processingStage = if (averageImageChecked) "average_done_median_processing" else "median_processing"
                            Log.d("PhotoCombinerApp", "Starting Median processing stage.")
                            progress = 0f // Reset progress for the median stage
                            withContext(Dispatchers.IO) {
                                processImages(
                                    context = context,
                                    uris = uris,
                                    processAverage = false,
                                    processMedian = true
                                ) { p -> scope.launch(Dispatchers.Main) { progress = p } }
                            }
                            Log.d("PhotoCombinerApp", "Median processing stage complete.")
                        }
                        processingStage = "all_done" // All selected operations are finished
                    } catch (e: Exception) {
                        Log.e("ImageProcessing", "Error during sequential processing: ${e.message}", e)
                        processingStage = "error" // You can have specific UI for error if needed
                    } finally {
                        isProcessing = false // Stop showing "Processing..." on button
                        progress = 0f // Reset progress bar/value

                        // Optional: Short delay to allow user to see "Done" or "Error" state
                        if (processingStage == "all_done" || processingStage == "error") {
                            delay(2000) // Delay for 2 seconds
                        }

                        // Reset UI to initial state
                        processingStage = null
                        averageImageChecked = false
                        medianImageChecked = false
                    }
                }
            } else {
                Log.d("PhotoPicker", "No media selected")
            }
        }

    // ... (Column and UI layout remains the same as your last version) ...
    // The Text composables for averageText and medianText will automatically
    // revert to their default state when processingStage becomes null and
    // averageImageChecked/medianImageChecked become false.

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Average Image Row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = averageImageChecked,
                onCheckedChange = { if (!isProcessing) averageImageChecked = it },
                enabled = !isProcessing
            )
            Spacer(modifier = Modifier.width(8.dp))
            val averageText = when (processingStage) {
                "average_processing" -> "Average Image (${(progress * 100).roundToInt()}%)"
                "average_done_median_processing", "all_done" -> if (averageImageChecked) "Average Image (Done)" else "Average Image"
                "error" -> if (averageImageChecked) "Average Image (Error)" else "Average Image" // Example error display
                else -> "Average Image"
            }
            val averageColor = if (isProcessing && averageImageChecked && processingStage != "average_processing" && processingStage != null) Color.Gray else Color.Unspecified
            Text(averageText, color = averageColor)
        }
        Spacer(modifier = Modifier.height(8.dp))

        // Median Image Row
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = medianImageChecked,
                onCheckedChange = { if (!isProcessing) medianImageChecked = it },
                enabled = !isProcessing
            )
            Spacer(modifier = Modifier.width(8.dp))
            val medianText = when (processingStage) {
                "average_processing" -> if (medianImageChecked) "Median Image (Pending)" else "Median Image"
                "median_processing", "average_done_median_processing" -> "Median Image (${(progress * 100).roundToInt()}%)"
                "all_done" -> if (medianImageChecked) "Median Image (Done)" else "Median Image"
                "error" -> if (medianImageChecked) "Median Image (Error)" else "Median Image" // Example error display
                else -> "Median Image"
            }
            val medianColor = if (isProcessing && medianImageChecked && (processingStage == "average_processing" || (processingStage != "median_processing" && processingStage != "average_done_median_processing" && processingStage != null))) Color.Gray else Color.Unspecified
            Text(medianText, color = medianColor)
        }
        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = { pickMultipleMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            enabled = !isProcessing && (averageImageChecked || medianImageChecked) // Also re-enable based on new checkbox states
        ) {
            Text(if (isProcessing) "Processing..." else "Load Images") // Button text will also reset due to isProcessing
        }
    }
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