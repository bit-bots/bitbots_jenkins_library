import de.bitbots.jenkins.PackageDefinition
import groovy.transform.Field

@Field
def BITBOTS_BUILDER_IMAGE = "registry.bit-bots.de:5000/bitbots_builder"

def pullContainer() {
    sh "docker pull ${BITBOTS_BUILDER_IMAGE}"
    sh "docker inspect -f . ${BITBOTS_BUILDER_IMAGE}"  // Fails if non-existent
}

def buildPackage(PackageDefinition p) {
    linkCatkinWorkspace(p.relativePath)
    installRosdeps(p.relativePath)
    catkinClean()
    catkinBuild(p.name)
}

def installPackage(PackageDefinition p) {
    linkCatkinWorkspace(p.relativePath)
    installRosdeps(p.relativePath)
    catkinClean()
    catkinInstall(p.name)
}

def documentPackage(PackageDefinition p) {
    linkCatkinWorkspace(p.relativePath)
    catkinClean()
    catkinBuild(p.name, "Documentation")
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
                    cleanWs()
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
                cleanWs()
            }
        }
    }
}

Closure documentPackageInStage(PackageDefinition p) {
    return {
        stage("Document package ${p.name}") {
            withDockerContainer(
                    image: BITBOTS_BUILDER_IMAGE,
                    args: "--volume /srv/shared_catkin_install_space:/srv/catkin_install:rw") {
                documentPackage(p)
                cleanWs()
            }
        }
    }
}

def call(PackageDefinition[] packages) {
    Map<String, Closure> buildClosures = new HashMap<>()
    Map<String, Closure> documentClosures = new HashMap<>()
    Map<String, Closure> installClosures = new HashMap<>()

    for (int i = 0; i < packages.length; i++) {
        buildClosures.put(packages[i].name, buildPackageInStage(packages[i]))
        if (packages[i].document)
            documentClosures.put(packages[i].name, documentPackageInStage(packages[i]))
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
}
