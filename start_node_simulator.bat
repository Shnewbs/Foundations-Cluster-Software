@echo off
setlocal
cd /d "%~dp0"
if not exist "node-simulator\build\FoundationsClusterNodeSimulator.jar" call node-simulator\build_simulator.bat
if errorlevel 1 exit /b 1
if not exist "runtime\cluster-agent.token" (
  echo Start the Cluster Manager once first so the agent token can be generated.
  exit /b 1
)
set /p FOUNDATIONS_CLUSTER_AGENT_TOKEN=<runtime\cluster-agent.token
set FOUNDATIONS_CLUSTER_MANAGER_URL=http://127.0.0.1:8765
set FOUNDATIONS_CLUSTER_WORKER_ID=sim-worker-001
set FOUNDATIONS_CLUSTER_WORKER_PORT=25599
java -jar "node-simulator\build\FoundationsClusterNodeSimulator.jar" --players 12 --mspt 18.5
