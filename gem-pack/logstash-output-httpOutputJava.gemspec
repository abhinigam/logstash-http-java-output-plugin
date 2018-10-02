Gem::Specification.new do |s|
  s.name          = 'logstash-output-httpOutputJava'
  s.version       = '1.0.0'
  s.licenses      = ['Apache License (2.0)']
  s.summary       = 'Http Output plugin written in java'
  s.description   = 'This plugin enables us to send log messages to multiple url endpoints depending on container name'
  s.homepage      = 'http://wwww.dothislater.com'
  s.authors       = ['Medallia Cloud Engineering']
  s.email         = ['shared-services@medallia.com']
  s.require_paths = ['lib']

  # Files
  s.files = Dir['lib/**/*','spec/**/*','vendor/**/*','*.gemspec','*.md','CONTRIBUTORS','Gemfile','LICENSE','NOTICE.TXT']
   # Tests
  s.test_files = s.files.grep(%r{^(test|spec|features)/})

  # Special flag to let us know this is actually a logstash plugin
  # s.metadata = { "logstash_plugin" => "true", "logstash_group" => "output" }

  # Gem dependencies
  s.add_runtime_dependency "logstash-core-plugin-api", "~> 2.0"
  s.add_runtime_dependency "jar-dependencies", '~> 0'
  s.add_runtime_dependency "logstash-codec-plain"
  s.add_development_dependency "logstash-devutils"
end
