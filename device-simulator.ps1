param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$DeviceSn = 'pile001',
    [string]$Password = 'pwd001'
)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = New-Object System.Text.UTF8Encoding($false)
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $project 'build.ps1')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
java "-Dsimulator.baseUrl=$BaseUrl" "-Dsimulator.sn=$DeviceSn" "-Dsimulator.password=$Password" '-Dfile.encoding=UTF-8' -cp (Join-Path $project 'build\classes') com.example.gateway.simulator.DeviceSimulatorApplication
