import de.bitbots.jenkins.PackageDefinition

def buildPackage(PackageDefinition p) {
    linkCatkinWorkspace(p.name, p.relativePath)
    installRosdeps(p.relativePath)
    catkinClean()
    catkinBuild(p.name)
}

def installPackage(PackageDefinition p) {
    linkCatkinWorkspace(p.name, p.relativePath)
    installRosdeps(p.relativePath)
    catkinClean()
    catkinInstall(p.name)
}

def documentPackage(PackageDefinition p) {
    linkCatkinWorkspace(p.name, p.relativePath)
    installRosdeps(p.relativePath)
    catkinBuild(p.name, "Documentation")
    dir("${WORKSPACE}") {
        if (p.relativePath == ".") {
            stash(name: "${p.name}_docs", includes: "docs/_out/**")
        } else {
            stash(name: "${p.name}_docs", includes: "${p.relativePath}/docs/_out/**")
        }
    }
}

def buildPackageInStage(PackageDefinition p) {
    stage("Build package ${p.name}") {
        //warnError("Package ${p.name} failed to build") {
        timeout(30) {
            container("builder") {
                buildPackage(p)
            }
        }
        //}
    }
}

def installPackageInStage(PackageDefinition p) {
    stage("Install package ${p.name}") {
        imperativeWhen(env.CHANGEID == null) {
            container("builder") {
                installPackage(p)
            }
        }
    }
}

def documentPackageInStage(PackageDefinition p) {
    stage("Build docs ${p.name}") {
        container("builder") {
            documentPackage(p)
        }
    }
}

def deployDocsInStage(PackageDefinition p) {
    stage("Deploy docs ${p.name}") {
        unstash("${p.name}_docs")
        publishHTML(allowMissing: false, alwaysLinkToLastBuild: false, keepAll: false, reportTitles: "",
                reportDir: "${p.relativePath}/docs/_out/",
                reportFiles: "index.html",
                reportName: "${p.name} Documentation")

        imperativeWhen(env.CHANGE_ID == null) {
            deployDocs(p.name, "latest", p.relativePath)
        }
    }
}

def doPipelineForPackage(PackageDefinition pd) {
    catchError(message: "Pipeline for ${pd.name} failed", buildResult: "FAILURE", stageResult: "FAILURE") {
        buildPackageInStage(pd)
        if (pd.document) {
            documentPackageInStage(pd)
        }
        installPackageInStage(pd)
    }
}

def call(PackageDefinition[] packages) {
    Map<String, Closure> buildClosures = new HashMap<String, Closure>()
    Map<String, Closure> webServerClosures = new HashMap<String, Closure>()

    //properties([pipelineTriggers([cron('H 7 * * 1')])])

    withKubernetesNode {
        stage("Pre: SCM, Pipeline Construction") {
            for (int i = 0; i < packages.length; i++) {
                PackageDefinition pd = packages[i]
                buildClosures.put(pd.name, { doPipelineForPackage(pd) })
                webServerClosures.put(pd.name, { deployDocsInStage(pd) })
            }

            checkout(scm)
        }

        parallel(buildClosures)
        cleanWs()
    }

    node("webserver") {
        parallel(webServerClosures)
    }
}
