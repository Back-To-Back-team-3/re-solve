# Judge0 1.13.1 워커의 Isolate 호출을 2.x 옵션으로 변환한다.
source = File.read(ARGV.fetch(0))

def replace_once(source, original, updated)
  raise "Judge0 워커의 원본이 예상과 다릅니다." unless source.scan(original).length == 1

  source.sub!(original) { updated }
end

replace_once(source, <<'OLD', <<'NEW')
    @box_id = submission.id%2147483647
    @cgroups = (!submission.enable_per_process_and_thread_time_limit || !submission.enable_per_process_and_thread_memory_limit) ? "--cg" : ""
    @workdir = `isolate #{cgroups} -b #{box_id} --init`.chomp
OLD
    @workdir = nil
    @box_id = submission.id % 2147423648
    @cgroups = "--cg"
    sandbox = `isolate #{cgroups} -b #{box_id} --init`.chomp
    unless $?.success? && sandbox == "/var/local/lib/isolate/#{box_id}"
      raise "Judge0 sandbox initialization failed."
    end
    @workdir = sandbox
NEW

timing_options, worker_lines = source.lines.partition { |line| line.include?("--cg-timing") }
raise "Judge0 시간 제한 옵션의 위치가 예상과 다릅니다." unless timing_options.length == 3

source = worker_lines.join
replace_once(source, "  def cleanup(raise_exception = true)\n", <<'RUBY')
  def cleanup(raise_exception = true)
    return unless workdir

RUBY
print source
