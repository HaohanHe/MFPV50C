# Windows headless verification (PowerShell counterpart to run_headless.sh).
# Drives the REAL classes shipped on main; no Minecraft, no display.
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)

$Jdk25 = 'D:\jdk25'
$Jdk21 = 'D:\jdk21'
$Gradle = 'D:\gradle-dist\gradle-9.8.0\bin\gradle.bat'
$GradleHome = 'D:\gradle-home'

$env:JAVA_HOME = $Jdk25
$env:GRADLE_USER_HOME = $GradleHome

Write-Host '>> compileKotlin'
& $Gradle --no-daemon -q -p $Root compileKotlin "-Dorg.gradle.java.installations.paths=$Jdk21"

function Find-Jar($group, $filter) {
  $base = Join-Path $GradleHome "caches/modules-2/files-2.1/$group"
  if (Test-Path $base) {
    $j = Get-ChildItem -Path $base -Recurse -Filter $filter | Select-Object -First 1
    if ($j) { return $j.FullName }
  }
  return $null
}
$Joml = Find-Jar 'org.joml/joml' 'joml-*.jar'
$Gson = Find-Jar 'com.google.code.gson/gson' 'gson-*.jar'
$Kstd = Find-Jar 'org.jetbrains.kotlin/kotlin-stdlib' 'kotlin-stdlib-2.4.20.jar'
if (-not $Joml -or -not $Kstd) { throw 'missing dependency jars; point GRADLE_USER_HOME at a populated cache' }

$CpItems = @("$Root/build/classes/kotlin/main", $Joml, $Kstd)
if ($Gson) { $CpItems += $Gson }
$Cp = [string]::Join(';', $CpItems)

$Out = Join-Path $Root 'headless/out'
New-Item -ItemType Directory -Force -Path $Out | Out-Null
Write-Host '>> javac harness'
& "$Jdk21/bin/javac.exe" -cp $Cp -d $Out "$Root/headless/FlightControlCheck.java"

Write-Host '>> run'
& "$Jdk21/bin/java.exe" -cp "$Out;$Cp" FlightControlCheck
