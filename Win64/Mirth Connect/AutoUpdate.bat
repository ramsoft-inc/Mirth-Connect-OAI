@echo OFF
SET ThisScriptsDirectory=%~dp0
SET PowerShellScriptPath=%ThisScriptsDirectory%\getLatestMirthOAIAutoUpdate.ps1
SET latestInstallerUrl=%1
SET latestVersion=%2
SET tempDownloadPath=%3
SET unZipFileLocation=%4
SET result=

@REM Set PowerShell execution policy unrestricted to allow to run ps script
powershell.exe -command "& {Set-ExecutionPolicy Unrestricted -Scope CurrentUser -Force}"

for /f "delims=" %%a in ('powershell.exe -file "%PowerShellScriptPath%" -latestInstallerUrl %latestInstallerUrl% -latestVersion %latestVersion% -tempDownloadPath %tempDownloadPath% -unZipFileLocation %unZipFileLocation%') do set "result=%%a"

if %result% neq 0 (
	ECHO %result%
	EXIT /B 1
)
EXIT /B 0