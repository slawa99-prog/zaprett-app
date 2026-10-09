@echo off
setlocal EnableExtensions EnableDelayedExpansion
cd /d "%~dp0"
set "EL_ADB=%~dp0adb.exe"
set "EL_JAR=%~dp0ethernet-usb-access.jar"
set "EL_REMOTE=/data/local/tmp/ethernet-link-usb-access-1.jar"

echo Ethernet Link - Samsung RTL8153 USB access - 1.0-test2
echo.
if not exist "!EL_ADB!" (
  echo Copy START.cmd and ethernet-usb-access.jar into your platform-tools folder.
  echo adb.exe must be in the same folder as START.cmd.
  goto finish
)
if not exist "!EL_JAR!" (
  echo Missing ethernet-usb-access.jar. Extract the whole ZIP first.
  goto finish
)

echo Keep wireless debugging connected, then plug the Ethernet adapter into the phone.
echo Available ADB connections:
"!EL_ADB!" devices -l > "usb-access-devices.txt" 2>&1
if errorlevel 1 (
  type "usb-access-devices.txt"
  echo Could not list ADB connections. Send usb-access-devices.txt for diagnosis.
  goto finish
)
type "usb-access-devices.txt"
echo.
set "EL_COUNT=0"
for /f "usebackq tokens=1,2" %%A in ("usb-access-devices.txt") do (
  if "%%B"=="device" (
    set /a EL_COUNT+=1
    set "EL_DEVICE_!EL_COUNT!=%%A"
  )
)
if "!EL_COUNT!"=="0" (
  echo No authorized, online phone was found.
  echo Enable wireless debugging and reconnect ADB. If shown as unauthorized, allow debugging on the phone.
  echo USB permission has not been changed. Save usb-access-devices.txt if you need help.
  goto finish
)
set "EL_TARGET="
if "!EL_COUNT!"=="1" (
  set "EL_TARGET=!EL_DEVICE_1!"
  goto selected
)

:selectdevice
echo More than one ADB connection is available:
for /l %%N in (1,1,!EL_COUNT!) do echo %%N - !EL_DEVICE_%%N!
echo The same phone can appear twice, by IP and by its adb-... name. Either connection works.
set "EL_INDEX="
set /p "EL_INDEX=Enter the connection number for your Samsung, or Q to quit: "
if /i "!EL_INDEX!"=="Q" goto finish
for /l %%N in (1,1,!EL_COUNT!) do (
  if "!EL_INDEX!"=="%%N" set "EL_TARGET=!EL_DEVICE_%%N!"
)
if not defined EL_TARGET (
  echo Enter a number from the list.
  goto selectdevice
)

:selected
echo Selected ADB connection: !EL_TARGET!

"!EL_ADB!" -s "!EL_TARGET!" get-state > "usb-access-result.txt" 2>&1
if errorlevel 1 (
  type "usb-access-result.txt"
  echo The selected connection was lost. Reconnect the phone and run START.cmd again.
  goto finish
)

echo.
echo 1 - CHECK current access; no settings changed
echo 2 - GRANT persistent USB access to Ethernet Link for this RTL8153
echo 3 - BLOCK access; this saves a denial, not the original permission prompt
echo Q - Quit
"%SystemRoot%\System32\choice.exe" /c 123Q /n /m "Choose 1, 2, 3 or Q: "
set "EL_CHOICE=!errorlevel!"
if "!EL_CHOICE!"=="4" goto finish
set "EL_MODE="
if "!EL_CHOICE!"=="1" set "EL_MODE=check"
if "!EL_CHOICE!"=="2" set "EL_MODE=grant"
if "!EL_CHOICE!"=="3" set "EL_MODE=block"
if not defined EL_MODE goto finish

if "!EL_MODE!"=="grant" (
  echo This grants com.slawa.ethernetlink.v2 ongoing access to this adapter.
  echo It does not enable automatic app launch.
  "%SystemRoot%\System32\choice.exe" /c YN /n /m "Apply this setting? Y/N: "
  if errorlevel 2 goto finish
)
if "!EL_MODE!"=="block" (
  echo Access will remain blocked until you use GRANT again.
  "%SystemRoot%\System32\choice.exe" /c YN /n /m "Store this denial? Y/N: "
  if errorlevel 2 goto finish
)

"!EL_ADB!" -s "!EL_TARGET!" push "!EL_JAR!" "!EL_REMOTE!" > "usb-access-result.txt" 2>&1
if errorlevel 1 (
  type "usb-access-result.txt"
  goto finish
)
"!EL_ADB!" -s "!EL_TARGET!" shell "CLASSPATH=!EL_REMOTE! app_process / com.slawa.ethernetlink.tools.UsbAccessHelper !EL_MODE!" >> "usb-access-result.txt" 2>&1
set "EL_EXIT=!errorlevel!"
"!EL_ADB!" -s "!EL_TARGET!" shell dumpsys usb > "usb-access-state.txt" 2>&1
set "EL_DUMP_EXIT=!errorlevel!"
"!EL_ADB!" -s "!EL_TARGET!" shell rm -f "!EL_REMOTE!" >nul 2>&1
echo.
type "usb-access-result.txt"
echo.
if not "!EL_EXIT!"=="0" echo The helper reported an error. Send usb-access-result.txt for diagnosis.
if not "!EL_DUMP_EXIT!"=="0" echo Could not read USB state; the phone may have disconnected.
echo Reports: usb-access-result.txt and usb-access-state.txt
echo Copy the reports before running this tool again; they are overwritten each time.
goto finish

:finish
echo.
pause
endlocal
