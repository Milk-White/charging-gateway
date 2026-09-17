$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
$classes = Join-Path $project 'build\classes'
$testClasses = Join-Path $project 'build\test-classes'
if (Test-Path -LiteralPath $classes) {
    Remove-Item -LiteralPath $classes -Recurse -Force
}
if (Test-Path -LiteralPath $testClasses) {
    Remove-Item -LiteralPath $testClasses -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $classes, $testClasses | Out-Null
$mainSources = Get-ChildItem -LiteralPath (Join-Path $project 'src\main\java') -Filter '*.java' -Recurse | ForEach-Object FullName
$testSources = Get-ChildItem -LiteralPath (Join-Path $project 'src\test\java') -Filter '*.java' -Recurse | ForEach-Object FullName
javac --release 21 -encoding UTF-8 -d $classes $mainSources
if ($LASTEXITCODE -ne 0) {
    throw "Main source compilation failed with exit code $LASTEXITCODE"
}
$resources = Join-Path $project 'src\main\resources'
if (Test-Path -LiteralPath $resources) {
    Copy-Item -Path (Join-Path $resources '*') -Destination $classes -Recurse -Force
}
javac --release 21 -encoding UTF-8 -cp $classes -d $testClasses $testSources
if ($LASTEXITCODE -ne 0) {
    throw "Test source compilation failed with exit code $LASTEXITCODE"
}
java -ea -cp "$classes;$testClasses" com.example.gateway.AllTests
if ($LASTEXITCODE -ne 0) {
    throw "Tests failed with exit code $LASTEXITCODE"
}
