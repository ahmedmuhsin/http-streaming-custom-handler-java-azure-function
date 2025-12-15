# HTTP Streaming Server (Java 21)

A minimal Java 21 HTTP streaming server for Azure Functions custom handlers. Streams large file uploads and downloads without buffering entire payloads in memory using the JDK's built-in `HttpServer` and virtual threads.

## Prerequisites

- JDK 21 (tested with Microsoft Build of OpenJDK 21)
- Apache Maven 3.9+
- Azure Functions Core Tools v4 (for local testing)
- Azure Developer CLI (`azd`) for deployment

## Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/upload?filename=<name>` | Upload a file (raw binary body, not multipart) |
| `GET` | `/download?filename=<name>` | Download a previously uploaded file |
| `GET` | `/generate?sizeMB=<size>` | Stream random binary data (1-10000 MB) |

## Build

```powershell
# Build for local development (includes debug flags)
mvn clean package -Plocal

# Build for cloud deployment
mvn clean package -Pcloud
```

Output is placed in `target/azure-functions/http-streaming-function/`.

## Run

### Option 1: Azure Functions (recommended)

```powershell
cd target/azure-functions/http-streaming-function
func start
```

Server runs on `http://localhost:7071`

### Option 2: Standalone Java

```powershell
java -jar target/azure-functions/http-streaming-function/http-streaming-0.1.0-SNAPSHOT.jar
```

Server runs on `http://localhost:8080`

## Environment Variables

| Name | Default | Description |
|------|---------|-------------|
| `FUNCTIONS_CUSTOMHANDLER_PORT` | - | Set automatically by Azure Functions runtime |
| `HTTP_STREAMING_PORT` | `8080` | Port for standalone mode |
| `HTTP_STREAMING_STORAGE` | `storage` | Directory for uploaded files |

## Try It Out

### Using demo.http (VS Code REST Client)

Open `demo.http` in VS Code with the REST Client extension installed and click "Send Request" on any endpoint.

### Using curl

```bash
# Upload a file
curl -X POST "http://localhost:7071/upload?filename=test.bin" --data-binary @"sample.bin"

# Download a file
curl -o "downloaded.bin" "http://localhost:7071/download?filename=test.bin"

# Generate and download 100MB of random data (streaming demo)
curl -o "generated.bin" "http://localhost:7071/generate?sizeMB=100"
```

### Using PowerShell

```powershell
# Upload
Invoke-RestMethod -Uri "http://localhost:7071/upload?filename=test.bin" -Method Post -InFile "sample.bin" -ContentType "application/octet-stream"

# Download
Invoke-WebRequest -Uri "http://localhost:7071/download?filename=test.bin" -OutFile "downloaded.bin"

# Generate (streaming)
Invoke-WebRequest -Uri "http://localhost:7071/generate?sizeMB=100" -OutFile "generated.bin"
```

## How Streaming Works

This server demonstrates true HTTP streaming:

- **Uploads**: Stream directly to disk via `Files.copy(InputStream, Path)` — memory stays flat even for multi-gigabyte files
- **Downloads**: Stream via `InputStream.transferTo(OutputStream)` — file is never fully loaded into memory
- **Generate**: Produces random data in 64KB chunks — proves streaming by generating files larger than available heap

### Prove It Works

Generate a 500MB file with only 256MB heap:

```powershell
java -Xmx256m -jar target/azure-functions/http-streaming-function/http-streaming-0.1.0-SNAPSHOT.jar
curl.exe -o "large.bin" "http://localhost:8080/generate?sizeMB=500"
```

If it completes without `OutOfMemoryError`, streaming is working!

## Azure Functions Custom Handler

This project uses `enableProxyingHttpRequest: true` in `host.json`, which means the Functions host proxies raw HTTP requests directly to the Java server. This enables true streaming without the request/response buffering that occurs with the standard Java worker.

## Deploy to Azure

### Initial Deployment

Use Azure Developer CLI to provision resources and deploy:

```powershell
# Build for cloud deployment
mvn clean package -Pcloud

# Provision Azure resources and deploy (first time)
azd up
```

This will:
- Create a resource group
- Provision an Azure Functions app (Linux, Flex Consumption plan)
- Deploy your function app

### Get Your Function URL and Key

After deployment, get your function URL and access key:

```powershell
# Get the function app name from azd
azd env get-values

# List function keys using Azure CLI
az functionapp keys list --name <function-app-name> --resource-group <resource-group-name>
```

Or via the Azure Portal:
1. Navigate to your Function App
2. Go to **Functions** → **function-handler** → **Function Keys**
3. Copy the `default` key

### Call Deployed Endpoints

Use the function key in your requests:

```powershell
# With query parameter
curl "https://<your-function-app>.azurewebsites.net/generate?sizeMB=10&code=<your-function-key>"

# Or with header
curl -H "x-functions-key: <your-function-key>" "https://<your-function-app>.azurewebsites.net/generate?sizeMB=10"
```

### Update After Changes

When you make code changes:

```powershell
# Rebuild
mvn clean package -Pcloud

# Package and deploy (without reprovisioning)
azd package
azd deploy
```

Or in one step:

```powershell
mvn clean package -Pcloud && azd package && azd deploy
```

### Clean Up Resources

When you're done with the sample, delete all Azure resources:

```powershell
azd down
```

This removes the resource group and all resources created by `azd up`.

## Project Structure

```
├── src/main/java/com/microsoft/azure/
│   └── FileStreamingServer.java    # Main server implementation
├── src/test/java/com/microsoft/azure/
│   └── FileStreamingServerTest.java
├── http-streaming-function/        # Azure Functions config
│   ├── function-handler/
│   │   └── function.json
│   └── local.settings.json
├── local.host.json                 # host.json for local dev (with debug)
├── cloud.host.json                 # host.json for cloud deployment
├── demo.http                       # REST Client test requests
└── pom.xml
```

## Notes

- Filenames are sanitized to prevent directory traversal attacks
- Virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`) provide high concurrency with low overhead
- Uploads overwrite existing files with the same name
- Extend with checksum validation, chunked uploads, or authentication as needed
