import de.bitbots.jenkins.PackageDefinition
import groovy.transform.Field

@Field
def BITBOTS_BUILDER_IMAGE = "registry.bit-bots.de/bitbots_builder"

def pullContainer() {
    sh "docker pull ${BITBOTS_BUILDER_IMAGE}"
    sh "docker inspect -f . ${BITBOTS_BUILDER_IMAGE}"  // Fails if non-existent
}

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
            withDockerContainer(
                    image: BITBOTS_BUILDER_IMAGE,
                    args: "--volume /srv/shared_catkin_install_space:/srv/catkin_install:ro") {
                buildPackage(p)
            }
        }
        //}
    }
}

def installPackageInStage(PackageDefinition p) {
    stage("Install package ${p.name}") {
        withDockerContainer(
                image: BITBOTS_BUILDER_IMAGE,
                args: "--volume /srv/shared_catkin_install_space:/srv/catkin_install:rw") {
            installPackage(p)
        }
    }
}

def documentPackageInStage(PackageDefinition p) {
    stage("Build docs ${p.name}") {
        withDockerContainer(
                image: BITBOTS_BUILDER_IMAGE,
                args: "--volume /srv/shared_catkin_install_space:/srv/catkin_install:rw") {
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

        if (env.BRANCH_NAME == "master")
            deployDocs(p.name, "latest", p.relativePath)
        else
            echo "Skipped webserver deployment because branch is not master"
    }
}

def doPipelineForPackage(PackageDefinition pd) {
    catchError(buildResult: null, stageResult: "NOT_BUILT") {
        buildPackageInStage(pd)
        if (pd.document) {
            documentPackageInStage(pd)
        }
        if (env.BRANCH_NAME == "master")
            installPackageInStage(pd)
        else
            echo "Skipped package installation because branch is not master"
    }
}

def call(PackageDefinition[] packages) {
    node {
        Map<String, Closure> buildClosures = new HashMap<String, Closure>()
        Map<String, Closure> webServerClosures = new HashMap<String, Closure>()

        stage("Pre: SCM, Docker, Pipeline Construction") {
            for (int i = 0; i < packages.length; i++) {
                PackageDefinition pd = packages[i]
                buildClosures.put(packages[i].name, { doPipelineForPackage(pd) })
                webServerClosures.put(packages[i].name, { deployDocsInStage(pd) })
            }

            checkout(scm)
            pullContainer()
        }

        parallel(buildClosures)
        cleanWs()
    }

    node("webserver") {
        parallel(webServerClosures)
    }
}
