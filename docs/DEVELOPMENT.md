# Development and release / 开发与发布

Current source: Applied Enhancements 1.1.0-forge, Java 17, Forge 47.4.20.
[API index](README.md) · [Configuration](CONFIGURATION.md)

## Build

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat clean build apiJavadoc --no-configuration-cache
```

Default AE2 is 15.4.10. For AE2 UELM 15.5.4, use
`-Pae2_uelm_version=15.5.4-uelm`; the output receives a `-uelm` classifier.
`-Puelm=C:/absolute/path/ae2-uelm-mapped.jar` remains available for an already
remapped development dependency. JEI is compile-only plus local development runtime; `-PwithoutJei` omits
its development runtime while retaining compilation.

| Output | Purpose |
| --- | --- |
| `build/libs/appliedenhancements-1.1.0-forge.jar` | Separate runtime mod |
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

Build both upstream AE2 and UELM when changing internal hooks. Parse resource
JSON and documentation links, inspect public API output, and ensure the JAR
contains no test/probe classes, nested AE2 binaries or extracted META-INF files.
Back up previous installed JARs outside mods, copy the new build and compare
SHA256. Use disposable worlds for runtime checks; do not modify personal saves.

Repository CI builds upstream AE2 and UELM and uploads the JAR, public JavaDoc and unit
report. Downstream builds can compile this source at a fixed Git commit to obtain
the matching API instead of using a renamed older binary.

The downstream OmniSequence paired engine/client gates run this upstream-built
JAR with AE2 15.4.10 and UELM 15.5.4. Client verification checks the actual submit
and CPU-screen hooks, resource loading, eight machine pages and native menu
packets. ForgeSubmitSelectorTest retains exact overload matching even when
Forge enables permissive runtime remapping.

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
使用当前 Gradle 属性，不把旧版本构建改名成 1.1.0-forge。自动测试验证逻辑边界，整合包
运行和 TPS 需要实际环境；不要把单元测试当作性能承诺。日志限流保留首次故障，
不会改变材料接收、取消、回滚或库存所有权。
