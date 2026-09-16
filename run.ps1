$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
& (Join-Path $project 'build.ps1')
java -cp (Join-Path $project 'build\classes') com.example.gateway.GatewayApplication
