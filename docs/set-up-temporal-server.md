# Set Up Temporal Server

The Ballerina Workflow module requires a Temporal server to execute workflows. This page covers different ways to set up a Temporal server for local development.

## Option 1: Temporal CLI (Recommended)

The Temporal CLI includes a built-in development server that requires no additional dependencies.

### macOS

```bash
brew install temporal
```

### Linux

```bash
curl -sSf https://temporal.download/cli | sh
```

After installation, add it to your PATH if prompted.

### Windows

Download the latest release from the [Temporal CLI releases page](https://github.com/temporalio/cli/releases) and add the binary to your PATH.

Or use Scoop:

```powershell
scoop install temporal-cli
```

### Start the Dev Server

```bash
temporal server start-dev
```

This starts a Temporal server on `localhost:7233` with an in-memory store. The Temporal Web UI is available at `http://localhost:8233`.

To persist data across restarts, use the `--db-filename` flag:

```bash
temporal server start-dev --db-filename temporal.db
```

## Option 2: Docker

Run Temporal using Docker Compose for a more production-like setup.

### Prerequisites

- [Docker](https://docs.docker.com/get-docker/) and Docker Compose

### Start with Docker Compose

```bash
git clone https://github.com/temporalio/docker-compose.git
cd docker-compose
docker compose up -d
```

This starts:
- Temporal server on `localhost:7233`
- Temporal Web UI on `localhost:8080`
- PostgreSQL for persistence

To stop:

```bash
docker compose down
```

### Minimal Docker Setup

For a lighter setup with just the Temporal server:

```bash
docker run --rm -p 7233:7233 temporalio/auto-setup:latest
```

## Verify the Server

Check that the server is running:

```bash
temporal workflow list
```

If the server is running, this returns an empty list (no workflows running yet).

## Configure Your Ballerina Project

Create a `Config.toml` in your Ballerina project:

```toml
[ballerina.workflow.workflowConfig]
mode = "LOCAL"
url = "localhost:7233"
namespace = "default"
```

For other deployment modes (Temporal Cloud, self-hosted), see [Configure the Module](configure-the-module.md).

## What's Next

- [Get Started](get-started.md) — Write and run your first workflow
- [Configure the Module](configure-the-module.md) — Cloud and self-hosted deployment options
