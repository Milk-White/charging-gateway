param([string]$BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
[Console]::InputEncoding = $utf8
[Console]::OutputEncoding = $utf8
$OutputEncoding = $utf8
if ($env:OS -eq 'Windows_NT') {
    & "$env:SystemRoot\System32\chcp.com" 65001 | Out-Null
}
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $project 'build.ps1')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
java "-Ddemo.baseUrl=$BaseUrl" '-Dfile.encoding=UTF-8' '-Dstdout.encoding=UTF-8' '-Dstderr.encoding=UTF-8' -cp (Join-Path $project 'build\classes') com.example.gateway.simulator.ProtocolDemoApplication
