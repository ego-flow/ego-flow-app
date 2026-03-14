#!/bin/bash

cd /home/js1044k/EgoFlow/samples/CameraAccessAndroid

sed -i 's/\r$//' gradlew
sed -i '/^org\.gradle\.java\.home=/d' gradle.properties

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
export GITHUB_TOKEN='your_classic_pat'

./gradlew assembleDebug

"/mnt/c/Users/Jinsu Kim/AppData/Local/Android/Sdk/platform-tools/adb.exe" devices
"/mnt/c/Users/Jinsu Kim/AppData/Local/Android/Sdk/platform-tools/adb.exe" install -r app/build/outputs/apk/debug/app-debug.apk
"/mnt/c/Users/Jinsu Kim/AppData/Local/Android/Sdk/platform-tools/adb.exe" shell am start -n com.meta.wearable.dat.externalsampleapps.cameraaccess/.MainActivity
