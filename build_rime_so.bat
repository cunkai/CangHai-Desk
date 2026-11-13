@echo off
rem ============================================================
rem  重新生成 libcanghairime.so（librime 的 Android 版）
rem
rem  前置条件（都已在本机做过，换机器要重来）：
rem    C:\Users\GoodBoy\MyCodes\p_android\librime-master\deps 下已 clone：
rem      leveldb / yaml-cpp / marisa-trie / opencc / spdlog
rem    rime-build\boost_1_84_0            解压好的 boost 头文件
rem    rime-build\prefix-arm64\lib        四个依赖的 arm64 静态库
rem    librime-master\build-android\lib\librime.a   （cmake 交叉编译产物）
rem
rem  依赖静态库怎么编（各自 build-android 目录，Ninja + NDK 工具链）：
rem    cmake -S <dep> -B <dep>\build-android -G Ninja ^
rem      -DCMAKE_TOOLCHAIN_FILE=%NDK%\build\cmake\android.toolchain.cmake ^
rem      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 ^
rem      -DCMAKE_BUILD_TYPE=Release ^
rem      -DCMAKE_INSTALL_PREFIX=<p_android>\rime-build\prefix-arm64 ^
rem      -DCMAKE_POSITION_INDEPENDENT_CODE=ON ...
rem    cmake --build <dep>\build-android --target install -- -j 2
rem  （-j 单线程是关键：Windows 上多 job 会撞「Permission denied」）
rem
rem  librime 本身：
rem    cmake -S librime-master -B librime-master\build-android -G Ninja ^
rem      -DCMAKE_TOOLCHAIN_FILE=%NDK%\build\cmake\android.toolchain.cmake ^
rem      -DANDROID_ABI=arm64-v8a -DANDROID_PLATFORM=android-26 ^
rem      -DCMAKE_BUILD_TYPE=Release -DCMAKE_FIND_ROOT_PATH=<prefix-arm64> ^
rem      -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=BOTH ^
rem      -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=BOTH ^
rem      -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=BOTH ^
rem      -DCMAKE_CXX_FLAGS="-I<prefix-arm64>/include -I<boost>" ^
rem      -DBUILD_SHARED_LIBS=OFF -DBUILD_STATIC=ON -DBUILD_TEST=OFF ^
rem      -DBUILD_SAMPLE=OFF -DBUILD_DATA=OFF -DENABLE_LOGGING=ON ^
rem      -DBOOST_ROOT=<boost> -DBoost_NO_SYSTEM_PATHS=ON
rem    cmake --build librime-master\build-android -- -j 2
rem ============================================================
setlocal

set ROOT=C:\Users\GoodBoy\MyCodes\p_android
set NDK=%LOCALAPPDATA%\Android\Sdk\ndk\28.0.13004108
set TC=%NDK%\toolchains\llvm\prebuilt\windows-x86_64
set CLANG=%TC%\bin\clang++.exe
set STRIP=%TC%\bin\llvm-strip.exe
set SYSROOT=%TC%\sysroot
set RIME=%ROOT%\librime-master
set PREFIX=%ROOT%\rime-build\prefix-arm64
set BOOST=%ROOT%\rime-build\boost_1_84_0
set OUT=%~dp0app\src\main\jniLibs\arm64-v8a

if not exist "%OUT%" mkdir "%OUT%"

echo [1/2] 编译 rime_jni.cpp ...
"%CLANG%" --target=aarch64-linux-android26 --sysroot="%SYSROOT%" ^
  -shared -fPIC -O2 -fvisibility=hidden ^
  -I"%RIME%\include" -I"%RIME%\src" -I"%PREFIX%\include" -I"%BOOST%" ^
  -o "%OUT%\libcanghairime.so" ^
  "%~dp0app\src\main\cpp\rime_jni.cpp" ^
  "%RIME%\build-android\lib\librime.a" ^
  "%RIME%\build-android\deps\spdlog\libspdlog.a" ^
  "%PREFIX%\lib\libopencc.a" ^
  "%PREFIX%\lib\libmarisa.a" ^
  "%PREFIX%\lib\libleveldb.a" ^
  "%PREFIX%\lib\libyaml-cpp.a" ^
  -llog -lz -static-libstdc++ -Wl,--exclude-libs,ALL
if errorlevel 1 goto fail

echo [2/2] 裁剪 ...
"%STRIP%" --strip-unneeded "%OUT%\libcanghairime.so"
if errorlevel 1 goto fail

echo 完成: %OUT%\libcanghairime.so
exit /b 0

:fail
echo 构建失败
exit /b 1
