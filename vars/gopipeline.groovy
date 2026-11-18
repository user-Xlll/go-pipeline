import com.github.userxlll.gopipeline.Values
import org.jenkinsci.plugins.pipeline.modeldefinition.Utils
import groovy.json.JsonOutput
import com.github.userxlll.gopipeline.utils.BuildUtils

def call(Closure<?> closure) {

def GV = Values.Prepare(this, closure)

pipeline {
    agent {
        kubernetes {
            cloud GV.cloud
            namespace GV.namespace
            yaml GV.podYAML
            defaultContainer 'go'
            retries 2
        }
    }

    options {
        ansiColor('xterm')
    }

    environment {
        GIT_COMMIT_HASH = "${env.GIT_COMMIT}"
        GIT_TAG = "${env.TAG_NAME}"
    }

    stages {
        stage('Prepare') {
            steps {
                sh 'go install github.com/user-Xlll/go-ci/cmd/goci@latest'
                script{
                    GV.AfterCheckout(this)

                    if (env.TAG_NAME) {
                        sh "git checkout ${env.TAG_NAME}"
                    }

                    def gitTag = env.TAG_NAME ?: ''
                    def tagMatcher = gitTag =~ /^v\d+\.(\d+)\.\d+$/
                    env.IS_RELEASE_TAG = tagMatcher.matches() && tagMatcher[0][1] != '0' ? 'true' : 'false'
                    println("IS_RELEASE_TAG: ${env.IS_RELEASE_TAG}")
                }

                sh 'mkdir -p /home/jenkins/agent/credentials ../reports ../builds dist && printf \'{"auths":{}}\\n\' > /home/jenkins/agent/credentials/auth.json'

                if (GV.imageRegistryHost && GV.credentialsId) {
                    container('podman') {
                        withCredentials([usernamePassword(credentialsId: GV.credentialsId, usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                            withEnv(["REGISTRY_HOST=${GV.imageRegistryHost}"]) {
                                sh 'printf "%s" "$PASSWORD" | podman login --authfile /home/jenkins/agent/credentials/auth.json "$REGISTRY_HOST" --username "$USERNAME" --password-stdin'
                            }
                        }
                    }
                }

                sh 'goci version'
                sh 'goci getmodules'

                script {
                    if (GV.imageTag) {
                        GV.gitPreviousTag = sh (script: "goci gitprevious ${env.GIT_BRANCH}", returnStdout: true).trim()
                        println("gitPreviousTag: $GV.gitPreviousTag")
                    }
                }
            }
            
        }
        
        stage("Quality gates") {
            parallel {
                stage("GO Tests") {
                    when {
                        expression {sh (script: 'goci containsdir --first-only go.mod',  returnStdout: true).trim()}
                    }
                    steps {
                        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                            sh "goci testreport ../reports"
                        }
                    }
                    post {
                        always {
                            dir('../reports') {
                                sh "ls"
                                junit allowEmptyResults:true, testResults:'junit.xml'

                                publishHTML(target : [allowMissing: true,
                                                        alwaysLinkToLastBuild: true,
                                                        keepAll: true,
                                                        reportDir: '.',
                                                        reportFiles: 'coverage.html',
                                                        reportName: 'Code Coverage',
                                                        reportTitles: 'Code Coverage'])

                                recordCoverage(tools: [[parser: 'COBERTURA', pattern: 'cobertura.xml']],
                                        sourceCodeRetention: 'LAST_BUILD',
                                        sourceDirectories: [[path: env.WORKSPACE]])
                            } 
                        }
                    }      
                }

                stage("JS/TS Tests") {
                    when {
                        expression {sh (script: 'ls | grep package.json | true',  returnStdout: true).trim()}
                    }
                    steps {
                        container('node') {
                            withEnv(["TEST_REPORTS_DIR=${WORKSPACE}/.test-reports"]) {
                                catchError(buildResult: "SUCCESS", stageResult: "UNSTABLE") {
                                    sh script: "npm run test:unit"
                                }

                                script {
                                    env.LCOV_REPORT_PATHS = sh(
                                        script: 'find ${TEST_REPORTS_DIR} -name "*.info" -printf "%p," | sed "s/,$//"',
                                        returnStdout: true,
                                    )

                                    def packages = sh(script: "ls ${TEST_REPORTS_DIR}", returnStdout: true).trim().split("\n")
                                    def sourceDirectories = []

                                    for (package_name in packages) {
                                        sourceDirectories += [
                                            [path:"${package_name}"],
                                            [path:"packages/${package_name}"],
                                        ]

                                        publishHTML(
                                            target : [
                                                allowMissing: true,
                                                alwaysLinkToLastBuild: true,
                                                keepAll: true,
                                                reportDir: "${TEST_REPORTS_DIR}/${package_name}",
                                                reportFiles: "index.html",
                                                reportName: "Code Coverage [${package_name}]",
                                                reportTitles: "Code Coverage [${package_name}]"
                                            ]
                                        )
                                    }

                                    junit allowEmptyResults:true, testResults:"*/**/junit.xml"

                                    recordCoverage(
                                        tools: [[parser: "COBERTURA", pattern: "*/**/cobertura-coverage.xml"]],
                                        sourceCodeRetention: "LAST_BUILD",
                                        sourceDirectories: sourceDirectories
                                    )
                                }
                            }
                        }
                    }
                }
                
                stage("Go Lint") {
                    when {
                        expression {sh (script: 'goci containsdir --first-only go.mod',  returnStdout: true).trim()}
                    }
                    steps {
                        script {
                            def code = sh (script:"golangci-lint run --issues-exit-code=222 --output.text.path stdout --timeout=10m --max-issues-per-linter=0 --max-same-issues=0 --uniq-by-line=false  --output.sarif.path=../reports/sarif-go.json  ./...", label: 'golangci-lint', returnStatus: true)
                            switch (code) {
                            case 0:
                                echo "golangci-lint: OK"
                                break
                            case 222:
                                catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                                    error "golangci-lint: WARNINGS"
                                }
                                break
                            default:
                                error "golangci-lint run failed"
                            }    
                        }
                    } 
                }
                
                stage("Proto Lint") {
                    when {
                        expression {sh (script: 'goci containsdir --first-only *.proto',  returnStdout: true).trim()}
                    }
                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                            sh script:"protolint --no-error-on-unmatched-pattern --add-reporter=sarif:../reports/sarif-proto.json --config_path=/app/.protolint.yaml .", label: 'protolint'
                        } 
                    } 
                }

                stage("Helm Lint") {
                    when {
                        expression {GV.helmDir != "" && GV.helmDir != "."}
                    }
                    steps {
                        catchError(buildResult: 'SUCCESS', stageResult: 'UNSTABLE') {
                            sh script:"goci helm lint --workdir=${GV.helmDir} --sarif=../reports/sarif-helm.json", label: 'helm lint'
                        } 
                    } 
                }

                stage("ESLint") {
                    when {
                        expression {sh (script: "ls | grep package.json | true",  returnStdout: true).trim()}
                    }
                    steps {
                        container('node') {
                            withEnv(["ESLINT_REPORTS_DIR=${WORKSPACE}/.eslint-reports"]) {
                                catchError(buildResult: "SUCCESS", stageResult: "UNSTABLE") {
                                    sh script: "npm run lint:check", label: "eslint"
                                }
                                script {
                                    env.ESLINT_REPORT_PATHS = sh(
                                        script: 'find ${ESLINT_REPORTS_DIR} -name "*.json" -printf "%p," | sed "s/,$//"',
                                        returnStdout: true,
                                        label: "concat eslint report paths"
                                    )
                                }
                            }
                        }
                    } 
                }
                                
                stage("Cross Compilation"){
                    steps {
                        catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                            withEnv([
                                "COPY_FILES_FLAGS=${GV.copyFilesFlags}",
                                "MAIN_PACKAGE=${GV.projectKey}",
                                "DOCKER_REGISTRY=${GV.dockerRegistry}",
                                "NGINX_IMAGE_HASH=${GV.nginxImageHash ?: ''}"
                            ]) {
                                sh '''
                                    goci build ../builds \
                                        $COPY_FILES_FLAGS \
                                        --main-package=$MAIN_PACKAGE \
                                        --docker-registry=$DOCKER_REGISTRY \
                                        --nginx-image-hash=$NGINX_IMAGE_HASH
                                '''
                            }
                            script {
                                GV.crossCompileSuccess = true
                            }
                        }
                    }
                }              
            }
        }

        stage('AppSec') {
            when {
                expression { GV.appSecScriptPath && env.IS_RELEASE_TAG == 'true' }
            }
            steps {
                catchError(buildResult: 'UNSTABLE', stageResult: 'FAILURE') {
                    script {
                        GV.appSecSuccess = false
                        def scanner = load GV.appSecScriptPath
                        def scanPassed = scanner.run(this, GV.appSecConfig)
                        if (scanPassed != true) {
                            error 'AppSec scan failed'
                        }
                        GV.appSecSuccess = true
                    }
                }
            }
        }

        stage('SonarQube') {
            when {
                expression { env.IS_RELEASE_TAG == 'true' && GV.sonarProjectKey && GV.sonarCredentialsId }
            }
            steps {
                withSonarQubeEnv(installationName: GV.sonarInstallation) {
                    withCredentials([usernamePassword(credentialsId: GV.sonarCredentialsId, usernameVariable: 'SONAR_LOGIN', passwordVariable: 'SONAR_PASSWORD')]) {
                        withEnv([
                            "SONAR_PROJECT=${GV.sonarProjectKey}",
                            "SONARQUBE_SCANNER_PARAMS=" + JsonOutput.toJson([
                                'sonar.host.url': env.SONAR_HOST_URL,
                                'sonar.login': env.SONAR_LOGIN,
                                'sonar.password': env.SONAR_PASSWORD
                            ])
                        ]) {
                            sh '''goci sonar \
                                -Dsonar.sourceEncoding=UTF-8 \
                                -Dsonar.scm.provider=git \
                                -Dsonar.language=go \
                                -Dsonar.eslint.reportPaths=${ESLINT_REPORT_PATHS} \
                                -Dsonar.javascript.lcov.reportPaths=${LCOV_REPORT_PATHS} \
                                -Dsonar.go.coverage.reportPaths=../reports/coverprofile.txt \
                                -Dsonar.go.tests.reportPaths=../reports/tests_report.json \
                                -Dsonar.sarifReportPaths="../reports/sarif-go.json,../reports/sarif-proto.json,../reports/sarif-helm.json" \
                                -Dsonar.sources=. \
                                -Dsonar.exclusions=**/*_test.go,**/vendor/**,**/node_modules/** \
                                -Dsonar.tests=. \
                                -Dsonar.test.exclusions=**/vendor/** \
                                -Dsonar.test.inclusions=**/*_test.go \
                                -Dsonar.projectKey=$SONAR_PROJECT \
                                -Dsonar.host.url=$SONAR_HOST_URL \
                                -Dsonar.c.file.suffixes=- \
                                -Dsonar.cpp.file.suffixes=- \
                                -Dsonar.objc.file.suffixes=- \
                                -Dsonar.login=$SONAR_LOGIN \
                                -Dsonar.password=$SONAR_PASSWORD'''
                        }
                    }
                }
                timeout(time: 10, unit: 'MINUTES') {
                    script {
                        def qualityGate = waitForQualityGate()
                        if (qualityGate.status != 'OK') {
                            error "SonarQube quality gate failed: ${qualityGate.status}"
                        }
                    }
                }
            }
        }

        stage("OCI Images") {
            steps {
                script {
                    GV.AfterCompile(this)
                    def parallelStages = [:]
                    def buildUtils = new BuildUtils(steps: this, GV: GV)
                    
                    GV.services.each { SERVICE ->
                        parallelStages[SERVICE.name] = {                                                      
                            stage("Build ${SERVICE.name}"){
                                script {
                                    SERVICE.imageHash = buildUtils.buildImage(SERVICE, GV.buildImageTag)
                                }
                            }
                            
                            stage("Push ${SERVICE.name}"){
                                script {
                                    if (!GV.imageTag || !GV.pushImagesTo) {
                                        Utils.markStageSkippedForConditional("Push ${SERVICE.name}")
                                        return
                                    }
                                    
                                    SERVICE.imageHash = buildUtils.pushImage(SERVICE, GV.imageTag)
                                    
                                    echo "Image pushed to registry with tag: ${GV.imageTag}"
                                    echo "Successfully got image hash: ${SERVICE.imageHash}"
                                    
                                    if (GV.services.size() == 1) {
                                        env.IMAGE_REGISTRY = SERVICE.imageRegistry
                                        env.IMAGE_HASH = SERVICE.imageHash
                                    }

                                    env."IMAGE_REGISTRY_${SERVICE.name.replaceAll('[^a-zA-Z0-9]', '_').toUpperCase()}" = SERVICE.imageRegistry
                                    env."IMAGE_HASH_${SERVICE.name.replaceAll('[^a-zA-Z0-9]', '_').toUpperCase()}" = SERVICE.imageHash
                                }
                            } 
                        }
                    }

                    parallel parallelStages
                }
            }   
        }

        stage('Helm Package') {
            when {
                expression { GV.pushDistributiveTo }
            }
            steps {
                script {
                    if (!GV.helmDir || GV.helmDir == '.') {
                        error 'Configure a single Helm chart directory before packaging'
                    }
                    withEnv(["OUT_FILE=${GV.pushDistributiveTo}", "REPLACE_RULES=${GV.replaceRulesJSON}", "HELM_DIR=${GV.helmDir}"]) {
                        sh 'goci archive --wrapdir=chart --workdir="$HELM_DIR" --out-file="$OUT_FILE" --replace-rules="$REPLACE_RULES" --comment="GIT_URL: $GIT_URL\nBUILD_URL: $BUILD_URL"'
                    }
                    archiveArtifacts artifacts: GV.pushDistributiveTo, fingerprint: true
                }
            }
        }

        stage("Final") {
            when {
                expression { GV.finalScriptPath }
            }
            steps {
                script {
                    def script = load GV.finalScriptPath
                    script.run(this)
                }
            }
        }
    }
}

}
