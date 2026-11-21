[CmdletBinding(DefaultParameterSetName = 'Keystore')]
param(
    [Parameter(Mandatory = $true)][string]$SdkRoot,
    [Parameter(ParameterSetName = 'Keystore')]
    [string]$KeystorePath = (Join-Path $env:LOCALAPPDATA 'Mundru\Signing\release.jks'),
    [Parameter(ParameterSetName = 'WindowsStore', Mandatory = $true)]
    [switch]$WindowsStore
)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$privateDir = Join-Path $env:LOCALAPPDATA 'Mundru\Signing'
$release = Join-Path $root 'app\build\outputs\apk\release'
$meta = Get-Content -LiteralPath (Join-Path $release 'output-metadata.json') -Raw | ConvertFrom-Json
$item = @($meta.elements)[0]
if ($meta.applicationId -ne 'com.lumina.cleaner' -or $item.outputFile -ne 'app-release-unsigned.apk') {
    throw 'Build the unsigned Mundru Release first.'
}
if ($WindowsStore) {
    $expectedCert = '5a1c8c181ed9e6406c47d27fb440cef0851c69f148be58748601234af3b390dc'
    $alias = 'Mundru Android Release v1.5'
    $matching = @(Get-ChildItem Cert:\CurrentUser\My | Where-Object {
        $_.FriendlyName -eq $alias -and $_.HasPrivateKey -and
        $_.GetCertHashString([Security.Cryptography.HashAlgorithmName]::SHA256) -eq $expectedCert
    })
    if ($matching.Count -ne 1) {
        throw 'The Mundru 1.5 release certificate and private key are not in the current Windows account.'
    }
    # The native Windows store uses the signed-in account, not a keystore password.
    $signingOptions = @('--ks', 'NONE', '--ks-type', 'Windows-MY', '--ks-key-alias', $alias,
        '--ks-pass', 'pass:', '--v2-signing-enabled', 'true', '--v3-signing-enabled', 'true')
} else {
    $expectedCert = '197d9edbf50ca323cea8c4fec6f0e7cd9625235e22271a510636b7ca10128d72'
    $key = (Get-Item -LiteralPath $KeystorePath).FullName
    $repoPath = [IO.Path]::GetFullPath($root).TrimEnd('\') + '\'
    if ($key.StartsWith($repoPath, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'Keep the signing keystore outside the repository.'
    }
    $signingOptions = @('--ks', $key)
    Write-Host 'Enter the keystore password only at the Android signing tool prompt.'
}
$unsigned = Get-Item -LiteralPath (Join-Path $release $item.outputFile)
$signer = (Get-Item -LiteralPath (Join-Path $SdkRoot 'build-tools\35.0.0\apksigner.bat')).FullName
[void](New-Item -ItemType Directory -Path $privateDir -Force)
$out = Join-Path $privateDir ("Mundru-{0}-{1}.apk" -f $item.versionName, $item.versionCode)
if (Test-Path -LiteralPath $out) { throw "Output exists; archive it before signing another build: $out" }
$stage = Join-Path $privateDir ("Mundru-signing-{0}.apk" -f [guid]::NewGuid())
try {
    & $signer sign @signingOptions --v4-signing-enabled false --out $stage $unsigned.FullName
    if ($LASTEXITCODE) { throw 'APK signing failed.' }
    $result = (& $signer verify --verbose --print-certs $stage) -join "`n"
    if ($LASTEXITCODE -or
        $result -notmatch '(?m)^Number of signers: 1\s*$' -or
        $result -notmatch "(?m)^Signer #1 certificate SHA-256 digest: ${expectedCert}\s*$") {
        throw 'APK signer does not match the selected Mundru release certificate.'
    }
    Move-Item -LiteralPath $stage -Destination $out
} finally {
    if (Test-Path -LiteralPath $stage) { Remove-Item -LiteralPath $stage }
}
Write-Host "Verified APK: $out"
Write-Host 'No password was saved and no release was published.'
