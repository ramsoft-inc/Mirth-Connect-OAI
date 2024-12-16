[CmdletBinding()]
param (
        [Parameter(Mandatory=$False)][string]$latestInstallerUrl = "__latestInstallerUrl__",
        [Parameter(Mandatory=$False )][string]$latestVersion = "__latestVersion__",
        [Parameter(Mandatory=$False )][string]$tempDownloadPath = "__tempDownloadPath__",
        [Parameter(Mandatory=$False )][string]$unZipFileLocation = "__unZipFileLocation__"
)
Write-Host  "Working on MirthOAI AutoUpdate installation"
Write-Host  "latestInstallerUrl: $latestInstallerUrl"
Write-Host  "latestVersion: $latestVersion"
Write-Host  "tempDownloadPath: $tempDownloadPath"
Write-Host  "unZipFileLocation: $unZipFileLocation"

$exepath = "{0}\MirthOAIAutoUpdate-{1}.zip" -f $tempDownloadPath, $latestVersion;
Write-Host  "execution path: $exepath"
$resultPS = "0";
try
{
    if (Test-Path -Path $exepath){
        Remove-Item $exepath;
    }

    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $latestInstallerUrl -OutFile $exepath;
    Expand-Archive -Path $exepath -DestinationPath $unZipFileLocation -Force
}
catch 
{
    Write-Host  "Error: $_.Exception.Message";
    $resultPS = "Error: $_.Exception.Message";
}
return $resultPS