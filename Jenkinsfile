#!/usr/bin/env groovy
// Jenkinsfile — root convenience entry point
//
// This file mirrors .plexalyzer/jenkins-plexalyzer-cli.groovy so that Jenkins
// jobs using the default Script Path ("Jenkinsfile") work without any extra
// configuration.
//
// Two ways to use this pipeline:
//
//   Option A (recommended): Pipeline → Script Path → Jenkinsfile
//     Uses this root file.  No changes to Jenkins configuration needed.
//
//   Option B (canonical path): Pipeline → Script Path
//                              → .plexalyzer/jenkins-plexalyzer-cli.groovy
//     Points Jenkins directly at the canonical script inside .plexalyzer/.
//     Useful when your repo already has a root Jenkinsfile for other purposes.
//
// Keep this file in sync with .plexalyzer/jenkins-plexalyzer-cli.groovy.
// All logic changes belong in the canonical script; update this file to match.

// Load and execute the canonical pipeline definition.
// load() runs the Groovy file in the current Jenkins execution context so the
// pipeline {} DSL block inside it is recognised and executed correctly.
// Jenkins must have already checked out the repository before reaching this
// point, which happens automatically for Pipeline-from-SCM jobs.
load '.plexalyzer/jenkins-plexalyzer-cli.groovy'
