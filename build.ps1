$ErrorActionPreference = 'Stop'
$project = Split-Path -Parent $MyInvocation.MyCommand.Path
$classes = Join-Path $project 'build\classes'
$testClasses = Join-Path $project 'build\test-classes'
New-Item -ItemType Directory -Force -Path $classes, $testClasses | Out-Null
$mainSources = Get-ChildItem -LiteralPath (Join-Path $project 'src\main\java') -Filter '*.java' -Recurse | ForEach-Object FullName
$testSources = Get-ChildItem -LiteralPath (Join-Path $project 'src\test\java') -Filter '*.java' -Recurse | ForEach-Object FullName
javac --release 21 -encoding UTF-8 -d $classes $mainSources
javac --release 21 -encoding UTF-8 -cp $classes -d $testClasses $testSources
java -ea -cp "$classes;$testClasses" com.example.gateway.AllTests
