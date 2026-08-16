/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2025 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.ui.main.settings

import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.reloadPinyinDict
import org.fcitx.fcitx5.android.data.pinyin.PinyinDictManager
import org.fcitx.fcitx5.android.data.pinyin.pinyinDictionaryImportTarget
import org.fcitx.fcitx5.android.data.pinyin.dict.BuiltinDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.LibIMEDictionary
import org.fcitx.fcitx5.android.data.pinyin.dict.PinyinDictionary
import org.fcitx.fcitx5.android.ui.common.BaseDynamicListUi
import org.fcitx.fcitx5.android.ui.common.OnItemChangedListener
import org.fcitx.fcitx5.android.ui.main.EditDeleteMenuProvider
import org.fcitx.fcitx5.android.ui.main.MainViewModel
import org.fcitx.fcitx5.android.ui.main.MainViewModel.ButtonMode
import org.fcitx.fcitx5.android.utils.requireInputStream
import org.fcitx.fcitx5.android.utils.NaiveDustman
import org.fcitx.fcitx5.android.utils.importErrorDialog
import org.fcitx.fcitx5.android.utils.lazyRoute
import org.fcitx.fcitx5.android.utils.notificationManager
import org.fcitx.fcitx5.android.utils.queryFileName
import org.fcitx.fcitx5.android.utils.removeIfExists
import org.fcitx.fcitx5.android.utils.toast
import timber.log.Timber

class PinyinDictionaryFragment : Fragment(), OnItemChangedListener<PinyinDictionary> {

    private val args by lazyRoute<SettingsRoute.PinyinDict>()

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var launcher: ActivityResultLauncher<String>

    private lateinit var pendingRouteImport: PendingPinyinDictionaryRouteImport

    private val dustman = NaiveDustman<Boolean>()

    private var uiInitialized = false

    private val ui: BaseDynamicListUi<PinyinDictionary> by lazy {
        object : BaseDynamicListUi<PinyinDictionary>(
            requireContext(),
            Mode.Custom(),
            PinyinDictManager.listDictionaries(),
            initCheckBox = { entry ->
                if (entry is LibIMEDictionary) {
                    isChecked = entry.isEnabled
                    setOnCheckedChangeListener { _, isChecked ->
                        if (if (isChecked) entry.enable() else entry.disable()) {
                            ui.updateItem(ui.indexItem(entry), entry)
                        } else {
                            this.isChecked = entry.isEnabled
                        }
                    }
                } else {
                    isChecked = true
                    isEnabled = false
                }
            }
        ) {
            init {
                enableUndo = false
                addTouchCallback()
                // since FAB is always shown in this fragment,
                // set shouldShowFab to true to hide it when entering multi select mode
                shouldShowFab = true
                fab.setOnClickListener {
                    launcher.launch("*/*")
                }
                setViewModel(viewModel)
                removable = { e -> e !is BuiltinDictionary }
            }

            override fun updateFAB() {
                // do nothing
            }

            override fun showEntry(x: PinyinDictionary): String = x.name
        }.also {
            it.addOnItemChangedListener(this)
            uiInitialized = true
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val restoredRouteImport = savedInstanceState?.containsKey(PENDING_ROUTE_IMPORT_URI) == true
        pendingRouteImport = PendingPinyinDictionaryRouteImport(
            if (restoredRouteImport) {
                savedInstanceState.getString(PENDING_ROUTE_IMPORT_URI)
            } else {
                args.uri
            }
        )
        registerLauncher()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(PENDING_ROUTE_IMPORT_URI, pendingRouteImport.uri)
        super.onSaveInstanceState(outState)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        createNotificationChannel()
        resetDustman()
        return ui.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        pendingRouteImport.start()?.let { uri ->
            importFromUri(Uri.parse(uri)) { shouldRetry ->
                pendingRouteImport.finish(shouldRetry)
            }
        }
        super.onViewCreated(view, savedInstanceState)
        viewModel.toolbarButton.value =
            if (ui.entries.isNotEmpty()) ButtonMode.EDIT else ButtonMode.NONE
        requireActivity().addMenuProvider(
            EditDeleteMenuProvider(
                buttonMode = viewModel.toolbarButton,
                editButtonAction = { ui.enterMultiSelect(requireActivity().onBackPressedDispatcher) },
                deleteButtonAction = { ui.deleteSelected(); ui.exitMultiSelect() },
                menuHost = requireActivity(),
                lifecycleOwner = viewLifecycleOwner,
            ),
            viewLifecycleOwner,
            Lifecycle.State.STARTED
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getText(R.string.pinyin_dict),
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = CHANNEL_ID }
            requireContext().notificationManager.createNotificationChannel(channel)
        }
    }

    private fun registerLauncher() {
        launcher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null)
                importFromUri(uri)
        }
    }

    private fun importFromUri(
        uri: Uri,
        onFinished: ((shouldRetry: Boolean) -> Unit)? = null,
    ) {
        val ctx = requireContext()
        val cr = ctx.contentResolver
        val nm = ctx.notificationManager
        lifecycleScope.launch {
            val id = IMPORT_ID++
            var shouldRetry = false
            try {
                val fileName = cr.queryFileName(uri) ?: return@launch
                val importTarget = pinyinDictionaryImportTarget(fileName)
                if (importTarget == null) {
                    ctx.importErrorDialog(R.string.invalid_dict)
                    return@launch
                }
                val entryName = importTarget.entryName
                if (ui.entries.any { it.name == entryName }) {
                    ctx.importErrorDialog(R.string.dict_already_exists)
                    return@launch
                }
                NotificationCompat.Builder(ctx, CHANNEL_ID)
                    .setSmallIcon(R.drawable.ic_baseline_library_books_24)
                    .setContentTitle(getString(R.string.pinyin_dict))
                    .setContentText("${getString(R.string.importing)} $entryName")
                    .setOngoing(true)
                    .setProgress(100, 0, true)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .build().let { nm.notify(id, it) }
                val imported = withContext(Dispatchers.IO) {
                    val inputStream = cr.requireInputStream(uri)
                    PinyinDictManager.importFromInputStream(inputStream, importTarget.sourceFileName)
                        .getOrThrow()
                }
                ui.addItem(item = imported)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    shouldRetry = true
                    throw e
                }
                ctx.importErrorDialog(e)
            } finally {
                try {
                    nm.cancel(id)
                } finally {
                    onFinished?.invoke(shouldRetry)
                }
            }
        }
    }

    private fun reloadDict() {
        if (!dustman.dirty) return
        resetDustman()
        val context = requireContext().applicationContext
        val nm = context.notificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_baseline_library_books_24)
            .setContentTitle(context.getString(R.string.pinyin_dict))
            .setContentText(context.getString(R.string.reloading))
            .setOngoing(true)
            .setProgress(100, 0, true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val connection = viewModel.fcitx
        viewModel.viewModelScope.launch {
            dustman.runCatchingSave {
                reloadMutex.withLock {
                    val id = RELOAD_ID++
                    try {
                        nm.notify(id, notification)
                        connection.runOnReady {
                            reloadPinyinDict()
                        }
                    } finally {
                        nm.cancel(id)
                    }
                }
            }.onFailure {
                Timber.e(it, "Failed to reload pinyin dictionaries")
                context.toast(it)
            }
        }
    }

    private fun resetDustman() {
        dustman.reset(ui.entries.mapNotNull { it as? LibIMEDictionary }
            .associate { it.name to it.isEnabled })
    }

    override fun onItemAdded(idx: Int, item: PinyinDictionary) {
        item as LibIMEDictionary
        dustman.addOrUpdate(item.name, item.isEnabled)
    }

    private fun removeItems(indexed: List<Pair<Int, PinyinDictionary>>) {
        applyFileBackedRemovals(
            indexed,
            remove = { item ->
                check(item is LibIMEDictionary)
                item.file.removeIfExists()
            },
            onRemoved = { item -> dustman.remove(item.name) },
            restore = ui::addItem,
        )?.let(requireContext()::toast)
    }

    override fun onItemRemoved(idx: Int, item: PinyinDictionary) {
        removeItems(listOf(idx to item))
    }

    override fun onItemRemovedBatch(indexed: List<Pair<Int, PinyinDictionary>>) {
        removeItems(indexed)
    }

    override fun onItemUpdated(idx: Int, old: PinyinDictionary, new: PinyinDictionary) {
        new as LibIMEDictionary
        dustman.addOrUpdate(new.name, new.isEnabled)
    }

    override fun onStop() {
        reloadDict()
        if (uiInitialized) {
            ui.exitMultiSelect()
        }
        super.onStop()
    }

    override fun onDestroy() {
        if (uiInitialized) {
            ui.removeItemChangedListener()
        }
        super.onDestroy()
    }

    companion object {
        private val reloadMutex = Mutex()
        private var RELOAD_ID = 0
        private var IMPORT_ID = 0
        private const val PENDING_ROUTE_IMPORT_URI = "pending_route_import_uri"
        const val CHANNEL_ID = "pinyin_dict"
    }
}
