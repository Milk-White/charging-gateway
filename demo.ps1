$base = 'http://localhost:8080'
$body = @{
    deviceSn = 'pile001'
    status = 'CHARGING'
    voltage = 380.5
    current = 32.25
    faultCode = ''
} | ConvertTo-Json

Invoke-RestMethod -Method Post -Uri "$base/api/simulator/report" -ContentType 'application/json' -Body $body
Invoke-RestMethod -Method Get -Uri "$base/api/devices/pile001/latest"
Invoke-RestMethod -Method Get -Uri "$base/api/devices/pile001/history?limit=10"
