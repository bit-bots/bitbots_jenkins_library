def call(String p, String makeArgs = "") {
    if (makeArgs.equals(""))
        sh "cd /catkin_ws; catkin build --no-status --summarize ${p}"
    else
        sh "cd /catkin_ws; catkin build --no-status --summarize ${p} --make-args ${makeArgs}"
}
