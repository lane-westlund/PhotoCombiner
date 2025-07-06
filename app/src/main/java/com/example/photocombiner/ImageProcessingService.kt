package com.example.photocombiner

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.isEmpty
import androidx.compose.ui.semantics.setProgress
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.collections.map

class ImageProcessingService : Service() {

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)

    private lateinit var notificationManager: NotificationManager
    private val notificationId = 101 // Unique ID for the notification
    private val channelId = "image_processing_channel"

    companion object {
        const val ACTION_START_PROCESSING = "com.example.photocombiner.action.START_PROCESSING"
        const val EXTRA_IMAGE_URIS = "com.example.photocombiner.extra.IMAGE_URIS"
        const val EXTRA_PROCESS_AVERAGE = "com.example.photocombiner.extra.PROCESS_AVERAGE"
        const val EXTRA_PROCESS_MEDIAN = "com.example.photocombiner.extra.PROCESS_MEDIAN"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("ImageProcessingService", "Timestamp Service onCreate: ${System.currentTimeMillis()}")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        Log.d("ImageProcessingService", "Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("ImageProcessingService", "onStartCommand received: ${intent?.action}")
        if (intent?.action == ACTION_START_PROCESSING) {
            val imageUrisStrings = intent.getStringArrayListExtra(EXTRA_IMAGE_URIS)
            val imageUris = imageUrisStrings?.map { Uri.parse(it) } ?: kotlin.collections.emptyList()
            val processAverage = intent.getBooleanExtra(EXTRA_PROCESS_AVERAGE, false)
            val processMedian = intent.getBooleanExtra(EXTRA_PROCESS_MEDIAN, false)

            if (imageUris.isEmpty() || (!processAverage && !processMedian)) {
                Log.w("ImageProcessingService", "No URIs or no processing type selected. Stopping service.")
                stopSelf()
                return android.app.Service.START_NOT_STICKY
            }

            Log.d("ImageProcessingService", "Timestamp PRE Service.startForeground: ${System.currentTimeMillis()}")
            startForeground(notificationId, createNotification("Starting processing...", 0, true))
            Log.d("ImageProcessingService", "Timestamp POST Service.startForeground: ${System.currentTimeMillis()}")

            serviceScope.launch {
                try {
                    Log.d("ImageProcessingService", "Processing ${imageUris.size} images. Average: $processAverage, Median: $processMedian")
                    // Your existing processImages logic will be called here
                    // It needs to be adapted to be called from the service and update notification progress

                    var currentTaskProgress = 0f // Progress for the current operation (average or median)

                    if (processAverage) {
                        updateNotification("Processing Average Image...", 0, true)
                        // Call your actual image processing function
                        // This is a placeholder for where you'd integrate your existing processImages
                        // or a refactored version of it.
                        // For demonstration, let's simulate some work:
                        com.example.photocombiner.processImages( // Call the global one
                            context = applicationContext,
                            uris = imageUris,
                            processAverage = true,
                            processMedian = false
                        ) { progressFraction ->
                            currentTaskProgress = progressFraction
                            // Update notification progress from here
                            // The progress for the notification should be overall if both are selected
                            // For simplicity, we'll update based on current task here
                            updateNotification(
                                "Processing Average Image (${(progressFraction * 100).toInt()}%)",
                                (progressFraction * 100).toInt(),
                                false // Not indeterminate anymore
                            )
                        }
                        Log.d("ImageProcessingService", "Average processing done.")
                    }

                    if (processMedian) {
                        currentTaskProgress = 0f // Reset for median
                        updateNotification("Processing Median Image...", 0, true)
                        com.example.photocombiner.processImages( // Call the global one
                            context = applicationContext,
                            uris = imageUris,
                            processAverage = false,
                            processMedian = true
                        ) { progressFraction ->
                            currentTaskProgress = progressFraction
                            updateNotification(
                                "Processing Median Image (${(progressFraction * 100).toInt()}%)",
                                (progressFraction * 100).toInt(),
                                false
                            )
                        }
                        Log.d("ImageProcessingService", "Median processing done.")
                    }

                    updateNotification("Processing Complete", 100, false, true) // Final success notification
                    Log.d("ImageProcessingService", "All processing finished successfully.")

                } catch (e: Exception) {
                    Log.e("ImageProcessingService", "Error during processing: ${e.message}", e)
                    updateNotification("Processing Error", 0, false, true, isError = true) // Error notification
                } finally {
                    Log.d("ImageProcessingService", "Stopping foreground service and self.")
                    // Don't call stopForeground(true) immediately if you want the "Complete" or "Error" notification to stay a bit
                    // The system will remove it if stopSelf() is called and no new startForeground is issued.
                    // Or, you can post a final non-ongoing notification.
                    // For simplicity, we'll let it be removed when service stops.
                    withContext(Dispatchers.Main) { // Ensure stopSelf is called on the main thread
                        stopSelf()
                    }
                }
            }
        } else {
            Log.w("ImageProcessingService", "Unknown action or no action. Stopping service.")
            stopSelf() // Stop if started with an unknown intent
        }

        return android.app.Service.START_NOT_STICKY // If the service is killed, don't restart it automatically
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Image Processing",
                NotificationManager.IMPORTANCE_LOW // Use LOW to avoid sound/vibration for progress
            ).apply {
                description = "Shows progress for image processing tasks"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(
        contentText: String,
        progress: Int,
        isIndeterminate: Boolean,
        isFinished: Boolean = false, // To make it non-ongoing when finished
        isError: Boolean = false
    ): Notification {
        val title = if (isError) "Processing Failed" else if (isFinished) "Processing Complete" else "Photo Combiner Processing"

        // Intent to open the app when notification is tapped (optional)
        val pendingIntent: PendingIntent? = Intent(this, MainActivity::class.java).let { notificationIntent ->
            PendingIntent.getActivity(this, 0, notificationIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        val builder = NotificationCompat.Builder(this, channelId)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with your app's icon
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(!isFinished && !isError) // Notification stays unless finished or error
            .setOnlyAlertOnce(true) // Don't make sound/vibrate for updates
            .setProgress(100, progress, isIndeterminate)

        if (pendingIntent != null) {
            builder.setContentIntent(pendingIntent)
        }

        // Optionally, add actions like "Cancel"
        // if (!isFinished && !isError) {
        //     val cancelIntent = Intent(this, ImageProcessingService::class.java).apply {
        //         action = ACTION_CANCEL_PROCESSING // You'd need to define and handle this
        //     }
        //     val cancelPendingIntent = PendingIntent.getService(this, 0, cancelIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT)
        //     builder.addAction(R.drawable.ic_cancel, "Cancel", cancelPendingIntent) // Replace with a cancel icon
        // }


        return builder.build()
    }

    private fun updateNotification(
        contentText: String,
        progress: Int,
        isIndeterminate: Boolean,
        isFinished: Boolean = false,
        isError: Boolean = false
    ) {
        val notification = createNotification(contentText, progress, isIndeterminate, isFinished, isError)
        notificationManager.notify(notificationId, notification)
    }


    override fun onBind(intent: Intent?): IBinder? {
        // We are not using binding in this basic example, so return null.
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel() // Cancel all coroutines started by this service
        Log.d("ImageProcessingService", "Service Destroyed")
    }
}