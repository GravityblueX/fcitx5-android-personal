/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main

import android.os.Bundle
import android.os.Debug
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts.CreateDocument
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.data.DataManager
import org.fcitx.fcitx5.android.daemon.FcitxDaemon
import org.fcitx.fcitx5.android.data.clipboard.ClipboardManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.ui.common.PaddingPreferenceFragment
import org.fcitx.fcitx5.android.ui.main.modified.MySwitchPreference
import org.fcitx.fcitx5.android.utils.requireOutputStream
import org.fcitx.fcitx5.android.utils.addPreference
import org.fcitx.fcitx5.android.utils.iso8601UTCDateTime
import org.fcitx.fcitx5.android.utils.removeIfExists
import org.fcitx.fcitx5.android.utils.runWithCleanup
import org.fcitx.fcitx5.android.utils.setupForest
import org.fcitx.fcitx5.android.utils.startActivity
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber
import java.io.File

class DeveloperFragment : PaddingPreferenceFragment() {

    private lateinit var pendingHeapDump: PendingHeapDump
    private lateinit var launcher: ActivityResultLauncher<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingHeapDump = PendingHeapDump(savedInstanceState?.getString(PENDING_HEAP_DUMP_PATH))
        launcher = registerForActivityResult(CreateDocument("application/octet-stream")) { uri ->
            val hprofFile = pendingHeapDump.consume() ?: return@registerForActivityResult
            if (uri == null) {
                hprofFile.removeIfExists().onFailure { failure ->
                    Timber.w(failure, "Failed to remove cancelled heap dump: ${hprofFile.path}")
                }
                return@registerForActivityResult
            }
            val ctx = requireContext()
            lifecycleScope.launch {
                try {
                    runWithCleanup(
                        cleanup = {
                            withContext(NonCancellable + Dispatchers.IO) {
                                hprofFile.removeIfExists()
                            }
                        },
                        onCleanupFailure = { failure ->
                            Timber.w(failure, "Failed to remove exported heap dump: ${hprofFile.path}")
                        },
                    ) {
                        withContext(Dispatchers.IO) {
                            ctx.contentResolver.requireOutputStream(uri).use { o ->
                                hprofFile.inputStream().use { i -> i.copyTo(o) }
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Timber.e(e, "Failed to export heap dump")
                    ctx.toast(e)
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PENDING_HEAP_DUMP_PATH, pendingHeapDump.path)
        super.onSaveInstanceState(outState)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceScreen = preferenceManager.createPreferenceScreen(requireContext()).apply {
            addPreference(R.string.real_time_logs) {
                startActivity<LogActivity>()
            }
            addPreference(MySwitchPreference(context).apply {
                key = AppPrefs.getInstance().internal.verboseLog.key
                setTitle(R.string.verbose_log)
                setDefaultValue(false)
                isIconSpaceReserved = false
                isSingleLineTitle = false
                setOnPreferenceChangeListener { _, newValue ->
                    val verbose = (newValue as? Boolean) == true
                    Timber.setupForest(verbose)
                    FcitxDaemon.getFirstConnectionOrNull()?.runIfReady {
                        setLogRule(verbose)
                    }
                    true
                }
            })
            addPreference(MySwitchPreference(context).apply {
                key = AppPrefs.getInstance().internal.editorInfoInspector.key
                setTitle(R.string.editor_info_inspector)
                setDefaultValue(false)
                isIconSpaceReserved = false
                isSingleLineTitle = false
            })
            addPreference(R.string.restart_fcitx_instance) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.restart_fcitx_instance)
                    .setMessage(R.string.restart_fcitx_instance_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            FcitxDaemon.restartFcitx()
                            context.toast(R.string.done)
                        }
                    }
                    .show()
            }
            addPreference(R.string.delete_and_sync_data) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.delete_and_sync_data)
                    .setMessage(R.string.delete_and_sync_data_message)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            try {
                                withContext(Dispatchers.IO) {
                                    DataManager.deleteAndSync()
                                }
                                context.toast(R.string.synced)
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                context.toast(e)
                            }
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            addPreference(R.string.clear_clb_db) {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.clear_clb_db)
                    .setMessage(R.string.clear_clp_db_confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        lifecycleScope.launch {
                            withContext(Dispatchers.IO) {
                                ClipboardManager.nukeTable()
                            }
                            context.toast(R.string.done)
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            addPreference(R.string.capture_heap_dump) {
                val fileName = "${context.packageName}_${iso8601UTCDateTime()}.hprof"
                var hprofFile: File? = null
                var registered = false
                var supersededCleanupFailure: Throwable? = null
                try {
                    val outputFile = File.createTempFile("heap-dump-", ".hprof", context.cacheDir)
                    hprofFile = outputFile
                    System.gc()
                    Debug.dumpHprofData(outputFile.absolutePath)
                    val superseded = pendingHeapDump.begin(outputFile)
                    registered = true
                    superseded
                        ?.takeIf { it != outputFile }
                        ?.removeIfExists()
                        ?.onFailure { failure ->
                            supersededCleanupFailure = failure
                            Timber.w(failure, "Failed to remove superseded heap dump")
                        }
                    launcher.launch(fileName)
                } catch (e: Exception) {
                    if (registered) pendingHeapDump.consume()
                    supersededCleanupFailure
                        ?.takeIf { cleanupFailure -> cleanupFailure !== e }
                        ?.let(e::addSuppressed)
                    hprofFile?.removeIfExists()?.onFailure(e::addSuppressed)
                    Timber.e(e, "Failed to capture heap dump")
                    context.toast(e)
                }
            }
        }
    }

    companion object {
        private const val PENDING_HEAP_DUMP_PATH = "pending_heap_dump_path"
    }

}
