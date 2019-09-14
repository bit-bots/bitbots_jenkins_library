import de.bitbots.jenkins.CatkinCommandBuilder

def call(String makeTarget = "") {
    sh CatkinCommandBuilder.build(makeTarget)
}