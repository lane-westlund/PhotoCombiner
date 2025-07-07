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
import androidx.localbroadcastmanager.content.LocalBroadcastManager

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
        const val EXTRA_PROCESS_MODAL = "com.example.photocombiner.extra.PROCESS_MODAL"
        const val BROADCAST_PROCESSING_COMPLETE = "com.example.photocombiner.broadcast.PROCESSING_COMPLETE"
        const val BROADCAST_PROCESSING_ERROR = "com.example.photocombiner.broadcast.PROCESSING_ERROR"
        const val EXTRA_ERROR_MESSAGE = "com.example.photocombiner.extra.ERROR_MESSAGE"
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
            val imageUris = imageUrisStrings?.mapNotNull { Uri.parse(it) } ?: emptyList() // Use mapNotNull
            val processAverage = intent.getBooleanExtra(EXTRA_PROCESS_AVERAGE, false)
            val processMedian = intent.getBooleanExtra(EXTRA_PROCESS_MEDIAN, false)
            val processModal = intent.getBooleanExtra(EXTRA_PROCESS_MODAL, false) // <-- Get new extra

            if (imageUris.isEmpty() || (!processAverage && !processMedian && !processModal)) { // <-- Check modal
                Log.w("ImageProcessingService", "No URIs or no processing type selected. Stopping service.")
                stopSelf()
                return START_NOT_STICKY
            }

            // Initial notification
            val totalOperations = listOf(processAverage, processMedian, processModal).count { it }
            var currentOperationIndex = 0

            startForeground(notificationId, createNotification("Starting processing...", 0, true))

            serviceScope.launch { // Coroutine for background work
                var success = true
                var errorMessage: String? = null
                try {
                    Log.d("ImageProcessingService", "Processing ${imageUris.size} images. Avg: $processAverage, Med: $processMedian, Mod: $processModal")

                    // --- Call the global processImages function ---
                    com.example.photocombiner.processImages( // Assuming processImages is in the same package
                        context = applicationContext,
                        uris = imageUris,
                        processAverage = processAverage,
                        processMedian = processMedian,
                        processModal = processModal, // <-- Pass to global function
                        onProgressUpdate = { overallProgress, operationName, operationProgress ->
                            // More detailed progress update
                            val progressText = "$operationName (${(operationProgress * 100).toInt()}%) - Overall: ${((overallProgress) * 100).toInt()}%"
                            updateNotification(
                                progressText,
                                (overallProgress * 100).toInt(), // Overall progress for the bar
                                false // Not indeterminate
                            )
                        },
                        onOperationStart = { opName ->
                            updateNotification("Starting $opName...", 0, true) // Indeterminate for new op
                        }
                    )
                    // --- End call to global processImages ---

                    updateNotification("Processing Complete!", 100, false, true) // Final success notification
                    Log.d("ImageProcessingService", "All processing finished successfully.")

                } catch (e: Exception) {
                    Log.e("ImageProcessingService", "Error during processing: ${e.message}", e)
                    success = false
                    errorMessage = e.message ?: "Unknown processing error"
                    updateNotification("Processing Error: $errorMessage", 0, false, true, isError = true)
                } finally {
                    Log.d("ImageProcessingService", "Processing ended. Success: $success")
                    // Send broadcast (if using LocalBroadcastManager)
                    val broadcastIntent = Intent()
                    if (success) {
                        broadcastIntent.action = BROADCAST_PROCESSING_COMPLETE // Assuming you have this
                    } else {
                        broadcastIntent.action = BROADCAST_PROCESSING_ERROR // Assuming you have this
                        broadcastIntent.putExtra(EXTRA_ERROR_MESSAGE, errorMessage) // Assuming you have this
                    }
                    LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(broadcastIntent)

                    withContext(Dispatchers.Main) { stopSelf() }
                }
            }
        } else {
            Log.w("ImageProcessingService", "Unknown action or no action. Stopping service.")
            stopSelf()
        }
        return START_NOT_STICKY
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