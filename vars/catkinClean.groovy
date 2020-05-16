def call() {
    sh "cd /catkin_ws; catkin clean -y --all-profiles --force"
}
