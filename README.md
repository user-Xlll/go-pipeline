# go-pipeline

`go-pipeline` is a reusable Jenkins Shared Library for Go and JavaScript/TypeScript repositories. It discovers buildable Go services, runs tests and linters, generates CI reports, builds and publishes OCI images, and can package Helm charts.

Optional integrations include SonarQube analysis and an AppSec scan. These integrations require compatible tools and configuration in your Jenkins environment.

## Features

- Go test, coverage, JUnit, Cobertura, and HTML reports
- JavaScript/TypeScript test and ESLint reports
- Go, Protocol Buffers, and Helm linting
- Go cross-compilation and OCI image builds
- Optional BuildKit builds
- Optional SonarQube quality-gate checks
- Optional AppSec scanning through a project-provided Groovy script
- Helm chart packaging with configurable placeholder replacement
- A final user-provided Groovy script hook

## Requirements

- Jenkins with Pipeline and Shared Libraries support
- A Kubernetes cloud configured in Jenkins, with the Kubernetes plugin
- Go, Podman, BuildKit, `golangci-lint`, `protolint`, Helm, and any optional scanners available to the build agent
- A container registry and Jenkins credentials if images are published
- Network access to the public [`go-ci`](https://github.com/user-Xlll/go-ci) source; the pipeline installs its `goci` CLI
- For optional SonarQube analysis: the Jenkins SonarQube plugin and a configured server

The supplied pod template is a starting point. Adapt it to your Kubernetes cluster, permitted images, credentials, and security policies before use.

## Configure the shared library

In Jenkins, add a Global Pipeline Library named `go-pipeline` and point it to:

```text
https://github.com/user-Xlll/go-pipeline.git
```

Choose the branch or release tag you want Jenkins to use. Then create a `Jenkinsfile` in your application repository:

```groovy
@Library('go-pipeline@main') _

gopipeline {
  pushImagesTo 'ghcr.io/your-owner/your-project'

  tagToVersion 'v(?<version>.+)': '$version-$GIT_COMMIT'
  branchToVersion 'release/(?<version>.+)': '$version-$GIT_COMMIT'

  useBuildkit false
  serviceNameRegexp '.+'
}
```

Replace the example registry path with your own. Configure registry credentials in Jenkins with `credentialsId` and ensure the selected agent can reach the registry. To enable SonarQube, configure the server and a username/password credential in Jenkins, then set `sonarInstallation`, `sonarProjectKey`, and `sonarCredentialsId` in the pipeline.

## Reports and outputs

The pipeline publishes Go and JavaScript test results and coverage reports to Jenkins when the corresponding tools and reports are available. Build artifacts are created by `goci` and the configured container/Helm tooling.

## AppSec integration

AppSec scanning is optional. Set `appSecScriptPath` to a Groovy script in your application repository that exposes `run(steps, config)` and returns `true` only when the scan passes. Supply scanner-specific options through `appSecConfig`. The scanner implementation and credentials remain under your control; this project does not assume a particular vendor or service.

## Configuration reference

| Setting | Description |
| --- | --- |
| `pushImagesTo` | Registry namespace used to publish discovered OCI images. |
| `credentialsId` | Optional Jenkins username/password credential for registry access. |
| `kubernetesCloud` | Jenkins Kubernetes cloud name; defaults to `kubernetes`. |
| `namespace` | Kubernetes namespace for the build pod; defaults to `default`. |
| `tagToVersion` | Map of Git tag patterns to generated release versions. |
| `branchToVersion` | Map of branch patterns to generated pre-release versions. |
| `helmPackage` | Placeholder-to-value mappings applied when packaging a Helm chart. |
| `serviceNameRegexp` | Regular expression used to filter discovered services. |
| `finalScriptPath` | Optional path to a Groovy script with a `run(steps)` function, executed at the end of the pipeline. |
| `useBuildkit` | Use BuildKit instead of Podman for image builds. |
| `copyFiles` | File patterns copied into generated images. |
| `dockerRegistry` | Base image registry/path used by the build process. |
| `sonarInstallation` | Jenkins SonarQube installation name for quality analysis. |
| `sonarProjectKey` | Enables SonarQube analysis and sets its project key. |
| `sonarCredentialsId` | Jenkins username/password credential used by the SonarQube scanner. |
| `appSecScriptPath` | Optional repository script that performs an AppSec scan and returns a boolean result. |
| `appSecConfig` | Scanner-specific configuration passed to the AppSec script. |

See `src/com/github/userxlll/gopipeline/dsl/GoPipeline.groovy` for the supported DSL configuration. Configure credentials in Jenkins; do not commit credentials to a `Jenkinsfile` or this repository.

## License

This project is licensed under the MIT License. See [LICENSE](./LICENSE) for details.
