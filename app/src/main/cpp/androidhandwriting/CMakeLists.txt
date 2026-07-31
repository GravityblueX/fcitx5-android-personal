add_library(androidhandwriting MODULE androidhandwriting.cpp)
target_link_libraries(
        androidhandwriting
        Fcitx5::Core
        Fcitx5::Utils
        Fcitx5::Module::Punctuation
)

configure_file(androidhandwriting.conf.in.in androidhandwriting.conf.in @ONLY)
fcitx5_translate_desktop_file(
        ${CMAKE_CURRENT_BINARY_DIR}/androidhandwriting.conf.in
        androidhandwriting.conf
)
install(
        FILES "${CMAKE_CURRENT_BINARY_DIR}/androidhandwriting.conf"
        DESTINATION "${FCITX_INSTALL_PKGDATADIR}/addon"
        COMPONENT config
)

configure_file(handwriting.conf.in.in handwriting.conf @ONLY)
install(
        FILES "${CMAKE_CURRENT_BINARY_DIR}/handwriting.conf"
        DESTINATION "${FCITX_INSTALL_PKGDATADIR}/inputmethod"
        COMPONENT config
)
