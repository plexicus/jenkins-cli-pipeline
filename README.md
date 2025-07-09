# Plexalyzer Jenkins Pipeline

A Jenkins pipeline for automated security analysis using Plexalyzer's comprehensive security scanning tools.

## 📋 Prerequisites

### Jenkins Requirements

1. **Jenkins Server** with Docker support
2. **Docker-in-Docker (DinD) enabled** - This script is specifically designed to run on Jenkins with Docker-in-Docker capabilities
   - Jenkins must be running in a Docker container with Docker socket mounted or DinD configured
   - The Jenkins agent must have access to Docker daemon
3. **AnsiColor Plugin** (Recommended) - For better terminal output visualization
   - Install via: `Manage Jenkins` → `Plugins` → `Available` → Search for "AnsiColor"
   - This plugin provides colored output in Jenkins console logs, making scan results more readable

### System Requirements

- **Docker** installed and running on Jenkins agent
- **Docker-in-Docker (DinD)** properly configured
- **Git** access to your repository
- **Plexalyzer token** (obtained from [Plexicus](https://app.plexicus.ai/))

> **Important**: This pipeline script is specifically designed for Jenkins environments running with Docker and Docker-in-Docker capabilities. It uses Docker volume mounting and container orchestration that requires proper DinD setup.

## 🏗️ Repository Setup

### Required Directory Structure

Your repository **must** contain a `.plexalyzer` directory with the following files:

```
.plexalyzer/
├── jenkins-plexalyzer-cli.groovy  # Jenkins pipeline script
└── custom_config.yml              # Plexalyzer configuration
```

### Configuration Files

#### 1. `custom_config.yml`
This file controls which security tools are included/excluded from analysis:

```yaml
# Tools to exclude from analysis
excluded_tools:
  - syft
  - trivy-sbom
  - bandit
  - trivy-sca
  - opengrep
  - kics-container
  - trivy-license

# Tools to specifically include (optional)
# included_tools:
#   - opengrep
#   - kics
#   - hadolint
```

#### 2. `jenkins-plexalyzer-cli.groovy`
The main Jenkins pipeline script that orchestrates the security analysis.

## 🔧 Jenkins Setup

### Step 1: Configure Credentials

1. Go to `Manage Jenkins` → `Credentials`
2. Select appropriate domain (usually `Global`)
3. Click `Add Credentials`
4. Choose `Secret text` as the kind
5. Enter your Plexalyzer token in the `Secret` field
6. Set the ID to `plexalyzer-token`
7. Add description: "Plexalyzer API Token"
8. Save the credential

### Step 2: Create Jenkins Pipeline

1. **Navigate to Jenkins Dashboard**
2. **Create New Item**:
   - Click `New Item`
   - Enter item name (e.g., "plexalyzer-security-scan")
   - Select `Pipeline`
   - Click `OK`

3. **Configure Pipeline**:
   - Scroll down to `Pipeline` section
   - Select `Pipeline script from SCM`
   - Choose `Git` as SCM
   - Enter your repository URL
   - Set credentials if repository is private
   - Specify branch (default: `main`)
   - **Script Path**: `.plexalyzer/jenkins-plexalyzer-cli.groovy`

4. **Save Configuration**

### Step 3: Pipeline Parameters

The pipeline supports the following parameters:

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `PROJECT_NAME` | String | `simplest-jenkins` | Name of the project being analyzed |
| `BRANCH_NAME` | String | `main` | Git branch to analyze |
| `OUTPUT_FORMAT` | Choice | `pretty` | Output format (`pretty`, `json`, `sarif`) |
| `DEFAULT_OWNER` | String | `plexicus` | Default owner for the analysis |
| `REPOSITORY_ID` | String | _(empty)_ | Specific repository ID |
| `AUTONOMOUS_SCAN` | Boolean | `false` | Enable autonomous scanning mode |
| `ONLY_GIT_CHANGES` | Boolean | `false` | Analyze only changed files in Git |
| `PROGRESS_BAR` | Boolean | `true` | Show progress bar during analysis |

## 🚀 Usage

### Running a Full Repository Scan

1. Navigate to your Jenkins pipeline job
2. Click `Build with Parameters`
3. Configure parameters as needed
4. Click `Build`

### Running Incremental Analysis

To analyze only changed files:

1. Set `ONLY_GIT_CHANGES` to `true`
2. The pipeline will automatically detect changed files from the last commit
3. Only modified files will be analyzed

### Monitoring Results

- **Console Output**: View real-time analysis progress
- **Build Status**: 
  - ✅ **Success**: No critical vulnerabilities found
  - ⚠️ **Unstable**: Vulnerabilities found but build continues
  - ❌ **Failed**: Critical errors or analysis failure
- **Detailed Reports**: Available at [Plexicus Dashboard](https://app.plexicus.ai/repositories)

## 📊 Understanding Results

### Exit Codes

| Exit Code | Status | Description |
|-----------|--------|-------------|
| `0` | Success | Scan completed successfully |
| `1` | Success | Scan completed successfully |
| `2` | Unstable | Vulnerabilities found |
| `500` | Failed | Fatal error in analysis |

### Output Formats

- **Pretty**: Human-readable formatted output
- **JSON**: Machine-readable JSON format
- **SARIF**: Static Analysis Results Interchange Format

## 🔍 Advanced Configuration

### Custom Tool Configuration

Modify `.plexalyzer/custom_config.yml` to customize tool behavior:

```yaml
# Example: Configure specific tools
semgrep_cli:
  token: "your-semgrep-token"

nuclei:
  application_url: "https://my-app.com"
  authorization:
    type: "bearer"
    token: "your-bearer-token"
```

### Docker Configuration

The pipeline uses the latest Plexalyzer Docker image:
- **Image**: `plexicus/plexalyzer:latest`
- **Auto-pull**: Latest version is downloaded automatically

## 🐛 Troubleshooting

### Common Issues

1. **Docker not available**
   - Ensure Docker is installed on Jenkins agent
   - Verify Docker daemon is running

2. **Docker-in-Docker (DinD) configuration issues**
   - Ensure Jenkins is running with Docker socket mounted: `-v /var/run/docker.sock:/var/run/docker.sock`
   - Or configure proper DinD setup with privileged containers
   - Verify Jenkins agent has Docker access: `docker info` should work from Jenkins
   - Check that `--volumes-from` flag works properly (used by the pipeline)

3. **Credential errors**
   - Check that `plexalyzer-token` credential exists
   - Verify credential has correct permissions

4. **Git repository issues**
   - Ensure repository is properly initialized
   - Check Git permissions and access

5. **AnsiColor plugin issues**
   - Plugin is optional but recommended
   - Pipeline gracefully falls back to standard output

### Debug Information

Check the console output for detailed error messages and debugging information. The pipeline provides comprehensive logging for each step.

## 📚 Additional Resources

- [Plexicus Platform](https://app.plexicus.ai/)
- [Jenkins AnsiColor Plugin](https://plugins.jenkins.io/ansicolor/)
- [Docker Documentation](https://docs.docker.com/)

## 🤝 Support

For support and questions:
- Visit the [Plexicus Platform](https://app.plexicus.ai/)
- Check the Jenkins console logs for detailed error information
- Ensure all prerequisites are properly configured
