package de.bitbots.jenkins

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

class BitbotsPipeline implements Serializable {
    def steps
    def env
    def currentBuild
    def scm
    def checkoutVars

    final Map<String, Closure> buildClosures

    private class GithubInfo {
        public final String owner
        public final String repo

        GithubInfo(String owner, String repo) {
            this.owner = owner
            this.repo = repo
        }
    }

    BitbotsPipeline(steps, env, currentBuild, scm) {
        this.steps = steps
        this.env = env
        this.currentBuild = currentBuild
        this.scm = scm

        this.buildClosures = new HashMap()
    }

    /**
     * Configure this pipeline for a given package.
     *
     * This does not directly execute the pipeline but instead configures later execution. Call {@link #execute()}
     */
    void configurePipelineForPackage(PackagePipelineSettings pkgSettings) {
        this.buildClosures.put(pkgSettings.getPkg().getName(), {
            this.steps.catchError(message: "Pipeline for ${pkgSettings.getPkg().getName()} failed", buildResult: "FAILURE", stageResult: "FAILURE") {
                // compilation
                this.withGithubStage("Build ${pkgSettings.getPkg().getName()}", "build_${pkgSettings.getPkg().getName()}") {
                    this.imperativeWhen(pkgSettings.getDoBuild()) {
                        this.inBuildContainer {
                            this.steps.timeout(30) {
                                this.linkCatkinWorkspace(pkgSettings.getPkg())
                                this.installRosdeps(pkgSettings.getPkg())
                                this.catkinClean()
                                this.catkinBuild(pkgSettings.getPkg())
                            }
                        }
                    }
                }

                // documentation
                this.withGithubStage("Document ${pkgSettings.getPkg().getName()}", "doc_${pkgSettings.getPkg().getName()}") {
                    this.imperativeWhen(pkgSettings.getDoDocument()) {
                        this.inBuildContainer {
                            this.steps.timeout(10) {
                                this.linkCatkinWorkspace(pkgSettings.getPkg())
                                this.installRosdeps(pkgSettings.getPkg())
                                this.catkinBuild(pkgSettings.getPkg(), "Documentation")
                                this.steps.dir(this.env.WORKSPACE) {
                                    def includes
                                    if (pkgSettings.getPkg().getRelativePath().equals(".")) {
                                        includes = "docs/_out/**"
                                        this.createArchive("./docs/_out/", "./docs.tar.gz")
                                    } else {
                                        includes = "${pkgSettings.getPkg().getRelativePath()}/docs/_out/**"
                                        this.createArchive("${pkgSettings.getPkg().getRelativePath()}/docs/_out/", "./docs.tar.gz")
                                    }
                                    this.steps.stash(name: "${pkgSettings.getPkg().getName()}_docs", includes: includes)
                                    this.steps.archiveArtifacts(artifacts: "docs.tar.gz")
                                }
                            }
                        }
                    }
                }

                // documentation publishing on webserver
                this.withGithubStage("Publish ${pkgSettings.getPkg().getName()}", "publish_${pkgSettings.getPkg().getName()}") {
                    this.imperativeWhen(pkgSettings.getDoPublish()) {
                        this.onWebserver {
                            this.steps.unstash("${pkgSettings.getPkg().getName()}_docs")
                            this.steps.dir(pkgSettings.getPkg().getRelativePath()) {
                                this.steps.sh "mkdir -p /srv/data/doku.bit-bots.de/package/${pkgSettings.getPkg().getName()}/latest/"
                                this.steps.sh "rsync --delete -v -r ./docs/_out/ /srv/data/doku.bit-bots.de/package/${pkgSettings.getPkg().getName()}/latest/"
                            }
                        }
                    }
                }
            }
})
    }

    /**
     * Execute the pipeline for all configured packages.
     *
     * You need to configure the pipeline for every package using {@link #configurePipelineForPackage(PackagePipelineSettings)} first.
     */
    void execute() {
        this.steps.podTemplate(yaml: """
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: builder
      image: registry.bit-bots.de/bitbots_builder
      securityContext:
        runAsUser: 0
      tty: true
      command:
      - cat
""") {
            this.steps.node(this.env.POD_LABEL) {
                // checkout
                this.steps.stage("Pre: Checkout") {
                    this.inBuildContainer {
                        this.checkoutVars = this.steps.checkout(this.scm)
                    }
                }

                // package pipeline execution
                this.steps.parallel(this.buildClosures)
            }
        }
    }

    /**
     * Execute the provided closure in our build container running in our Kubernetes build cloud
     */
    private void inBuildContainer(Closure body) {
        this.steps.container("builder") {
            body()
        }
    }

    /**
     * Execute the provided closure directly on our Webserver node
     */
    private void onWebserver(Closure body) {
        this.steps.node("webserver") {
            body()
        }
    }

    /**
     * Execute something in a stage that is also displayed on Github (if this is a Github-related job)
     *
     * @param name Name / Description of the stage
     * @param githubContext A context used by github to identify this stage (basically an id you can choose)
     * @param githubCredentialsId Jenkins credentials id used to authenticate against the Github API
     */
    private void withGithubStage(String name, String githubContext, String githubCredentialsId = "github-credentials", Closure body) {
        this.steps.stage(name) {
            def githubInfo = this.getGithubInfo()
            if (githubInfo != null) {
                this.steps.gitStatusWrapper(
                        credentialsId: githubCredentialsId,
                        description: name,
                        failureDescription: "${name} failed",
                        successDescription: "${name} passed",
                        gitHubContext: githubContext,
                        account: githubInfo.owner,
                        repo: githubInfo.repo,
                        sha: this.checkoutVars.GIT_COMMIT
                ) {
                    body()
                }
            } else {
                body()
            }
        }
    }

    private void linkCatkinWorkspace(PackageDefinition pkg) {
        this.steps.sh(
                label: "linkCatkinWorkspace",
                script: "ln -s ${this.env.WORKSPACE}/${pkg.getRelativePath()} /catkin_ws/src/${pkg.getName()}"
        )
    }

    private void catkinClean() {
        this.steps.sh(
                label: "catkinClean",
                script: "catkin clean -w /catkin_ws -y"
        )
    }

    private void catkinBuild(PackageDefinition pkg, String makeArgs = "", String profile = "default") {
        def cmd = "catkin build -w /catkin_ws --profile ${profile} --no-status --summarize ${pkg.getName()}"

        if (!makeArgs.equals("")) {
            cmd += " --make-args ${makeArgs}"
        }

        this.steps.sh(
                label: "catkinBuild",
                script: cmd
        )
    }

    private void installRosdeps(PackageDefinition pkg) {
        this.steps.sh(
                label: "installRosdeps",
                script: "rosdep install -y -i /catkin_ws/src -i /srv/catkin_install --from-paths /catkin_ws/src/${pkg.getName()}"
        )
    }

    /**
     * Execute the provided Closure if the condition is true.
     * Otherwise mark the current stage as skipped
     */
    private void imperativeWhen(boolean condition, Closure body) {
        def config = [:]
        body.resolveStrategy = Closure.OWNER_FIRST
        body.delegate = config

        if (condition) {
            body()
        } else {
            Utils.markStageSkippedForConditional(this.env.STAGE_NAME)
        }
    }

    private GithubInfo getGithubInfo() {
        def pattern = ~"^https://github.com/(?<owner>[^/]+)/(?<repo>[^/]+).git\$"
        def matcher = this.checkoutVars.GIT_URL =~ pattern

        if (!matcher.find())
            return null
        return new GithubInfo(matcher.group("owner"), matcher.group("repo"))
    }

    /**
     * Create a .tar.gz archive from all files under rootPath at targetPath
     *
     * @param rootPath Directory under which all files will be included in the archive
     * @param targetPath Filename which will be the resulting archive
     */
    private createArchive(String rootPath, String targetPath) {
        this.steps.sh(
                label: "createArchive",
                script: "tar -c -a -f ${targetPath} ${rootPath}"
        )
    }
}
