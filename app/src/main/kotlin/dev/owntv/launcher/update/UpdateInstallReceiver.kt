package dev.owntv.launcher.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.owntv.launcher.OwnTvApp

/** Receives the installer's status for an update session, and the new version's first start, for the [Updater]. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val updater = (context.applicationContext as OwnTvApp).container.updater
        if (intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) updater.onPackageReplaced() else updater.onInstallStatus(intent)
    }
}
