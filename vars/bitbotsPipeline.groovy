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
        stash(name: "${p.name}_docs", includes: "${p.relativePath}/docs/_out/**")
    }
}

Closure buildPackageInStage(PackageDefinition p) {
    return {
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
}

Closure installPackageInStage(PackageDefinition p) {
    return {
        stage("Install pacakge ${p.name}") {
            withDockerContainer(
                    image: BITBOTS_BUILDER_IMAGE,
                    args: "--volume /srv/shared_catkin_install_space:/srv/catkin_install:rw") {
                installPackage(p)
            }
        }
    }
}

Closure documentPackageInStage(PackageDefinition p) {
    return {
        stage("Build docs ${p.name}") {
            withDockerContainer(
                    image: BITBOTS_BUILDER_IMAGE,
                    args: "--volume /srv/shared_catkin_install_space:/srv/catkin_install:rw") {
                documentPackage(p)
            }
        }
    }
}

Closure deployDocsInStage(PackageDefinition p) {
    return {
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
}

def call(PackageDefinition[] packages) {
    Map<String, Closure> buildClosures = new HashMap<>()
    Map<String, Closure> documentClosures = new HashMap<>()
    Map<String, Closure> installClosures = new HashMap<>()
    Map<String, Closure> deployDocsClosures = new HashMap<>()

    for (int i = 0; i < packages.length; i++) {
        buildClosures.put(packages[i].name, buildPackageInStage(packages[i]))
        if (packages[i].document) {
            documentClosures.put(packages[i].name, documentPackageInStage(packages[i]))
            deployDocsClosures.put(packages[i].name, deployDocsInStage(packages[i]))
        }
        installClosures.put(packages[i].name, installPackageInStage(packages[i]))
    }

    node {
        checkout(scm)
        pullContainer()
        parallel(buildClosures)
        parallel(documentClosures)
        parallel(installClosures)
        cleanWs()
    }

    node("webserver") {
        parallel(deployDocsClosures)
    }
}
