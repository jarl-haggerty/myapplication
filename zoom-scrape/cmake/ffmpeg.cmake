FetchContent_Declare(
  ffmpeg 
  GIT_REPOSITORY https://github.com/FFmpeg/FFmpeg.git
  GIT_TAG        n6.1
)
FetchContent_MakeAvailable(ffmpeg)
file(MAKE_DIRECTORY "${ffmpeg_BINARY_DIR}/Debug/Modules")
file(MAKE_DIRECTORY "${ffmpeg_BINARY_DIR}/Release/Modules")

execute_process(COMMAND git apply "${CMAKE_SOURCE_DIR}/patches/ffmpeg" WORKING_DIRECTORY "${ffmpeg_SOURCE_DIR}")

set(FFTOOL_OBJECTS 
    "${ffmpeg_BINARY_DIR}/$<CONFIG>/fftools/ffmpeg_dec.o"
    "${ffmpeg_BINARY_DIR}/$<CONFIG>/fftools/ffmpeg_demux.o"
    "${ffmpeg_BINARY_DIR}/$<CONFIG>/fftools/ffmpeg_enc.o"
    "${ffmpeg_BINARY_DIR}/$<CONFIG>/fftools/ffmpeg_filter.o"
    "${ffmpeg_BINARY_DIR}/$<CONFIG>/fftools/ffmpeg_hw.o"
    "${ffmpeg_BINARY_DIR}/$<CONFIG>/fftools/ffmpeg_mux.o"
    "${ffmpeg_BINARY_DIR}/$<CONFIG>/fftools/ffmpeg_mux_init.o"
    "${ffmpeg_BINARY_DIR}/$<CONFIG>/fftools/ffmpeg_opt.o"
    "${ffmpeg_BINARY_DIR}/$<CONFIG>/fftools/objpool.o"
    "${ffmpeg_BINARY_DIR}/$<CONFIG>/fftools/sync_queue.o"
    "${ffmpeg_BINARY_DIR}/$<CONFIG>/fftools/thread_queue.o"
    "${ffmpeg_BINARY_DIR}/$<CONFIG>/fftools/cmdutils.o"
    "${ffmpeg_BINARY_DIR}/$<CONFIG>/fftools/opt_common.o")

if(WIN32)
  if(EXISTS "C:\\MSYS2")
    set(MSYS2_ROOT "C:\\MSYS2")
  else()
    set(MSYS2_ROOT "C:\\MSYS64")
  endif()

  set(FFMPEG_ALL_COMPILE_OPTIONS_SPACED "${ALL_C_COMPILE_OPTIONS_SPACED}")
  string(PREPEND FFMPEG_ALL_COMPILE_OPTIONS_SPACED " ")
  string(REPLACE " /" " -" FFMPEG_ALL_COMPILE_OPTIONS_SPACED "${FFMPEG_ALL_COMPILE_OPTIONS_SPACED}")
  string(REPLACE "-MP" "" FFMPEG_ALL_COMPILE_OPTIONS_SPACED "${FFMPEG_ALL_COMPILE_OPTIONS_SPACED}")

  set(FFMPEG_ALL_LINK_OPTIONS_SPACED "${ALL_C_LINK_OPTIONS_SPACED}")
  string(PREPEND FFMPEG_ALL_LINK_OPTIONS_SPACED " ")
  string(REPLACE " /" " -" FFMPEG_ALL_LINK_OPTIONS_SPACED "${FFMPEG_ALL_LINK_OPTIONS_SPACED}")

  message("ALL_LINK_OPTIONS ${ALL_LINK_OPTIONS}")
  message("ALL_C_LINK_OPTIONS_SPACED ${ALL_C_LINK_OPTIONS_SPACED}")
  message("FFMPEG_ALL_LINK_OPTIONS_SPACED ${FFMPEG_ALL_LINK_OPTIONS_SPACED}")

  string(STRIP "${FFMPEG_ALL_COMPILE_OPTIONS_SPACED}" FFMPEG_ALL_COMPILE_OPTIONS_SPACED)
  if("${CMAKE_CXX_COMPILER_ID}" MATCHES "MSVC")
    string(APPEND FFMPEG_ALL_COMPILE_OPTIONS_SPACED " -FS")
    add_custom_command(
      OUTPUT "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/Makefile"
      COMMAND
      ${MSYS2_ROOT}\\msys2_shell.cmd -here -use-full-path -no-start -defterm -c "'${CMAKE_SOURCE_DIR}/config_ffmpeg_msvc.bash' '${ffmpeg_SOURCE_DIR}/configure' '${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install' '-MT$<IF:$<CONFIG:Debug>,d,> ${FFMPEG_ALL_COMPILE_OPTIONS_SPACED}' $<IF:$<CONFIG:Debug>,--enable-debug,> '-MT$<IF:$<CONFIG:Debug>,d,> ${FFMPEG_ALL_LINK_OPTIONS_SPACED}'"
      && cmake -E touch_nocreate "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/Makefile"
      WORKING_DIRECTORY "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>")
  else()
    set(FFMPEG_ALL_LINK_OPTIONS_SPACED "${ALL_C_LINK_OPTIONS_SPACED}")
    string(REPLACE "/DEBUG" "" FFMPEG_ALL_LINK_OPTIONS_SPACED "${ALL_C_LINK_OPTIONS_SPACED}")

    string(REPLACE "-Zi" "" FFMPEG_ALL_COMPILE_OPTIONS_SPACED "${FFMPEG_ALL_COMPILE_OPTIONS_SPACED}")
    add_library(ffmpeg_m m_stub.cpp)
    set_target_properties(ffmpeg_m PROPERTIES 
      ARCHIVE_OUTPUT_DIRECTORY "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>"
      LIBRARY_OUTPUT_DIRECTORY "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>"
      OUTPUT_NAME m)
    string(REGEX REPLACE "^([a-zA-Z]):" "/\\1" FFMPEG_COMPILER "${CMAKE_C_COMPILER}")
    string(REPLACE "clang-cl" "clang" FFMPEG_COMPILER "${FFMPEG_COMPILER}")
    string(REPLACE "Program Files (x86)" "Progra~2" FFMPEG_COMPILER "${FFMPEG_COMPILER}")
    string(REPLACE "Program Files" "Progra~1" FFMPEG_COMPILER "${FFMPEG_COMPILER}")
    add_custom_command(
      OUTPUT "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/Makefile"
      DEPENDS ffmpeg_m
      COMMAND
      ${MSYS2_ROOT}\\msys2_shell.cmd -here -use-full-path -no-start -defterm -c "'${ffmpeg_SOURCE_DIR}/configure' --target-os=win64 --arch=x86_64 '--cc=${FFMPEG_COMPILER}' --enable-static --disable-shared '--prefix=${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install' '--extra-cflags=${FFMPEG_ALL_COMPILE_OPTIONS_SPACED}' '--extra-ldflags=${FFMPEG_ALL_LINK_OPTIONS_SPACED}' $<IF:$<CONFIG:Debug>,--enable-debug,>"
      && cmake -E touch_nocreate "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/Makefile"
      && ${MSYS2_ROOT}\\msys2_shell.cmd -here -use-full-path -no-start -defterm -c "sed -i s/LIBPREF=lib/LIBPREF=/ ffbuild/config.mak"
      && ${MSYS2_ROOT}\\msys2_shell.cmd -here -use-full-path -no-start -defterm -c "sed -i s/LIBSUF=.a/LIBSUF=.lib/ ffbuild/config.mak"
      WORKING_DIRECTORY "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>")
  endif()
  set(FFMPEG_MAKE_COMMAND ${MSYS2_ROOT}\\msys2_shell.cmd -here -use-full-path -no-start -defterm -c \"export VERBOSE=1 && make -j ${CPU_COUNT} && make install\")
else()
  string(REPLACE "-nostdinc++" "" FFMPEG_COMPILE_OPTIONS_SPACED "${ALL_C_COMPILE_OPTIONS_SPACED}")
  if(APPLE)
    set(FFMPEG_APPLE_FLAGS --disable-libxcb)
  endif()
  add_custom_command(
    OUTPUT "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/Makefile"
    COMMAND cmake -E env 
    "${ffmpeg_SOURCE_DIR}/configure" ${FFMPEG_APPLE_FLAGS} --cc=clang "--extra-cflags=${FFMPEG_COMPILE_OPTIONS_SPACED}" "--extra-ldflags=${ALL_C_LINK_OPTIONS_SPACED}" --enable-static --disable-shared --disable-sndio $<IF:$<CONFIG:Debug>,--enable-debug,> --prefix=${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install
    && cmake -E touch_nocreate "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/Makefile"
    WORKING_DIRECTORY "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>")
  set(FFMPEG_MAKE_COMMAND make -j ${CPU_COUNT} && make install)
endif()

if(WIN32)
  if("${CMAKE_CXX_COMPILER_ID}" MATCHES "MSVC")
    set(FFMPEG_OUTPUT_LIBRARIES
      "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/libavcodec.a"
      "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/libavdevice.a"
      "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/libavfilter.a"
      "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/libavformat.a"
      "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/libavutil.a"
      "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/libswresample.a"
      "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/libswscale.a")
  else()
    set(FFMPEG_OUTPUT_LIBRARIES
      "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/avcodec.lib"
      "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/avdevice.lib"
      "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/avfilter.lib"
      "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/avformat.lib"
      "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/avutil.lib"
      "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/swresample.lib"
      "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/swscale.lib")
  endif()
  set(FFMPEG_LIBRARIES "${FFMPEG_OUTPUT_LIBRARIES}"
    Ws2_32.lib Secur32.lib Bcrypt.lib Mfplat.lib Ole32.lib User32.lib dxguid.lib uuid.lib Mfuuid.lib strmiids.lib Kernel32.lib Psapi.lib)
  set(FFMPEG_EXECUTABLE "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/bin/ffmpeg.exe")
  set(FFPROBE_EXECUTABLE "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/bin/ffprobe.exe")
else()
  set(FFMPEG_OUTPUT_LIBRARIES
    "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/libavcodec.a"
    "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/libavdevice.a"
    "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/libavfilter.a"
    "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/libavformat.a"
    "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/libavutil.a"
    "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/libswresample.a"
    "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/libswscale.a")
  set(FFMPEG_LIBRARIES "${FFMPEG_OUTPUT_LIBRARIES}")
  set(FFMPEG_EXECUTABLE "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/bin/ffmpeg")
  set(FFPROBE_EXECUTABLE "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/bin/ffprobe")
endif()
set(FFMPEG_PKG_CONFIG_DIR "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/pkgconfig")

add_custom_command(
  OUTPUT "${FFMPEG_OUTPUT_LIBRARIES}" ${FFTOOL_OBJECTS} "${ffmpeg_BINARY_DIR}/$<CONFIG>/ffbuild/config.mak" 
  DEPENDS "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/Makefile"
  COMMAND ${FFMPEG_MAKE_COMMAND}
  && cmake -E touch_nocreate "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/lib/libavcodec.a"
  WORKING_DIRECTORY "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>")

set(FFMPEG_FOUND 1)
set(FFMPEG_INCLUDE_DIRS
  "${ffmpeg_BINARY_DIR}/$<IF:$<CONFIG:Debug>,Debug,Release>/install/include")
string(REPLACE "$<IF:$<CONFIG:Debug>,Debug,Release>" "Debug" FFMPEG_INCLUDE_DIRS_DEBUG "${FFMPEG_INCLUDE_DIRS}")
string(REPLACE "$<IF:$<CONFIG:Debug>,Debug,Release>" "Release" FFMPEG_INCLUDE_DIRS_RELEASE "${FFMPEG_INCLUDE_DIRS}")
string(REPLACE "$<IF:$<CONFIG:Debug>,Debug,Release>" "Debug" FFMPEG_LIBRARIES_DEBUG "${FFMPEG_LIBRARIES}")
string(REPLACE "$<IF:$<CONFIG:Debug>,Debug,Release>" "Release" FFMPEG_LIBRARIES_RELEASE "${FFMPEG_LIBRARIES}")

set(FIND_FFMPEG "")
set(FIND_FFMPEG "${FIND_FFMPEG}execute_process(COMMAND ${ffmpeg_BINARY_DIR}/Debug/install/bin/ffmpeg -version\n")
set(FIND_FFMPEG "${FIND_FFMPEG}                OUTPUT_VARIABLE FFMPEG_OUTPUT)\n")
set(FIND_FFMPEG "${FIND_FFMPEG}string(REGEX MATCHALL \"lib[^/]+\" VERSIONS \"\${FFMPEG_OUTPUT}\")\n")
set(FIND_FFMPEG "${FIND_FFMPEG}foreach(line \${VERSIONS})\n")
set(FIND_FFMPEG "${FIND_FFMPEG}  string(REGEX REPLACE \"[a-z ]\" \"\" version \${line})\n")
set(FIND_FFMPEG "${FIND_FFMPEG}  string(REGEX REPLACE \"[ .0-9]\" \"\" lib \${line})\n")
set(FIND_FFMPEG "${FIND_FFMPEG}  set(FFMPEG_\${lib}_VERSION \${version})\n")
set(FIND_FFMPEG "${FIND_FFMPEG}  message(\"FFMPEG_\${lib}_VERSION \${FFMPEG_\${lib}_VERSION}\")\n")
set(FIND_FFMPEG "${FIND_FFMPEG}endforeach()\n")
set(FIND_FFMPEG "${FIND_FFMPEG}set(FFMPEG_FOUND 1)\n")
set(FIND_FFMPEG "${FIND_FFMPEG}set(FFMPEG_INCLUDE_DIRS \"${FFMPEG_INCLUDE_DIRS_DEBUG}\")\n")
set(FIND_FFMPEG "${FIND_FFMPEG}set(FFMPEG_LIBRARIES \"${FFMPEG_LIBRARIES_DEBUG}\")\n")
file(WRITE "${ffmpeg_BINARY_DIR}/Debug/Modules/FindFFMPEG.cmake" "${FIND_FFMPEG}")
set(FIND_FFMPEG "")
set(FIND_FFMPEG "${FIND_FFMPEG}execute_process(COMMAND ${ffmpeg_BINARY_DIR}/Release/install/bin/ffmpeg -version\n")
set(FIND_FFMPEG "${FIND_FFMPEG}                OUTPUT_VARIABLE FFMPEG_OUTPUT)\n")
set(FIND_FFMPEG "${FIND_FFMPEG}string(REGEX MATCHALL \"lib[^/]+\" VERSIONS \"\${FFMPEG_OUTPUT}\")\n")
set(FIND_FFMPEG "${FIND_FFMPEG}foreach(line \${VERSIONS})\n")
set(FIND_FFMPEG "${FIND_FFMPEG}  string(REGEX REPLACE \"[a-z ]\" \"\" version \${line})\n")
set(FIND_FFMPEG "${FIND_FFMPEG}  string(REGEX REPLACE \"[ .0-9]\" \"\" lib \${line})\n")
set(FIND_FFMPEG "${FIND_FFMPEG}  set(FFMPEG_\${lib}_VERSION \${version})\n")
set(FIND_FFMPEG "${FIND_FFMPEG}  message(\"FFMPEG_\${lib}_VERSION \${FFMPEG_\${lib}_VERSION}\")\n")
set(FIND_FFMPEG "${FIND_FFMPEG}endforeach()\n")
set(FIND_FFMPEG "${FIND_FFMPEG}set(FFMPEG_FOUND 1)\n")
set(FIND_FFMPEG "${FIND_FFMPEG}set(FFMPEG_INCLUDE_DIRS \"${FFMPEG_INCLUDE_DIRS_RELEASE}\")\n")
set(FIND_FFMPEG "${FIND_FFMPEG}set(FFMPEG_LIBRARIES \"${FFMPEG_LIBRARIES_RELEASE}\")\n")
file(WRITE "${ffmpeg_BINARY_DIR}/Release/Modules/FindFFMPEG.cmake" "${FIND_FFMPEG}")

list(GET FFMPEG_LIBRARIES 0 FIRST_FFMPEG_LIB)
message("FIRST_FFMPEG_LIB ${FIRST_FFMPEG_LIB} ${FFMPEG_LIBRARIES}")

add_library(ffmpeg INTERFACE "${FIRST_FFMPEG_LIB}")
target_include_directories(ffmpeg INTERFACE ${FFMPEG_INCLUDE_DIRS})
target_link_libraries(ffmpeg INTERFACE ${FFMPEG_LIBRARIES})
if(WIN32)
elseif(APPLE)
  target_link_libraries(ffmpeg INTERFACE bz2)
else()
  target_link_libraries(ffmpeg INTERFACE va X11 lzma va-drm va-x11 vdpau Xext Xv asound bz2)
endif()


if(WIN32)
  set(THALAMUS_EXPORT "__declspec(dllexport)")
  if("${CMAKE_CXX_COMPILER_ID}" MATCHES "MSVC")
    set(FFMPEG_OUT_ARG "-Fo")
  else()
    set(FFMPEG_OUT_ARG "-o")
  endif()
else()
  set(THALAMUS_EXPORT "__attribute__((visibility(\"default\")))")
  set(FFMPEG_OUT_ARG "-o")
endif()

