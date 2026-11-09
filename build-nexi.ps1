# Nexi Player, derived from Myspoti, GPL-3.0.
param(
    [ValidateSet('Debug', 'Release')][string]$Configuration = 'Debug',
    [ValidateSet('arm64-v8a', 'armeabi-v7a', 'x86_64')][string]$Abi = 'arm64-v8a'
)
$ErrorActionPreference = 'Stop'
$base = Join-Path $PSScriptRoot 'base'
if ($Configuration -eq 'Release' -and !$env:MYSPOTI_KEYSTORE) {
    throw 'Set MYSPOTI_KEYSTORE, MYSPOTI_KEYSTORE_PASSWORD, MYSPOTI_KEY_ALIAS and MYSPOTI_KEY_PASSWORD to your own signing key.'
}
$buildArgs = @('-p', $base, ":app:assemble$Configuration", '-PnexiBuild', "-Pabis=$Abi",
    '--console=plain')
& (Join-Path $base 'gradlew.bat') @buildArgs
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
