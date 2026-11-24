"""Compile selected production sources with each regression harness's Android/service fixtures."""

import functools
import os
from pathlib import Path
import subprocess


def sources(paths):
    """Locate each production class by its path in a Java, Kotlin or mixed checkout."""
    found = []
    for entry in paths:
        path = Path(entry)
        candidates = [path.with_suffix('.java'), path.with_suffix('.kt')]
        for parent in path.parents:
            if parent.name in ('java', 'kotlin') and parent.parent.name in ('main', 'test'):
                other = 'kotlin' if parent.name == 'java' else 'java'
                relative = path.relative_to(parent)
                candidates.extend(parent.with_name(other) / relative.with_suffix(suffix)
                                  for suffix in ('.java', '.kt'))
                break
        matches = [candidate for candidate in candidates if candidate.is_file()]
        if len(matches) != 1:
            raise ValueError(f'Expected one production source for {path}; found {matches}')
        found.append(matches[0])
    return found


@functools.lru_cache(maxsize=None)
def compiler_classpath(root):
    project = root / 'app'
    wrapper = project / ('gradlew.bat' if os.name == 'nt' else 'gradlew')
    init = Path(__file__).with_name('fixture-compiler.init.gradle')
    result = subprocess.run([str(wrapper), '--offline', '--quiet', '-I', str(init),
                             ':app:fixtureCompilerClasspath'], cwd=project,
                            text=True, capture_output=True)
    if result.returncode:
        raise RuntimeError(f'Could not resolve the project Kotlin compiler.\n{result.stderr}')
    marker = 'FIXTURE_COMPILER='
    for line in result.stdout.splitlines():
        if line.startswith(marker):
            return line[len(marker):]
    raise RuntimeError('The project did not report its Kotlin compiler classpath.')


def compile_sources(root, java, javac, classes, paths, classpath):
    """K2 resolves the fixture Java symbols; javac compiles those sources against Kotlin bytecode."""
    paths = [Path(p) for p in paths]
    java_files = [str(p) for p in paths if p.suffix == '.java']
    kotlin_files = [str(p) for p in paths if p.suffix == '.kt']
    compiler_log = ''
    if kotlin_files:
        compiler = compiler_classpath(root)
        stdlib = [p for p in compiler.split(os.pathsep) if Path(p).name.startswith('kotlin-stdlib-')]
        if len(stdlib) != 1:
            raise ValueError(f'Expected the Kotlin standard library used by the compiler; found {stdlib}')
        classpath = os.pathsep.join([classpath, *stdlib])
        if classes.exists():
            for compiled in classes.rglob('*.class'):
                compiled.unlink()
            for metadata in (classes / 'META-INF').glob('*.kotlin_module'):
                metadata.unlink()
        command = [str(java), '-cp', compiler, 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler',
                   '-no-stdlib', '-no-reflect', '-jvm-target', '17',
                   '-classpath', classpath, '-d', str(classes), *kotlin_files, *java_files]
        first = subprocess.run(command, text=True, capture_output=True)
        if first.returncode or not java_files:
            return first, classpath
        compiler_log = first.stdout + first.stderr
    command = [str(javac), '--release', '17', '-encoding', 'UTF-8', '-cp', classpath,
               '-d', str(classes), *java_files]
    result = subprocess.run(command, text=True, capture_output=True)
    result.stdout = compiler_log + result.stdout
    return result, classpath
