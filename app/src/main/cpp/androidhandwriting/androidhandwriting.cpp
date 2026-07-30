/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2026 Fcitx5 for Android Contributors
 */
#include <fcitx/addonfactory.h>
#include <fcitx/addonmanager.h>
#include <fcitx/inputcontext.h>
#include <fcitx/inputmethodengine.h>
#include <fcitx/inputpanel.h>
#include <fcitx/instance.h>
#include <fcitx/userinterfacemanager.h>
#include <punctuation_public.h>

namespace fcitx {

class AndroidHandwritingEngine final : public InputMethodEngineV3 {
public:
    explicit AndroidHandwritingEngine(Instance *instance)
        : instance_(instance) {}

    void activate(const InputMethodEntry &entry,
                  InputContextEvent &event) override;
    void keyEvent(const InputMethodEntry &entry, KeyEvent &event) override;
    void reset(const InputMethodEntry &entry,
               InputContextEvent &event) override;

    FCITX_ADDON_DEPENDENCY_LOADER(punctuation, instance_->addonManager());

private:
    Instance *instance_;
};

void AndroidHandwritingEngine::activate(const InputMethodEntry &entry,
                                        InputContextEvent &event) {
    FCITX_UNUSED(entry);
    // Load the module before looking up its status action. This exposes the
    // same full/half-width punctuation toggle used by Pinyin.
    punctuation();
    if (auto *action =
            instance_->userInterfaceManager().lookupAction("punctuation")) {
        event.inputContext()->statusArea().addAction(StatusGroup::InputMethod,
                                                     action);
    }
}

void AndroidHandwritingEngine::keyEvent(const InputMethodEntry &entry,
                                        KeyEvent &event) {
    // Recognition and editor commits are handled by the Android UI and the
    // separately installed recognition provider. Only punctuation emitted by
    // the handwriting bottom row needs to pass through the shared Fcitx
    // punctuation converter.
    if (event.isRelease() || !event.key().isSimple() ||
        event.key().isKeyPad()) {
        return;
    }
    const auto unicode = Key::keySymToUnicode(event.key().sym());
    if (unicode != ',' && unicode != '.') {
        return;
    }
    const auto &converted = punctuation()->call<IPunctuation::pushPunctuation>(
        entry.languageCode(), event.inputContext(), unicode);
    if (converted.empty()) {
        return;
    }
    event.inputContext()->commitString(converted);
    event.filterAndAccept();
}

void AndroidHandwritingEngine::reset(const InputMethodEntry &entry,
                                     InputContextEvent &event) {
    FCITX_UNUSED(entry);
    auto *inputContext = event.inputContext();
    inputContext->inputPanel().reset();
    inputContext->updatePreedit();
    inputContext->updateUserInterface(UserInterfaceComponent::InputPanel);
}

class AndroidHandwritingEngineFactory final : public AddonFactory {
public:
    AddonInstance *create(AddonManager *manager) override {
        return new AndroidHandwritingEngine(manager->instance());
    }
};

} // namespace fcitx

FCITX_ADDON_FACTORY(fcitx::AndroidHandwritingEngineFactory)
