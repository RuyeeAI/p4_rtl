// p4_rtl —— P4 → XLS IR → RTL 工具链
//
// 路线（见 docs/p4c-xls后端扩展方案.md 的路线修正注记）：
//   P4C 前端/中端（复用自本地 P4C 工程，包名保持 P4C 便于与上游 diff）
//     → XLS IR 文本（IrText 已具备）
//     → 新增 proc 编排生成（唯一缺口）
//     → XLS codegen → Verilog
//
// 本工程源码为纯 Scala：P4C 包只发射字符串，**运行时不依赖 chisel3**，
// 因此可打成不含 chisel3 的 fat jar。

ThisBuild / scalaVersion := "2.13.12"
ThisBuild / version      := "0.1.0"
ThisBuild / organization := "io.github.p4xls"

lazy val root = (project in file("."))
  .settings(
    name := "p4xls",
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
    ),
    // A0 门禁：校验 testcases/ir 下的 XLS IR 文本
    xlsIrLint := {
      val log    = streams.value.log
      val py     = python.value
      val irDir  = baseDirectory.value / "testcases" / "ir"
      val script = baseDirectory.value / "scripts" / "xls_ir_lint.py"
      log.info(s"[A0] 校验 $irDir 下的 XLS IR 文本（解释器 $py）")
      val rc = scala.sys.process.Process(
        Seq(py, script.toString, "--recursive", irDir.toString)
      ).!
      if (rc != 0) sys.error(s"xlsIrLint 失败（退出码 $rc）")
      ()
    },
  )

lazy val python    = settingKey[String]("用于跑 scripts/ 下 Python 工具的解释器")
lazy val xlsIrLint = taskKey[Unit]("校验 testcases/ir 下的 XLS IR 文本（A0 门禁）")

python := sys.env.getOrElse("P4XLS_PYTHON", "python3")

// 独立 CLI：产出单文件 fat jar，入口 p4xls.Main
lazy val cli = (project in file("cli"))
  .enablePlugins(AssemblyPlugin)
  .dependsOn(root)
  .settings(
    name := "p4xls-cli",
    Compile / mainClass := Some("p4xls.Main"),
    assembly / assemblyJarName := "p4xls.jar",
    assembly / mainClass := Some("p4xls.Main"),
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _ @ _*) => MergeStrategy.discard
      case "module-info.class"          => MergeStrategy.discard
      case x =>
        val old = (assembly / assemblyMergeStrategy).value
        old(x)
    },
  )

// ---------------------------------------------------------------------------
// 外部表模块：参数化 EM（后续 LPM / TCAM）行为模型。
//
// 独立子工程（有自己的 scalaVersion 与 chisel 依赖），**不参与 root 的编译**，
// 因此 root 依然保持"纯 Scala、不打进 chisel3"的定位（fat jar 不受影响）。
//
// chisel 版本与 ../HardwareDesign 对齐（chisel 5.3.0 / scala 2.13.12），
// 因为 EM 的存储直接复用 HardwareDesign BaseCbb 的 Memory 体系
//（HT/KT 用 TpMemoryWrap3，AD 用 SpMemoryWrap3）—— 源码级依赖，不拷贝文件。
// ---------------------------------------------------------------------------
lazy val hwRepo = sys.env.getOrElse(
  "HARDWARE_DESIGN_REPO",
  "/Users/haoyu/Documents/01-Work/Code-Repos/HardwareDesign"
)

lazy val em = (project in file("em"))
  .settings(
    name := "p4xls-em",
    scalaVersion := "2.13.12",
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % "5.3.0",
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % "5.3.0" cross CrossVersion.full),
    Compile / unmanagedSources ++= Seq(
      file(hwRepo) / "src/main/scala/BaseCbb/data/GenBundle.scala",
      file(hwRepo) / "src/main/scala/BaseCbb/memory/Memory.scala",
      file(hwRepo) / "src/main/scala/BaseCbb/memory/MemInitCpuAccess.scala",
    ),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations",
    ),
  )
