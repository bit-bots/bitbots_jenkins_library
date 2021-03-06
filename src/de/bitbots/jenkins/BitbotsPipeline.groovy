package de.bitbots.jenkins

import org.jenkinsci.plugins.pipeline.modeldefinition.Utils

class BitbotsPipeline implements Serializable {
    def steps
    def env
    def currentBuild
    def scm
    def checkoutVars
    Boolean restrictPublishingToMainBranch

    /**
     * The list of packages for which this pipeline is configured
     *
     * It is needed in addition to buildClosures so that workspace setup tasks can be done before the actual build
     * which is necessary to prevent issues of parallel execution.
     */
    final List<PackagePipelineSettings> packages

    /**
     * Map of package names to closures which build them
     */
    private Map<String, Closure> buildClosures

    private class GithubInfo {
        public final String owner
        public final String repo

        GithubInfo(String owner, String repo) {
            this.owner = owner
            this.repo = repo
        }
    }

    /**
     * Construct a new BitBotsPipeline.
     * Most of the time it can be constructed like <code>new BitbotsPipeline(this, env, currentBuild, scm)</code>
     *
     * @param steps Reference to the current pipeline object which is needed to call Jenkins steps from within the
     *  pipeline. Should always be <code>this</code>
     * @param env Pipeline environment. Should always be <code>env</code>
     * @param currentBuild Jenkins information about the current build. Should always be <code>currentBuild</code>
     * @param scm Source control information as given by Jenkins. Should always be <code>scm</code>
     * @param restrictPublishingToMainBranch Whether documentation publishing should automatically be restricted to the
     *  main branch of the repository regardless of a packages {@link PackagePipelineSettings}
     */
    BitbotsPipeline(steps, env, currentBuild, scm, Boolean restrictPublishingToMainBranch = true) {
        this.steps = steps
        this.env = env
        this.currentBuild = currentBuild
        this.scm = scm
        this.restrictPublishingToMainBranch = restrictPublishingToMainBranch

        this.packages = new LinkedList()
        this.buildClosures = new HashMap<String, Closure>()
    }

    /**
     * Configure this pipeline for a given package.
     *
     * This does not directly execute the pipeline but instead configures later execution.
     * Call {@link #execute()} to execute the pipeline for all configured packages.
     */
    void configurePipelineForPackage(PackagePipelineSettings pkgSettings) {
        this.packages.add(pkgSettings)

        this.buildClosures.put(pkgSettings.getPkg().getName(), {
            this.steps.catchError(message: "Pipeline for ${pkgSettings.getPkg().getName()} failed", buildResult: "FAILURE", stageResult: "FAILURE") {
                // compilation
                this.withGithubStage("Build ${pkgSettings.getPkg().getName()}", "build_${pkgSettings.getPkg().getName()}") {
                    this.imperativeWhen(pkgSettings.getDoBuild()) {
                        this.inBuildContainer {
                            this.steps.timeout(30) {
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
                                this.catkinBuild(pkgSettings.getPkg(), "Documentation", "default", false)
                                this.steps.dir(this.env.WORKSPACE) {
                                    def includes
                                    if (pkgSettings.getPkg().getRelativePath().equals(".")) {
                                        includes = "docs/_out/**"
                                        this.createArchive("docs/_out/", "./docs.tar.gz")
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
                    def shouldRestrict = this.restrictPublishingToMainBranch && this.steps.isPrimaryBranch()
                    this.imperativeWhen(pkgSettings.getDoPublish() && shouldRestrict) {
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
      imagePullPolicy: Always
      securityContext:
        runAsUser: 0
      tty: true
      command:
      - cat
""") {
            this.steps.node(this.env.POD_LABEL) {
                // setup workspace
                this.steps.stage("Pre: Workspace Setup") {
                    this.inBuildContainer {
                        this.checkoutVars = this.steps.checkout(this.scm)
                        this.updateRosdeps()
                        for (pkgSettings in this.packages) {
                            this.linkCatkinWorkspace(pkgSettings.getPkg())
                        }
                        for (pkgSettings in this.packages) {
                            if (pkgSettings.getDoBuild()) {
                                this.installRosdeps(pkgSettings.getPkg())
                            }
                        }
                    }
                }

                // execute actual package builds
                //this.steps.parallel(buildClosures)
                for (key in this.buildClosures.keySet()) {
                    this.steps.echo "Executing closure ${key}"
                    this.buildClosures[key]()
                }
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
                ) {
                    body()
                }
            } else {
                body()
            }
        }
    }

    private void linkCatkinWorkspace(PackageDefinition pkg) {
        if (pkg.getRelativePath().equals(".")) {
            this.steps.sh(
                    label: "linkCatkinWorkspace",
                    script: "ln -sf ${this.env.WORKSPACE}/ /catkin_ws/src/${pkg.getName()}"
            )
        } else {
            this.steps.sh(
                    label: "linkCatkinWorkspace",
                    script: "ln -sf ${this.env.WORKSPACE}/${pkg.getRelativePath()} /catkin_ws/src/${pkg.getName()}"
            )
        }
    }

    private void catkinBuild(PackageDefinition pkg, String makeArgs = "", String profile = "default", boolean withDeps = true) {
        def cmd = "catkin build -w /catkin_ws --profile ${profile} --no-status --summarize ${pkg.getName()}"

        if (!withDeps) {
            cmd += " --no-deps"
        }

        if (!makeArgs.equals("")) {
            cmd += " --make-args ${makeArgs}"
        }

        this.steps.sh(
                label: "catkinBuild",
                script: cmd
        )
    }

    private void installRosdeps(PackageDefinition pkg) {
        this.steps.lock("${this.env.BUILD_TAG}_apt") {
            this.steps.sh(
                    label: "installRosdeps",
                    script: """
                            . /opt/ros/melodic/setup.sh
                            rosdep install -y -i --from-paths /catkin_ws/src/${pkg.getName()}
                            """
            )
        }
    }

    private void updateRosdeps() {
        this.steps.sh(
                label: "updateRosdeps",
                script: "rosdep update"
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
