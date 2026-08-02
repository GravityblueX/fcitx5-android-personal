/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2026 Fcitx5 for Android Contributors
 */
package org.fcitx.fcitx5.android.input.keyboard

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import androidx.annotation.Keep
import androidx.core.view.allViews
import androidx.core.view.ViewCompat
import org.fcitx.fcitx5.android.R
import org.fcitx.fcitx5.android.core.InputMethodEntry
import org.fcitx.fcitx5.android.core.KeyState
import org.fcitx.fcitx5.android.core.KeyStates
import org.fcitx.fcitx5.android.data.prefs.AppPrefs
import org.fcitx.fcitx5.android.data.prefs.ManagedPreference
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.popup.PopupAction
import org.fcitx.fcitx5.android.input.picker.PickerWindow
import splitties.views.imageResource

@SuppressLint("ViewConstructor")
class TextKeyboard(
    context: Context,
    theme: Theme,
    bottomRow: List<KeyDef> = BottomRow
) : BaseKeyboard(context, theme, Layout.dropLast(1) + listOf(bottomRow)) {

    enum class CapsState { None, Once, Lock }

    companion object {
        const val Name = "Text"
        const val EmailName = "TextEmail"
        const val UrlName = "TextUrl"

        val BottomRow: List<KeyDef> = listOf(
            LayoutSwitchKey(
                "?123",
                "",
                popup = arrayOf(
                    KeyDef.Popup.Menu(
                        arrayOf(
                            KeyDef.Popup.Menu.Item(
                                "Number keyboard",
                                R.drawable.ic_number_pad,
                                KeyAction.LayoutSwitchAction(NumberKeyboard.Name)
                            ),
                            KeyDef.Popup.Menu.Item(
                                "Symbol keyboard",
                                R.drawable.ic_baseline_emoji_symbols_24,
                                KeyAction.LayoutSwitchAction(PickerWindow.Key.Symbol.name)
                            ),
                            KeyDef.Popup.Menu.Item(
                                "Left-handed mode",
                                R.drawable.ic_material_mobile_hand_left_24,
                                KeyAction.OneHandedModeAction(OneHandedMode.Left)
                            )
                        )
                    )
                )
            ),
            CommaKey(0.1f, KeyDef.Appearance.Variant.Alternative),
            LanguageKey(),
            SpaceKey(),
            SymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative),
            ReturnKey()
        )

        val EmailBottomRow: List<KeyDef> = BottomRow.toMutableList().apply {
            this[1] = SymbolKey("@", 0.1f, KeyDef.Appearance.Variant.Alternative)
            this[4] = DomainSymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative)
        }

        val UrlBottomRow: List<KeyDef> = BottomRow.toMutableList().apply {
            this[1] = SymbolKey("/", 0.1f, KeyDef.Appearance.Variant.Alternative)
            this[4] = DomainSymbolKey(".", 0.1f, KeyDef.Appearance.Variant.Alternative)
        }

        val Layout: List<List<KeyDef>> = listOf(
            listOf(
                AlphabetKey("Q", "1"),
                AlphabetKey("W", "2"),
                AlphabetKey("E", "3"),
                AlphabetKey("R", "4"),
                AlphabetKey("T", "5"),
                AlphabetKey("Y", "6"),
                AlphabetKey("U", "7"),
                AlphabetKey("I", "8"),
                AlphabetKey("O", "9"),
                AlphabetKey("P", "0")
            ),
            listOf(
                AlphabetKey("A", "@"),
                AlphabetKey("S", "*"),
                AlphabetKey("D", "+"),
                AlphabetKey("F", "-"),
                AlphabetKey("G", "="),
                AlphabetKey("H", "/"),
                AlphabetKey("J", "#"),
                AlphabetKey("K", "("),
                AlphabetKey("L", ")")
            ),
            listOf(
                CapsKey(),
                AlphabetKey("Z", "'"),
                AlphabetKey("X", ":"),
                AlphabetKey("C", "\""),
                AlphabetKey("V", "?"),
                AlphabetKey("B", "!"),
                AlphabetKey("N", "~"),
                AlphabetKey("M", "\\"),
                BackspaceKey()
            ),
            BottomRow
        )
    }

    val caps: ImageKeyView by lazy { findViewById(R.id.button_caps) }
    val backspace: ImageKeyView by lazy { findViewById(R.id.button_backspace) }
    val quickphrase: ImageKeyView by lazy { findViewById(R.id.button_quickphrase) }
    val lang: ImageKeyView by lazy { findViewById(R.id.button_lang) }
    val space: TextKeyView by lazy { findViewById(R.id.button_space) }
    val `return`: ImageKeyView by lazy { findViewById(R.id.button_return) }

    private val showLangSwitchKey = AppPrefs.getInstance().keyboard.showLangSwitchKey

    @Keep
    private val showLangSwitchKeyListener = ManagedPreference.OnChangeListener<Boolean> { _, v ->
        updateLangSwitchKey(v)
    }

    private val keepLettersUppercase = AppPrefs.getInstance().keyboard.keepLettersUppercase

    @Keep
    private val keepLettersUppercaseListener = ManagedPreference.OnChangeListener<Boolean> { _, _ ->
        updateAlphabetKeys()
    }

    private val singleTapCapsLock by AppPrefs.getInstance().keyboard.singleTapCapsLock

    init {
        updateLangSwitchKey(showLangSwitchKey.getValue())
        showLangSwitchKey.registerOnChangeListener(showLangSwitchKeyListener)
        keepLettersUppercase.registerOnChangeListener(keepLettersUppercaseListener)
    }

    private val textKeys: List<TextKeyView> by lazy {
        allViews.filterIsInstance(TextKeyView::class.java).toList()
    }

    private var capsState: CapsState = CapsState.None
    private var autoCapsMode = AutoCapsMode.None
    private var autoCapsApplied = false
    private var autoCapsSuppressed = false

    private fun transformAlphabet(c: String): String {
        return when (capsState) {
            CapsState.None -> c.lowercase()
            else -> c.uppercase()
        }
    }

    private var punctuationMapping: Map<String, String> = mapOf()
    private fun transformPunctuation(p: String) = punctuationMapping.getOrDefault(p, p)

    override fun onAction(action: KeyAction, source: KeyActionListener.Source) {
        var transformed = action
        when (action) {
            is KeyAction.FcitxKeyAction -> when (source) {
                KeyActionListener.Source.Keyboard -> {
                    when (capsState) {
                        CapsState.None -> {
                            transformed = action.copy(act = action.act.lowercase())
                        }
                        CapsState.Once -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.Shift)
                            )
                            switchCapsState()
                        }
                        CapsState.Lock -> {
                            transformed = action.copy(
                                act = action.act.uppercase(),
                                states = KeyStates(KeyState.Virtual, KeyState.CapsLock)
                            )
                        }
                    }
                }
                KeyActionListener.Source.Popup -> {
                    if (capsState == CapsState.Once) {
                        switchCapsState()
                    }
                }
            }
            is KeyAction.CapsAction -> {
                if (autoCapsMode != AutoCapsMode.None) {
                    autoCapsSuppressed = true
                    autoCapsApplied = false
                }
                switchCapsState(action.lock)
            }
            else -> {}
        }
        super.onAction(transformed, source)
    }

    override fun onAttach() {
        capsState = CapsState.None
        autoCapsApplied = false
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    override fun onAutoCapsUpdate(mode: AutoCapsMode) {
        if (mode == AutoCapsMode.None) {
            autoCapsMode = mode
            autoCapsSuppressed = false
            if (autoCapsApplied) updateCapsState(CapsState.None)
            autoCapsApplied = false
            return
        }
        autoCapsMode = mode
        if (autoCapsSuppressed) return
        val target = when (mode) {
            AutoCapsMode.Once -> CapsState.Once
            AutoCapsMode.Lock -> CapsState.Lock
            AutoCapsMode.None -> CapsState.None
        }
        if (capsState == CapsState.None || autoCapsApplied) {
            autoCapsApplied = true
            updateCapsState(target)
        }
    }

    override fun onReturnDrawableUpdate(returnDrawable: Int) {
        `return`.img.imageResource = returnDrawable
        `return`.contentDescription = context.getString(
            returnKeyAccessibilityLabel(returnDrawable).resId
        )
    }

    override fun onPunctuationUpdate(mapping: Map<String, String>) {
        punctuationMapping = mapping
        updatePunctuationKeys()
    }

    override fun onInputMethodUpdate(ime: InputMethodEntry) {
        space.mainText.text = buildString {
            append(ime.displayName)
            ime.subMode.run { label.ifEmpty { name.ifEmpty { null } } }?.let { append(" ($it)") }
        }
        if (capsState != CapsState.None) {
            switchCapsState()
        }
    }

    private fun transformPopupPreview(c: String): String {
        if (c.length != 1) return c
        if (c[0].isLetter()) return transformAlphabet(c)
        return transformPunctuation(c)
    }

    override fun onPopupAction(action: PopupAction) {
        val newAction = when (action) {
            is PopupAction.PreviewAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.PreviewUpdateAction -> action.copy(content = transformPopupPreview(action.content))
            is PopupAction.ShowKeyboardAction -> {
                when (action.keyboard) {
                    is KeyDef.Popup.Keyboard.Preset -> {
                        val label = action.keyboard.label
                        if (label.length == 1 && label[0].isLetter())
                            action.copy(
                                keyboard = action.keyboard.copy(label = transformAlphabet(label))
                            )
                        else action
                    }
                    is KeyDef.Popup.Keyboard.Explicit,
                    is KeyDef.Popup.Keyboard.Actions -> action
                }
            }
            else -> action
        }
        super.onPopupAction(newAction)
    }

    private fun switchCapsState(lock: Boolean = false) {
        updateCapsState(nextCapsState(capsState, lock, singleTapCapsLock))
    }

    private fun updateCapsState(state: CapsState) {
        if (capsState == state) return
        capsState = state
        updateCapsButtonIcon()
        updateAlphabetKeys()
    }

    private fun updateCapsButtonIcon() {
        caps.img.apply {
            imageResource = when (capsState) {
                CapsState.None -> R.drawable.ic_capslock_none
                CapsState.Once -> R.drawable.ic_capslock_once
                CapsState.Lock -> R.drawable.ic_capslock_lock
            }
        }
        ViewCompat.setStateDescription(
            caps,
            capsStateAccessibilityLabel(capsState)?.let { context.getString(it.resId) }
        )
    }

    private fun updateLangSwitchKey(visible: Boolean) {
        lang.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun updateAlphabetKeys() {
        textKeys.forEach {
            if (it.def !is KeyDef.Appearance.AltText) return
            it.mainText.text = it.def.displayText.let { str ->
                if (str.length != 1 || !str[0].isLetter()) return@forEach
                if (keepLettersUppercase.getValue()) str.uppercase() else transformAlphabet(str)
            }
        }
    }

    private fun updatePunctuationKeys() {
        textKeys.forEach {
            if (it is AltTextKeyView) {
                it.def as KeyDef.Appearance.AltText
                it.altText.text = transformPunctuation(it.def.altText)
            } else {
                it.def as KeyDef.Appearance.Text
                it.mainText.text = it.def.displayText.let { str ->
                    if (str[0].run { isLetter() || isWhitespace() }) return@forEach
                    transformPunctuation(str)
                }
            }
        }
    }

}


internal fun nextCapsState(
    current: TextKeyboard.CapsState,
    lock: Boolean,
    singleTapLocks: Boolean
): TextKeyboard.CapsState {
    return if (lock || singleTapLocks) {
        if (current == TextKeyboard.CapsState.Lock) {
            TextKeyboard.CapsState.None
        } else {
            TextKeyboard.CapsState.Lock
        }
    } else {
        if (current == TextKeyboard.CapsState.None) {
            TextKeyboard.CapsState.Once
        } else {
            TextKeyboard.CapsState.None
        }
    }
}
