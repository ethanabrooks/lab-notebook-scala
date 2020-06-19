#! /usr/bin/env python3
import random

print(
    f"""\
--gridworld \
--control-flow-types Subtask \
--conv-hidden-size={random.choice([128, 256])} \
""")
# --entropy-coef=0.015 \
# --eval-interval=100 \
# --eval-lines=1 \
# --eval-steps=500 \
# --flip-prob=0.5 \
# --gate-coef=0.01 \
