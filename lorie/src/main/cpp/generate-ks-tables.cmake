if (NOT DEFINED tina_output OR NOT DEFINED tina_compiler OR NOT DEFINED tina_source OR
        NOT DEFINED tina_headers_dir OR NOT DEFINED tina_binary_dir)
    message(FATAL_ERROR "Missing makekeys generation arguments")
endif()

if (DEFINED tina_host_tool_path AND NOT tina_host_tool_path STREQUAL "")
    if (WIN32)
        set(ENV{PATH} "${tina_host_tool_path};$ENV{PATH}")
    else()
        set(ENV{PATH} "${tina_host_tool_path}:$ENV{PATH}")
    endif()
endif()

if (NOT CMAKE_EXECUTABLE_SUFFIX AND WIN32)
    set(CMAKE_EXECUTABLE_SUFFIX ".exe")
endif()

set(generator "${tina_binary_dir}/makekeys${CMAKE_EXECUTABLE_SUFFIX}")
execute_process(
        COMMAND "${tina_compiler}" -o "${generator}" "${tina_source}"
        RESULT_VARIABLE compile_result
        ERROR_VARIABLE compile_error)
if (NOT compile_result EQUAL 0)
    message(FATAL_ERROR "Compiling makekeys failed (exit ${compile_result}):\n${compile_error}")
endif()

execute_process(
        COMMAND "${generator}" keysymdef.h XF86keysym.h Sunkeysym.h DECkeysym.h HPkeysym.h
        WORKING_DIRECTORY "${tina_headers_dir}"
        RESULT_VARIABLE generate_result
        OUTPUT_FILE "${tina_output}"
        ERROR_VARIABLE generate_error)
if (NOT generate_result EQUAL 0)
    message(FATAL_ERROR "Generating ks_tables.h failed (exit ${generate_result}):\n${generate_error}")
endif()
