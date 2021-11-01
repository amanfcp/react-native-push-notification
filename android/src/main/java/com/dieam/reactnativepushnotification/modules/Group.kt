package com.dieam.reactnativepushnotification.modules

import androidx.core.app.NotificationCompat
import androidx.core.app.Person

class Group (
    var id: Int,
    var name: String,
    var messageStyle: NotificationCompat.MessagingStyle,
    var messagePersons: ArrayList<MessagePerson>
)