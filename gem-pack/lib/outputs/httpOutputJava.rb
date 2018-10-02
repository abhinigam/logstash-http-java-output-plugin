require "logstash/outputs/base"
require "logstash/namespace"
require 'logstash-output-logprocessor_jars.rb'
require 'java'

# An httpOutputJava which allows log forwarding to http endpoints.
class LogStash::Outputs::Httpoutputjava < LogStash::Outputs::Base
  config_name "httpOutputJava"

  config :file_path, :validate => :string, :required => true

  config :max_connections_per_route, :validate => :number, :default => 25

  config :max_connections, :validate => :number, :default => 25

  config :num_threads, :validate => :number, :default => 25

  config :queue_size, :validate => :number, :default => 1000

  config :retry_count, :validate => :number, :default => 1

  config :send_timeout, :validate => :number, :default => 5000

  config :reload_minutes, :validate => :number, :default => 5

  config :enable_batching, :validate => :boolean, :default => true

  # String format of the multiplexer key, based on the event (e.g "%{message}")
  # This is the format string to generate the key from a log event.
  # For example, if the key to be looked in the mpx_table is generated on
  # the concatenation of the fields 'log_prefix' and 'log_field', mpx_key
  # should be configured to '%{log_prefix}%{log_field}'
  config :mpx_key, :validate => :string, :required => true

  config :message, :validate => :string, :required => true

  config :flush_interval_seconds, :validate => :number, :default => 30

  config :buffer_size, :validate => :number, :default => 2048

  # This sets the concurrency behavior of this plugin. By default it is :legacy, which was the standard
  # way concurrency worked before Logstash 2.4
  #
  # You should explicitly set it to either :single or :shared as :legacy will be removed in Logstash 6.0
  #
  # When configured as :single a single instance of the Output will be shared among the
  # pipeline worker threads. Access to the `#multi_receive/#multi_receive_encoded/#receive` method will be synchronized
  # i.e. only one thread will be active at a time making threadsafety much simpler.
  #
  # You can set this to :shared if your output is threadsafe. This will maximize
  # concurrency but you will need to make appropriate uses of mutexes in `#multi_receive/#receive`.
  #
  # Only the `#multi_receive/#multi_receive_encoded` methods need to actually be threadsafe, the other methods
  # will only be executed in a single thread
  concurrency :shared

  public
  def register
    @processor = com.medallia.logging.LogProcessor.new(@max_connections, @max_connections_per_route, @queue_size, @num_threads, @enable_batching, @retry_count, @send_timeout, @file_path, @reload_minutes, @flush_interval_seconds, @buffer_size)
  end # def register

  public
  def receive(event)
    @processor.sendLog(event.sprintf(@message), event.sprintf(@mpx_key))
    return "Event received"
  end # def event

  def multi_receive(events) 
    events.each do |event|
      receive(event)
    end
  end

  def close

  end
end # class LogStash::Outputs::Httpoutputjava
