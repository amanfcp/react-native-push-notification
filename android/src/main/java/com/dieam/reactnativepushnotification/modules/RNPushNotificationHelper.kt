package com.dieam.reactnativepushnotification.modules

import android.R
import android.app.*
import android.app.ActivityManager.RunningAppProcessInfo
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.text.HtmlCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.WritableArray
import org.json.JSONArray
import org.json.JSONException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.*

class RNPushNotificationHelper(context: Application) {
    private val personsArray = ArrayList<InboxPerson>()
    private val context: Context
    private val config: RNPushNotificationConfig
    private val scheduledNotificationsPersistence: SharedPreferences
    val mainActivityClass: Class<*>?
        get() {
            val packageName = context.packageName
            val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
            val className = launchIntent!!.component!!.className
            return try {
                Class.forName(className)
            } catch (e: ClassNotFoundException) {
                e.printStackTrace()
                null
            }
        }
    private val alarmManager: AlarmManager
        private get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun invokeApp(bundle: Bundle?) {
        val packageName = context.packageName
        val launchIntent = context.packageManager.getLaunchIntentForPackage(packageName)
        val className = launchIntent!!.component!!.className
        try {
            val activityClass = Class.forName(className)
            val activityIntent = Intent(context, activityClass)
            if (bundle != null) {
                activityIntent.putExtra("notification", bundle)
            }
            activityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(activityIntent)
        } catch (e: Exception) {
            Log.e(RNPushNotification.LOG_TAG, "Class not found", e)
            return
        }
    }

    private fun toScheduleNotificationIntent(bundle: Bundle): PendingIntent? {
        try {
            val notificationID = bundle.getString("id")!!.toInt()
            val notificationIntent = Intent(context, RNPushNotificationPublisher::class.java)
            notificationIntent.putExtra(RNPushNotificationPublisher.NOTIFICATION_ID, notificationID)
            notificationIntent.putExtras(bundle)
            val flags =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
            return PendingIntent.getBroadcast(context, notificationID, notificationIntent, flags)
        } catch (e: Exception) {
            Log.e(RNPushNotification.LOG_TAG, "Unable to parse Notification ID", e)
        }
        return null
    }

    fun sendNotificationScheduled(bundle: Bundle) {
        val intentClass = mainActivityClass
        if (intentClass == null) {
            Log.e(
                RNPushNotification.LOG_TAG,
                "No activity class found for the scheduled notification"
            )
            return
        }
        if (bundle.getString("message") == null) {
            Log.e(RNPushNotification.LOG_TAG, "No message specified for the scheduled notification")
            return
        }
        if (bundle.getString("id") == null) {
            Log.e(
                RNPushNotification.LOG_TAG,
                "No notification ID specified for the scheduled notification"
            )
            return
        }
        val fireDate = bundle.getDouble("fireDate")
        if (fireDate == 0.0) {
            Log.e(RNPushNotification.LOG_TAG, "No date specified for the scheduled notification")
            return
        }
        val notificationAttributes = RNPushNotificationAttributes(bundle)
        val id = notificationAttributes.id
        Log.d(RNPushNotification.LOG_TAG, "Storing push notification with id $id")
        val editor = scheduledNotificationsPersistence.edit()
        editor.putString(id, notificationAttributes.toJson().toString())
        editor.apply()
        val isSaved = scheduledNotificationsPersistence.contains(id)
        if (!isSaved) {
            Log.e(RNPushNotification.LOG_TAG, "Failed to save $id")
        }
        sendNotificationScheduledCore(bundle)
    }

    fun sendNotificationScheduledCore(bundle: Bundle) {
        val fireDate = bundle.getDouble("fireDate").toLong()
        val allowWhileIdle = bundle.getBoolean("allowWhileIdle")

        // If the fireDate is in past, this will fire immediately and show the
        // notification to the user
        val pendingIntent = toScheduleNotificationIntent(bundle) ?: return
        Log.d(
            RNPushNotification.LOG_TAG, String.format(
                "Setting a notification with id %s at time %s",
                bundle.getString("id"), java.lang.Long.toString(fireDate)
            )
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            if (allowWhileIdle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    fireDate,
                    pendingIntent
                )
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, fireDate, pendingIntent)
            }
        } else {
            alarmManager[AlarmManager.RTC_WAKEUP, fireDate] = pendingIntent
        }
    }

    fun sendToNotificationCentre(bundle: Bundle) {
        val aggregator =
            RNPushNotificationPicturesAggregator { largeIconImage, bigPictureImage, bigLargeIconImage ->
                sendToNotificationCentreWithPicture(
                    bundle,
                    largeIconImage,
                    bigPictureImage,
                    bigLargeIconImage
                )
            }
        aggregator.setLargeIconUrl(context, bundle.getString("largeIconUrl"))
        aggregator.setBigLargeIconUrl(context, bundle.getString("bigLargeIconUrl"))
        aggregator.setBigPictureUrl(context, bundle.getString("bigPictureUrl"))
    }

    private fun sendToNotificationCentreWithPicture(
        bundle: Bundle,
        largeIconBitmap: Bitmap?,
        bigPictureBitmap: Bitmap?,
        bigLargeIconBitmap: Bitmap?
    ) {
        var largeIconBitmap = largeIconBitmap
        var bigLargeIconBitmap = bigLargeIconBitmap
        try {
            val intentClass = mainActivityClass
            if (intentClass == null) {
                Log.e(RNPushNotification.LOG_TAG, "No activity class found for the notification")
                return
            }
            if (bundle.getString("message") == null) {
                // this happens when a 'data' notification is received - we do not synthesize a local notification in this case
                Log.d(
                    RNPushNotification.LOG_TAG,
                    "Ignore this message if you sent data-only notification. Cannot send to notification centre because there is no 'message' field in: $bundle"
                )
                return
            }
            val notificationIdString = bundle.getString("id")
            if (notificationIdString == null) {
                Log.e(
                    RNPushNotification.LOG_TAG,
                    "No notification ID specified for the notification"
                )
                return
            }
            val res = context.resources
            val packageName = context.packageName
            var title = bundle.getString("title")
            if (title == null) {
                val appInfo = context.applicationInfo
                title = context.packageManager.getApplicationLabel(appInfo).toString()
            }
            var priority = NotificationCompat.PRIORITY_HIGH
            val priorityString = bundle.getString("priority")
            if (priorityString != null) {
                priority = when (priorityString.toLowerCase()) {
                    "max" -> NotificationCompat.PRIORITY_MAX
                    "high" -> NotificationCompat.PRIORITY_HIGH
                    "low" -> NotificationCompat.PRIORITY_LOW
                    "min" -> NotificationCompat.PRIORITY_MIN
                    "default" -> NotificationCompat.PRIORITY_DEFAULT
                    else -> NotificationCompat.PRIORITY_HIGH
                }
            }
            var visibility = NotificationCompat.VISIBILITY_PRIVATE
            val visibilityString = bundle.getString("visibility")
            if (visibilityString != null) {
                visibility = when (visibilityString.toLowerCase()) {
                    "private" -> NotificationCompat.VISIBILITY_PRIVATE
                    "public" -> NotificationCompat.VISIBILITY_PUBLIC
                    "secret" -> NotificationCompat.VISIBILITY_SECRET
                    else -> NotificationCompat.VISIBILITY_PRIVATE
                }
            }
            var channel_id = bundle.getString("channelId")
            if (channel_id == null) {
                channel_id = config.notificationDefaultChannelId
            }
            val notification = NotificationCompat.Builder(context, channel_id!!)
                .setContentTitle(title)
                .setTicker(bundle.getString("ticker"))
                .setVisibility(visibility)
                .setPriority(priority)
                .setAutoCancel(bundle.getBoolean("autoCancel", true))
                .setOnlyAlertOnce(bundle.getBoolean("onlyAlertOnce", false))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { // API 24 and higher
                // Restore showing timestamp on Android 7+
                // Source: https://developer.android.com/reference/android/app/Notification.Builder.html#setShowWhen(boolean)
                val showWhen = bundle.getBoolean("showWhen", true)
                notification.setShowWhen(showWhen)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { // API 26 and higher
                // Changing Default mode of notification
                notification.setDefaults(Notification.DEFAULT_LIGHTS)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) { // API 20 and higher
                val group = bundle.getString("group")
                if (group != null) {
                    notification.setGroup(group)
                }
                if (bundle.containsKey("groupSummary") || bundle.getBoolean("groupSummary")) {
                    notification.setGroupSummary(bundle.getBoolean("groupSummary"))
                }
            }
            val numberString = bundle.getString("number")
            if (numberString != null) {
                notification.setNumber(numberString.toInt())
            }

            // Small icon
            var smallIconResId = 0
            val smallIcon = bundle.getString("smallIcon")
            if (smallIcon != null && !smallIcon.isEmpty()) {
                smallIconResId = res.getIdentifier(smallIcon, "drawable", packageName)
                if (smallIconResId == 0) {
                    smallIconResId = res.getIdentifier(smallIcon, "mipmap", packageName)
                }
            } else if (smallIcon == null) {
                smallIconResId = res.getIdentifier("ic_notification", "mipmap", packageName)
            }
            if (smallIconResId == 0) {
                smallIconResId = res.getIdentifier("ic_launcher", "mipmap", packageName)
                if (smallIconResId == 0) {
                    smallIconResId = R.drawable.ic_dialog_info
                }
            }
            notification.setSmallIcon(smallIconResId)

            // Large icon
            if (largeIconBitmap == null) {
                var largeIconResId = 0
                val largeIcon = bundle.getString("largeIcon")
                if (largeIcon != null && !largeIcon.isEmpty()) {
                    largeIconResId = res.getIdentifier(largeIcon, "drawable", packageName)
                    if (largeIconResId == 0) {
                        largeIconResId = res.getIdentifier(largeIcon, "mipmap", packageName)
                    }
                } else if (largeIcon == null) {
                    largeIconResId = res.getIdentifier("ic_launcher", "mipmap", packageName)
                }

                // Before Lolipop there was no large icon for notifications.
                if (largeIconResId != 0 && (largeIcon != null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)) {
                    largeIconBitmap = BitmapFactory.decodeResource(res, largeIconResId)
                }
            }
            if (largeIconBitmap != null) {
                notification.setLargeIcon(largeIconBitmap)
            }

            val message = bundle.getString("message")
            notification.setContentText(message)

            val subText = bundle.getString("subText")
            if (subText != null) {
                notification.setSubText(subText)
            }
            val style: NotificationCompat.Style
            if (bigPictureBitmap != null) {

                // Big large icon
                if (bigLargeIconBitmap == null) {
                    var bigLargeIconResId = 0
                    val bigLargeIcon = bundle.getString("bigLargeIcon")
                    if (bigLargeIcon != null && !bigLargeIcon.isEmpty()) {
                        bigLargeIconResId = res.getIdentifier(bigLargeIcon, "mipmap", packageName)
                        if (bigLargeIconResId != 0) {
                            bigLargeIconBitmap =
                                BitmapFactory.decodeResource(res, bigLargeIconResId)
                        }
                    }
                }
                style = NotificationCompat.BigPictureStyle()
                    .bigPicture(bigPictureBitmap)
                    .setBigContentTitle(title)
                    .setSummaryText(message)
                    .bigLargeIcon(bigLargeIconBitmap)
            } else {
                val bigText = bundle.getString("bigText")
                style = if (bigText == null) {
                    NotificationCompat.BigTextStyle().bigText(message)
                } else {
                    val styledText = HtmlCompat.fromHtml(bigText, HtmlCompat.FROM_HTML_MODE_LEGACY)
                    NotificationCompat.BigTextStyle().bigText(styledText)
                }
            }
            notification.setStyle(style)
            val intent = Intent(context, intentClass)
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            bundle.putBoolean("foreground", isApplicationInForeground)
            bundle.putBoolean("userInteraction", true)
            intent.putExtra("notification", bundle)

            // Add message_id to intent so react-native-firebase/messaging can identify it
            val messageId = bundle.getString("messageId")
            if (messageId != null) {
                intent.putExtra("message_id", messageId)
            }
            var soundUri: Uri? = null
            if (!bundle.containsKey("playSound") || bundle.getBoolean("playSound")) {
                val soundName = bundle.getString("soundName")
                soundUri = getSoundUri(soundName)
                notification.setSound(soundUri)
            }
            if (soundUri == null || Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notification.setSound(null)
            }
            if (bundle.containsKey("ongoing") || bundle.getBoolean("ongoing")) {
                notification.setOngoing(bundle.getBoolean("ongoing"))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                notification.setCategory(NotificationCompat.CATEGORY_CALL)
                val color = bundle.getString("color")
                val defaultColor = config.notificationColor
                if (color != null) {
                    notification.color = Color.parseColor(color)
                } else if (defaultColor != -1) {
                    notification.color = defaultColor
                }
            }
            val notificationID = notificationIdString.toInt()
            val pendingIntent = PendingIntent.getActivity(
                context, notificationID, intent,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notificationManager = notificationManager()
            var vibratePattern = longArrayOf(0)
            if (!bundle.containsKey("vibrate") || bundle.getBoolean("vibrate")) {
                var vibration = if (bundle.containsKey("vibration")) bundle.getDouble("vibration")
                    .toLong() else DEFAULT_VIBRATION
                if (vibration == 0L) vibration = DEFAULT_VIBRATION
                vibratePattern = longArrayOf(0, vibration)
                notification.setVibrate(vibratePattern)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Define the shortcutId
                val shortcutId = bundle.getString("shortcutId")
                if (shortcutId != null) {
                    notification.setShortcutId(shortcutId)
                }
                val timeoutAfter = bundle.getDouble("timeoutAfter").toLong()
                if (timeoutAfter != null && timeoutAfter >= 0) {
                    notification.setTimeoutAfter(timeoutAfter)
                }
            }
            val `when` = bundle.getDouble("when").toLong()
            if (`when` != null && `when` >= 0) {
                notification.setWhen(`when`)
            }
            notification.setUsesChronometer(bundle.getBoolean("usesChronometer", false))
            notification.setChannelId(channel_id)
            notification.setContentIntent(pendingIntent)
            var actionsArray: JSONArray? = null
            try {
                actionsArray =
                    if (bundle.getString("actions") != null) JSONArray(bundle.getString("actions")) else null
            } catch (e: JSONException) {
                Log.e(
                    RNPushNotification.LOG_TAG,
                    "Exception while converting actions to JSON object.",
                    e
                )
            }
            if (actionsArray != null) {
                // No icon for now. The icon value of 0 shows no icon.
                val icon = 0

                // Add button for each actions.
                for (i in 0 until actionsArray.length()) {
                    var action: String
                    action = try {
                        actionsArray.getString(i)
                    } catch (e: JSONException) {
                        Log.e(
                            RNPushNotification.LOG_TAG,
                            "Exception while getting action from actionsArray.",
                            e
                        )
                        continue
                    }
                    val actionIntent = Intent(context, RNPushNotificationActions::class.java)
                    actionIntent.action = "$packageName.ACTION_$i"
                    actionIntent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)

                    // Add "action" for later identifying which button gets pressed.
                    bundle.putString("action", action)
                    actionIntent.putExtra("notification", bundle)
                    actionIntent.setPackage(packageName)
                    if (messageId != null) {
                        intent.putExtra("message_id", messageId)
                    }
                    val flags =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
                    val pendingActionIntent =
                        PendingIntent.getBroadcast(context, notificationID, actionIntent, flags)
                    if (action == "ReplyInput") {
                        //Action with inline reply
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
                            val remoteInput = RemoteInput.Builder(RNPushNotification.KEY_TEXT_REPLY)
                                .setLabel(bundle.getString("reply_placeholder_text"))
                                .build()
                            val replyAction = NotificationCompat.Action.Builder(
                                icon, bundle.getString("reply_button_text"), pendingActionIntent
                            )
                                .addRemoteInput(remoteInput)
                                .setAllowGeneratedReplies(true)
                                .build()
                            notification.addAction(replyAction)
                        } else {
                            // The notification will not have action
                            break
                        }
                    } else {
                        // Add "action" for later identifying which button gets pressed
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                            notification.addAction(
                                NotificationCompat.Action.Builder(
                                    icon,
                                    action,
                                    pendingActionIntent
                                ).build()
                            )
                        } else {
                            notification.addAction(icon, action, pendingActionIntent)
                        }
                    }
                }
            }

            // GROUPING OF NOTIFICATIONS WORK

            val notificationStyle = bundle.getString("style")
            if (notificationStyle == "inbox") {

                val newPerson = InboxPerson(
                    notificationID,
                    NotificationCompat.InboxStyle()
                )
                val updatedPerson = getPerson(newPerson)
                notification.setStyle(updatedPerson.inboxStyle.addLine(message))

            }

            // Remove the notification from the shared preferences once it has been shown
            // to avoid showing the notification again when the phone is rebooted. If the
            // notification is not removed, then every time the phone is rebooted, we will
            // try to reschedule all the notifications stored in shared preferences and since
            // these notifications will be in the past time, they will be shown immediately
            // to the user which we shouldn't do. So, remove the notification from the shared
            // preferences once it has been shown to the user. If it is a repeating notification
            // it will be scheduled again.
            if (scheduledNotificationsPersistence.getString(
                    notificationIdString,
                    null
                ) != null
            ) {
                val editor = scheduledNotificationsPersistence.edit()
                editor.remove(notificationIdString)
                editor.apply()
            }
            if (!(isApplicationInForeground && bundle.getBoolean("ignoreInForeground"))) {
                val info = notification.build()
                info.defaults = info.defaults or Notification.DEFAULT_LIGHTS
                if (bundle.containsKey("tag")) {
                    val tag = bundle.getString("tag")
                    notificationManager.notify(tag, notificationID, info)
                } else {
                    notificationManager.notify(notificationID, info)
                }
            }

            // Can't use setRepeating for recurring notifications because setRepeating
            // is inexact by default starting API 19 and the notifications are not fired
            // at the exact time. During testing, it was found that notifications could
            // late by many minutes.
            scheduleNextNotificationIfRepeating(bundle)
        } catch (e: Exception) {
            Log.e(RNPushNotification.LOG_TAG, "failed to send push notification", e)
        }
    }

    private fun getPerson(person: InboxPerson): InboxPerson {

        val index = personsArray.indexOfFirst { it.id == person.id }

        val updatedPerson: InboxPerson

        if (index != -1) {
            updatedPerson = personsArray[index]
        } else {
            updatedPerson = person
            personsArray.add(updatedPerson)
        }
        return updatedPerson
    }

    private fun scheduleNextNotificationIfRepeating(bundle: Bundle) {
        val repeatType = bundle.getString("repeatType")
        val repeatTime = bundle.getDouble("repeatTime").toLong()
        if (repeatType != null) {
            val fireDate = bundle.getDouble("fireDate").toLong()
            val validRepeatType =
                Arrays.asList("time", "month", "week", "day", "hour", "minute")
                    .contains(repeatType)

            // Sanity checks
            if (!validRepeatType) {
                Log.w(
                    RNPushNotification.LOG_TAG,
                    String.format("Invalid repeatType specified as %s", repeatType)
                )
                return
            }
            if ("time" == repeatType && repeatTime <= 0) {
                Log.w(
                    RNPushNotification.LOG_TAG,
                    "repeatType specified as time but no repeatTime " +
                            "has been mentioned"
                )
                return
            }
            val newFireDate: Long
            if ("time" == repeatType) {
                newFireDate = fireDate + repeatTime
            } else {
                val repeatField = getRepeatField(repeatType)
                val nextEvent = Calendar.getInstance()
                nextEvent.timeInMillis = fireDate
                // Limits repeat time increment to int instead of long
                val increment = if (repeatTime > 0) repeatTime.toInt() else 1
                nextEvent.add(repeatField, increment)
                newFireDate = nextEvent.timeInMillis
            }

            // Sanity check, should never happen
            if (newFireDate != 0L) {
                Log.d(
                    RNPushNotification.LOG_TAG, String.format(
                        "Repeating notification with id %s at time %s",
                        bundle.getString("id"), java.lang.Long.toString(newFireDate)
                    )
                )
                bundle.putDouble("fireDate", newFireDate.toDouble())
                sendNotificationScheduled(bundle)
            }
        }
    }

    private fun getRepeatField(repeatType: String): Int {
        return when (repeatType) {
            "month" -> Calendar.MONTH
            "week" -> Calendar.WEEK_OF_YEAR
            "hour" -> Calendar.HOUR
            "minute" -> Calendar.MINUTE
            "day" -> Calendar.DATE
            else -> Calendar.DATE
        }
    }

    private fun getSoundUri(soundName: String?): Uri {
        var soundName: String? = soundName
        return if (soundName == null || "default".equals(soundName, ignoreCase = true)) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } else {

            // sound name can be full filename, or just the resource name.
            // So the strings 'my_sound.mp3' AND 'my_sound' are accepted
            // The reason is to make the iOS and android javascript interfaces compatible
            val resId: Int
            if (context.resources.getIdentifier(soundName, "raw", context.packageName) != 0) {
                resId = context.resources.getIdentifier(soundName, "raw", context.packageName)
            } else {
                soundName = soundName.substring(0, soundName.lastIndexOf('.'))
                resId = context.resources.getIdentifier(soundName, "raw", context.packageName)
            }
            Uri.parse("android.resource://" + context.packageName + "/" + resId)
        }
    }

    fun clearNotifications() {
        Log.i(RNPushNotification.LOG_TAG, "Clearing alerts from the notification centre")
        val notificationManager = notificationManager()
        notificationManager.cancelAll()
    }

    fun clearNotification(tag: String?, notificationID: Int) {
        Log.i(RNPushNotification.LOG_TAG, "Clearing notification: $notificationID")
        val notificationManager = notificationManager()
        if (tag != null) {
            notificationManager.cancel(tag, notificationID)
        } else {
            notificationManager.cancel(notificationID)
        }
    }

    fun clearDeliveredNotifications(identifiers: ReadableArray) {
        val notificationManager = notificationManager()
        for (index in 0 until identifiers.size()) {
            val id = identifiers.getString(index)
            Log.i(RNPushNotification.LOG_TAG, "Removing notification with id $id")
            notificationManager.cancel(id.toInt())
        }
    }

    /*
         * stay consistent to the return structure in
         * https://facebook.github.io/react-native/docs/pushnotificationios.html#getdeliverednotifications
         * but there is no such thing as a 'userInfo'
         */
    @get:RequiresApi(api = Build.VERSION_CODES.M)
    val deliveredNotifications: WritableArray
        get() {
            val result = Arguments.createArray()
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return result
            }
            val notificationManager = notificationManager()
            val delivered = notificationManager.activeNotifications
            Log.i(
                RNPushNotification.LOG_TAG,
                "Found " + delivered.size + " delivered notifications"
            )
            /*
         * stay consistent to the return structure in
         * https://facebook.github.io/react-native/docs/pushnotificationios.html#getdeliverednotifications
         * but there is no such thing as a 'userInfo'
         */for (notification in delivered) {
                val original = notification.notification
                val extras = original.extras
                val notif = Arguments.createMap()
                notif.putString("identifier", "" + notification.id)
                notif.putString("title", extras.getString(Notification.EXTRA_TITLE))
                notif.putString("body", extras.getString(Notification.EXTRA_TEXT))
                notif.putString("tag", notification.tag)
                notif.putString("group", original.group)
                result.pushMap(notif)
            }
            return result
        }
    val scheduledLocalNotifications: WritableArray
        get() {
            val scheduled = Arguments.createArray()
            val scheduledNotifications = scheduledNotificationsPersistence.all
            for ((_, value) in scheduledNotifications) {
                try {
                    val notification = RNPushNotificationAttributes.fromJson(value.toString())
                    val notificationMap = Arguments.createMap()
                    notificationMap.putString("title", notification.title)
                    notificationMap.putString("message", notification.message)
                    notificationMap.putString("number", notification.number)
                    notificationMap.putDouble("date", notification.fireDate)
                    notificationMap.putString("id", notification.id)
                    notificationMap.putString("repeatInterval", notification.repeatType)
                    notificationMap.putString("soundName", notification.sound)
                    notificationMap.putString("data", notification.userInfo)
                    scheduled.pushMap(notificationMap)
                } catch (e: JSONException) {
                    Log.e(
                        RNPushNotification.LOG_TAG ?: "RNPushNotification ERROR",
                        "Something went wrong"
                    )
                }
            }
            return scheduled
        }

    fun cancelAllScheduledNotifications() {
        Log.i(RNPushNotification.LOG_TAG, "Cancelling all notifications")
        for (id in scheduledNotificationsPersistence.all.keys) {
            cancelScheduledNotification(id)
        }
    }

    fun cancelScheduledNotification(notificationIDString: String) {
        Log.i(RNPushNotification.LOG_TAG, "Cancelling notification: $notificationIDString")

        // remove it from the alarm manger schedule
        val b = Bundle()
        b.putString("id", notificationIDString)
        val pendingIntent = toScheduleNotificationIntent(b)
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
        }
        if (scheduledNotificationsPersistence.contains(notificationIDString)) {
            // remove it from local storage
            val editor = scheduledNotificationsPersistence.edit()
            editor.remove(notificationIDString)
            editor.apply()
        } else {
            Log.w(
                RNPushNotification.LOG_TAG,
                "Unable to find notification $notificationIDString"
            )
        }

        // removed it from the notification center
        val notificationManager = notificationManager()
        try {
            notificationManager.cancel(notificationIDString.toInt())
        } catch (e: Exception) {
            Log.e(
                RNPushNotification.LOG_TAG,
                "Unable to parse Notification ID $notificationIDString",
                e
            )
        }
    }

    private fun notificationManager(): NotificationManager {
        return context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    fun listChannels(): List<String> {
        val channels: MutableList<String> = ArrayList()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return channels
        val manager = notificationManager() ?: return channels
        val listChannels = manager.notificationChannels
        for (channel in listChannels) {
            channels.add(channel.id)
        }
        return channels
    }

    fun channelBlocked(channel_id: String?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = notificationManager() ?: return false
        val channel = manager.getNotificationChannel(channel_id) ?: return false
        return NotificationManager.IMPORTANCE_NONE == channel.importance
    }

    fun channelExists(channel_id: String?): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = notificationManager() ?: return false
        val channel = manager.getNotificationChannel(channel_id)
        return channel != null
    }

    fun deleteChannel(channel_id: String?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager() ?: return
        manager.deleteNotificationChannel(channel_id)
    }

    private fun checkOrCreateChannel(
        manager: NotificationManager?,
        channel_id: String?,
        channel_name: String?,
        channel_description: String?,
        soundUri: Uri?,
        importance: Int,
        vibratePattern: LongArray?
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        if (manager == null) return false
        var channel = manager.getNotificationChannel(channel_id ?: "CHANNEL_ID")
        if (channel == null && channel_name != null && channel_description != null ||
            channel != null &&
            (channel_name != null && channel_name != channel.name ||
                    channel_description != null && channel_description != channel.description)
        ) {
            // If channel doesn't exist create a new one.
            // If channel name or description is updated then update the existing channel.
            channel = NotificationChannel(channel_id, channel_name, importance)
            channel.description = channel_description
            channel.enableLights(true)
            channel.enableVibration(vibratePattern != null)
            channel.vibrationPattern = vibratePattern
            if (soundUri != null) {
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .build()
                channel.setSound(soundUri, audioAttributes)
            } else {
                channel.setSound(null, null)
            }
            manager.createNotificationChannel(channel)
            return true
        }
        return false
    }

    fun createChannel(channelInfo: ReadableMap): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val channelId = channelInfo.getString("channelId")
        val channelName = channelInfo.getString("channelName")
        val channelDescription =
            if (channelInfo.hasKey("channelDescription")) channelInfo.getString("channelDescription") else ""
        val playSound = !channelInfo.hasKey("playSound") || channelInfo.getBoolean("playSound")
        val soundName =
            if (channelInfo.hasKey("soundName")) channelInfo.getString("soundName") else "default"
        val importance =
            if (channelInfo.hasKey("importance")) channelInfo.getInt("importance") else 4
        val vibrate = channelInfo.hasKey("vibrate") && channelInfo.getBoolean("vibrate")
        val vibratePattern = if (vibrate) longArrayOf(0, DEFAULT_VIBRATION) else null
        val manager = notificationManager()
        val soundUri = if (playSound) getSoundUri(soundName) else null
        return checkOrCreateChannel(
            manager,
            channelId,
            channelName,
            channelDescription,
            soundUri,
            importance,
            vibratePattern
        )
    }

    val isApplicationInForeground: Boolean
        get() {
            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val processInfos = activityManager.runningAppProcesses
            if (processInfos != null) {
                for (processInfo in processInfos) {
                    if (processInfo.processName == context.packageName && processInfo.importance == RunningAppProcessInfo.IMPORTANCE_FOREGROUND && processInfo.pkgList.size > 0) {
                        return true
                    }
                }
            }
            return false
        }

    companion object {
        const val PREFERENCES_KEY = "rn_push_notification"
        private const val DEFAULT_VIBRATION = 300L
    }

    init {
        this.context = context
        config = RNPushNotificationConfig(context)
        scheduledNotificationsPersistence =
            context.getSharedPreferences(PREFERENCES_KEY, Context.MODE_PRIVATE)
    }
}