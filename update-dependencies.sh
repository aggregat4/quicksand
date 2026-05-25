#!/usr/bin/env bash
set -euo pipefail
mvn versions:display-dependency-updates
mvn versions:display-property-updates
#mvn org.owasp:dependency-check-maven:check -DfailBuildOnCVSS=7
