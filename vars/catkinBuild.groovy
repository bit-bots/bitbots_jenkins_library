def call(String p, String makeArgs = "", String profile = "default") {
    if (makeArgs.equals(""))
        sh "cd /catkin_ws; catkin build --profile ${profile} --no-status --summarize ${p}"
    else
        sh "cd /catkin_ws; catkin build --profile ${profile} --no-status --summarize ${p} --make-args ${makeArgs}"
}
