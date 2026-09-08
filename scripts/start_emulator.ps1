$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'workspace-path.ps1')
$projectRoot = Get-LedgerBuildRoot
$env:ANDROID_HOME = Join-Path $projectRoot '.tools\android-sdk'
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:ANDROID_USER_HOME = Join-Path $projectRoot '.tools\android-user'
$env:ANDROID_AVD_HOME = Join-Path $projectRoot '.tools\avd'
New-Item -ItemType Directory -Force -Path $env:ANDROID_AVD_HOME,$env:ANDROID_USER_HOME | Out-Null
$avdPath = Join-Path $env:ANDROID_AVD_HOME 'LedgerTestAscii.avd'
New-Item -ItemType Directory -Force -Path $avdPath | Out-Null
@"
avd.ini.encoding=UTF-8
path=$avdPath
target=android-30
"@ | Set-Content -LiteralPath (Join-Path $env:ANDROID_AVD_HOME 'LedgerTestAscii.ini') -Encoding utf8
$imagePath = Join-Path $env:ANDROID_HOME 'system-images\android-30\default\x86_64\'
@"
AvdId=LedgerTestAscii
avd.ini.displayname=LedgerTestAscii
abi.type=x86_64
hw.cpu.arch=x86_64
hw.cpu.ncore=2
hw.ramSize=1536
hw.lcd.width=720
hw.lcd.height=1280
hw.lcd.density=320
hw.gpu.enabled=yes
hw.gpu.mode=swiftshader_indirect
hw.keyboard=yes
hw.mainKeys=no
image.sysdir.1=$imagePath
tag.id=default
tag.display=Default
disk.dataPartition.size=1536M
showDeviceFrame=no
"@ | Set-Content -LiteralPath (Join-Path $avdPath 'config.ini') -Encoding utf8
$emulator = Join-Path $env:ANDROID_HOME 'emulator\emulator.exe'
Start-Process -FilePath $emulator -ArgumentList '-avd','LedgerTestAscii','-port','5580','-no-window','-no-audio','-no-snapshot','-no-boot-anim','-accel','off','-gpu','swiftshader_indirect','-feature','-Vulkan' -WindowStyle Hidden -RedirectStandardOutput (Join-Path $projectRoot 'reports\emulator-stdout.log') -RedirectStandardError (Join-Path $projectRoot 'reports\emulator-stderr.log') -PassThru | Select-Object Id
