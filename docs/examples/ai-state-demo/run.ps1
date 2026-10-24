param(
    [string]$MavenCommand = "mvn",
    [switch]$Offline
)

$ErrorActionPreference = "Stop"
$projectFile = Join-Path $PSScriptRoot "pom.xml"
$buildArgs = @("-q", "-f", $projectFile, "compile", "dependency:build-classpath", "-Dmdep.outputFile=target/classpath.txt")
if ($Offline) { $buildArgs = @("-o") + $buildArgs }

& $MavenCommand @buildArgs
if ($LASTEXITCODE -ne 0) { throw "示例编译或依赖解析失败" }

$dependencyClasspath = (Get-Content -LiteralPath (Join-Path $PSScriptRoot "target/classpath.txt") -Raw).Trim()
$classDirectory = Join-Path $PSScriptRoot "target/classes"
$classpath = $classDirectory + [IO.Path]::PathSeparator + $dependencyClasspath
& java "-Dfile.encoding=UTF-8" "-cp" $classpath "com.qiheng.erp.examples.StateManagementDemo"
if ($LASTEXITCODE -ne 0) { throw "State 示例验证失败" }
