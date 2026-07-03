#!/usr/bin/env groovy
// .plexalyzer/jenkins-plexalyzer-cli.groovy
//
// Plexicus Plexalyzer — Jenkins Declarative Pipeline
//
// Configure your Jenkins job → Pipeline → Script Path to this file, OR
// point to the root Jenkinsfile (kept in sync with this script).
//
// Platform evidence anchors (platform repo, branch docs/helm-1.2.12-sync):
//   CLI entrypoint  : plexalyzer/analyze.py (python /app/analyze.py)
//   CLI args        : plexalyzer/analyze.py lines 61-93
//   Exit codes      : plexalyzer/analyze.py lines 134, 157
//                     (0 = no findings, 1 = findings, 500 = fatal)
//   Config stripping: plexalyzer/analyze.py parse_config() line 24
//                     (message_url + plexalyzer_token stripped → must be env vars)
//   MESSAGE_URL env : plexalyzer/common/config/constants.py line 6
//   Token env       : plexalyzer/common/config/constants.py line 7
//   Backend endpoint: fastapi/routes/plexalyzer.py line 342
//                     POST /plexalyzer-message-receipts
//   Token JWT       : fastapi/helper.py create_plexalyzer_token() line 221
//                     HS256 JWT signed with PLEXALYZER_SECRET_KEY
//   Scan mount      : plexalyzer/analyze.py line 138
//                     update_repository_id writes to /mounted_volumes/.plexalyzer/config.yaml
//   Docker CMD      : plexalyzer/code/Dockerfile (default CMD = uvicorn web server;
//                     CLI use requires --entrypoint python override)
//   Valid tool names: shared/tool_bundle_params.py TOOL_CATEGORIES lines 11-27

pipeline {
    agent any

    parameters {
        string(
            name: 'PROJECT_NAME',
            defaultValue: 'my-project',
            description: 'Repository / project name shown in the Plexicus dashboard.'
        )
        string(
            name: 'BRANCH_NAME',
            defaultValue: 'main',
            description: 'Branch being scanned (informational label; does not trigger a checkout).'
        )
        choice(
            name: 'OUTPUT_FORMAT',
            choices: ['pretty', 'json', 'sarif'],
            description: 'CLI output format. json and sarif outputs are archived as build artefacts.'
        )
        string(
            name: 'DEFAULT_OWNER',
            defaultValue: 'plexicus',
            description: 'Default owner label assigned to detected findings.'
        )
        string(
            name: 'REPOSITORY_ID',
            defaultValue: '',
            description: 'Pre-existing Plexicus repository ID. Leave blank on the first scan; ' +
                         'the CLI will register a new repository and write its ID back to ' +
                         '.plexalyzer/config.yaml automatically.'
        )
        booleanParam(
            name: 'AUTONOMOUS_SCAN',
            defaultValue: false,
            description: 'Enable autonomous AI remediation mode (adds --auto to the CLI).'
        )
        booleanParam(
            name: 'ONLY_GIT_CHANGES',
            defaultValue: false,
            description: 'Restrict scan to files changed since the previous commit ' +
                         '(git diff HEAD~1 HEAD). Falls back to HEAD on shallow clones.'
        )
        booleanParam(
            name: 'PROGRESS_BAR',
            defaultValue: true,
            description: 'Show progress bar in console. Disable for cleaner CI logs.'
        )
        string(
            name: 'PLEXICUS_URL',
            defaultValue: 'https://app.plexicus.ai',
            description: 'Base URL of your Plexicus instance (no trailing slash). ' +
                         'SaaS default: https://app.plexicus.ai. ' +
                         'Self-hosted: https://your-plexicus-host'
        )
    }

    environment {
        // Secret-text credential (ID: plexalyzer-token).
        // How to obtain: Settings → Plexalyzer Token in the Plexicus dashboard.
        // JWT format: HS256, claims {client_id, created_at}
        // (fastapi/helper.py create_plexalyzer_token())
        PLEXALYZER_TOKEN = credentials('plexalyzer-token')

        // MESSAGE_URL is read by constants.py os.getenv("MESSAGE_URL").
        // parse_config() strips it from YAML so it MUST come from the environment.
        // Full endpoint: POST /plexalyzer-message-receipts
        // (fastapi/routes/plexalyzer.py line 342)
        MESSAGE_URL = "${params.PLEXICUS_URL}/plexalyzer-message-receipts"

        // Public Docker Hub image (hub.docker.com/r/plexicus/plexalyzer, 15K+ pulls).
        // Image default CMD is uvicorn (web server); override with --entrypoint python
        // to invoke the CLI script at /app/analyze.py.
        DOCKER_IMAGE      = 'plexicus/plexalyzer:latest'
        OUTPUT_FILE_JSON  = 'plexalyzer-results.json'
        OUTPUT_FILE_SARIF = 'plexalyzer-results.sarif'
    }

    stages {
        // -----------------------------------------------------------------------
        stage('Prepare workspace') {
        // -----------------------------------------------------------------------
            steps {
                sh '''
                    mkdir -p "${WORKSPACE}/.plexalyzer"

                    # Generate a minimal config template when none is committed.
                    # parse_config() merges all non-reserved keys into args_data,
                    # so excluded_tools / included_tools defined here drive tool selection.
                    if [ ! -f "${WORKSPACE}/.plexalyzer/custom_config.yml" ]; then
                        echo "No .plexalyzer/custom_config.yml found — creating minimal template."
                        cat > "${WORKSPACE}/.plexalyzer/custom_config.yml" <<\'EOFCFG\'
# Plexalyzer scan configuration
# Commit this file to .plexalyzer/custom_config.yml in your own repository.
#
# Valid individual tool names (shared/tool_bundle_params.py TOOL_CATEGORIES):
#   SAST        : plexicus-sast
#   SCA         : plexicus-sca
#   Secrets     : plexicus-secrets
#   Container   : plexicus-container
#   IaC         : plexicus-iac
#   CI/CD       : plexicus-cicd
#   Cloud       : plexicus-cloud
#   DAST        : plexicus-dast
#   Pentest     : plexicus-pentest
#   SBOM        : plexicus-sbom
#   AI BOM      : plexicus-aibom
#   Crypto BOM  : plexicus-cbom
#   License     : plexicus-license
#   SCM         : plexicus-scm
#   Registry    : plexicus-registry
#
# excluded_tools: []   # skip specific tools
# included_tools: []   # restrict scan to only these tools (empty = run all)
EOFCFG
                    fi
                '''
            }
        }

        // -----------------------------------------------------------------------
        stage('Collect changed files') {
        // -----------------------------------------------------------------------
            when {
                expression { return params.ONLY_GIT_CHANGES }
            }
            steps {
                sh '''
                    # Write changed-file paths to a temp file that the CLI reads via --files.
                    # Falls back to HEAD diff for shallow clones (depth=1 SCM checkouts).
                    git diff --name-only HEAD~1 HEAD 2>/dev/null \
                        > "${WORKSPACE}/.plexalyzer/changed_files.txt" \
                        || git diff --name-only HEAD \
                        > "${WORKSPACE}/.plexalyzer/changed_files.txt" \
                        || true

                    echo "=== Files queued for analysis ==="
                    cat "${WORKSPACE}/.plexalyzer/changed_files.txt"
                '''
            }
        }

        // -----------------------------------------------------------------------
        stage('Pull plexalyzer image') {
        // -----------------------------------------------------------------------
            steps {
                sh "docker pull ${env.DOCKER_IMAGE}"
            }
        }

        // -----------------------------------------------------------------------
        stage('Run security scan') {
        // -----------------------------------------------------------------------
            steps {
                script {
                    def exitCode = runPlexalyzerScan()
                    handleExitCode(exitCode)
                }
            }
        }

        // -----------------------------------------------------------------------
        stage('Archive results') {
        // -----------------------------------------------------------------------
            when {
                expression { return params.OUTPUT_FORMAT in ['json', 'sarif'] }
            }
            steps {
                script {
                    def artifact = params.OUTPUT_FORMAT == 'sarif'
                        ? env.OUTPUT_FILE_SARIF
                        : env.OUTPUT_FILE_JSON
                    if (fileExists("${env.WORKSPACE}/${artifact}")) {
                        archiveArtifacts artifacts: artifact, fingerprint: true
                        echo "Archived: ${artifact}"
                    } else {
                        echo "Warning: expected output file '${artifact}' not found — skipping."
                    }
                }
            }
        }
    }

    post {
        always {
            sh 'rm -f "${WORKSPACE}/.plexalyzer/changed_files.txt" || true'
        }
        success {
            echo "Plexalyzer scan complete. View results: ${params.PLEXICUS_URL}/repositories"
        }
        failure {
            echo 'Plexalyzer scan failed. Review the console output above for details.'
        }
    }
}

// =============================================================================
// Helpers
// =============================================================================

/**
 * Build the docker run command, execute it, and return the container exit code.
 *
 * Volume mounts (plexalyzer/analyze.py lines 101, 138):
 *   /workspace       → WORKSPACE  scan root; scan_path="." resolves here
 *   /mounted_volumes → WORKSPACE  write-back: update_repository_id() writes to
 *                                 /mounted_volumes/.plexalyzer/config.yaml
 *
 * For json/sarif modes stdout is redirected to a file that is archived and
 * then echoed to the console so the full output appears in the build log.
 */
def runPlexalyzerScan() {
    def repoIdArg = params.REPOSITORY_ID?.trim()
        ? "--repository_id '${params.REPOSITORY_ID}'"
        : ''
    def autoArg  = params.AUTONOMOUS_SCAN  ? '--auto'            : ''
    def noBarArg = params.PROGRESS_BAR     ? ''                  : '--no-progress-bar'
    def filesArg = params.ONLY_GIT_CHANGES
        ? '--files /workspace/.plexalyzer/changed_files.txt'
        : ''

    // stdout redirect for artefact archival.
    // The redirect runs in the outer shell so docker run's container exit code
    // passes through correctly to sh(returnStatus: true).
    def outputRedirect = ''
    if (params.OUTPUT_FORMAT == 'json') {
        outputRedirect = "> \"\${WORKSPACE}/${env.OUTPUT_FILE_JSON}\""
    } else if (params.OUTPUT_FORMAT == 'sarif') {
        outputRedirect = "> \"\${WORKSPACE}/${env.OUTPUT_FILE_SARIF}\""
    }

    // --entrypoint python overrides the image's default CMD (uvicorn web server).
    // /app/analyze.py is the CLI entrypoint (plexalyzer/analyze.py in the image).
    def dockerCmd = """
        docker run --rm \\
            -v "\${WORKSPACE}:/workspace" \\
            -v "\${WORKSPACE}:/mounted_volumes" \\
            -w /workspace \\
            -e PLEXALYZER_TOKEN="\${PLEXALYZER_TOKEN}" \\
            -e MESSAGE_URL="\${MESSAGE_URL}" \\
            --entrypoint python \\
            ${env.DOCKER_IMAGE} \\
                /app/analyze.py \\
                --config /workspace/.plexalyzer/custom_config.yml \\
                --name '${params.PROJECT_NAME}' \\
                --branch '${params.BRANCH_NAME}' \\
                --output ${params.OUTPUT_FORMAT} \\
                --owner '${params.DEFAULT_OWNER}' \\
                ${repoIdArg} \\
                ${autoArg} \\
                ${noBarArg} \\
                ${filesArg} \\
        ${outputRedirect}
    """

    def exitCode = sh(script: dockerCmd, returnStatus: true)

    // Echo captured file to console so output is visible in the build log.
    if (params.OUTPUT_FORMAT == 'json') {
        sh "cat \"\${WORKSPACE}/${env.OUTPUT_FILE_JSON}\" 2>/dev/null || true"
    } else if (params.OUTPUT_FORMAT == 'sarif') {
        sh "cat \"\${WORKSPACE}/${env.OUTPUT_FILE_SARIF}\" 2>/dev/null || true"
    }

    return exitCode
}

/**
 * Map CLI exit codes to Jenkins build statuses.
 *
 * Source: plexalyzer/analyze.py
 *   line 134-135 : return 500 on task creation failure
 *   line 157     : return 1 if findings > 0, else return 0
 *
 * Exit 2 is reserved for future CLI versions and mapped to UNSTABLE here.
 */
void handleExitCode(int exitCode) {
    switch (exitCode) {
        case 0:
            echo 'Scan completed — no findings detected.'
            currentBuild.result = 'SUCCESS'
            break
        case 1:
            echo 'Scan completed — findings detected. Review the Plexicus dashboard.'
            currentBuild.result = 'SUCCESS'
            break
        case 2:
            currentBuild.result = 'UNSTABLE'
            echo 'Scan finished with exit code 2 — build marked UNSTABLE.'
            break
        case 500:
            error(
                'Plexalyzer fatal error (exit 500): failed to create scan task. ' +
                'Verify that the plexalyzer-token credential is correct and ' +
                "that PLEXICUS_URL (${params.PLEXICUS_URL}) is reachable " +
                'from this Jenkins agent. ' +
                'Backend endpoint: POST /plexalyzer-message-receipts ' +
                '(fastapi/routes/plexalyzer.py line 342)'
            )
            break
        default:
            error("Unexpected exit code ${exitCode} from plexalyzer container.")
    }
}
