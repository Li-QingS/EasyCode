@echo off
set "JAVA_HOME=D:\Java JDK"
set "M2_HOME=C:\ProgramData\chocolatey\lib\maven\apache-maven-3.9.16"
D:
cd "D:\agent project\EasyCode"
"C:\ProgramData\chocolatey\lib\maven\apache-maven-3.9.16\bin\mvn.cmd" compile
