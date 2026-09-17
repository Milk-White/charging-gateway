param(
    [string]$BaseUrl = 'http://localhost:8080'
)

$ErrorActionPreference = 'Stop'

function Show-Step([int]$Number, [string]$Title) {
    Write-Host ""
    Write-Host "[$Number/5] $Title" -ForegroundColor Cyan
}

Show-Step 1 '检查服务是否启动'
$health = Invoke-RestMethod -Method Get -Uri "$BaseUrl/health"
$health | ConvertTo-Json

Show-Step 2 '模拟 pile001 上报充电数据'
$body = @{
    deviceSn = 'pile001'
    status = 'CHARGING'
    voltage = 380.5
    current = 32.25
    faultCode = ''
} | ConvertTo-Json
$accepted = Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/simulator/report" `
    -ContentType 'application/json' -Body $body
$accepted | ConvertTo-Json

Show-Step 3 '查询 pile001 最新状态'
Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/devices/pile001/latest" | ConvertTo-Json

Show-Step 4 '查询 pile001 最近 10 条历史记录'
Invoke-RestMethod -Method Get -Uri "$BaseUrl/api/devices/pile001/history?limit=10" | ConvertTo-Json

Show-Step 5 '验证未登记设备会被拒绝'
$invalidBody = @{
    deviceSn = 'unknownDevice999'
    status = 'IDLE'
    voltage = 220
    current = 10
    faultCode = ''
} | ConvertTo-Json
try {
    Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/simulator/report" `
        -ContentType 'application/json' -Body $invalidBody
    throw '验证失败：未登记设备不应被接受'
} catch {
    if ($_.Exception.Response.StatusCode.value__ -ne 400) {
        throw
    }
    Write-Host '通过：接口返回 400，未登记设备没有入库。' -ForegroundColor Green
}

Write-Host ""
Write-Host '演示完成：健康检查、合法上报、最新查询、历史查询和异常拦截均已验证。' -ForegroundColor Green
