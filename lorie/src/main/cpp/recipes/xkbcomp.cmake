# Normally xkbcomp is build as executable, but in our case it is better to embed it.

file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/X11")

add_custom_command(
        OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/ks_tables.h"
        COMMAND "${CMAKE_COMMAND}" -E make_directory "${CMAKE_CURRENT_BINARY_DIR}"
        # VERBATIM 已经负责给每个参数加引号，这里不能再手写 "..."，
        # 否则引号会变成 -D 值的一部分，路径失效。
        COMMAND "${CMAKE_COMMAND}"
                -Dtina_output=${CMAKE_CURRENT_BINARY_DIR}/ks_tables.h
                -Dtina_compiler=${TINA_HOST_C_COMPILER}
                -Dtina_source=${CMAKE_CURRENT_SOURCE_DIR}/libx11/src/util/makekeys.c
                -Dtina_headers_dir=${CMAKE_CURRENT_SOURCE_DIR}/xorgproto/include/X11
                -Dtina_binary_dir=${CMAKE_CURRENT_BINARY_DIR}
                -Dtina_host_tool_path=${TINA_HOST_TOOL_PATH}
                -P "${CMAKE_CURRENT_SOURCE_DIR}/generate-ks-tables.cmake"
        DEPENDS
                "${CMAKE_CURRENT_SOURCE_DIR}/libx11/src/util/makekeys.c"
                "${CMAKE_CURRENT_SOURCE_DIR}/xorgproto/include/X11/keysymdef.h"
                "${CMAKE_CURRENT_SOURCE_DIR}/xorgproto/include/X11/XF86keysym.h"
                "${CMAKE_CURRENT_SOURCE_DIR}/xorgproto/include/X11/Sunkeysym.h"
                "${CMAKE_CURRENT_SOURCE_DIR}/xorgproto/include/X11/DECkeysym.h"
                "${CMAKE_CURRENT_SOURCE_DIR}/xorgproto/include/X11/HPkeysym.h"
        COMMENT "Generating source code (ks_tables.h)"
        VERBATIM)

BISON_TARGET(xkbcomp_parser xkbcomp/xkbparse.y ${CMAKE_CURRENT_BINARY_DIR}/xkbparse.c)
add_library(xkbcomp STATIC
        "libx11/src/KeyBind.c"
        "libx11/src/KeysymStr.c"
        "libx11/src/Quarks.c"
        "libx11/src/StrKeysym.c"
        "libx11/src/Xrm.c"

        "libx11/src/xkb/XKBGeom.c"
        "libx11/src/xkb/XKBMisc.c"
        "libx11/src/xkb/XKBMAlloc.c"
        "libx11/src/xkb/XKBGAlloc.c"
        "libx11/src/xkb/XKBAlloc.c"

        "libxkbfile/src/xkbatom.c"
        "libxkbfile/src/xkberrs.c"
        "libxkbfile/src/xkbmisc.c"
        "libxkbfile/src/xkbout.c"
        "libxkbfile/src/xkbtext.c"
        "libxkbfile/src/xkmout.c"

        "xkbcomp/action.c"
        "xkbcomp/alias.c"
        "xkbcomp/compat.c"
        "xkbcomp/expr.c"
        "xkbcomp/geometry.c"
        "xkbcomp/indicators.c"
        "xkbcomp/keycodes.c"
        "xkbcomp/keymap.c"
        "xkbcomp/keytypes.c"
        "xkbcomp/listing.c"
        "xkbcomp/misc.c"
        "xkbcomp/parseutils.c"
        "xkbcomp/symbols.c"
        "xkbcomp/utils.c"
        "xkbcomp/vmod.c"
        "xkbcomp/xkbcomp.c"
        "xkbcomp/xkbparse.y"
        "xkbcomp/xkbpath.c"
        "xkbcomp/xkbscan.c"
        "${CMAKE_CURRENT_BINARY_DIR}/ks_tables.h"
        "${CMAKE_CURRENT_BINARY_DIR}/xkbparse.c")
# TinaIDE 本地改动：Windows / macOS 宿主的文件系统大小写不敏感。
# 本 target 按上游要求把 libx11/include/X11 直接放进搜索路径（KeyBind.c 等用的是
# 裸 include，如 <XKBlib.h>、<Xresource.h>），于是 bionic 头文件里的
# `#include <xlocale.h>` 会命中 libX11 的 Xlocale.h。后者只 include <locale.h>，
# 从不声明 locale_t，而它的 include guard 又已置位，导致 locale.h 二次 include 被跳过，
# 最终报 "unknown type name 'locale_t'"。
# 这里在更靠前的位置放一个同名 shim，把请求转回 sysroot 里真正的 xlocale.h。
# Linux 宿主大小写敏感，不会发生该冲突，因此不需要 shim。
set(xlocale_shim_dir "${CMAKE_CURRENT_BINARY_DIR}/xlocale-shim")
if (CMAKE_HOST_WIN32 OR CMAKE_HOST_APPLE)
    if (NOT EXISTS "${CMAKE_SYSROOT}/usr/include/xlocale.h")
        message(FATAL_ERROR
                "Expected NDK sysroot header not found: ${CMAKE_SYSROOT}/usr/include/xlocale.h")
    endif()
    file(WRITE "${xlocale_shim_dir}/xlocale.h"
            "#pragma once\n#include \"${CMAKE_SYSROOT}/usr/include/xlocale.h\"\n")
    target_include_directories(xkbcomp BEFORE PRIVATE "${xlocale_shim_dir}")
endif()

target_include_directories(xkbcomp
        PUBLIC
        "libxkbfile/include"
        PRIVATE
        "xkbcomp"
        "libx11/include"
        "libx11/include/X11"
        "libx11/src"
        "libx11/src/xlibi18n"
        "libxkbfile/include/X11/extensions"
        "${CMAKE_CURRENT_BINARY_DIR}")
target_link_libraries(xkbcomp PRIVATE xorgproto)
target_link_options(xkbcomp PRIVATE "-fPIE" "-fPIC")
target_compile_options(xkbcomp PRIVATE ${common_compile_options} "-fvisibility=hidden" "-DHAVE_STRCASECMP" "-DHAVE_STRDUP" "-DDFLT_XKB_CONFIG_ROOT=\"/\"" "-DHAVE_SYS_IOCTL_H" "-fPIE" "-fPIC" "-DPACKAGE_VERSION=\"2.70\"" "-Wno-shadow")
target_apply_patch(xkbcomp "${CMAKE_CURRENT_SOURCE_DIR}/xkbcomp" "${CMAKE_CURRENT_SOURCE_DIR}/patches/xkbcomp.patch")
target_apply_patch(xkbfile "${CMAKE_CURRENT_SOURCE_DIR}/libxkbfile" "${CMAKE_CURRENT_SOURCE_DIR}/patches/xkbfile.patch")
target_apply_patch(X11 "${CMAKE_CURRENT_SOURCE_DIR}/libx11" "${CMAKE_CURRENT_SOURCE_DIR}/patches/x11.patch")
