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

// 注：EM（外部表匹配模块）已迁至 ../HardwareDesign 维护
//（src/main/scala/BaseCbb/em/，含 svsim + FST 波形的 Scala 侧仿真），
// 本工程不再持有 em 子工程，root/cli 保持纯 Scala。

// ---------------------------------------------------------------------------
// rtl —— 路线 3 的 Chisel 集成与仿真。
//
// Chisel 版本与 ../HardwareDesign 对齐（chisel 7.15.0 / scala 2.13.16）：
// gen_chisel_wrapper.py 生成的 BlackBox/Shell 放在 rtl/src/main/scala/p4xlsrtl/，
// `--with-sim` 生成的 svsim 仿真入口（ExtModule 内嵌 Verilog + FST 波形）跑法：
//   sbt "rtl/runMain p4xlsrtl.<Cls>Sim" [拍数]
// 波形落 <workspace>/workdir-verilator/trace.fst（chisel 7 起 svsim Verilator
// 后端支持 FST；5.3/6.x 只有 VCD）。
// 独立子工程，root/cli 保持纯 Scala（fat jar 不受影响）。
// ---------------------------------------------------------------------------
lazy val rtl = (project in file("rtl"))
  .settings(
    name := "p4xls-rtl",
    scalaVersion := "2.13.16",
    // chisel 7.15.0 的传递依赖带 scala-library 2.13.18；sbt 1.12 按 SIP-51 要求
    // 编译器 ≥ 库版本。HardwareDesign（sbt 1.9.7 无此检查）实际就是 2.13.16 编译器
    // + 2.13.18 库的组合，这里显式声明同一行为。
    allowUnsafeScalaLibUpgrade := true,
    libraryDependencies ++= Seq(
      "org.chipsalliance" %% "chisel" % "7.15.0",
      "org.scalatest" %% "scalatest" % "3.2.20" % "test",
    ),
    addCompilerPlugin("org.chipsalliance" % "chisel-plugin" % "7.15.0" cross CrossVersion.full),
    scalacOptions ++= Seq(
      "-language:reflectiveCalls",
      "-deprecation",
      "-feature",
      "-Xcheckinit",
      "-Ymacro-annotations",
    ),
  )
