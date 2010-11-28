require 'rubygems'
require 'fileutils'
require 'jake'

desc "Compile proxy"
task :build => :clean do
  Javac.in('.').execute do |javac|
    javac.src = 'src/**/*.java'
    javac.cp << 'libs/**/*.jar'
    javac.output = 'bin'
  end
end

desc "Clean proxy build"
task :clean do
  FileUtils.mkdir 'bin' unless File.exists?('bin')
  FileUtils.rm_rf Dir.glob('bin/*')
end

desc "Build proxy.jar with manifest"
task :jar do
  Jar.in('.').execute do |jar|
    jar.name = 'proxy.jar'
    jar.bin << 'bin'
    jar.with_manifest = true
  end
end

task :default => [:build,:jar]
