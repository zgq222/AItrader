param(
    [string]$AndroidSdk = "$env:LOCALAPPDATA\AItrader-android-sdk",
    [string]$JavaDirectory = 'C:\Program Files\Android\openjdk\jdk-21.0.8'
)
$ErrorActionPreference = 'Stop'
# Never allow Gradle to generate a replacement debug key for an existing app.
$originalKey = Join-Path $env:USERPROFILE '.android\debug.keystore'
if (-not (Test-Path -LiteralPath $originalKey)) {
    throw '原安装签名密钥缺失。请恢复原密钥后构建，不能重新生成密钥并要求用户卸载。'
}
$env:JAVA_HOME = $JavaDirectory
$env:ANDROID_HOME = $AndroidSdk
$gradle = Join-Path $AndroidSdk 'gradle-unpack\gradle-8.9\bin\gradle.bat'
Push-Location $PSScriptRoot
try {
    & $gradle :app:testDebugUnitTest :app:assembleDebug :app:lintDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw '测试或构建失败，未输出新版安装包。' }
    $apk = Join-Path $PSScriptRoot 'app\build\outputs\apk\debug\app-debug.apk'
    $signer = Join-Path $AndroidSdk 'build-tools\35.0.0\apksigner.bat'
    $aapt = Join-Path $AndroidSdk 'build-tools\35.0.0\aapt.exe'
    $certificate = (& $signer verify --print-certs $apk | Out-String)
    if ($LASTEXITCODE -ne 0 -or $certificate -notmatch '614ac1ad3d419e376f878b8d402eb78a2b7ef7a3ededd134f91993081e7c240a') {
        throw '签名与现有安装包不一致，停止发布。'
    }
    $metadata = (& $aapt dump badging $apk | Select-Object -First 1)
    if ($metadata -notmatch "name='com.aitrader.hammer1430' versionCode='(\d+)' versionName='([^']+)'") {
        throw '包名或版本信息异常，停止发布。'
    }
    $buildCode = [int]$Matches[1]
    $buildVersion = $Matches[2]
    $previous = Get-ChildItem -LiteralPath $PSScriptRoot -Filter 'AItrader-v*.apk' |
        Where-Object { $_.Name -ne "AItrader-v$buildVersion.apk" } |
        Sort-Object { [version]($_.BaseName -replace '^AItrader-v','') } -Descending | Select-Object -First 1
    if ($previous) {
        $oldMetadata = (& $aapt dump badging $previous.FullName | Select-Object -First 1)
        if ($oldMetadata -match "versionCode='(\d+)'" -and $buildCode -le [int]$Matches[1]) {
            throw 'versionCode 必须高于已发布版本，停止发布。'
        }
    }
    $destination = Join-Path $PSScriptRoot "AItrader-v$buildVersion.apk"
    Copy-Item -LiteralPath $apk -Destination $destination
    Write-Output "已通过测试、包名、版本和原签名检查：$destination"
} finally { Pop-Location }
