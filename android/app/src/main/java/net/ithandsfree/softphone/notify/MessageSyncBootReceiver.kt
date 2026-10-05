package net.ithandsfree.softphone.notify

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import net.ithandsfree.softphone.sip.IncomingCallNotifier
import net.ithandsfree.softphone.sip.SipRegistrationService

/** Re-arm WorkManager + message-sync / SIP FGS after reboot or APK update. */
class MessageSyncBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        MessageNotifier.ensureChannel(context)
        MessageSyncService.ensureSyncChannel(context)
        IncomingCallNotifier.ensureChannel(context)
        SipRegistrationService.ensureChannel(context)
        SmsPollWorker.schedule(context)
        SmsPollWorker.kick(context)
        MessageSyncService.reconcile(context)
        SipRegistrationService.reconcile(context)
    }
}
