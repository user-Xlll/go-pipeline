package com.github.userxlll.gopipeline.utils

class BuildUtils implements Serializable {
    def steps
    def GV
    def builderType

    BuildUtils(Map config) {
        this.steps = config.steps
        this.GV = config.GV
        this.builderType = GV.useBuildkit ? 'buildkit' : 'podman'
    }

    def buildImage(service, imageTag) {
        builderType == 'buildkit' ? buildWithBuildkit(service, imageTag, false) : buildWithPodman(service, imageTag)
    }

    def pushImage(service, imageTag) {
        def hash = builderType == 'buildkit' ? buildWithBuildkit(service, imageTag, true) : pushWithPodman(service, imageTag)
        hash ?: inspectImage(service, imageTag)
    }

    private def buildWithBuildkit(service, imageTag, push) {
        def image = "${service.imageRegistry}:${imageTag}"
        steps.container('buildkit') {
            steps.dir(service.dir) {
                def build = {
                    steps.withEnv(["REGISTRY_HOST=${GV.imageRegistryHost ?: ''}", "IMAGE=${image}"]) {
                        steps.sh '''
                            set -eu
                            mkdir -p /home/jenkins/agent/credentials
                            if [ -n "$REGISTRY_HOST" ] && [ -n "${REGISTRY_USERNAME:-}" ]; then
                                REGISTRY_AUTH=$(printf '%s' "$REGISTRY_USERNAME:$REGISTRY_PASSWORD" | base64 | tr -d '\\n')
                                printf '{"auths":{"%s":{"auth":"%s"}}}\\n' "$REGISTRY_HOST" "$REGISTRY_AUTH" > /home/jenkins/agent/credentials/config.json
                            else
                                printf '{"auths":{}}\\n' > /home/jenkins/agent/credentials/config.json
                            fi
                            export DOCKER_CONFIG=/home/jenkins/agent/credentials
                            buildctl build \\
                                --frontend dockerfile.v0 \\
                                --local context=. \\
                                --local dockerfile=. \\
                                --output "type=image,name=$IMAGE,push=${push}"
                        '''
                    }
                }
                if (GV.credentialsId && GV.imageRegistryHost) {
                    steps.withCredentials([steps.usernamePassword(credentialsId: GV.credentialsId, usernameVariable: 'REGISTRY_USERNAME', passwordVariable: 'REGISTRY_PASSWORD')], build)
                } else {
                    build()
                }
            }
        }
        push ? inspectImage(service, imageTag) : ''
    }

    private def buildWithPodman(service, imageTag) {
        steps.container('podman') {
            steps.dir(service.dir) {
                steps.sh "podman build --format=docker -t ${service.imageRegistry}:${imageTag} ."
            }
        }
        ''
    }

    private def pushWithPodman(service, imageTag) {
        def image = "${service.imageRegistry}:${imageTag}"
        steps.withEnv(["IMAGE=${image}"]) {
            steps.container('podman') {
                steps.dir(service.dir) {
                    steps.sh 'podman push --authfile /home/jenkins/agent/credentials/auth.json "$IMAGE"'
                }
            }
        }
        inspectImage(service, imageTag)
    }

    private def inspectImage(service, imageTag) {
        def image = "${service.imageRegistry}:${imageTag}"
        def digest = ''
        def inspect = {
            steps.withEnv(["IMAGE=${image}"]) {
                digest = steps.container('podman') {
                    steps.sh(script: 'skopeo inspect --authfile /home/jenkins/agent/credentials/auth.json --format "{{.Digest}}" "docker://$IMAGE"', returnStdout: true).trim()
                }
            }
        }
        if (GV.credentialsId && GV.imageRegistryHost) {
            steps.withCredentials([steps.usernamePassword(credentialsId: GV.credentialsId, usernameVariable: 'USERNAME', passwordVariable: 'PASSWORD')]) {
                steps.withEnv(["IMAGE=${image}"]) {
                    digest = steps.container('podman') {
                        steps.sh(script: 'skopeo inspect --creds "$USERNAME:$PASSWORD" --format "{{.Digest}}" "docker://$IMAGE"', returnStdout: true).trim()
                    }
                }
            }
        } else {
            inspect()
        }
        digest
    }
}
