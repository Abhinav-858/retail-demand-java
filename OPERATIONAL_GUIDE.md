# Retail Demand System: Operational Guide

This guide provides instructions for managing and using the Retail Demand Intelligence Pipeline and its associated dashboard.

## 🚀 Getting Started

### 1. Starting the Server
To start the application, open your terminal in the project root and run:
```powershell
java -jar target/retail-demand-system.jar
```
The server will start on port **8080**.

### 2. Accessing the Dashboard
Once the server is running, open your web browser and navigate to:
**[http://localhost:8080](http://localhost:8080)**

---

## 🛠️ Server Management

### Stopping the Server
To shut down the server, go to the terminal where it is running and press:
`Ctrl` + `C`

### Rebuilding After Code Changes
If you modify any Java source files, you must rebuild the project before the changes take effect:
1. Stop the server (`Ctrl` + `C`).
2. Run the build command:
   ```powershell
   mvn compile package -DskipTests
   ```
3. Restart the server using the `java -jar` command mentioned above.

---

## 📊 Using the Dashboard

### Running the Pipeline
- **Budget Input**: Adjust the "Budget ($)" field in the top navigation bar to set the resource limit for inventory allocation.
- **Run Pipeline**: Click the **Run Pipeline** button to execute the ML clustering, forecasting, and optimization logic.

### Monitoring System Status
The **System Status** tab (located in the bottom panel) provides real-time health metrics:
- **Health Card**: Monitor backend uptime and ensure the SQLite database is "CONNECTED".
- **Pipeline Card**: Check the timestamp of the last successful run and cache status.
- **Config Card**: View the current active parameters (Budget, Forecast Horizon, etc.).
- **Latency Monitoring**: Observe the round-trip latency (ms) for each API endpoint.

---

## 📁 Project Structure
- `/src/main/java`: Backend logic (Java).
- `/public`: Frontend assets (HTML, CSS, JS).
- `/target`: Compiled JAR and build artifacts.
- `pom.xml`: Maven configuration and dependencies.
