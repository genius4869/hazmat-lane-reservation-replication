"""Build and run the study algorithms using a local CPLEX installation."""
import argparse
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys

ROOT = Path(__file__).resolve().parent


def require_file(path):
    path = Path(path)
    if not path.is_file():
        raise SystemExit(f"Required file was not found: {path}")
    return path.resolve()


def package_home(name):
    if not os.environ.get(name):
        raise SystemExit(f"Set {name} to the installation directory.")
    return Path(os.environ[name]).resolve()


def java_tool(name):
    filename = name + (".exe" if os.name == "nt" else "")
    if os.environ.get("JAVA_HOME"):
        return require_file(Path(os.environ["JAVA_HOME"]) / "bin" / filename)
    tool = shutil.which(name)
    if tool is None:
        raise SystemExit(f"Set JAVA_HOME to a JDK installation containing {filename}.")
    return require_file(tool)


def kotlin_stdlib():
    return require_file(os.environ.get("KOTLIN_STDLIB") or package_home("KOTLIN_HOME") / "lib/kotlin-stdlib.jar")


def cplex_jar():
    return require_file(os.environ.get("CPLEX_JAR") or package_home("CPLEX_STUDIO_DIR") / "cplex/lib/cplex.jar")


def native_directory():
    if os.environ.get("CPLEX_NATIVE_DIR"):
        root = Path(os.environ["CPLEX_NATIVE_DIR"])
    else:
        names = {"Windows": "x64_win64", "Linux": "x86-64_linux", "Darwin": "x86-64_osx"}
        if platform.system() not in names:
            raise SystemExit("Specify CPLEX_NATIVE_DIR for this operating system.")
        root = package_home("CPLEX_STUDIO_DIR") / "cplex/bin" / names[platform.system()]
    if not root.is_dir():
        raise SystemExit(f"Native library directory was not found: {root}. Set CPLEX_NATIVE_DIR.")
    return root.resolve()


def classpath(items):
    return os.pathsep.join(map(str, items))


def build(classes):
    if classes.exists() and any(classes.iterdir()):
        raise SystemExit(f"Build destination must be empty. Choose a new directory with --classes: {classes}")
    classes.mkdir(parents=True, exist_ok=True)
    kotlin = package_home("KOTLIN_HOME")
    compiler = require_file(kotlin / "lib/kotlin-compiler.jar")
    stdlib = kotlin_stdlib()
    cplex = cplex_jar()
    java_sources = sorted((ROOT / "src/main/java").glob("*.java"))
    subprocess.run([java_tool("java"), "-cp", compiler, "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler",
                    "-kotlin-home", kotlin, "-language-version", "2.1", "-api-version", "2.1",
                    "-jvm-target", "1.8", "-no-stdlib", "-classpath", stdlib, "-d", classes,
                    ROOT / "src/main/kotlin/DataCreator.kt"], check=True)
    subprocess.run([java_tool("javac"), "-J-Duser.language=en", "-J-Duser.country=US",
                    "-encoding", "UTF-8", "--release", "15",
                    "-classpath", classpath([classes, cplex, stdlib]), "-d", classes, *java_sources], check=True)
    print(f"Compiled {len(java_sources)} Java sources and the Kotlin data class into {classes}")


def run(classes, args):
    require_file(classes / "Replication.class")
    native = native_directory()
    env = dict(os.environ)
    env["PATH"] = str(native) + os.pathsep + env.get("PATH", "")
    key = {"Linux": "LD_LIBRARY_PATH", "Darwin": "DYLD_LIBRARY_PATH"}.get(platform.system())
    if key:
        env[key] = str(native) + (os.pathsep + env[key] if env.get(key) else "")
    cmd = [java_tool("java"), "-Xmx" + env.get("HAZMAT_HEAP", "1024m"),
           f"-Djava.library.path={native}", "-cp", classpath([classes, cplex_jar(), kotlin_stdlib()]),
           "Replication", *args]
    return subprocess.run(cmd, env=env).returncode


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classes", type=Path, default=ROOT / "build/classes",
                        help="Directory for newly compiled classes (default: build/classes).")
    parser.add_argument("command", choices=("build", "solve", "evaluate"))
    parser.add_argument("arguments", nargs="*")
    args = parser.parse_args()
    classes = args.classes.resolve()
    if args.command == "build":
        if args.arguments:
            parser.error("The build command takes no positional arguments.")
        build(classes)
        return 0
    return run(classes, [args.command, *args.arguments])


if __name__ == "__main__":
    sys.exit(main())
