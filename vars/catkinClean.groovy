def call() {
    sh "cd /catkin_ws; catkin clean -y"
    sh "rm -rf /catkin_ws/install"
    sh "rm -rf /catkin_ws/devel"
    sh "rm -rf /catkin_ws/build"
    sh "rm -rf /catkin_ws/logs"
}
