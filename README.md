# kotlin-native bug reports

## Building libuv and openssl

```
cd src/nativeInterop/cinterop
./checkout.sh
```

## After Building, Run:

```
./build/bin/native/debugTest/test.kexe --ktest_gradle_filter=com.koushikdutta.scratch.KotlinBugs
```
