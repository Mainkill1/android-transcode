$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$jar = Join-Path $root 'gradle/wrapper/gradle-wrapper.jar'
$hash = '81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f'
if (!(Test-Path $jar)) {
    $temp = "$jar.$([guid]::NewGuid()).tmp"
    try {
        Invoke-WebRequest -UseBasicParsing -Uri 'https://raw.githubusercontent.com/gradle/gradle/v8.13.0/gradle/wrapper/gradle-wrapper.jar' -OutFile $temp
        if ((Get-FileHash $temp -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) { throw 'Gradle wrapper checksum mismatch.' }
        Move-Item $temp $jar -Force
    } finally { if (Test-Path $temp) { Remove-Item $temp } }
}
if ((Get-FileHash $jar -Algorithm SHA256).Hash.ToLowerInvariant() -ne $hash) { throw 'Gradle wrapper checksum mismatch.' }
$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
& $java '-Dorg.gradle.appname=gradlew' '-classpath' $jar 'org.gradle.wrapper.GradleWrapperMain' @args
exit $LASTEXITCODE
