#! /usr/bin/env bash

psql -c 'create user postgres createdb' postgres
psql -c 'create database runs;' -U postgres
psql -c '\i runs.sql' -d runs -U postgres
