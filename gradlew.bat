@ECHO OFF
SET DIRNAME=%~dp0
IF "%DIRNAME%"=="" SET DIRNAME=.
SET APP_BASE_NAME=%~n0
SET APP_HOME=%DIRNAME%
FOR %%i IN ("%APP_HOME%") DO SET APP_HOME=%%~fi

SET DEFAULT_JVM_OPTS="-Xmx64m" "-Xms64m"

IF EXIST "%APP_HOME%\gradle\wrapper\gradle-wrapper.jar" (
  SET CLASSPATH=%APP_HOME%\gradle\wrapper\gradle-wrapper.jar
) ELSE (
  ECHO ERROR: gradle-wrapper.jar not found. Please generate wrapper in Android Studio.
  EXIT /B 1
)

"%JAVA_HOME%\bin\java.exe" %DEFAULT_JVM_OPTS% %JAVA_OPTS% %GRADLE_OPTS% -classpath "%CLASSPATH%" org.gradle.wrapper.GradleWrapperMain %*
