package com.peter.mutter.updater

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

class UpdateInstallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val tag = "MutterUpdater"
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
        val vc = intent.getIntExtra(EXTRA_VERSION_CODE, -1)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = @Suppress("DEPRECATION")
                intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm == null) {
                    Log.w(tag, "pending user action but EXTRA_INTENT missing")
                    return
                }
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(confirm)
                Log.i(tag, "launched system installer for v$vc")
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i(tag, "install succeeded for v$vc")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w(tag, "install status=$status msg=$msg vc=$vc")
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_COMPLETE = "com.peter.mutter.updater.INSTALL_COMPLETE"
        const val EXTRA_VERSION_CODE = "versionCode"
    }
}
