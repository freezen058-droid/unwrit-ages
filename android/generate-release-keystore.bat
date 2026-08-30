@echo off
"C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot\bin\keytool.exe" -genkeypair -v -keystore "C:\Users\user\forClaude\dapp\Unciv\android\civilwars-release.keystore" -alias civilwars -keyalg RSA -keysize 2048 -validity 10000
pause
