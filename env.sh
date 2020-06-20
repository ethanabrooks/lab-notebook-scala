#! /usr/bin/env bash

export RUN_DB_PATH="$HOME/IdeaProjects/lab-notebook/runs"
export RUN_LOG_DIR="$HOME/IdeaProjects/lab-notebook/runs/.runs"
export RUN_IMAGE_BUILD_PATH="$HOME/IdeaProjects/lab-notebook"
export RUN_DOCKERFILE_PATH="$HOME/IdeaProjects/lab-notebook/Dockerfile"
export RUN_IMAGE='lab-notebook-test-image'
export RUN_CONFIG_SCRIPT='run_launcher.py'
export RUN_CONFIG_SCRIPT_INTERPRETER='python3'
export RUN_CONFIG_SCRIPT_INTERPRETER_ARGS='-c'
export RUN_KILL_SCRIPT='kill_script.sh'
export RUN_LAUNCH_SCRIPT="run_script.sh"

