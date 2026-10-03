# Development and release / 开发与发布

Current source: Applied Enhancements 1.1.0, Java 21, NeoForge 21.1.220.
[API index](README.md) · [Configuration](CONFIGURATION.md)

## Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build apiJavadoc --no-configuration-cache
```

Default AE2 is 19.2.18; use `-Pae2_version=19.2.17` for the earlier supported
patch. JEI is compile-only plus local development runtime; `-PwithoutJei` omits
its development runtime while retaining compilation.

| Output | Purpose |
| --- | --- |
| `build/libs/appliedenhancements-1.1.0.jar` | Separate runtime mod |
| `build/docs/api/index.html` | Generated public JavaDoc |
| `build/reports/tests/test/index.html` | Unit regression report |

The default test suite covers exact arithmetic/metadata, planning and fallback,
cycle phases, shared extraction/ownership rollback, reservations, infinite source
classification, terminal/menu APIs, cache invalidation and config migration.
Optional-mod runtime behavior requires a matching isolated game environment; unit
success alone does not establish a full modpack TPS result. Test fixtures under
src/test are excluded from the runtime JAR. The 1.0.3 API fixture checks retained
compatibility and is maintained regression input.

## Release checks

Build both supported AE2 patches when changing internal hooks. Parse resource
JSON and documentation links, inspect public API output, and ensure the JAR
contains no test/probe classes, nested AE2 binaries or extracted META-INF files.
Back up previous installed JARs outside mods, copy the new build and compare
SHA256. Use disposable worlds for runtime checks; do not modify personal saves.

Repository CI builds both AE2 patches and uploads the JAR, public JavaDoc and unit
report. Downstream builds can compile this source at a fixed Git commit to obtain
the matching API instead of using a renamed older binary.

## Maintenance

Repeated failures use AelisPlanningLog with constant categories/templates. Optional
planning diagnostics default off and share a global one-minute window. Category
capacity preserves known history; excess categories share a bounded overflow
window. Do not construct category IDs from positions, job IDs or dynamic amounts.
Normal production needs no per-item/tick success log. Startup configuration changes
may describe an actual migration.

The artwork/project-logo-ae-text.png file is the README logo source; it is maintained
artwork and is not included in the runtime JAR. Generated logs, unpacked inspection
files, test worlds and API HTML stay outside source control. Old local audit reports
are archived outside the project, not used as release documentation.

## 中文

先运行正常构建和 apiJavadoc，确认测试没有失败或跳过，再检查产物内容和哈希。
使用当前 Gradle 属性，不把旧版本构建改名成 1.1.0。自动测试验证逻辑边界，整合包
运行和 TPS 需要实际环境；不要把单元测试当作性能承诺。日志限流保留首次故障，
不会改变材料接收、取消、回滚或库存所有权。
