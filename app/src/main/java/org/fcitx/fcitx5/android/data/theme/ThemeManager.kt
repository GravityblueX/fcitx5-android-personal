/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2023 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.data.theme

import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreferenceProvider
import org.fcitx.fcitx5.android.data.theme.ThemeManager.activeTheme
import org.fcitx.fcitx5.android.utils.WeakHashSet
import org.fcitx.fcitx5.android.utils.appContext
import org.fcitx.fcitx5.android.utils.isDarkMode
import java.io.File
import java.io.InputStream

object ThemeManager {

    fun interface OnThemeChangeListener {
        fun onThemeChange(theme: Theme)
    }

    val BuiltinThemes = listOf(
        ThemePreset.MaterialLight,
        ThemePreset.MaterialDark,
        ThemePreset.PixelLight,
        ThemePreset.PixelDark,
        ThemePreset.NordLight,
        ThemePreset.NordDark,
        ThemePreset.DeepBlue,
        ThemePreset.Monokai,
        ThemePreset.AMOLEDBlack,
    )

    val DefaultTheme = ThemePreset.PixelDark

    private val customThemes = ThemeCatalog<Theme>(
        ThemeFilesManager.listThemes(),
        Theme::name,
        listOf(ThemeMonet.getLight(), ThemeMonet.getDark()),
    )

    fun getTheme(name: String) =
        customThemes.find(name) ?: BuiltinThemes.find { it.name == name }

    fun getAllThemes() = customThemes.snapshot() + BuiltinThemes

    fun refreshThemes() {
        val activeThemeChanged = runThemeManagerMutation {
            customThemes.replaceAll(ThemeFilesManager.listThemes())
            updateActiveTheme(evaluateActiveTheme())
        }
        if (activeThemeChanged) fireChange()
    }

    /**
     * [backing property](https://kotlinlang.org/docs/properties.html#backing-properties)
     * of [activeTheme]; holds the [Theme] object currently in use
     */
    @Volatile
    private lateinit var _activeTheme: Theme

    var activeTheme: Theme
        get() = _activeTheme
        private set(value) {
            val changed = runThemeManagerMutation { updateActiveTheme(value) }
            if (changed) fireChange()
        }

    @Volatile
    private var isDarkMode = false

    private val onChangeListeners = WeakHashSet<OnThemeChangeListener>()
    private val onChangeListenersLock = Any()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun addOnChangedListener(listener: OnThemeChangeListener) {
        synchronized(onChangeListenersLock) { onChangeListeners.add(listener) }
    }

    fun removeOnChangedListener(listener: OnThemeChangeListener) {
        synchronized(onChangeListenersLock) { onChangeListeners.remove(listener) }
    }

    private fun fireChange() {
        mainHandler.post {
            val theme = activeTheme
            synchronized(onChangeListenersLock) { onChangeListeners.toList() }
                .forEach { it.onThemeChange(theme) }
        }
    }

    val prefs = AppPrefs.getInstance().registerProvider(::ThemePrefs)

    fun saveTheme(theme: Theme.Custom) {
        saveTheme(theme) { ThemeFilesManager.saveThemeFiles(theme) }
    }

    fun saveTheme(
        theme: Theme.Custom,
        pendingCroppedImage: File,
        replaceExistingImage: Boolean,
    ) {
        saveTheme(theme) {
            ThemeFilesManager.saveThemeFiles(
                theme,
                pendingCroppedImage,
                replaceExistingImage,
            )
        }
    }

    private fun saveTheme(theme: Theme.Custom, persist: () -> Unit) {
        val activeThemeChanged = runThemeManagerMutation {
            persist()
            applyPersistedTheme(theme)
        }
        if (activeThemeChanged) fireChange()
    }

    fun importTheme(src: InputStream): Result<Triple<Boolean, Theme.Custom, Boolean>> {
        var activeThemeChanged = false
        val result = runThemeManagerMutation {
            ThemeFilesManager.importTheme(src).onSuccess { (_, theme) ->
                activeThemeChanged = applyPersistedTheme(theme)
            }
        }
        if (activeThemeChanged) fireChange()
        return result
    }

    private fun applyPersistedTheme(theme: Theme.Custom): Boolean {
        customThemes.upsert(theme)
        return updateActiveTheme(evaluateActiveTheme())
    }

    fun deleteTheme(name: String): Result<Unit> {
        var activeThemeChanged = false
        val result = runThemeManagerMutation {
            val theme = customThemes.find(name) as? Theme.Custom
                ?: return@runThemeManagerMutation Result.success(Unit)
            ThemeFilesManager.deleteThemeFiles(theme).onSuccess {
                customThemes.remove(name)
                if (activeTheme.name == name) {
                    activeThemeChanged = updateActiveTheme(evaluateActiveTheme())
                }
            }
        }
        if (activeThemeChanged) fireChange()
        return result
    }

    fun setNormalModeTheme(theme: Theme) {
        // `normalModeTheme.setValue(theme)` would trigger `onThemePrefsChange` listener,
        // which calls `fireChange()`.
        // `activateTheme`'s setter would also trigger `fireChange()` when theme actually changes.
        // write to backing property directly to avoid unnecessary `fireChange()`
        runThemeManagerMutation {
            _activeTheme = theme
            prefs.normalModeTheme.setValue(theme)
        }
    }

    private fun updateActiveTheme(theme: Theme): Boolean {
        if (_activeTheme == theme) return false
        _activeTheme = theme
        return true
    }

    private fun evaluateActiveTheme(): Theme {
        return if (prefs.followSystemDayNightTheme.getValue()) {
            if (isDarkMode) prefs.darkModeTheme else prefs.lightModeTheme
        } else {
            prefs.normalModeTheme
        }.getValue()
    }

    @Keep
    private val onThemePrefsChange = ManagedPreferenceProvider.OnChangeListener { key ->
        if (prefs.dayNightModePrefNames.contains(key)) {
            activeTheme = evaluateActiveTheme()
        } else {
            fireChange()
        }
    }

    fun init(configuration: Configuration) {
        runThemeManagerMutation {
            isDarkMode = configuration.isDarkMode()
            // fire all `OnThemeChangedListener`s on theme preferences change
            prefs.registerOnChangeListener(onThemePrefsChange)
            _activeTheme = evaluateActiveTheme()
        }
    }

    fun onSystemPlatteChange(newConfig: Configuration) {
        val activeThemeChanged = runThemeManagerMutation {
            isDarkMode = newConfig.isDarkMode()
            customThemes.replaceSupplemental(
                listOf(ThemeMonet.getLight(), ThemeMonet.getDark())
            )
            // `ManagedThemePreference` finds a theme with same name in `getAllThemes()`
            // thus `evaluateActiveTheme()` should be called after updating `monetThemes`
            updateActiveTheme(evaluateActiveTheme())
        }
        if (activeThemeChanged) fireChange()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun syncToDeviceEncryptedStorage() {
        val ctx = appContext.createDeviceProtectedStorageContext()
        val sp = PreferenceManager.getDefaultSharedPreferences(ctx)
        sp.edit {
            prefs.managedPreferences.forEach {
                it.value.putValueTo(this@edit)
            }
        }
    }

}
