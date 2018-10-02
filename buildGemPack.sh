#!/usr/bin/env bash
rm build/libs/logProcessor-*.jar
./gradlew shadowJar
mkdir -p gem-pack/vendor/jar-dependencies/runtime-jars/
rm gem-pack/vendor/jar-dependencies/runtime-jars/*.jar
cp build/libs/logProcessor-*-all.jar ./gem-pack/vendor/jar-dependencies/runtime-jars/
cd gem-pack
gem build logstash-output-httpOutputJava.gemspec
cd ..
mv gem-pack/logstash-output-httpOutputJava-*.gem build/libs/
