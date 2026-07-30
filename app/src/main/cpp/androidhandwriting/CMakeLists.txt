add_library(androidhandwriting MODULE androidhandwriting.cpp)
target_link_libraries(androidhandwriting Fcitx5::Core Fcitx5::Utils)

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
