!define APPNAME "JoynixScribe"
!define COMPANY "Joynix"
!define VERSION "2.2.0"
!define EXE "JoynixScribe.exe"

Name "${APPNAME}"
OutFile "JoynixScribe-Setup-2.2.0.exe"
InstallDir "$PROGRAMFILES64\${APPNAME}"
RequestExecutionLevel admin

Page directory
Page instfiles

Section "Install"
    SetOutPath "$INSTDIR"
    File "target\x86_64-pc-windows-gnu\release\${EXE}"
    CreateDirectory "$SMPROGRAMS\${APPNAME}"
    CreateShortcut "$SMPROGRAMS\${APPNAME}\${APPNAME}.lnk" "$INSTDIR\${EXE}"
    CreateShortcut "$DESKTOP\${APPNAME}.lnk" "$INSTDIR\${EXE}"
    WriteUninstaller "$INSTDIR\Uninstall.exe"
SectionEnd

Section "Uninstall"
    Delete "$INSTDIR\${EXE}"
    Delete "$INSTDIR\Uninstall.exe"
    RMDir "$INSTDIR"
    Delete "$SMPROGRAMS\${APPNAME}\${APPNAME}.lnk"
    RMDir "$SMPROGRAMS\${APPNAME}"
    Delete "$DESKTOP\${APPNAME}.lnk"
SectionEnd