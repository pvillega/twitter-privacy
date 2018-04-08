#!/usr/bin/env bash
dir="$( cd "$( dirname "$0" )" && pwd )"
cd ${dir}/..

echo "Deleting old tweets"
sbt assembly
java -jar target/scala-2.12/twitter-privacy-assembly-1.0.jar