# Plexalyzer Jenkins Pipeline

A Jenkins pipeline for automated security analysis using the Plexicus Plexalyzer CLI.

## Prerequisites

### Jenkins requirements

1. **Jenkins with Docker support** — the pipeline runs Plexalyzer inside a container.
2. **Docker-in-Docker (DinD) or Docker socket mounted** — the Jenkins agent must be able to
   execute `docker run`. The typical setup is to mount the host socket:
   `-v /var/run/docker.sock:/var/run/docker.sock`
3. **AnsiColor Plugin** (optional but recommended) — improves the readability of `pretty`
   output. Install via **Manage Jenkins → Plugins → Available → AnsiColor**.
4. **Git** — available on the Jenkins agent (used when `ONLY_GIT_CHANGES=true`).

### Plexalyzer token

The pipeline authenticates with the Plexicus backend using a **Plexalyzer Token** — a
short-lived JWT issued by the platform.

**How to generate a token:**

1. Log in to the Plexicus dashboard (`https://app.plexicus.ai` or your self-hosted URL).
2. Navigate to **Settings → Plexalyzer Token**.
3. Click **Create token**, enter a name and optional expiry date, and click **Submit**.
4. Click **Copy token** next to the new entry.

**How to store the token in Jenkins:**

1. Go to **Manage Jenkins → Credentials → (Global) → Add Credentials**.
2. Kind: **Secret text**.
3. Secret: paste the token.
4. ID: `plexalyzer-token`.
5. Description: `Plexalyzer API Token`.
6. Click **Save**.

## Repository structure

Your repository (the one being scanned) must contain:

```
.plexalyzer/
├── jenkins-plexalyzer-cli.groovy   # Jenkins pipeline script (canonical path)
└── custom_config.yml               # Scan configuration (tool selection)
```

The root `Jenkinsfile` is a convenience entry point that loads the canonical script.

## Jenkins pipeline setup

### Step 1: Create a Pipeline job

1. Jenkins Dashboard → **New Item** → enter a name → **Pipeline** → **OK**.
2. Scroll to the **Pipeline** section.
3. Definition: **Pipeline script from SCM**.
4. SCM: **Git** — enter your repository URL and credentials.
5. Script Path:
   - **Recommended:** `Jenkinsfile` (root convenience wrapper)
   - **Alternative:** `.plexalyzer/jenkins-plexalyzer-cli.groovy` (canonical script)
6. Click **Save**.

### Step 2: Run the pipeline

Click **Build with Parameters** and configure the options below.

## Pipeline parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `PROJECT_NAME` | String | `my-project` | Name shown in the Plexicus dashboard |
| `BRANCH_NAME` | String | `main` | Branch label attached to findings |
| `OUTPUT_FORMAT` | Choice | `pretty` | `pretty` (console) · `json` · `sarif` |
| `DEFAULT_OWNER` | String | `plexicus` | Default owner label for findings |
| `REPOSITORY_ID` | String | _(empty)_ | Existing repository ID; blank = auto-register |
| `AUTONOMOUS_SCAN` | Boolean | `false` | Enable autonomous AI remediation mode |
| `ONLY_GIT_CHANGES` | Boolean | `false` | Scan only files changed since the last commit |
| `PROGRESS_BAR` | Boolean | `true` | Show progress bar (disable for cleaner CI logs) |
| `PLEXICUS_URL` | String | `https://app.plexicus.ai` | Base URL of your Plexicus instance |

## Scan configuration (`custom_config.yml`)

All keys in this file (except the reserved `message_url` and `plexalyzer_token`) are
passed directly to the Plexalyzer CLI.  Use `excluded_tools` or `included_tools` to
control which security tools run.

**Valid configuration values.** `excluded_tools` / `included_tools` accept the
platform's internal engine identifiers — machine configuration values, not
product names. Each identifier belongs to a branded Plexicus scanner bundle:

| Category | Bundle | Engine identifiers (config values) |
|----------|--------|-------------------------------------|
| SAST | `plexicus-sast` | `opengrep`, `bandit` |
| SCA | `plexicus-sca` | `grype` |
| Secrets | `plexicus-secrets` | `gitleaks`, `trufflehog` |
| Container | `plexicus-container` | `trivy-container` |
| IaC | `plexicus-iac` | `checkov-iac`, `checkov-configuration`, `checkov-container` |
| CI/CD | `plexicus-cicd` | `checkov-ci/cd` |
| Cloud | `plexicus-cloud` | `cloudsploit` |
| DAST | `plexicus-dast` | `nuclei` |
| Pentest | `plexicus-pentest` | `strix` |
| SBOM | `plexicus-sbom` | `syft` |
| AI BOM | `plexicus-aibom` | `cdxgen-mlbom` |
| Crypto BOM | `plexicus-cbom` | `cdxgen-cbom`, `opengrep-crypto` |
| License | `plexicus-license` | `trivy-license` |
| SCM | `plexicus-scm` | `chainbench` |
| Registry | `plexicus-registry` | `trivy-registry` |

Example `custom_config.yml`:

```yaml
# Exclude slow / environment-specific tools
excluded_tools:
  - strix       # plexicus-pentest — requires network access
  - cloudsploit # plexicus-cloud — only relevant for cloud config repos

# Or restrict to specific tools only:
# included_tools:
#   - opengrep
#   - bandit
#   - grype
#   - gitleaks
#   - trivy-license
```

> **Note:** The tool names `trivy-sbom`, `trivy-sca`, and `kics-container` were used in
> older versions of Plexalyzer and are no longer valid. Use `syft`, `grype`, and
> `checkov-container` respectively.

## Docker image

The pipeline uses the official public image:

- **Image:** `plexicus/plexalyzer:latest`
- **Registry:** Docker Hub (`hub.docker.com/r/plexicus/plexalyzer`)
- **CLI entrypoint:** `python /app/analyze.py` (the image default CMD is the web server;
  the pipeline overrides it with `--entrypoint python`)

## Build results and exit codes

| Exit code | Build status | Meaning |
|-----------|-------------|---------|
| `0` | Success | Scan completed — no findings |
| `1` | Success | Scan completed — findings detected; review the dashboard |
| `2` | Unstable | Reserved for future CLI versions |
| `500` | Failure | Fatal error — wrong token or backend unreachable |

Exit codes 0 and 1 both indicate a successful scan. The difference is whether findings
were detected; neither marks the build as failed by default.  To fail the build on
findings, change `SUCCESS` to `FAILURE` in the `case 1` block of `handleExitCode()`.

For `json` and `sarif` output formats, the results are archived as build artefacts and
also echoed to the console.

## How it works

1. **Prepare workspace** — creates `.plexalyzer/` and a default `custom_config.yml` if
   none is committed.
2. **Collect changed files** (when `ONLY_GIT_CHANGES=true`) — runs `git diff HEAD~1 HEAD`
   and writes the file list to `.plexalyzer/changed_files.txt`.
3. **Pull image** — runs `docker pull plexicus/plexalyzer:latest`.
4. **Run scan** — executes:
   ```
   docker run --rm \
     -v "${WORKSPACE}:/workspace" \
     -v "${WORKSPACE}:/mounted_volumes" \
     -w /workspace \
     -e PLEXALYZER_TOKEN="..." \
     -e MESSAGE_URL="https://app.plexicus.ai/plexalyzer-message-receipts" \
     --entrypoint python \
     plexicus/plexalyzer:latest \
       /app/analyze.py --config ... --name ... --branch ... --output ...
   ```
   The `/workspace` mount is the scan root.  The `/mounted_volumes` mount is used by the
   CLI to write back the repository ID after registration.
5. **Archive results** — for `json`/`sarif` output formats only.

## Troubleshooting

**`exit 500` — failed to create task**
- Verify the `plexalyzer-token` credential is correct and not expired.
- Confirm `PLEXICUS_URL` is reachable from the Jenkins agent: `curl -I <PLEXICUS_URL>`.

**Docker not available**
- Confirm Docker is installed on the agent: `docker --version`.
- Confirm the Docker socket is mounted or DinD is configured.
- Test with: `docker info`.

**Shallow clone — no git history for `ONLY_GIT_CHANGES`**
- The pipeline falls back to `git diff HEAD` automatically.
- For a complete diff, configure your Jenkins SCM with depth > 1.

**AnsiColor output not working**
- Install the AnsiColor plugin (**Manage Jenkins → Plugins → AnsiColor**).
- The `pretty` output format uses ANSI colour codes; without the plugin they appear as
  raw escape sequences in the console.

**Tool name not recognised**
- Use only tool names from the table above.  The names `trivy-sbom`, `trivy-sca`, and
  `kics-container` are no longer valid.

## Additional resources

- [Plexicus Dashboard](https://app.plexicus.ai/)
- [Plexicus Documentation](https://docs.plexicus.ai/)
- [Jenkins AnsiColor Plugin](https://plugins.jenkins.io/ansicolor/)
- [Docker Documentation](https://docs.docker.com/)
