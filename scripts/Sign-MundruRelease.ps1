param(
    [Parameter(Mandatory = $true)][string]$SdkRoot,
    [string]$KeystorePath = (Join-Path $env:LOCALAPPDATA 'Mundru\Signing\release.jks')
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$privateDir = Join-Path $env:LOCALAPPDATA 'Mundru\Signing'
$expectedCert = '197d9edbf50ca323cea8c4fec6f0e7cd9625235e22271a510636b7ca10128d72'
$release = Join-Path $root 'app\build\outputs\apk\release'
$meta = Get-Content -LiteralPath (Join-Path $release 'output-metadata.json') -Raw | ConvertFrom-Json
$item = @($meta.elements)[0]
if ($meta.applicationId -ne 'com.lumina.cleaner' -or $item.outputFile -ne 'app-release-unsigned.apk') {
    throw 'Build the unsigned Mundru Release first.'
}
$key = (Get-Item -LiteralPath $KeystorePath).FullName
$repoPath = [IO.Path]::GetFullPath($root).TrimEnd('\') + '\'
if ($key.StartsWith($repoPath, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'Keep the signing keystore outside the repository.'
}
$unsigned = Get-Item -LiteralPath (Join-Path $release $item.outputFile)
$signer = (Get-Item -LiteralPath (Join-Path $SdkRoot 'build-tools\35.0.0\apksigner.bat')).FullName
[void](New-Item -ItemType Directory -Path $privateDir -Force)
$out = Join-Path $privateDir ("Mundru-{0}-{1}.apk" -f $item.versionName, $item.versionCode)
if (Test-Path -LiteralPath $out) { throw "Output exists; archive it before signing another build: $out" }
Write-Host 'Enter the keystore password only at the Android signing tool prompt.'
& $signer sign --ks $key --out $out $unsigned.FullName
if ($LASTEXITCODE) { throw 'APK signing failed.' }
$result = (& $signer verify --verbose --print-certs $out) -join "`n"
if ($LASTEXITCODE -or $result -notmatch "(?m)^Signer #1 certificate SHA-256 digest: ${expectedCert}\s*$") {
    throw 'APK signer does not match the published Mundru certificate. Do not distribute this output.'
}
Write-Host "Verified APK: $out"
Write-Host 'No password was saved and no release was published.'
