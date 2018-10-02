# logstash-aggregator-http-plugin-java
Logstash aggregator http plugin java

We have not switched to reactive way of making calls to http endpoint
primarily because we want to limit downstream load. With a synchronous
way of sending we control the number of requests. With batching we achieve
higher throughput with a smaller number of requests.

There is a configuration file which needs to be supplied to this logstash plugin using "file_path" argument.
The following is an example of the contents of the file.
"
microservice1: https://collectors.sumologic.com/receiver/v1/http/<plug your httpendpoint here>
regex@^micro/microservice2/[0-9]: https://collectors.sumologic.com/receiver/v1/http/<plug your httpendpoint here>
"

Logstash httpOutputJava is configured using the following configuration.
httpOutputJava {
   file_path => "/config-dir/sumologic.yaml"
   mpx_key => "%{tag}"
   message => "%{message}"
   num_threads => 128
   max_connections => 1200
   max_connections_per_route => 100
   flush_interval_seconds => 30
   queue_size => 3000
}
