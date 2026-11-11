# 공개 CE에서 Re-Solve가 선택하는 세 실행 환경을 등록한다.
@languages.concat([
  {
    id: 91,
    name: "Java (JDK 17.0.6)",
    is_archived: false,
    source_file: "Main.java",
    compile_cmd: "/usr/local/resolve-jdk17/bin/javac %s Main.java",
    run_cmd: "/usr/local/resolve-jdk17/bin/java Main"
  },
  {
    id: 113,
    name: "Python (3.14.0)",
    is_archived: false,
    source_file: "script.py",
    run_cmd: "/usr/local/resolve-python/bin/python3.14 script.py"
  },
  {
    id: 105,
    name: "C++ (GCC 14.1.0)",
    is_archived: false,
    source_file: "main.cpp",
    compile_cmd: "/usr/local/resolve-gcc/bin/g++ %s main.cpp -o a.out",
    run_cmd: "LD_LIBRARY_PATH=/usr/local/resolve-gcc/lib64 ./a.out"
  }
])
