import de.bitbots.jenkins.CatkinCommandBuilder

def call() {
    sh CatkinCommandBuilder.linkWorkspace(WORKSPACE)
}