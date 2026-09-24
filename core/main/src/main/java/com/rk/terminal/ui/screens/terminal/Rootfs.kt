package com.rk.terminal.ui.screens.terminal

import android.content.Context
import android.os.Build
import androidx.compose.runtime.mutableStateOf
import com.rk.libcommons.child
import com.rk.libcommons.localDir
import com.rk.libcommons.toast
import com.rk.settings.Settings
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

enum class ExecMode(val value: Int) {
    CHROOT(0),
    PROOT(1);

    companion object {
        fun fromInt(v: Int): ExecMode? = entries.firstOrNull { it.value == v }
    }
}

object Rootfs {
    private const val WOLFI_RELEASE = "https://github.com/leloush-x/wolfi-os-rootfs/releases/download/wolfi-latest"

    var isInstalled = mutableStateOf(false)
    var execMode = mutableStateOf(ExecMode.fromInt(Settings.exec_mode))

    @Volatile
    private var wolfiDownloading = false

    fun setExecMode(mode: ExecMode) {
        execMode.value = mode
        Settings.exec_mode = mode.value
    }

    fun checkInstallation(context: Context) {
        isInstalled.value = isRootfsInstalled(context)
    }

    fun isRootfsInstalled(context: Context): Boolean {
        val alpineDir = context.localDir().child("alpine")
        val isExtracted = alpineDir.exists() && (alpineDir.list()?.any { it != "root" && it != "tmp" } == true)
        val isArchivePresent = context.filesDir.child("alpine.tar.gz").exists()
        return isExtracted || isArchivePresent
    }

    fun downloadWolfi(context: Context) {
        if (wolfiDownloading || isWolfiDownloaded(context)) return
        val abi = Build.SUPPORTED_ABIS.firstOrNull { it == "arm64-v8a" || it == "x86_64" }
        if (abi == null) {
            toast("Wolfi requires arm64 or x86_64, use Alpine on this device")
            return
        }
        val arch = if (abi == "arm64-v8a") "aarch64" else "x86_64"
        wolfiDownloading = true
        toast("Downloading Wolfi rootfs (~24 MB)")
        Thread {
            val tmp = context.filesDir.child("wolfi.tar.gz.part")
            try {
                var url = "$WOLFI_RELEASE/wolfi-rootfs-$arch.tar.gz"
                var redirects = 0
                while (true) {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 60000
                    conn.instanceFollowRedirects = false
                    when (val code = conn.responseCode) {
                        in 200..299 -> {
                            conn.inputStream.use { input ->
                                FileOutputStream(tmp).use { output -> input.copyTo(output) }
                            }
                            conn.disconnect()
                            break
                        }
                        301, 302, 303, 307, 308 -> {
                            url = conn.getHeaderField("Location") ?: throw IOException("Missing redirect location")
                            conn.disconnect()
                            if (++redirects > 5) throw IOException("Too many redirects")
                        }
                        else -> throw IOException("HTTP $code")
                    }
                }
                val target = context.filesDir.child("wolfi.tar.gz")
                if (!tmp.renameTo(target)) {
                    tmp.copyTo(target, overwrite = true)
                    tmp.delete()
                }
                toast("Wolfi rootfs downloaded")
            } catch (e: Exception) {
                tmp.delete()
                toast("Wolfi download failed: ${e.message}")
            } finally {
                wolfiDownloading = false
            }
        }.start()
    }

    fun isWolfiDownloaded(context: Context): Boolean {
        val file = context.filesDir.child("wolfi.tar.gz")
        return file.exists() && file.length() > 0
    }
}
