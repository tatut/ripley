#!/bin/sh

cd "$(dirname "$0")"

clojure -m todomvc.main atom-per-session 8888 &

echo "Waiting for todomvc app to be up"

while ! nc -z localhost 8888; do
  sleep 0.1 # wait for 1/10 of the second before check again
done

cd $GITHUB_WORKSPACE/cypress-todomvc
npm ci
npx cypress run
