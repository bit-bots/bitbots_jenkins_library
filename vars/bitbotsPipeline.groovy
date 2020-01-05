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
    catkinBuild(p.name)

    dir("/catkin_ws/install") {
        stash {
            name "${p.name}_bin"
            includes "${p.name}"
        }
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

def call(PackageDefinition[] packages) {
    Map<String, Closure> buildClosures = new HashMap<>()
    for (int i = 0; i < packages.length; i++) {
        buildClosures.put(packages[i].name, buildPackageInStage(packages[i]))
    }

    Map<String, Closure> deployClosures = new HashMap<>()

    node {
        checkout(scm)
        pullContainer()
        parallel(buildClosures)
        //parallel(deployClosures)
        cleanWs()
    }
}
