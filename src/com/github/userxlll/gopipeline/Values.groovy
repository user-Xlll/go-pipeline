package com.github.userxlll.gopipeline

import com.github.userxlll.gopipeline.dsl.GoPipeline
import com.github.userxlll.gopipeline.dsl.Runner
import groovy.text.SimpleTemplateEngine
import java.util.regex.Pattern

class Values {
    static class Service {
        String name
        String dir
        String imageRegistry
        String imageHash

        Service(String name, String dir, String imageRegistry) {
            this.name = name
            this.dir = dir
            this.imageRegistry = imageRegistry ? "${imageRegistry}/${name}" : name
        }
    }

    enum GitEventType { BRANCH, TAG, CHANGE }

    List<Service> services = []
    String cloud
    String namespace
    String credentialsId
    String podYAML
    String projectKey
    String sonarProjectKey
    String sonarInstallation
    GitEventType gitEvent
    String pushImagesTo
    String imageTag
    String buildImageTag
    String gitPreviousTag
    Map appSecConfig = [:]
    String imageRegistryHost
    String imageRegistryPath
    String pushDistributiveTo
    Boolean appSecSuccess = true
    Boolean crossCompileSuccess
    String replaceRulesJSON
    String helmDir
    String nginxImageHash
    Boolean useBuildkit = false
    String serviceNameRegexp = '.+'
    String finalScriptPath
    String copyFilesFlags = ''
    String dockerRegistry
    String appSecScriptPath
    private GoPipeline pipelineData

    private static List<String> getNamedGroups(String regex) {
        def names = []
        def matcher = regex =~ /\(?<([a-zA-Z][a-zA-Z0-9]*)>/
        while (matcher.find()) names.add(matcher.group(1))
        names
    }

    private static String removeSuffix(String value, String suffix) {
        value.endsWith(suffix) ? value.substring(0, value.length() - suffix.length()) : value
    }

    private static String formatVersion(String version, String format) {
        def result = []
        def formatParts = format =~ /(\d+)|([^0-9]+)/
        def versionParts = version =~ /\d+/
        def index = 0
        while (formatParts.find()) {
            if (formatParts.group(2)) {
                result << formatParts.group(2)
            } else {
                result << sprintf("%0${formatParts.group(1).length()}d", index < versionParts.size() ? versionParts[index].toInteger() : 0)
                index++
            }
        }
        result.join('')
    }

    private String versionName(Map<String, String> patterns, String name, Object parent) {
        for (entry in patterns ?: [:]) {
            def matcher = Pattern.compile(entry.key).matcher(name ?: '')
            if (!matcher.matches()) continue
            def params = [:]
            parent.env.getEnvironment().each { key, value -> params[key] = value }
            getNamedGroups(entry.key).each { group -> params[group] = matcher.group(group) }
            def version = new SimpleTemplateEngine().createTemplate(entry.value).make(params).toString()
            return formatVersion(version, pipelineData.versionFormat)
        }
        null
    }

    private String clearName(String value) {
        value.toLowerCase().replaceAll('[^a-z0-9.]', '-')
    }

    public AfterCompile(Object parent) {
        def output = parent.sh(returnStdout: true, script: 'ls ../builds').trim()
        if (!output) return
        for (dir in output.split('\n')) {
            def serviceName = clearName(dir)
            if (serviceName ==~ serviceNameRegexp) {
                services << new Service(serviceName, "../builds/${dir}", pushImagesTo)
                parent.println("${serviceName} added to list")
            } else {
                parent.println("${serviceName} skipped because it does not match ${serviceNameRegexp}")
            }
        }
    }

    public AfterCheckout(Object parent) {
        if (!parent.env.GIT_URL) {
            parent.error('GIT_URL is not defined')
            return
        }

        projectKey = clearName(removeSuffix(parent.env.GIT_URL.tokenize('/').last(), '.git'))
        sonarProjectKey = pipelineData.sonarProjectKey ?: projectKey
        sonarInstallation = pipelineData.sonarInstallation ?: 'SonarQube'
        gitEvent = (parent.env.CHANGE_BRANCH || parent.env.CHANGE_TARGET) ? GitEventType.CHANGE :
            (parent.env.TAG_NAME ? GitEventType.TAG : GitEventType.BRANCH)

        def versionPatterns = gitEvent == GitEventType.TAG ? pipelineData.tagToVersion :
            (gitEvent == GitEventType.BRANCH ? pipelineData.branchToVersion : [:])

        if (pipelineData.pushImagesTo) {
            pushImagesTo = pipelineData.pushImagesTo.replaceAll('/+$', '')
            def separator = pushImagesTo.indexOf('/')
            imageRegistryHost = separator < 0 ? pushImagesTo : pushImagesTo.substring(0, separator)
            imageRegistryPath = separator < 0 ? '' : pushImagesTo.substring(separator + 1)
            if (!imageRegistryHost || !imageRegistryPath) {
                parent.error('pushImagesTo must include a registry host and namespace, such as ghcr.io/owner/project')
                return
            }
        }
        if (versionPatterns) {
            imageTag = versionName(versionPatterns, parent.env.GIT_BRANCH ?: parent.env.TAG_NAME, parent)
            parent.env.VERSION = imageTag
        }
        buildImageTag = imageTag ?: parent.env.BUILD_NUMBER ?: 'local'

        appSecConfig = pipelineData.appSecConfig ?: [:]
        helmDir = parent.sh(script: 'goci containsdir Chart.yaml Chart.yml', returnStdout: true).trim()
        useBuildkit = pipelineData.useBuildkit

        if (pipelineData.helmPackage && imageTag) {
            pushDistributiveTo = "dist/${projectKey}-${imageTag}.tgz"
            replaceRulesJSON = groovy.json.JsonOutput.toJson(pipelineData.helmPackage)
        }

        parent.println("""projectKey: ${projectKey}
sonarProjectKey: ${sonarProjectKey}
gitEvent: ${gitEvent}
imageRegistryHost: ${imageRegistryHost}
imageRegistryPath: ${imageRegistryPath}
imageTag: ${imageTag}
helmDir: ${helmDir}
useBuildkit: ${useBuildkit}
serviceNameRegexp: ${pipelineData.serviceNameRegexp}
finalScriptPath: ${pipelineData.finalScriptPath}
""")
    }

    public static Values Prepare(parent, closure) {
        def values = new Values()
        values.pipelineData = Runner.run(new GoPipeline(parent), closure)
        values.cloud = values.pipelineData.kubernetesCloud ?: 'kubernetes'
        values.namespace = values.pipelineData.namespace ?: 'default'
        values.credentialsId = values.pipelineData.credentialsId ?: parent.scm.userRemoteConfigs?.get(0)?.credentialsId
        values.podYAML = RenderPod(parent)
        values.serviceNameRegexp = values.pipelineData.serviceNameRegexp ?: '.+'
        values.finalScriptPath = values.pipelineData.finalScriptPath
        values.nginxImageHash = values.pipelineData.nginxImageHash
        values.useBuildkit = values.pipelineData.useBuildkit
        values.dockerRegistry = values.pipelineData.dockerRegistry
        values.appSecScriptPath = values.pipelineData.appSecScriptPath
        (values.pipelineData.copyFiles ?: []).each { item -> values.copyFilesFlags += "--copy-files '${item}' " }
        parent.println("Kubernetes cloud: ${values.cloud}")
        values
    }

    private static String RenderPod(parent) {
        def template = parent.libraryResource('podtemplate.yaml')
        new SimpleTemplateEngine().createTemplate(template).make([:]).toString()
    }
}
