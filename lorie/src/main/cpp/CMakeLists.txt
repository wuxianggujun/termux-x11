cmake_minimum_required(VERSION 3.22)
project(lorie)

set(can_use_assembler TRUE)
enable_language(ASM)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_BUILD_TYPE RelWithDebInfo)

include(TestBigEndian)
include(CheckIncludeFile)
include(CheckSourceCompiles)
include(CheckCCompilerFlag)
include(CheckSymbolExists)
include(CheckFunctionExists)
include(CheckTypeSize)

if (NOT Python3_EXECUTABLE)
    find_program(Python3_EXECUTABLE
            NAMES python3 python
            HINTS
            "$ENV{LOCALAPPDATA}/Programs/Python/Python313"
            "$ENV{LOCALAPPDATA}/Programs/Python/Python312"
            "D:/Programs/Python/Python313"
            "C:/Python313"
            "C:/Python312"
            DOC "Python 3 interpreter used by X.Org code generators")
endif()
if (NOT BISON_EXECUTABLE)
    find_program(BISON_EXECUTABLE
            NAMES bison
            HINTS
            "D:/Programs/msys64/usr/bin"
            "C:/msys64/usr/bin"
            "D:/Programs/cygwin64/bin"
            "C:/cygwin64/bin"
            "$ENV{ProgramFiles}/Git/usr/bin"
            DOC "GNU Bison used to generate xkbcomp parser sources")
endif()

find_package(Python3 REQUIRED)
find_package(BISON REQUIRED)

find_program(TINA_HOST_C_COMPILER
        NAMES gcc cc
        HINTS
        "D:/Programs/msys64/mingw64/bin"
        "C:/msys64/mingw64/bin"
        "D:/Programs/cygwin64/bin"
        "C:/cygwin64/bin"
        DOC "Host C compiler used by build-time code generators")
if (NOT TINA_HOST_C_COMPILER)
    message(FATAL_ERROR
            "Host C compiler not found. Pass -DTINA_HOST_C_COMPILER=<path-to-gcc-or-cc>.")
endif()

# MSYS2/MinGW host tools load DLLs from their bin directory. This is intentionally separate
# from the Gradle launcher's PATH: replacing the latter changes Git Bash path conversion and
# prevents gradlew from locating its JVM on Windows.
set(TINA_HOST_TOOL_PATH "" CACHE PATH
        "Directories prepended to PATH for build-time host tools")
if (TINA_HOST_TOOL_PATH STREQUAL "")
    get_filename_component(TINA_HOST_TOOL_PATH "${TINA_HOST_C_COMPILER}" DIRECTORY)
endif()

# TinaIDE 本地改动：原实现是
#   execute_process(COMMAND "bash" "-c" "patch ... || patch ...")
# 在 Windows 上 `bash` 会按 PATH 解析。机器级 PATH 中 C:\WINDOWS\system32 排在
# Git 的 usr\bin 之前，于是解析到 WSL 的 bash.exe，它无法处理 C:/... 形式的路径，
# 报 "patch: **** Can't change to directory C:/...".
# 这里改为不经 shell、直接调用 patch，逻辑与上游等价：
#   反向 dry-run 成功 => 补丁已打过，跳过；否则正向应用。
find_program(TINA_PATCH_EXECUTABLE
        NAMES patch
        HINTS
        "$ENV{ProgramFiles}/Git/usr/bin"
        "$ENV{ProgramFiles\(x86\)}/Git/usr/bin"
        "D:/Program Files/Git/usr/bin"
        DOC "GNU patch executable used to apply X.Org source patches")
if (NOT TINA_PATCH_EXECUTABLE)
    message(FATAL_ERROR
            "GNU patch not found. Install it (Git for Windows ships usr/bin/patch.exe) "
            "or pass -DTINA_PATCH_EXECUTABLE=<path>.")
endif()

function(target_apply_patch patch_target d p)
    execute_process(
            COMMAND "${TINA_PATCH_EXECUTABLE}" -p1 -d "${d}" -i "${p}" -R --dry-run
            RESULT_VARIABLE reverse_applies
            OUTPUT_QUIET
            ERROR_QUIET)
    if (reverse_applies EQUAL 0)
        # 补丁已经打过，重复应用会失败。
        return()
    endif()

    execute_process(
            COMMAND "${TINA_PATCH_EXECUTABLE}" -p1 -N -d "${d}" -i "${p}" -r -
            RESULT_VARIABLE patch_result
            OUTPUT_VARIABLE patch_output
            ERROR_VARIABLE patch_output)
    if (NOT patch_result EQUAL 0)
        message(FATAL_ERROR
                "Failed to apply ${p} to ${d} (target ${patch_target}, exit ${patch_result}):\n${patch_output}")
    endif()
endfunction()

set(test_compile_options
        "-Wall"
        "-Wpointer-arith"
        "-Wmissing-declarations"
        "-Wformat=2"
        "-Winvalid-pch"
        "-Wstrict-prototypes"
        "-Wmissing-prototypes"
        "-Wnested-externs"
        "-Wbad-function-cast"
        "-Wold-style-definition"
        "-Wunused"
        "-Wuninitialized"
        "-Wshadow"
        "-Wmissing-noreturn"
        "-Wmissing-format-attribute"
        "-Wredundant-decls"
        "-Wlogical-op"
        "-Werror=implicit"
        "-Werror=nonnull"
        "-Werror=init-self"
        "-Werror=main"
        "-Werror=missing-braces"
        "-Werror=sequence-point"
        "-Werror=return-type"
        "-Werror=trigraphs"
        "-Werror=array-bounds"
        "-Werror=write-strings"
        "-Werror=address"
        "-Werror=int-to-pointer-cast"
        "-Werror=pointer-to-int-cast"
        "-fno-strict-aliasing"
        "-Wno-unused-but-set-variable"
        "-Wno-unused-parameter"
        "-Wno-unused-variable"
        "-Wno-unused-function"
        "-Wno-missing-prototypes"
        "-Wno-macro-redefined"
        "-Wno-uninitialized"
        "-Wno-shadow")

set(CMAKE_REQUIRED_QUIET_OLD ${CMAKE_REQUIRED_QUIET})
set(CMAKE_REQUIRED_QUIET 1)
set(common_compile_options)
set(I 0)
foreach (option ${test_compile_options})
    check_c_compiler_flag(${option} COMPILER_TEST_${I})
    if (COMPILER_TEST_${I})
        set(common_compile_options ${common_compile_options} ${option})
    endif()
    MATH(EXPR I "${I}+1")
endforeach ()
set(CMAKE_REQUIRED_QUIET ${CMAKE_REQUIRED_QUIET_OLD})

foreach(project xorgproto fontenc md pixman tirpc xshmfence Xdmcp Xau Xfont2 xkbcomp xserver)
    include(recipes/${project}.cmake)
endforeach()
