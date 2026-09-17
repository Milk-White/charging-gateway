param([string]$BaseUrl = 'http://localhost:8080')
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $project 'build.ps1')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
java "-Ddemo.baseUrl=$BaseUrl" '-Dfile.encoding=UTF-8' -cp (Join-Path $project 'build\classes') com.example.gateway.simulator.ProtocolDemoApplication
