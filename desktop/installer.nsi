; Installeur JoynixScribe pour Windows (NSIS, installation par utilisateur, sans admin)
Unicode true
!include "MUI2.nsh"
!include "FileFunc.nsh"

!define APP_NAME "JoynixScribe"
!define APP_VERSION "2.4.0"
!define APP_PUBLISHER "Joynix"
!define APP_EXE "JoynixScribe.exe"
!define APP_ID "com.joynix.joynixscribe"
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_NAME}"

Name "${APP_NAME} ${APP_VERSION}"
OutFile "JoynixScribe-Setup-${APP_VERSION}.exe"
InstallDir "$LOCALAPPDATA\Programs\${APP_NAME}"
InstallDirRegKey HKCU "Software\${APP_NAME}" "InstallDir"
RequestExecutionLevel user
SetCompressor /SOLID lzma
BrandingText "${APP_NAME} — dictée vocale IA"
ShowInstDetails show
ShowUninstDetails show

!define MUI_ABORTWARNING
!define MUI_ICON "src-tauri\icons\icon.ico"
!define MUI_UNICON "src-tauri\icons\icon.ico"
!define MUI_WELCOMEPAGE_TITLE "Installer ${APP_NAME}"
!define MUI_WELCOMEPAGE_TEXT "JoynixScribe transforme votre voix en texte avec Gemini, Groq, Cloudflare ou Aperture.$\r$\n$\r$\nAucune capture d'écran, aucune télémétrie : seul l'audio part chez le fournisseur que vous choisissez.$\r$\n$\r$\nL'installation se fait pour votre compte Windows, sans droits administrateur."
!define MUI_FINISHPAGE_RUN "$INSTDIR\${APP_EXE}"
!define MUI_FINISHPAGE_RUN_TEXT "Lancer ${APP_NAME}"
!define MUI_FINISHPAGE_SHOWREADME ""
!define MUI_FINISHPAGE_SHOWREADME_NOTCHECKED
!define MUI_FINISHPAGE_LINK "Dépôt GitHub"
!define MUI_FINISHPAGE_LINK_LOCATION "https://github.com/joynix28/joynixscribe"

!insertmacro MUI_PAGE_WELCOME
!insertmacro MUI_PAGE_COMPONENTS
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES

!insertmacro MUI_LANGUAGE "French"

LangString DESC_APP ${LANG_FRENCH} "Application JoynixScribe et fichiers nécessaires."
LangString DESC_DESKTOP ${LANG_FRENCH} "Ajoute un raccourci JoynixScribe sur le Bureau."
LangString DESC_AUTOSTART ${LANG_FRENCH} "Démarre JoynixScribe automatiquement à l'ouverture de session."

Section "Application JoynixScribe" SEC_APP
  SectionIn RO
  SetOutPath "$INSTDIR"
  SetOverwrite on

  ; Ferme une instance éventuellement ouverte pour pouvoir remplacer l'exécutable.
  nsExec::Exec 'taskkill /IM "${APP_EXE}" /F'
  Pop $0
  Sleep 400

  File "/oname=${APP_EXE}" "src-tauri\target\x86_64-pc-windows-gnu\release\joynixscribe-desktop.exe"
  ; Le runtime WebView2 est fourni par Windows, mais son chargeur (binaire
  ; GNU) doit accompagner l'exécutable, sinon l'application ne démarre pas.
  File "/oname=WebView2Loader.dll" "src-tauri\target\x86_64-pc-windows-gnu\release\WebView2Loader.dll"
  File "/oname=LICENSE.txt" "..\LICENSE"
  File "/oname=README.txt" "..\README.md"

  WriteUninstaller "$INSTDIR\Uninstall.exe"

  CreateDirectory "$SMPROGRAMS\${APP_NAME}"
  CreateShortcut "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\${APP_EXE}" 0
  CreateShortcut "$SMPROGRAMS\${APP_NAME}\Désinstaller.lnk" "$INSTDIR\Uninstall.exe"

  WriteRegStr HKCU "Software\${APP_NAME}" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "Software\${APP_NAME}" "Version" "${APP_VERSION}"

  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName" "${APP_NAME}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion" "${APP_VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "Publisher" "${APP_PUBLISHER}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon" "$INSTDIR\${APP_EXE}"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegStr HKCU "${UNINST_KEY}" "QuietUninstallString" '"$INSTDIR\Uninstall.exe" /S'
  WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "URLInfoAbout" "https://github.com/joynix28/joynixscribe"
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1

  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  IntFmt $0 "0x%08X" $0
  WriteRegDWORD HKCU "${UNINST_KEY}" "EstimatedSize" "$0"
SectionEnd

Section "Raccourci sur le Bureau" SEC_DESKTOP
  CreateShortcut "$DESKTOP\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}" "" "$INSTDIR\${APP_EXE}" 0
SectionEnd

Section /o "Démarrer avec Windows" SEC_AUTOSTART
  WriteRegStr HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "${APP_NAME}" '"$INSTDIR\${APP_EXE}"'
SectionEnd

!insertmacro MUI_FUNCTION_DESCRIPTION_BEGIN
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_APP} $(DESC_APP)
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_DESKTOP} $(DESC_DESKTOP)
  !insertmacro MUI_DESCRIPTION_TEXT ${SEC_AUTOSTART} $(DESC_AUTOSTART)
!insertmacro MUI_FUNCTION_DESCRIPTION_END

Section "Uninstall"
  nsExec::Exec 'taskkill /IM "${APP_EXE}" /F'
  Pop $0

  Delete "$DESKTOP\${APP_NAME}.lnk"
  Delete "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk"
  Delete "$SMPROGRAMS\${APP_NAME}\Désinstaller.lnk"
  RMDir "$SMPROGRAMS\${APP_NAME}"

  Delete "$INSTDIR\${APP_EXE}"
  Delete "$INSTDIR\WebView2Loader.dll"
  Delete "$INSTDIR\LICENSE.txt"
  Delete "$INSTDIR\README.txt"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir "$INSTDIR"

  DeleteRegValue HKCU "Software\Microsoft\Windows\CurrentVersion\Run" "${APP_NAME}"
  DeleteRegKey HKCU "Software\${APP_NAME}"
  DeleteRegKey HKCU "${UNINST_KEY}"

  MessageBox MB_YESNO|MB_ICONQUESTION "Supprimer aussi vos réglages, vos clés API et votre historique ?" IDNO KeepData
  RMDir /r "$APPDATA\${APP_ID}"
  KeepData:
SectionEnd
