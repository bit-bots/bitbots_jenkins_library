Boolean call() {
    def current_branch
    if (isChangeRequest()) {
        current_branch = env.CHANGE_BRANCH
    } else {
        current_branch = env.BRANCH_NAME
    }

    return current_branch == "master" || current_branch == "main"
}