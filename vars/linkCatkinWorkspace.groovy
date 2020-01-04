def call(String p) {
    sh "ln -s ${WORKSPACE}/${p} /catkin_ws/src/${p}"
}
