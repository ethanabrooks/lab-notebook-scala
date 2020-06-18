#! /usr/bin/env python3
import random

print(
    f"""\
--gridworld \
--control-flow-types Subtask \
--conv-hidden-size={random.choice([128, 256])} \
""")

