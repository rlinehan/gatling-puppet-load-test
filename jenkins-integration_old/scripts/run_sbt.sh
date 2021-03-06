#!/bin/bash
set -x

export BEAKER_TESTSUITE="${BEAKER_TESTSUITE:-beaker/gatling/20_run_sbt.rb}"
export BEAKER_KEYFILE="~/.ssh/id_rsa-acceptance"

bundle install --path vendor/bundle

bundle exec beaker \
  --config $BEAKER_CONFIG \
  --type aio \
  --load-path lib \
  --tests $BEAKER_TESTSUITE \
  --keyfile $BEAKER_KEYFILE \
  --preserve-hosts onfail \
  --no-color \
  --debug
