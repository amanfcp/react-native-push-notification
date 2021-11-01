package com.dieam.reactnativepushnotification.modules

import androidx.core.app.NotificationCompat
import androidx.core.app.Person

class MessagePerson (
    var id: Int,
    var person: Person,
    var messageStyle: NotificationCompat.MessagingStyle?
)