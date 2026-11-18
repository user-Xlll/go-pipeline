package com.github.userxlll.gopipeline.dsl

@Section('gopipeline')
class GoPipeline {
    def dsl
    String sonarProjectKey
    String sonarInstallation = 'SonarQube'
    String sonarCredentialsId
    String pushImagesTo
    String kubernetesCloud = 'kubernetes'
    String namespace = 'default'
    Map<String, String> tagToVersion = [:]
    Map<String, String> branchToVersion = [:]
    Map<String, String> helmPackage
    String versionFormat = 'D-00.000.00'
    String nginxImageHash
    Boolean useBuildkit = false
    String serviceNameRegexp = '.+'
    String finalScriptPath
    List<String> copyFiles = []
    String dockerRegistry = 'docker.io/library/alpine:latest'
    String credentialsId
    String appSecScriptPath
    Map appSecConfig = [:]

    GoPipeline(dsl) {
        this.dsl = dsl
    }

    void sonarProjectKey(String value) { sonarProjectKey = value }
    void sonarInstallation(String value) { sonarInstallation = value }
    void sonarCredentialsId(String value) { sonarCredentialsId = value }
    void pushImagesTo(String value) { pushImagesTo = value }
    void kubernetesCloud(String value) { kubernetesCloud = value }
    void namespace(String value) { namespace = value }
    void tagToVersion(Map value) { tagToVersion = value }
    void branchToVersion(Map value) { branchToVersion = value }
    void helmPackage(Map value) { helmPackage = value }
    void versionFormat(String value) { versionFormat = value }
    void nginxImageHash(String value) { nginxImageHash = value }
    void useBuildkit(Boolean value) { useBuildkit = value }
    void serviceNameRegexp(String value) { serviceNameRegexp = value }
    void finalScriptPath(String value) { finalScriptPath = value }
    void copyFiles(List<String> value) { copyFiles = value }
    void dockerRegistry(String value) { dockerRegistry = value }
    void credentialsId(String value) { credentialsId = value }
    void appSecScriptPath(String value) { appSecScriptPath = value }
    void appSecConfig(Map value) { appSecConfig = value }
}
