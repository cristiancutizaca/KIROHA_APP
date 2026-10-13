# Build and test

## Environment

```bash
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export ANDROID_HOME=/home/ubuntu/android-sdk
```

## Core tests and Android compilation

```bash
./gradlew :kiroha-core:test :data:compileDebugKotlin :app:compileDebugKotlin --stacktrace --no-daemon
```

## Clean final build

```bash
./gradlew clean :kiroha-core:test :app:assembleDebug --stacktrace --no-daemon
cp app/build/outputs/apk/debug/app-x86_64-debug.apk Kiroha_v0.1_debug.apk
```

The sandbox currently produces the x86_64 debug variant. Hardware builds may expose additional ABI variants.

## Git bundle

```bash
git bundle create Kiroha-history.bundle --all
git bundle verify Kiroha-history.bundle
```
