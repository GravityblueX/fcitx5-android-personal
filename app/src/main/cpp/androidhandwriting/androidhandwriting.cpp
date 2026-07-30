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

namespace fcitx {

class AndroidHandwritingEngine final : public InputMethodEngineV3 {
public:
    void keyEvent(const InputMethodEntry &entry, KeyEvent &event) override {
        FCITX_UNUSED(entry);
        FCITX_UNUSED(event);
        // Recognition and editor commits are handled by the Android UI and the
        // separately installed recognition provider. Physical keys pass through.
    }

    void reset(const InputMethodEntry &entry, InputContextEvent &event) override {
        FCITX_UNUSED(entry);
        auto *inputContext = event.inputContext();
        inputContext->inputPanel().reset();
        inputContext->updatePreedit();
        inputContext->updateUserInterface(UserInterfaceComponent::InputPanel);
    }
};

class AndroidHandwritingEngineFactory final : public AddonFactory {
public:
    AddonInstance *create(AddonManager *manager) override {
        FCITX_UNUSED(manager);
        return new AndroidHandwritingEngine();
    }
};

} // namespace fcitx

FCITX_ADDON_FACTORY(fcitx::AndroidHandwritingEngineFactory)
