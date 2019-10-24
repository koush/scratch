# kotlin-native bug reports

## Building libuv and openssl

```
cd src/nativeInterop/cinterop
./checkout.sh
```

## Build

```
# I build on mac, may need to set up a linux/windows multiplatform entry
# Just change the macOS target to the appropriate in build.gradle.
gradle linkDebugTestNative
```

## After Building, Run:

```
./build/bin/native/debugTest/test.kexe --ktest_gradle_filter=com.koushikdutta.scratch.KotlinBugs
```
