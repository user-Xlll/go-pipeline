package com.github.userxlll.gopipeline

import static com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration.library
import static com.lesfurets.jenkins.unit.global.lib.ProjectSource.projectSource

//import static com.lesfurets.jenkins.unit.global.lib.GitSource.gitSource

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import com.lesfurets.jenkins.unit.declarative.*

class PipelineTest extends DeclarativePipelineTest {
   static class envMock {
     @Delegate Map<String,String> env = [
            'WORKSPACE': '/tmp/workspace/',
            'GIT_URL': 'https://git.host:443/scm/project1/test_repo.git',
            'GIT_BRANCH': 'release/v1.2.3',
            'BUILD_NUMBER': '123',
            'GIT_COMMIT': 'd7866a07255d2b82c0d3ae2b971d6e92eaa390a6'
        ]
   public Map<String,String> getEnvironment() { return  env     }

    }






    @Override
    @BeforeEach
    void setUp() {
        super.setUp()
        helper.registerAllowedMethod('sh', [Map]) { args -> (args.returnStatus) ? 0 : 'sh stub' }
        helper.registerAllowedMethod('sh', [String]) { command -> null }
        helper.registerAllowedMethod('container', [String, Closure]) { name, body -> body() }
        helper.registerAllowedMethod('withEnv', [List, Closure]) { values, body -> body() }
        helper.registerAllowedMethod('withCredentials', [List, Closure]) { values, body -> body() }
        helper.registerAllowedMethod('libraryResource', [String]) { name -> new File("resources/${name}").text }
        helper.registerAllowedMethod('archiveArtifacts', [Map])
        helper.registerAllowedMethod('git', [Map]) { args -> 'git stub' }
        helper.registerAllowedMethod('ansiColor', [String])
        helper.registerAllowedMethod('file', [Map])
        helper.registerAllowedMethod('retries', [Integer])
        helper.registerAllowedMethod('checkStyle', [Map])
        helper.registerAllowedMethod('recordCoverage', [Map])
        helper.registerAllowedMethod('junit', [Map])
        helper.registerAllowedMethod('publishHTML', [Map])
        helper.registerAllowedMethod('catchError', [Map, Closure]) { a, b -> return b() }
        helper.registerAllowedMethod('withSonarQubeEnv', [Map, Closure]) { a, b -> return b() }
        helper.registerAllowedMethod('waitForQualityGate', []) {return [status: "OK"]}


        this.binding.setVariable('env', new envMock())

    }

    @Test
    void testLibrary() {
        def Object lib  = library()
            .name('go-pipeline')
            .defaultVersion('<notNeeded>')
            .allowOverride(true)
            .implicit(true)
            .targetPath('<notNeeded>')
            .retriever(projectSource())
            .build()

        helper.registerSharedLibrary(lib)

        runScript('test/resources/Jenkinsfile')

        assertJobStatusSuccess()
     //   printCallStack()
    }

}
